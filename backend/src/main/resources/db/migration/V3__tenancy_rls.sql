-- ===========================================================================
-- V3: row-level security on the tenant axis.
--
-- This is layer 2 of a two-layer enforcement model. Layer 1 is the Hibernate
-- @TenantId filter, which scopes every ORM-routed read and write. Layer 2 is
-- here, and it catches what layer 1 structurally cannot: raw SQL, native
-- queries, and any code path that reaches the database without passing the
-- ORM. Either layer alone would be load-bearing; both together are the seam.
--
-- THE PREDICATE, AND WHY IT IS WRITTEN THIS WAY
--
--     tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
--
-- `current_setting(…, true)` returns NULL instead of raising when the
-- setting is absent; NULLIF turns an empty string into NULL as well; and
-- `tenant_id = NULL` is not FALSE but NULL, which a policy treats as failing.
-- So a session that never bound a tenant sees no rows at all rather than
-- every row. RLS fails CLOSED, and that is the design rather than a side
-- effect — it is what makes a forgotten binding a visible emptiness instead
-- of a silent leak.
--
-- BOTH `USING` AND `WITH CHECK` ARE REQUIRED
--
-- `USING` filters what a statement may see; `WITH CHECK` constrains what it
-- may write. A policy with only `USING` lets a session insert a row under a
-- foreign tenant and then lose sight of it — data planted across the
-- boundary, invisible to the planter and to the tenant that now owns it.
--
-- BOTH `ENABLE` AND `FORCE` ARE REQUIRED, AND HERE FOR A DIFFERENT REASON
-- THAN IN THE SIBLING SERVICE
--
-- `ENABLE` switches the policy on for every role EXCEPT the table's owner.
-- In the dispatch service the runtime role IS the owner, so `ENABLE` alone
-- would have switched the policy off for the only role that ever connects,
-- and `FORCE` was what bound it.
--
-- Here the owner is the MIGRATOR and the runtime role owns nothing (V2), so
-- `ENABLE` already binds the role the service connects as. `FORCE` is still
-- required and still load-bearing, for the other role: it binds the migrator,
-- which is the role every future migration carrying DML runs as. Without it
-- a backfill would silently write across every tenant in the table, and the
-- migration would report success. `RowLevelSecurityProbeIT` removes FORCE,
-- watches the migrator walk past the policy, and puts it back.
--
-- The consequence for the probes is worth stating plainly, because it is
-- where this schema differs from its template: removing FORCE no longer
-- changes what the RUNTIME role sees. The probe that shows the runtime role's
-- isolation collapsing has to remove the POLICY, and that is what Probe B in
-- `RowLevelSecurityProbeIT` does.
--
-- This migration is pure DDL. Row-level security filters DML only, so no
-- tenant context is needed to apply it. A `beforeEachMigrate` callback
-- (TenantMigrationCallback) binds the GUC for any future migration that does
-- carry DML — without it, a seed or backfill would fail closed under FORCE
-- and quietly write nothing.
-- ===========================================================================

ALTER TABLE worklist.item ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.item FORCE  ROW LEVEL SECURITY;

CREATE POLICY item_tenant_isolation ON worklist.item
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
