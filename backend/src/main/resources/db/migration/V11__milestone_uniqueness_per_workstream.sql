-- ===========================================================================
-- V11: milestone number uniqueness moves from per-scope to per-workstream.
--
-- The ratified counter split of 2026-09-08 puts the milestone number
-- space per (scope, workstream). V4 constrained milestone numbers as
-- UNIQUE (tenant, scope, number), which is the exact statement of a
-- per-scope counter. With the counter per workstream, two workstreams
-- LEGITIMATELY share a number — and the old constraint refuses the
-- second insert.
--
-- V8, V9 and V10 built the workstream, backfilled the reference and
-- narrowed it. Splitting the uniqueness is the fourth step; it lands
-- as its own migration for the same reason V10 was one — an applied
-- file is not edited, and V9's checksum is recorded from the first
-- boot after V9 landed.
--
-- WHY NO GRANT IS ISSUED HERE
--
-- Same reason V5, V6, V7 and V10 hand out none. The runtime role holds
-- SELECT, INSERT, UPDATE on `worklist.milestone` from V4 without a
-- column list; a table-level privilege covers every constraint the
-- table acquires. `ServiceRolePrivilegeIT` asserts the exact privilege
-- set in both directions and would report a departure either way.
-- ===========================================================================

-- The old shape stated a per-scope counter as the store's identity of a
-- milestone. Drop it, and add the per-(scope, workstream) form beside.
--
-- The workstream_id column is NOT NULL as of V10, so it always
-- participates in the tuple — there is no null-in-unique-index subtlety
-- to reason about.

ALTER TABLE worklist.milestone
    DROP CONSTRAINT uq_milestone_number;

ALTER TABLE worklist.milestone
    ADD CONSTRAINT uq_milestone_number_per_workstream
        UNIQUE (tenant_id, scope_id, workstream_id, number);
