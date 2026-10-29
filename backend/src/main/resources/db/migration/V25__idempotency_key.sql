-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V25: the ledger of spent idempotency keys (C10.4).
--
-- `create` takes an idempotency key: a repeat under the same key with the
-- same arguments answers the object the first call produced, and a repeat
-- that cannot be answered that way -- another call, other arguments, or the
-- object is gone -- is refused with IDEMPOTENCY_KEY_REUSED. The call is
-- never carried out a second time. A key speaks for its call for 24 hours;
-- the service decides that from `first_seen_at` and writes a later use over
-- an expired row.
--
-- WHAT A ROW HOLDS
--
-- The key, the call, a digest of its arguments and the address of what it
-- produced, as its view and its number. Nothing the caller wrote is stored:
-- the digest is a SHA-256 of the canonical arguments, and the arguments
-- themselves are the object's own columns where they belong at all.
--
-- No subject. A key is spent within a scope, not per caller. A row naming
-- its caller would be a second record of who created what and when, kept
-- past the 24 hours it is needed for, because the runtime role holds no
-- DELETE. That is the per-member activity record this service does not
-- collect (constraint audit-no-activity-monitoring). Two callers do not
-- share a key by accident; a key is the caller's own random value.
--
-- The object is named by its number, as every reference between rows is
-- (ADR-0042), never by its uuid. No foreign key: one `create` serves three
-- views, so the number points into one of three tables, named by
-- `produced_view`; and a foreign key would make "the object is gone" a state
-- the store cannot reach, which C10.4 answers explicitly.
--
-- ADDITIVE, AND COMPATIBLE WITH THE RELEASE BEFORE IT (DEC-0018)
--
-- A new relation and nothing else. The image before this one does not know
-- the table and runs unchanged against this schema.
-- ===========================================================================

CREATE TABLE worklist.idempotency_key (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        UUID         NOT NULL,
    scope_id         UUID         NOT NULL,

    idempotency_key  TEXT         NOT NULL,
    call_name        TEXT         NOT NULL,
    argument_digest  TEXT         NOT NULL,

    produced_view    TEXT         NOT NULL,
    produced_number  BIGINT       NOT NULL,

    first_seen_at    TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_idempotency_key_once
        UNIQUE (tenant_id, scope_id, idempotency_key),
    CONSTRAINT ck_idempotency_key_view
        CHECK (produced_view IN ('item', 'iteration', 'milestone')),
    CONSTRAINT ck_idempotency_key_number
        CHECK (produced_number > 0)
);

ALTER TABLE worklist.idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.idempotency_key FORCE  ROW LEVEL SECURITY;

CREATE POLICY idempotency_key_tenant_isolation ON worklist.idempotency_key
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- The three privileges every table of this schema carries: SELECT to look a
-- key up, INSERT to spend it, UPDATE to write a later use over an expired
-- row. No DELETE: an expired row is overwritten, never removed.
-- `ServiceRolePrivilegeIT` asserts the exact set for every table.
GRANT SELECT, INSERT, UPDATE ON worklist.idempotency_key TO kumbuka_worklist;
