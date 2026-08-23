-- ===========================================================================
-- V1: the worklist schema.
--
-- One PostgreSQL instance, one database, one NAMED schema and one database
-- role per service (ADR-0038, ADR-0042 clause 1). `worklist` is that schema.
-- Nothing here reaches into another service's schema: no foreign key, no
-- join, no view. A reference to an object owned elsewhere is stored as an
-- address and never resolved.
--
-- ON THE PER-SERVICE READING OF THE DATA-HOLDING CLAUSE
--
-- Section 9 of the target state quotes the clause as one schema and one
-- database role per SCOPE. The clause says per SERVICE, and the two cannot
-- both hold: a schema per scope would put the tenancy axis in the schema
-- name and leave row-level security nothing to filter on. The sibling
-- service resolved this the same way — one schema, row-level security on
-- `tenant_id` — and this migration follows that resolution. Correcting the
-- document is not this repository's act.
--
-- WHAT THIS MIGRATION DELIBERATELY DOES NOT CONTAIN
--
-- The worklist domain — declared vocabulary and the four predicates, the
-- customer-defined attribute set, typed relations between items, the
-- planning layer of milestones and iterations, the claim lease, the number
-- space — is the domain half and is built separately. This is the substrate:
-- the schema, the tenancy axis, and the seam that makes both provable.
--
-- WHY THE TABLE HERE IS THE DOMAIN OBJECT AND NOT A SCAFFOLD
--
-- Row-level security cannot be asserted against an empty schema, so the
-- probes need a table to hang on. The sibling service used a `scope` table
-- for that and then had to retire it in a later migration, because a service
-- that keeps its own scope table is a service that can widen its own
-- entitlement. The lesson is taken here rather than repeated: this service
-- keeps NO scope table, holds a `scope_id` column on its own rows, and reads
-- the platform's published access contract at runtime.
--
-- So the probe table is `item` — the object the list is actually made of
-- (target state section 3.1: not "row", which belongs to the Markdown store
-- being replaced, and not "issue", which two foreign products already own).
-- It carries the smallest core that is genuinely an item and nothing that
-- anticipates the domain half.
--
-- ON THE SCHEMA ITSELF
--
-- Flyway is configured with this schema as its default (see
-- application.properties), so it creates the schema before running this
-- migration and places its history table inside it. The statement below is
-- therefore normally a no-op, and it is written anyway: the schema is the
-- first thing this service owns, and a reader should find that fact in the
-- migration rather than only in a property file.
-- ===========================================================================

CREATE SCHEMA IF NOT EXISTS worklist;

-- Never reachable via PUBLIC. What the service role may do in this schema is
-- enumerated in V2, statement by statement; PUBLIC gets nothing, and no role
-- acquires anything here by default.
REVOKE ALL ON SCHEMA worklist FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- item — what a scope intends to do, one entry of it.
--
--   tenant_id   the row-level-security axis. Every tenant-scoped table in
--               this schema carries it under this exact name; the
--               completeness probe reads the catalog and fails on any that
--               does not.
--   scope_id    the scope this item belongs to, as the platform directory
--               identifies it. Stored, never resolved, no foreign key —
--               resolving it is a runtime read of `platform.scope_access`,
--               not a schema-level reference.
--   title       one line, human readable (target state section 3.3).
--
-- Status, the declared attribute set, relations and planning membership are
-- NOT here. They are the domain half, and an item that carries a status
-- column before the vocabulary mechanism exists would be an item whose
-- status means whatever the first writer assumed.
--
-- gen_random_uuid() is a core function since PostgreSQL 13 and needs no
-- extension — which is one superuser-only operation the migrator does not
-- have to carry.
-- ---------------------------------------------------------------------------
CREATE TABLE worklist.item (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL,
    scope_id    UUID         NOT NULL,
    title       TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_item_title CHECK (length(btrim(title)) > 0)
);

CREATE INDEX idx_item_tenant ON worklist.item (tenant_id);
CREATE INDEX idx_item_scope  ON worklist.item (tenant_id, scope_id);
