-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V30: the fulfilment ledger of an item.
--
-- Per item, an append-only record of what instances reported against its
-- acceptance criteria: criterion identifier, instance address, status,
-- evidence reference, actor and time. The ledger stands beside the item and
-- is not its content: writing it writes no row of `item`, so the item's
-- conflict token and change time stay where they are.
--
-- WHAT A ROW HOLDS
--
-- The item, by its key (tenant, scope, number) as every key onto an item is
-- held; the identifier `criterion_number`, the `k` of the criterion's address
-- `worklist://<scope>/item/<n>/criterion/<k>` (V29); the instance address and
-- the evidence reference, both strings the service positions and never
-- follows; the status, one of three values the platform fixes; the actor, the
-- calling subject as the service derived it from the token, in the form
-- `item.creator` holds it; and the time of the booking. The row's own
-- surrogate `pk` is the store's, orders two bookings of the same instant, and
-- is never part of an answer.
--
-- No key onto `item_criterion`: a booking against a criterion that was
-- withdrawn later is taken, because the ledger holds the contract an instance
-- entered with its copy of the criteria. That the identifier was issued by
-- the item is checked by the service before the insert.
--
-- APPEND-ONLY BY GRANT
--
-- The runtime role holds SELECT and INSERT on this table and nothing else: no
-- UPDATE, no DELETE. Nothing in the ledger is ever changed or removed; a
-- correction is a new booking, and the latest booking per criterion and
-- instance is the one that holds.
--
-- ADDITIVE, AND N-1 COMPATIBLE AS HELD BY THE AUTHOR
--
-- A new table, its index, its checks, its policy and its grants, and nothing
-- else. No column, table or policy that exists is changed, and nothing is
-- filled retroactively. The image before this one does not know the table and
-- reads and writes items unchanged against this schema.
-- ===========================================================================

CREATE TABLE worklist.item_fulfilment (
    pk                BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         UUID         NOT NULL,
    scope_id          UUID         NOT NULL,
    item_number       BIGINT       NOT NULL,

    criterion_number  BIGINT       NOT NULL,
    instance          TEXT         NOT NULL,
    status            TEXT         NOT NULL,
    evidence          TEXT         NOT NULL,
    actor             TEXT         NOT NULL,
    booked_at         TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_item_fulfilment_criterion_number CHECK (criterion_number >= 1),
    CONSTRAINT ck_item_fulfilment_instance CHECK (length(btrim(instance)) > 0),
    CONSTRAINT ck_item_fulfilment_evidence CHECK (length(btrim(evidence)) > 0),
    CONSTRAINT ck_item_fulfilment_actor    CHECK (length(btrim(actor)) > 0),
    CONSTRAINT ck_item_fulfilment_status
        CHECK (status IN ('met', 'not_met', 'not_applicable')),

    CONSTRAINT fk_item_fulfilment_item FOREIGN KEY (tenant_id, scope_id, item_number)
        REFERENCES worklist.item (tenant_id, scope_id, number)
);

-- The read of an item's ledger narrows on the item and answers in booking
-- order: by time, and by the surrogate where two bookings share an instant.
CREATE INDEX idx_item_fulfilment_item
    ON worklist.item_fulfilment (tenant_id, scope_id, item_number, booked_at, pk);

ALTER TABLE worklist.item_fulfilment ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.item_fulfilment FORCE  ROW LEVEL SECURITY;

CREATE POLICY item_fulfilment_tenant_isolation ON worklist.item_fulfilment
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- SELECT and INSERT, and nothing else: no UPDATE and no DELETE, so the runtime
-- role can neither change nor remove an entry. No TRUNCATE, no TRIGGER, no
-- REFERENCES.
GRANT SELECT, INSERT ON worklist.item_fulfilment TO kumbuka_worklist;
