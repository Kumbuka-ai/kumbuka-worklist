-- ===========================================================================
-- V18: ADR-0042 stage R1 -- expand. Readable keys beside the uuid ones.
--
-- ADR-0042: inside a service every relation between two rows is a declared
-- foreign key, and no foreign key carries a uuid. A reference to a primary
-- object (item, iteration, milestone, workstream) targets its tenant, scope
-- and number; a reference to any other row targets a BIGINT surrogate with
-- its tenant. This schema references everything through uuids today.
--
-- The conversion runs in three releases (DEC-0018: additive, compatible with
-- the previous image), and this is the first of them. It ADDS and changes
-- nothing that the running image reads or writes:
--
--   1. Every support table that loses its uuid identity in stage R3 gets
--      `pk BIGINT GENERATED ALWAYS AS IDENTITY` and UNIQUE (tenant_id, pk),
--      the target a stage-R2 foreign key will point at.
--   2. `item` gets UNIQUE (tenant_id, scope_id, number), the target of every
--      key onto an item. Today an item's address is unique only together
--      with its selector; every item hangs on the selector `item`, so the
--      narrower key holds -- and if a store says otherwise, this migration
--      refuses with the count rather than letting the index error speak.
--   3. Every child column `<ref>_id` gets a nullable sibling: `<ref>_number`
--      where the target is a primary object, `<ref>_pk` where it is a
--      support row. No constraint yet (stage R2), nothing renamed, the uuid
--      columns untouched.
--   4. `attribute_option` gets its name unique per definition. V4 left that
--      open on purpose; the decision to travel options by name outward
--      closes it. Refuses with the count on a collision.
--   5. The number and scope of a primary object become immutable below the
--      domain: one trigger per primary table. This is permanent, not part
--      of the stage machinery: a number that is a key target must never
--      move, or every row holding it silently re-points.
--   6. One BEFORE INSERT OR UPDATE trigger per child table keeps the uuid
--      column and its sibling in step, whichever side the writer filled.
--      The image before this one writes uuids only; the stage-R2 image
--      writes the new columns only; both must leave a consistent row. These
--      triggers are scaffolding and are REMOVED IN STAGE R3.
--   7. A backfill of every new column, per scope, idempotent.
--
-- THE SYNC RULE
--
--   INSERT  the missing side is resolved from the parent; if both are given
--           they must name the same parent; if neither, the row carries no
--           reference (only where the uuid column is nullable).
--   UPDATE  the side that changed against OLD wins and the other is
--           re-resolved; if both changed they must agree.
--   An unresolvable reference and a contradicting pair end in RAISE, never
--   in NULL: a NULL would turn a dangling reference into "no reference",
--   which is the silent failure this whole change exists to remove.
--
-- A primary object is resolved within the referencing row's own scope: a
-- uuid that names an item of another scope is refused rather than turned
-- into a number, because that number would name a DIFFERENT item in this
-- scope. Support rows are resolved by tenant alone, which is the shape of
-- the key they will get.
--
-- WHY SECURITY INVOKER, AND WHY THE SEARCH PATH IS PINNED
--
-- The resolver runs with the privileges and the row-level-security binding
-- of whoever writes the child row. A parent in another tenant is therefore
-- invisible to it, exactly as it is to the writer. The lookups also name
-- the tenant explicitly, so the isolation does not rest on the policies
-- alone. `SET search_path = worklist, pg_temp` keeps a caller's search path
-- from substituting a table of the same name.
--
-- WHO OWNS WHAT
--
-- The migrator owns every function and trigger here. The runtime role holds
-- no TRIGGER privilege (V2) and cannot drop or replace them. EXECUTE on the
-- two resolvers is taken from PUBLIC and given to the runtime role, which
-- needs it because a trigger function calls them under the writer's
-- identity.
--
-- RLS
--
-- FORCE ROW LEVEL SECURITY binds the migrator too, so the backfill binds
-- `app.tenant_id` per scope before its DML -- the pattern of V8, V12, V15.
--
-- NO GRANT ON A TABLE IS ISSUED HERE
--
-- Every column added below sits on a table the runtime role already holds
-- SELECT, INSERT, UPDATE on without a column list (V4, V8). An identity
-- column draws from its own sequence on INSERT without a sequence grant.
-- `ServiceRolePrivilegeIT` asserts the exact table privilege set.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- 1. Surrogates on the support tables.
-- ---------------------------------------------------------------------------
ALTER TABLE worklist.item_status          ADD COLUMN pk BIGINT GENERATED ALWAYS AS IDENTITY;
ALTER TABLE worklist.relation_type        ADD COLUMN pk BIGINT GENERATED ALWAYS AS IDENTITY;
ALTER TABLE worklist.selector             ADD COLUMN pk BIGINT GENERATED ALWAYS AS IDENTITY;
ALTER TABLE worklist.attribute_definition ADD COLUMN pk BIGINT GENERATED ALWAYS AS IDENTITY;
ALTER TABLE worklist.attribute_option     ADD COLUMN pk BIGINT GENERATED ALWAYS AS IDENTITY;
ALTER TABLE worklist.number_space         ADD COLUMN pk BIGINT GENERATED ALWAYS AS IDENTITY;
ALTER TABLE worklist.item_reference       ADD COLUMN pk BIGINT GENERATED ALWAYS AS IDENTITY;

ALTER TABLE worklist.item_status          ADD CONSTRAINT uq_item_status_tenant_pk          UNIQUE (tenant_id, pk);
ALTER TABLE worklist.relation_type        ADD CONSTRAINT uq_relation_type_tenant_pk        UNIQUE (tenant_id, pk);
ALTER TABLE worklist.selector             ADD CONSTRAINT uq_selector_tenant_pk             UNIQUE (tenant_id, pk);
ALTER TABLE worklist.attribute_definition ADD CONSTRAINT uq_attribute_definition_tenant_pk UNIQUE (tenant_id, pk);
ALTER TABLE worklist.attribute_option     ADD CONSTRAINT uq_attribute_option_tenant_pk     UNIQUE (tenant_id, pk);
ALTER TABLE worklist.number_space         ADD CONSTRAINT uq_number_space_tenant_pk         UNIQUE (tenant_id, pk);
ALTER TABLE worklist.item_reference       ADD CONSTRAINT uq_item_reference_tenant_pk       UNIQUE (tenant_id, pk);


-- ---------------------------------------------------------------------------
-- 2. and 4. The two uniqueness rules, each checked first so a store that
-- breaks it is refused with what breaks it.
--
-- The check runs as the migrator under the deployment's tenant binding and
-- sees that tenant's rows. The index is built over every row; a duplicate in
-- another tenant would still stop the build below, with the index's own
-- message instead of this one.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    clashes BIGINT;
BEGIN
    SELECT count(*) INTO clashes FROM (
        SELECT 1 FROM worklist.item
        GROUP BY tenant_id, scope_id, number HAVING count(*) > 1) d;
    IF clashes > 0 THEN
        RAISE EXCEPTION
            'ADR-0042 stage R1 refused: % (tenant, scope, number) combinations of '
            'worklist.item are held by more than one item. A key onto an item '
            'needs its number unique within the scope, and this store does not '
            'give that. Nothing was changed; resolve the duplicates first.',
            clashes;
    END IF;

    SELECT count(*) INTO clashes FROM (
        SELECT 1 FROM worklist.attribute_option
        GROUP BY tenant_id, definition_id, name HAVING count(*) > 1) d;
    IF clashes > 0 THEN
        RAISE EXCEPTION
            'ADR-0042 stage R1 refused: % option names repeat within one attribute '
            'definition. Options travel outward by name, so a name has to say which '
            'option it is. Nothing was changed; rename or merge the duplicates first.',
            clashes;
    END IF;
END $$;

ALTER TABLE worklist.item
    ADD CONSTRAINT uq_item_number UNIQUE (tenant_id, scope_id, number);
ALTER TABLE worklist.attribute_option
    ADD CONSTRAINT uq_attribute_option_name UNIQUE (tenant_id, definition_id, name);


-- ---------------------------------------------------------------------------
-- 3. The sibling columns. Nullable, unconstrained until stage R2.
-- ---------------------------------------------------------------------------
ALTER TABLE worklist.item                 ADD COLUMN status_pk                BIGINT,
                                          ADD COLUMN milestone_number         BIGINT,
                                          ADD COLUMN workstream_number        BIGINT;
ALTER TABLE worklist.attribute_option     ADD COLUMN definition_pk            BIGINT;
ALTER TABLE worklist.number_space         ADD COLUMN selector_pk              BIGINT;
ALTER TABLE worklist.item_reference       ADD COLUMN item_number              BIGINT;
ALTER TABLE worklist.item_relation        ADD COLUMN from_item_number         BIGINT,
                                          ADD COLUMN to_item_number           BIGINT,
                                          ADD COLUMN relation_type_pk         BIGINT;
ALTER TABLE worklist.iteration_membership ADD COLUMN iteration_number         BIGINT,
                                          ADD COLUMN item_number              BIGINT;
ALTER TABLE worklist.claim                ADD COLUMN item_number              BIGINT;
ALTER TABLE worklist.scope_setting        ADD COLUMN current_iteration_number BIGINT;


-- ---------------------------------------------------------------------------
-- 5. A primary object's number and scope never move.
--
-- Permanent. Stage R3 removes the sync triggers below, not this one.
-- ---------------------------------------------------------------------------
CREATE FUNCTION worklist.primary_address_is_immutable() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER SET search_path = worklist, pg_temp AS $$
BEGIN
    IF NEW.number IS DISTINCT FROM OLD.number OR NEW.scope_id IS DISTINCT FROM OLD.scope_id THEN
        RAISE EXCEPTION
            'the address of this % row is immutable: number % in scope % may not become number % '
            'in scope %. Every row that refers to it holds that number as its key, so a '
            'change would re-point all of them at once and silently.',
            TG_TABLE_NAME, OLD.number, OLD.scope_id, NEW.number, NEW.scope_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER item_address_is_immutable BEFORE UPDATE ON worklist.item
    FOR EACH ROW EXECUTE FUNCTION worklist.primary_address_is_immutable();
CREATE TRIGGER iteration_address_is_immutable BEFORE UPDATE ON worklist.iteration
    FOR EACH ROW EXECUTE FUNCTION worklist.primary_address_is_immutable();
CREATE TRIGGER milestone_address_is_immutable BEFORE UPDATE ON worklist.milestone
    FOR EACH ROW EXECUTE FUNCTION worklist.primary_address_is_immutable();
CREATE TRIGGER workstream_address_is_immutable BEFORE UPDATE ON worklist.workstream
    FOR EACH ROW EXECUTE FUNCTION worklist.primary_address_is_immutable();


-- ---------------------------------------------------------------------------
-- 6. The two resolvers and the per-table sync triggers.
--
-- `sync_number_reference` keeps a (uuid, number) pair onto a primary object
-- in step; `sync_pk_reference` a (uuid, pk) pair onto a support row. The
-- parent table name comes only from the trigger functions below, never from
-- a caller, and is quoted with %I.
-- ---------------------------------------------------------------------------
CREATE FUNCTION worklist.sync_number_reference(
        parent TEXT, op TEXT, tenant UUID, scope UUID,
        old_id UUID, new_id UUID, old_number BIGINT, new_number BIGINT,
        OUT id UUID, OUT number BIGINT)
    LANGUAGE plpgsql SECURITY INVOKER SET search_path = worklist, pg_temp AS $$
DECLARE
    id_moved     BOOLEAN := op = 'INSERT' OR new_id     IS DISTINCT FROM old_id;
    number_moved BOOLEAN := op = 'INSERT' OR new_number IS DISTINCT FROM old_number;
    found_number BIGINT;
    found_scope  UUID;
    found_id     UUID;
BEGIN
    id := new_id;
    number := new_number;
    IF NOT id_moved AND NOT number_moved THEN
        RETURN;
    END IF;
    IF op = 'UPDATE' AND id_moved AND NOT number_moved THEN
        number := NULL;
    ELSIF op = 'UPDATE' AND number_moved AND NOT id_moved THEN
        id := NULL;
    END IF;
    IF id IS NULL AND number IS NULL THEN
        RETURN;
    END IF;

    IF id IS NOT NULL THEN
        EXECUTE format('SELECT number, scope_id FROM worklist.%I WHERE tenant_id = $1 AND id = $2',
                       parent)
            INTO found_number, found_scope USING tenant, id;
        IF found_number IS NULL THEN
            RAISE EXCEPTION
                'ADR-0042 sync: the % % referred to cannot be resolved in tenant %. '
                'Refused rather than stored without its number.', parent, id, tenant;
        END IF;
        IF found_scope IS DISTINCT FROM scope THEN
            RAISE EXCEPTION
                'ADR-0042 sync: the % % lives in scope %, not in scope % of the referring '
                'row. A key onto a primary object stays inside one scope.',
                parent, id, found_scope, scope;
        END IF;
        IF number IS NOT NULL AND number <> found_number THEN
            RAISE EXCEPTION
                'ADR-0042 sync: contradicting reference -- % % is number %, the row names '
                'number %.', parent, id, found_number, number;
        END IF;
        number := found_number;
    ELSE
        EXECUTE format('SELECT id FROM worklist.%I WHERE tenant_id = $1 AND scope_id = $2 '
                       'AND number = $3', parent)
            INTO found_id USING tenant, scope, number;
        IF found_id IS NULL THEN
            RAISE EXCEPTION
                'ADR-0042 sync: no % numbered % in scope % of tenant %. Refused rather '
                'than stored without its identity.', parent, number, scope, tenant;
        END IF;
        id := found_id;
    END IF;
END;
$$;

CREATE FUNCTION worklist.sync_pk_reference(
        parent TEXT, op TEXT, tenant UUID,
        old_id UUID, new_id UUID, old_pk BIGINT, new_pk BIGINT,
        OUT id UUID, OUT pk BIGINT)
    LANGUAGE plpgsql SECURITY INVOKER SET search_path = worklist, pg_temp AS $$
DECLARE
    id_moved BOOLEAN := op = 'INSERT' OR new_id IS DISTINCT FROM old_id;
    pk_moved BOOLEAN := op = 'INSERT' OR new_pk IS DISTINCT FROM old_pk;
    found_pk BIGINT;
    found_id UUID;
BEGIN
    id := new_id;
    pk := new_pk;
    IF NOT id_moved AND NOT pk_moved THEN
        RETURN;
    END IF;
    IF op = 'UPDATE' AND id_moved AND NOT pk_moved THEN
        pk := NULL;
    ELSIF op = 'UPDATE' AND pk_moved AND NOT id_moved THEN
        id := NULL;
    END IF;
    IF id IS NULL AND pk IS NULL THEN
        RETURN;
    END IF;

    IF id IS NOT NULL THEN
        EXECUTE format('SELECT pk FROM worklist.%I WHERE tenant_id = $1 AND id = $2', parent)
            INTO found_pk USING tenant, id;
        IF found_pk IS NULL THEN
            RAISE EXCEPTION
                'ADR-0042 sync: the % % referred to cannot be resolved in tenant %. '
                'Refused rather than stored without its key.', parent, id, tenant;
        END IF;
        IF pk IS NOT NULL AND pk <> found_pk THEN
            RAISE EXCEPTION
                'ADR-0042 sync: contradicting reference -- % % is key %, the row names '
                'key %.', parent, id, found_pk, pk;
        END IF;
        pk := found_pk;
    ELSE
        EXECUTE format('SELECT id FROM worklist.%I WHERE tenant_id = $1 AND pk = $2', parent)
            INTO found_id USING tenant, pk;
        IF found_id IS NULL THEN
            RAISE EXCEPTION
                'ADR-0042 sync: no % with key % in tenant %. Refused rather than stored '
                'without its identity.', parent, pk, tenant;
        END IF;
        id := found_id;
    END IF;
END;
$$;

REVOKE EXECUTE ON FUNCTION worklist.sync_number_reference(TEXT, TEXT, UUID, UUID, UUID, UUID, BIGINT, BIGINT) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION worklist.sync_pk_reference(TEXT, TEXT, UUID, UUID, UUID, BIGINT, BIGINT) FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION worklist.sync_number_reference(TEXT, TEXT, UUID, UUID, UUID, UUID, BIGINT, BIGINT) TO kumbuka_worklist;
GRANT  EXECUTE ON FUNCTION worklist.sync_pk_reference(TEXT, TEXT, UUID, UUID, UUID, BIGINT, BIGINT) TO kumbuka_worklist;


-- One trigger function per child table. On INSERT there is no OLD row; the
-- resolvers ignore the old values then, and NEW stands in for them.

CREATE FUNCTION worklist.item_sync_references() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER SET search_path = worklist, pg_temp AS $$
DECLARE
    o RECORD;
BEGIN
    IF TG_OP = 'INSERT' THEN o := NEW; ELSE o := OLD; END IF;
    SELECT * INTO NEW.status_id, NEW.status_pk FROM worklist.sync_pk_reference(
        'item_status', TG_OP, NEW.tenant_id, o.status_id, NEW.status_id, o.status_pk, NEW.status_pk);
    SELECT * INTO NEW.milestone_id, NEW.milestone_number FROM worklist.sync_number_reference(
        'milestone', TG_OP, NEW.tenant_id, NEW.scope_id,
        o.milestone_id, NEW.milestone_id, o.milestone_number, NEW.milestone_number);
    SELECT * INTO NEW.workstream_id, NEW.workstream_number FROM worklist.sync_number_reference(
        'workstream', TG_OP, NEW.tenant_id, NEW.scope_id,
        o.workstream_id, NEW.workstream_id, o.workstream_number, NEW.workstream_number);
    RETURN NEW;
END;
$$;

CREATE FUNCTION worklist.attribute_option_sync_references() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER SET search_path = worklist, pg_temp AS $$
DECLARE
    o RECORD;
BEGIN
    IF TG_OP = 'INSERT' THEN o := NEW; ELSE o := OLD; END IF;
    SELECT * INTO NEW.definition_id, NEW.definition_pk FROM worklist.sync_pk_reference(
        'attribute_definition', TG_OP, NEW.tenant_id,
        o.definition_id, NEW.definition_id, o.definition_pk, NEW.definition_pk);
    RETURN NEW;
END;
$$;

CREATE FUNCTION worklist.number_space_sync_references() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER SET search_path = worklist, pg_temp AS $$
DECLARE
    o RECORD;
BEGIN
    IF TG_OP = 'INSERT' THEN o := NEW; ELSE o := OLD; END IF;
    SELECT * INTO NEW.selector_id, NEW.selector_pk FROM worklist.sync_pk_reference(
        'selector', TG_OP, NEW.tenant_id,
        o.selector_id, NEW.selector_id, o.selector_pk, NEW.selector_pk);
    RETURN NEW;
END;
$$;

CREATE FUNCTION worklist.item_reference_sync_references() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER SET search_path = worklist, pg_temp AS $$
DECLARE
    o RECORD;
BEGIN
    IF TG_OP = 'INSERT' THEN o := NEW; ELSE o := OLD; END IF;
    SELECT * INTO NEW.item_id, NEW.item_number FROM worklist.sync_number_reference(
        'item', TG_OP, NEW.tenant_id, NEW.scope_id,
        o.item_id, NEW.item_id, o.item_number, NEW.item_number);
    RETURN NEW;
END;
$$;

CREATE FUNCTION worklist.item_relation_sync_references() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER SET search_path = worklist, pg_temp AS $$
DECLARE
    o RECORD;
BEGIN
    IF TG_OP = 'INSERT' THEN o := NEW; ELSE o := OLD; END IF;
    SELECT * INTO NEW.from_item_id, NEW.from_item_number FROM worklist.sync_number_reference(
        'item', TG_OP, NEW.tenant_id, NEW.scope_id,
        o.from_item_id, NEW.from_item_id, o.from_item_number, NEW.from_item_number);
    SELECT * INTO NEW.to_item_id, NEW.to_item_number FROM worklist.sync_number_reference(
        'item', TG_OP, NEW.tenant_id, NEW.scope_id,
        o.to_item_id, NEW.to_item_id, o.to_item_number, NEW.to_item_number);
    SELECT * INTO NEW.relation_type_id, NEW.relation_type_pk FROM worklist.sync_pk_reference(
        'relation_type', TG_OP, NEW.tenant_id,
        o.relation_type_id, NEW.relation_type_id, o.relation_type_pk, NEW.relation_type_pk);
    RETURN NEW;
END;
$$;

CREATE FUNCTION worklist.iteration_membership_sync_references() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER SET search_path = worklist, pg_temp AS $$
DECLARE
    o RECORD;
BEGIN
    IF TG_OP = 'INSERT' THEN o := NEW; ELSE o := OLD; END IF;
    SELECT * INTO NEW.iteration_id, NEW.iteration_number FROM worklist.sync_number_reference(
        'iteration', TG_OP, NEW.tenant_id, NEW.scope_id,
        o.iteration_id, NEW.iteration_id, o.iteration_number, NEW.iteration_number);
    SELECT * INTO NEW.item_id, NEW.item_number FROM worklist.sync_number_reference(
        'item', TG_OP, NEW.tenant_id, NEW.scope_id,
        o.item_id, NEW.item_id, o.item_number, NEW.item_number);
    RETURN NEW;
END;
$$;

CREATE FUNCTION worklist.claim_sync_references() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER SET search_path = worklist, pg_temp AS $$
DECLARE
    o RECORD;
BEGIN
    IF TG_OP = 'INSERT' THEN o := NEW; ELSE o := OLD; END IF;
    SELECT * INTO NEW.item_id, NEW.item_number FROM worklist.sync_number_reference(
        'item', TG_OP, NEW.tenant_id, NEW.scope_id,
        o.item_id, NEW.item_id, o.item_number, NEW.item_number);
    RETURN NEW;
END;
$$;

CREATE FUNCTION worklist.scope_setting_sync_references() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER SET search_path = worklist, pg_temp AS $$
DECLARE
    o RECORD;
BEGIN
    IF TG_OP = 'INSERT' THEN o := NEW; ELSE o := OLD; END IF;
    SELECT * INTO NEW.current_iteration_id, NEW.current_iteration_number
        FROM worklist.sync_number_reference(
            'iteration', TG_OP, NEW.tenant_id, NEW.scope_id,
            o.current_iteration_id, NEW.current_iteration_id,
            o.current_iteration_number, NEW.current_iteration_number);
    RETURN NEW;
END;
$$;

CREATE TRIGGER item_sync_references BEFORE INSERT OR UPDATE ON worklist.item
    FOR EACH ROW EXECUTE FUNCTION worklist.item_sync_references();
CREATE TRIGGER attribute_option_sync_references BEFORE INSERT OR UPDATE ON worklist.attribute_option
    FOR EACH ROW EXECUTE FUNCTION worklist.attribute_option_sync_references();
CREATE TRIGGER number_space_sync_references BEFORE INSERT OR UPDATE ON worklist.number_space
    FOR EACH ROW EXECUTE FUNCTION worklist.number_space_sync_references();
CREATE TRIGGER item_reference_sync_references BEFORE INSERT OR UPDATE ON worklist.item_reference
    FOR EACH ROW EXECUTE FUNCTION worklist.item_reference_sync_references();
CREATE TRIGGER item_relation_sync_references BEFORE INSERT OR UPDATE ON worklist.item_relation
    FOR EACH ROW EXECUTE FUNCTION worklist.item_relation_sync_references();
CREATE TRIGGER iteration_membership_sync_references BEFORE INSERT OR UPDATE ON worklist.iteration_membership
    FOR EACH ROW EXECUTE FUNCTION worklist.iteration_membership_sync_references();
CREATE TRIGGER claim_sync_references BEFORE INSERT OR UPDATE ON worklist.claim
    FOR EACH ROW EXECUTE FUNCTION worklist.claim_sync_references();
CREATE TRIGGER scope_setting_sync_references BEFORE INSERT OR UPDATE ON worklist.scope_setting
    FOR EACH ROW EXECUTE FUNCTION worklist.scope_setting_sync_references();

COMMENT ON TRIGGER item_sync_references ON worklist.item IS
    'ADR-0042 stage R1 scaffolding: keeps the uuid and the number/pk columns in step. Removed in stage R3.';
COMMENT ON TRIGGER attribute_option_sync_references ON worklist.attribute_option IS
    'ADR-0042 stage R1 scaffolding: keeps the uuid and the number/pk columns in step. Removed in stage R3.';
COMMENT ON TRIGGER number_space_sync_references ON worklist.number_space IS
    'ADR-0042 stage R1 scaffolding: keeps the uuid and the number/pk columns in step. Removed in stage R3.';
COMMENT ON TRIGGER item_reference_sync_references ON worklist.item_reference IS
    'ADR-0042 stage R1 scaffolding: keeps the uuid and the number/pk columns in step. Removed in stage R3.';
COMMENT ON TRIGGER item_relation_sync_references ON worklist.item_relation IS
    'ADR-0042 stage R1 scaffolding: keeps the uuid and the number/pk columns in step. Removed in stage R3.';
COMMENT ON TRIGGER iteration_membership_sync_references ON worklist.iteration_membership IS
    'ADR-0042 stage R1 scaffolding: keeps the uuid and the number/pk columns in step. Removed in stage R3.';
COMMENT ON TRIGGER claim_sync_references ON worklist.claim IS
    'ADR-0042 stage R1 scaffolding: keeps the uuid and the number/pk columns in step. Removed in stage R3.';
COMMENT ON TRIGGER scope_setting_sync_references ON worklist.scope_setting IS
    'ADR-0042 stage R1 scaffolding: keeps the uuid and the number/pk columns in step. Removed in stage R3.';


-- ---------------------------------------------------------------------------
-- 7. Backfill, per scope, idempotent.
--
-- Each statement fills a sibling only where it is still NULL and the uuid
-- side is set, so a second run changes nothing. The UPDATE passes through
-- the sync trigger above, which re-checks the pair it was handed: a uuid
-- whose parent is missing or sits in another scope fails here, loudly,
-- rather than leaving a NULL behind.
--
-- The scopes are enumerated from every table that carries a reference, not
-- from `scope_setting` alone: a scope whose rows were planted without a
-- setting row would otherwise be skipped silently.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    s     RECORD;
    prior TEXT := current_setting('app.tenant_id', true);
BEGIN
    FOR s IN
        SELECT tenant_id, scope_id FROM worklist.item
        UNION SELECT tenant_id, scope_id FROM worklist.attribute_option
        UNION SELECT tenant_id, scope_id FROM worklist.number_space
        UNION SELECT tenant_id, scope_id FROM worklist.item_reference
        UNION SELECT tenant_id, scope_id FROM worklist.item_relation
        UNION SELECT tenant_id, scope_id FROM worklist.iteration_membership
        UNION SELECT tenant_id, scope_id FROM worklist.claim
        UNION SELECT tenant_id, scope_id FROM worklist.scope_setting
    LOOP
        PERFORM set_config('app.tenant_id', s.tenant_id::text, true);

        UPDATE worklist.item c SET status_pk = p.pk
          FROM worklist.item_status p
         WHERE c.tenant_id = s.tenant_id AND c.scope_id = s.scope_id AND c.status_pk IS NULL
           AND p.tenant_id = c.tenant_id AND p.id = c.status_id;
        UPDATE worklist.item c SET milestone_number = p.number
          FROM worklist.milestone p
         WHERE c.tenant_id = s.tenant_id AND c.scope_id = s.scope_id AND c.milestone_number IS NULL
           AND p.tenant_id = c.tenant_id AND p.scope_id = c.scope_id AND p.id = c.milestone_id;
        UPDATE worklist.item c SET workstream_number = p.number
          FROM worklist.workstream p
         WHERE c.tenant_id = s.tenant_id AND c.scope_id = s.scope_id AND c.workstream_number IS NULL
           AND p.tenant_id = c.tenant_id AND p.scope_id = c.scope_id AND p.id = c.workstream_id;

        UPDATE worklist.attribute_option c SET definition_pk = p.pk
          FROM worklist.attribute_definition p
         WHERE c.tenant_id = s.tenant_id AND c.scope_id = s.scope_id AND c.definition_pk IS NULL
           AND p.tenant_id = c.tenant_id AND p.id = c.definition_id;

        UPDATE worklist.number_space c SET selector_pk = p.pk
          FROM worklist.selector p
         WHERE c.tenant_id = s.tenant_id AND c.scope_id = s.scope_id AND c.selector_pk IS NULL
           AND p.tenant_id = c.tenant_id AND p.id = c.selector_id;

        UPDATE worklist.item_reference c SET item_number = p.number
          FROM worklist.item p
         WHERE c.tenant_id = s.tenant_id AND c.scope_id = s.scope_id AND c.item_number IS NULL
           AND p.tenant_id = c.tenant_id AND p.scope_id = c.scope_id AND p.id = c.item_id;

        UPDATE worklist.item_relation c SET from_item_number = p.number
          FROM worklist.item p
         WHERE c.tenant_id = s.tenant_id AND c.scope_id = s.scope_id AND c.from_item_number IS NULL
           AND p.tenant_id = c.tenant_id AND p.scope_id = c.scope_id AND p.id = c.from_item_id;
        UPDATE worklist.item_relation c SET to_item_number = p.number
          FROM worklist.item p
         WHERE c.tenant_id = s.tenant_id AND c.scope_id = s.scope_id AND c.to_item_number IS NULL
           AND p.tenant_id = c.tenant_id AND p.scope_id = c.scope_id AND p.id = c.to_item_id;
        UPDATE worklist.item_relation c SET relation_type_pk = p.pk
          FROM worklist.relation_type p
         WHERE c.tenant_id = s.tenant_id AND c.scope_id = s.scope_id AND c.relation_type_pk IS NULL
           AND p.tenant_id = c.tenant_id AND p.id = c.relation_type_id;

        UPDATE worklist.iteration_membership c SET iteration_number = p.number
          FROM worklist.iteration p
         WHERE c.tenant_id = s.tenant_id AND c.scope_id = s.scope_id AND c.iteration_number IS NULL
           AND p.tenant_id = c.tenant_id AND p.scope_id = c.scope_id AND p.id = c.iteration_id;
        UPDATE worklist.iteration_membership c SET item_number = p.number
          FROM worklist.item p
         WHERE c.tenant_id = s.tenant_id AND c.scope_id = s.scope_id AND c.item_number IS NULL
           AND p.tenant_id = c.tenant_id AND p.scope_id = c.scope_id AND p.id = c.item_id;

        UPDATE worklist.claim c SET item_number = p.number
          FROM worklist.item p
         WHERE c.tenant_id = s.tenant_id AND c.scope_id = s.scope_id AND c.item_number IS NULL
           AND p.tenant_id = c.tenant_id AND p.scope_id = c.scope_id AND p.id = c.item_id;

        UPDATE worklist.scope_setting c SET current_iteration_number = p.number
          FROM worklist.iteration p
         WHERE c.tenant_id = s.tenant_id AND c.scope_id = s.scope_id
           AND c.current_iteration_number IS NULL
           AND p.tenant_id = c.tenant_id AND p.scope_id = c.scope_id AND p.id = c.current_iteration_id;
    END LOOP;
    -- Restored rather than cleared: the completeness check below runs in the
    -- same transaction and has to read under the deployment's binding. A
    -- cleared GUC would hide every row from it and make it pass on anything.
    PERFORM set_config('app.tenant_id', coalesce(prior, ''), true);
END $$;

-- What the backfill could not fill is a reference whose parent is missing or
-- lives in another scope. Such a row cannot take the key stage R2 declares,
-- so it is named here, before anything is built on it, and the migration
-- stops. The counts are read under the deployment's tenant binding, the same
-- one the backfill wrote under; a row of another tenant is not seen here and
-- is caught by the NOT NULL of stage R2 instead.
DO $$
DECLARE
    unfilled TEXT;
BEGIN
    SELECT string_agg(edge || '=' || n, ', ') INTO unfilled FROM (
        SELECT 'item.status' AS edge, count(*) AS n FROM worklist.item WHERE status_pk IS NULL
        UNION ALL SELECT 'item.milestone', count(*) FROM worklist.item
            WHERE milestone_id IS NOT NULL AND milestone_number IS NULL
        UNION ALL SELECT 'item.workstream', count(*) FROM worklist.item WHERE workstream_number IS NULL
        UNION ALL SELECT 'attribute_option.definition', count(*) FROM worklist.attribute_option
            WHERE definition_pk IS NULL
        UNION ALL SELECT 'number_space.selector', count(*) FROM worklist.number_space
            WHERE selector_pk IS NULL
        UNION ALL SELECT 'item_reference.item', count(*) FROM worklist.item_reference
            WHERE item_number IS NULL
        UNION ALL SELECT 'item_relation.from', count(*) FROM worklist.item_relation
            WHERE from_item_number IS NULL
        UNION ALL SELECT 'item_relation.to', count(*) FROM worklist.item_relation
            WHERE to_item_number IS NULL
        UNION ALL SELECT 'item_relation.type', count(*) FROM worklist.item_relation
            WHERE relation_type_pk IS NULL
        UNION ALL SELECT 'iteration_membership.iteration', count(*) FROM worklist.iteration_membership
            WHERE iteration_number IS NULL
        UNION ALL SELECT 'iteration_membership.item', count(*) FROM worklist.iteration_membership
            WHERE item_number IS NULL
        UNION ALL SELECT 'claim.item', count(*) FROM worklist.claim WHERE item_number IS NULL
        UNION ALL SELECT 'scope_setting.current_iteration', count(*) FROM worklist.scope_setting
            WHERE current_iteration_id IS NOT NULL AND current_iteration_number IS NULL
    ) e WHERE n > 0;
    IF unfilled IS NOT NULL THEN
        RAISE EXCEPTION
            'ADR-0042 stage R1 refused: references whose parent is missing or lives in '
            'another scope: %. Nothing was changed.', unfilled;
    END IF;
END $$;
