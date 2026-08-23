-- ===========================================================================
-- V2: the service's own database role, and every privilege it holds.
--
-- The service connects as `kumbuka_worklist` and as nothing else. It OWNS
-- nothing. Everything it may do in this schema is written out below, one
-- privilege at a time, on one named table at a time.
--
-- WHY THIS DEPARTS FROM THE SIBLING SERVICE, WHICH IS OTHERWISE THE TEMPLATE
--
-- The dispatch service arranges the same thing by ownership: its migration
-- issues no table grant at all, and an `afterMigrate` callback hands the
-- schema and every relation in it to the runtime role, on the reasoning that
-- an owner needs no GRANT and a grant list is a thing that drifts.
--
-- The consequence of that arrangement follows from PostgreSQL rather than
-- from anyone's intent: an owner holds the FULL privilege set on what it
-- owns — DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE —
-- implicitly, with no grant anywhere to show for it. A sweep that hands over
-- every relation in a schema hands over the Flyway history table with them,
-- because that table lives in the schema too.
--
-- TRUNCATE is why that matters. It bypasses row-level security completely,
-- independently of every policy and of whether `app.tenant_id` is bound. A
-- runtime role holding it can empty a tenant-scoped table across the tenant
-- boundary, and no part of the isolation apparatus sees it happen. The same
-- is true of TRIGGER and REFERENCES for a smaller blast radius: both let a
-- role attach something of its own to a table whose contents it should only
-- be reading and writing row by row.
--
-- So this service keeps the migrator as owner and enumerates. The cost is
-- real and is accepted: a table added by a later migration receives NO
-- privilege automatically, and a migration that forgets its grant produces a
-- service that cannot read its own new table. That failure is loud and
-- immediate. The failure it replaces is silent and permanent.
--
-- The drift the sibling's comment warns about is answered by a probe rather
-- than by ownership: `ServiceRolePrivilegeIT` reads the catalog, requires
-- every table in this schema to carry exactly SELECT, INSERT, UPDATE for the
-- runtime role, and requires `flyway_schema_history` to carry nothing. A
-- grant list that drifts from its schema fails the build.
--
-- WHY THERE IS NO DELETE
--
-- DELETE is granted where a verb deletes, and that is shown rather than
-- assumed. The substrate's entire caller surface is `WhoamiResource` and the
-- health endpoint; neither writes. `ScopeDirectory` reads one view. No path
-- in this repository issues a DELETE against `worklist.item`, and the target
-- state removes the need in the domain half too: withdrawal is a status and
-- there is no hard delete (target state section 3.2, ADR-0040). So DELETE is
-- not granted, and the day a verb genuinely deletes is the day the grant is
-- added with that verb.
--
-- TWO ROLE ATTRIBUTES ARE LOAD-BEARING, AND THIS MIGRATION ONLY CHECKS THEM
--
--   NOSUPERUSER   a superuser bypasses row-level security unconditionally.
--   NOBYPASSRLS   so does a role carrying BYPASSRLS.
--
-- Either one silently evaporates the tenant filter: rows returned, no error,
-- every test green. The migrator cannot grant either attribute and cannot
-- take it away — those are superuser-only operations, and this service
-- migrates with CREATEROLE and nothing more. That is why the block below
-- RAISES instead of repairing. A migration that quietly stripped a security
-- attribute would be a migration that could quietly add one; refusing to run
-- against a wrongly-shaped role leaves the decision with whoever shaped it,
-- and leaves a message saying so.
--
-- THE OPERATOR BOUNDARY IS THE LINE THAT IS NOT HERE
--
-- No grant is issued to the provider role. It cannot read an item because no
-- privilege exists that would let it, not because a rule in the application
-- forbids it. There is deliberately no statement below naming that role: an
-- assurance about an absence is kept by writing nothing, and it is proven by
-- a probe that observes the refusal at the database — and observes the access
-- a temporarily granted privilege allows, because an absence that was never
-- seen to matter is not a boundary.
--
-- THE PASSWORD BELOW IS A PLACEHOLDER AND MUST BE ROTATED
--
-- It is written so that the service comes up against an empty database with
-- no manual step, which is what makes a cold start reproducible. It is not a
-- credential: any deployment reachable from outside a development machine
-- replaces it with `ALTER ROLE kumbuka_worklist PASSWORD …` from its own
-- secret store, as an operational act outside this repository.
-- ===========================================================================

DO $do$
DECLARE
    is_super   boolean;
    is_bypass  boolean;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'kumbuka_worklist') THEN
        -- A CREATEROLE migrator cannot confer SUPERUSER or BYPASSRLS, so a
        -- role created here structurally cannot carry either. The check below
        -- is for the other path: a role an operator created beforehand.
        CREATE ROLE kumbuka_worklist LOGIN PASSWORD 'change-me-kumbuka-worklist';
        RAISE NOTICE 'created role kumbuka_worklist with the placeholder password — rotate it';
    END IF;

    SELECT rolsuper, rolbypassrls INTO is_super, is_bypass
    FROM pg_catalog.pg_roles WHERE rolname = 'kumbuka_worklist';

    IF is_super OR is_bypass THEN
        RAISE EXCEPTION
            'kumbuka_worklist carries superuser=% bypassrls=% — either one makes the '
            'row-level-security policies in V3 inert, and this migration will not '
            'create a schema whose isolation cannot hold. Recreate the role without '
            -- Cast to text before the placeholder. RAISE formats a boolean
            -- through the type's own output function, which yields `t` and
            -- `f`; the message is read by whoever has to repair the role, at
            -- deployment time, and `superuser=f bypassrls=t` is a puzzle where
            -- `superuser=false bypassrls=true` is an instruction.
            'them.', is_super::text, is_bypass::text;
    END IF;

    -- Raw SQL and psql sessions land in the service's own schema rather than
    -- in `public`. Hibernate and Flyway are pinned by configuration and do
    -- not depend on this; it is here so an unqualified statement typed by a
    -- human fails in the right place.
    --
    -- Since PostgreSQL 16 a CREATEROLE role may only ALTER a role it holds
    -- ADMIN OPTION on, which it does for a role it created itself. The other
    -- path is a role an operator created beforehand, and there the statement
    -- is refused — with a message about privileges that says nothing about
    -- what is actually wrong, so it is named here instead.
    BEGIN
        EXECUTE 'ALTER ROLE kumbuka_worklist SET search_path = worklist';
    EXCEPTION WHEN insufficient_privilege THEN
        RAISE EXCEPTION
            'the migrating role % may not configure kumbuka_worklist. That happens when '
            'the service role was created by somebody else, so this migrator holds no '
            'ADMIN OPTION on it. Grant it (GRANT kumbuka_worklist TO %I WITH ADMIN '
            'OPTION) and re-run, or let this migration create the role itself.',
            current_user, current_user;
    END;
END
$do$;

-- ---------------------------------------------------------------------------
-- The privileges. This block is the whole entitlement of the runtime role in
-- this schema, and it is meant to be read as a list rather than trusted as a
-- rule.
--
-- The REVOKE first, and the asymmetry is deliberate. A collective REVOKE can
-- only ever remove, so it cannot widen anything and it makes the GRANTs below
-- the exact statement of what is held rather than an addition to whatever was
-- held before. A collective GRANT is the opposite in every respect, which is
-- why there is none.
--
-- `flyway_schema_history` is inside this schema and is therefore covered by
-- the REVOKE and named in no GRANT. The runtime role holds nothing on it: it
-- is the migrator's record of what the migrator did, and a runtime role that
-- can rewrite it can make a schema lie about its own version.
-- ---------------------------------------------------------------------------
REVOKE ALL ON ALL TABLES IN SCHEMA worklist FROM kumbuka_worklist;
REVOKE ALL ON SCHEMA worklist FROM kumbuka_worklist;

-- Reaching the schema at all. USAGE is not a privilege on any table in it.
GRANT USAGE ON SCHEMA worklist TO kumbuka_worklist;

-- One table, three privileges, both named. No DELETE (see above), no
-- TRUNCATE, no TRIGGER, no REFERENCES.
GRANT SELECT, INSERT, UPDATE ON worklist.item TO kumbuka_worklist;
