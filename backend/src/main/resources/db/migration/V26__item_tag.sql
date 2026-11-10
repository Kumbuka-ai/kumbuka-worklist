-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V26: the tags an item carries.
--
-- A tag is a token of its scope's vocabulary. The vocabulary is the
-- platform's: `platform.tag`, read by this service only through the view
-- `platform.tag_access`, which publishes the tags of the scopes the calling
-- subject may see. This table holds the one thing that is this service's:
-- which item carries which tag, who attached it and when.
--
-- WHAT A ROW HOLDS
--
-- The item, by its key (tenant, scope, number) as every reference onto an
-- item is held (ADR-0042), and the tag, by the platform's identity. The tag
-- carries NO foreign key: it lives in another schema, owned by another
-- service, and a key across that boundary would couple this schema's
-- migrations to the platform's. Whether a tag exists and whether it is
-- withdrawn is asked of the view at the moment of attaching.
--
-- `attached_by` is the subject that attached the tag: who did what, the
-- same kind of fact a shared-scope write records. It is never counted and
-- never aggregated per subject (constraint audit-no-activity-monitoring).
--
-- A tag is a relation and not a field of the item: attaching and detaching
-- move neither the item's `changed_at` nor its conflict token.
--
-- DELETE, AND WHY THIS IS THE FIRST TABLE THAT CARRIES IT
--
-- V2 grants DELETE "the day a verb genuinely deletes", and `tag_detach` is
-- that verb: an assignment removed is gone, it has no status to fall back
-- to and no address that must keep resolving. The privilege is granted on
-- this table and on no other; `ServiceRolePrivilegeIT` holds the set per
-- table. The item is never deleted (no DELETE on `worklist.item`), so the
-- ON DELETE CASCADE below states what the assignment is -- a dependent of
-- its item -- rather than a path any verb takes.
--
-- ADDITIVE, AND COMPATIBLE WITH THE RELEASE BEFORE IT (DEC-0018)
--
-- A new relation and nothing else. No column, table or policy that exists
-- is changed. The image before this one does not know the table and runs
-- unchanged against this schema.
-- ===========================================================================

CREATE TABLE worklist.item_tag (
    tenant_id    UUID         NOT NULL,
    scope_id     UUID         NOT NULL,
    item_number  BIGINT       NOT NULL,

    tag_id       UUID         NOT NULL,

    attached_by  TEXT         NOT NULL,
    attached_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, scope_id, item_number, tag_id),

    CONSTRAINT fk_item_tag_item FOREIGN KEY (tenant_id, scope_id, item_number)
        REFERENCES worklist.item (tenant_id, scope_id, number) ON DELETE CASCADE
);

-- The filter of `query` asks which items carry one tag.
CREATE INDEX idx_item_tag_tag ON worklist.item_tag (tag_id);

ALTER TABLE worklist.item_tag ENABLE ROW LEVEL SECURITY;

CREATE POLICY item_tag_tenant_isolation ON worklist.item_tag
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- The three privileges every table of this schema carries, and DELETE for
-- the verb that removes an assignment (see above). No TRUNCATE, no TRIGGER,
-- no REFERENCES.
GRANT SELECT, INSERT, UPDATE, DELETE ON worklist.item_tag TO kumbuka_worklist;
