-- ===========================================================================
-- V8: the workstream — the fourth view.
--
-- The item is in the middle. It carries the workstream as an obligation and
-- the milestone as an option; the milestone carries its workstream as an
-- obligation of its own, and the invariant that binds the two is: if an item
-- has a milestone, that milestone lies in the item's workstream. The
-- iteration is on a different axis and touches neither.
--
-- The workstream is a SELECTOR in the full sense: it is declared, never
-- minted by first use; it is withdrawable, never deleted; it is renamable
-- for as long as nothing points at it and fixed once anything does; it
-- carries a mandatory description, because a workstream without one is a
-- violation and not a report.
--
-- WHY THIS IS ADDITIVE AND NOT A REPLACEMENT OF V4
--
-- V4 replaced its own previous content once and the header records the
-- measurement that made that admissible. V5 refused to repeat it and said
-- why. This migration inherits both: it adds a table, seeds one row per
-- scope, opens the new selector in the scopes that already exist, and
-- touches no earlier file. The chain moves forward — never sideways.
--
-- WHY THE ORDER MATTERS
--
-- V9 hangs `workstream_id` on `item` and `milestone` and points it at rows in
-- this table. Those rows have to EXIST at the moment the pointing column is
-- created, or the migration that creates it has to fabricate targets it did
-- not declare. So V8 does the whole declaration: the table, the selector row
-- for each scope, the number-space counter for that selector, and the
-- default workstream that every existing item and milestone will point at.
-- V9 adds the columns and the backfill. V10 narrows to NOT NULL.
--
-- WHY THIS FILE USES DO-BLOCKS TO SET THE TENANT
--
-- V3 turned on FORCE ROW LEVEL SECURITY on every table, which binds the
-- table's OWNER too — including the migrator running this file. Without
-- `app.tenant_id` bound in the transaction, an INSERT is refused by the
-- policy and the migration halts. The blocks below loop over every scope
-- registered in `worklist.scope_setting`, bind the axis for the length of
-- one statement, and then unset it. A migration that guessed a value would
-- defeat the point of the setting; the value comes from the row being
-- inserted for.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- 1. THE `workstream` TABLE.
--
-- Shape follows `worklist.selector`: id, tenancy pair, number, token, status,
-- and the address's usual composite uniqueness so a foreign key from another
-- table can bind the reference to the SAME tenant it belongs to.
--
-- ADDITIONS THIS TABLE CARRIES THAT `selector` DOES NOT
--
--   * `number`: the workstream has an address of its own —
--     `worklist://<scope>/workstream/<n>` — and it is drawn from the number
--     space of the `workstream` selector, exactly the way `item`,
--     `iteration` and `milestone` draw theirs. See section 2 for the
--     counter and section 3 for the row.
--
--   * `description`: a workstream without one is a VIOLATION, and the check
--     lives in the domain because a `CHECK (length(btrim(description)) > 0)`
--     is a constraint violation at flush and reaches the caller as a 500
--     rather than as a typed refusal. `WorkstreamService.declare` refuses
--     an empty or whitespace-only description with the reason spelled out.
--
--   * `is_default`: a boolean that stamps the auto-created workstream every
--     scope opens with. The default admits no rename and no withdrawal —
--     `WorkstreamService.rename` and `.withdraw` refuse it by this flag —
--     and a partial unique index below fixes that a scope has AT MOST ONE.
--     A flag rather than a magic token (`'default'`) is deliberate: the
--     token is renamable up to the first pointer and would otherwise be an
--     unreliable identity carrier.
--
-- WHY THE RENAME GUARD IS NOT A TRIGGER
--
-- `selector.token` is guarded by `selector_address_is_immutable` because a
-- selector's token is NEVER renamable — every address ever issued resolves
-- through that row. A workstream's is CONDITIONALLY renamable: allowed
-- while nothing points at it, refused once something does. That predicate
-- is a two-table read (`item`, `milestone`) and a trigger doing it would
-- push the enforcement path across a table boundary the trigger does not
-- own. The service enforces it in one place, and the tests below its
-- signature exercise both directions.
-- ---------------------------------------------------------------------------
CREATE TABLE worklist.workstream (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL,
    scope_id      UUID         NOT NULL,
    number        BIGINT       NOT NULL,
    token         TEXT         NOT NULL,
    description   TEXT         NOT NULL,
    status        TEXT         NOT NULL DEFAULT 'declared',
    is_default    BOOLEAN      NOT NULL DEFAULT false,
    conflict_token TEXT        NOT NULL DEFAULT gen_random_uuid()::text,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Same form as the selector token: a leading letter, lower-case
    -- alphanumerics and interior hyphens. Case is REJECTED rather than
    -- folded, so `Product` and `product` can never resolve to one row.
    CONSTRAINT ck_workstream_token       CHECK (token ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    CONSTRAINT ck_workstream_status      CHECK (status IN ('declared', 'withdrawn')),
    CONSTRAINT ck_workstream_number      CHECK (number > 0),
    -- A whitespace-only description would round-trip through the domain
    -- check; a stored constraint would then reject it at flush. Keeping
    -- description non-empty is the domain's job — see the header above —
    -- and NOT NULL alone here is the write-path floor.
    CONSTRAINT uq_workstream_token       UNIQUE (tenant_id, scope_id, token),
    CONSTRAINT uq_workstream_number      UNIQUE (tenant_id, scope_id, number),
    CONSTRAINT uq_workstream_tenant_id   UNIQUE (tenant_id, id)
);

-- One default workstream per scope, and only one. The flag is what says
-- which row it is; the partial unique index is what makes that a rule the
-- store keeps.
CREATE UNIQUE INDEX uq_workstream_default_per_scope
    ON worklist.workstream (tenant_id, scope_id)
    WHERE is_default = true;

CREATE INDEX idx_workstream_scope ON worklist.workstream (tenant_id, scope_id);

ALTER TABLE worklist.workstream ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.workstream FORCE  ROW LEVEL SECURITY;

CREATE POLICY workstream_tenant_isolation ON worklist.workstream
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- The runtime role reaches the table through the same three privileges every
-- other table in this schema carries: SELECT, INSERT, UPDATE, no DELETE, no
-- TRUNCATE, no TRIGGER, no REFERENCES. `ServiceRolePrivilegeIT` asserts the
-- exact set for every table in the schema and would report a departure here
-- in either direction.
GRANT SELECT, INSERT, UPDATE ON worklist.workstream TO kumbuka_worklist;


-- ---------------------------------------------------------------------------
-- 2. THE `workstream` SELECTOR FOR EVERY SCOPE THAT ALREADY EXISTS.
--
-- Bootstrap declares three selectors — `item`, `iteration`, `milestone` — as
-- part of opening a scope. A fourth view was not there when those scopes
-- were opened, so this migration inserts it for each of them, and
-- `bootstrap-scope.sql` gains the same INSERT so a scope opened AFTER V8
-- carries it from the first act.
--
-- Every scope registered in `worklist.scope_setting` is one that ran
-- bootstrap; there is no other path to a settings row. That table is the
-- reliable enumerator, and the loop reads it.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    scope_row RECORD;
BEGIN
    FOR scope_row IN
        SELECT tenant_id, scope_id FROM worklist.scope_setting
    LOOP
        PERFORM set_config('app.tenant_id', scope_row.tenant_id::text, true);

        INSERT INTO worklist.selector (tenant_id, scope_id, token)
        VALUES (scope_row.tenant_id, scope_row.scope_id, 'workstream')
        ON CONFLICT (tenant_id, scope_id, token) DO NOTHING;

        -- The counter for the new selector opens at zero and is advanced by
        -- every workstream declaration that follows — including the one
        -- below for `default`. There is no scope-wide counter beside these
        -- three; the address form carries the view.
        INSERT INTO worklist.number_space (tenant_id, scope_id, selector_id, high_water_mark)
        SELECT scope_row.tenant_id,
               scope_row.scope_id,
               s.id,
               0
        FROM worklist.selector s
        WHERE s.tenant_id = scope_row.tenant_id
          AND s.scope_id  = scope_row.scope_id
          AND s.token     = 'workstream'
        ON CONFLICT (tenant_id, scope_id, selector_id) DO NOTHING;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END $$;


-- ---------------------------------------------------------------------------
-- 3. THE `default` WORKSTREAM FOR EVERY SCOPE.
--
-- The default is what an item and a milestone that name no workstream fall
-- to, so it has to EXIST at the moment V9 hangs `workstream_id` on those
-- tables and populates it. The mark of the workstream selector is advanced
-- to 1 in the same act — the first number handed out under the new view.
--
-- The description is stated here as a matter-of-fact sentence and can be
-- rewritten later through `WorkstreamService.update`: the default is the
-- fixed identity, its description is not.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    scope_row RECORD;
BEGIN
    FOR scope_row IN
        SELECT tenant_id, scope_id FROM worklist.scope_setting
    LOOP
        PERFORM set_config('app.tenant_id', scope_row.tenant_id::text, true);

        INSERT INTO worklist.workstream
            (tenant_id, scope_id, number, token, description, is_default)
        SELECT scope_row.tenant_id,
               scope_row.scope_id,
               1,
               'default',
               'The default workstream. Everything that names no other lands here; '
                    || 'its identity is fixed and only the description is settable.',
               true
        WHERE NOT EXISTS (
            SELECT 1 FROM worklist.workstream w
            WHERE w.tenant_id = scope_row.tenant_id
              AND w.scope_id  = scope_row.scope_id
              AND w.is_default = true
        );

        -- Move the counter to 1 so the next `declare` allocates 2. The
        -- number was HANDED OUT even though the domain did not run the
        -- allocator to hand it out — the invariant on the mark is what was
        -- issued, and the row above issued it.
        UPDATE worklist.number_space ns
        SET high_water_mark = GREATEST(ns.high_water_mark, 1)
        FROM worklist.selector s
        WHERE ns.tenant_id   = scope_row.tenant_id
          AND ns.scope_id    = scope_row.scope_id
          AND ns.selector_id = s.id
          AND s.token        = 'workstream';
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END $$;
