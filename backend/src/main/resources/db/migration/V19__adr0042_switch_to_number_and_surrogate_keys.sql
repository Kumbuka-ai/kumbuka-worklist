-- ===========================================================================
-- V19: ADR-0042 stage R2 -- switch. The readable keys become the declared ones.
--
-- Stage R1 (V18) put a number or surrogate beside every uuid reference and
-- keeps the two in step by trigger. The image that ships with this
-- migration reads and writes the new columns only; the trigger fills the
-- uuid side for the image before it. This migration declares the new
-- columns as what they are -- foreign keys -- and closes them where the uuid
-- column they mirror is closed.
--
--   1. The backfill of V18 runs again. A row written by the previous image
--      between V18 and this migration was filled by the trigger already; the
--      backfill is the net under that claim, and its completeness check
--      stops this migration on any reference it could not fill.
--   2. `item.selector_id` is no longer written by the image. Every item
--      hangs on the view selector `item`, so the item's sync trigger fills
--      the column on insert from that selector, and the NOT NULL the image
--      before this one relies on keeps holding. The column goes in stage R3.
--   3. The foreign keys onto (tenant_id, scope_id, number) of a primary
--      object and onto (tenant_id, pk) of a support row are added NOT VALID,
--      so the ALTER does not hold the table while it scans, and then
--      validated, which fails loudly on a row that does not resolve.
--   4. SET NOT NULL wherever the uuid column a sibling mirrors is NOT NULL.
--
-- WHY THIS IS SAFE FOR THE PREVIOUS IMAGE
--
-- The image of stage R1 writes uuids only. The sync trigger of V18 fills
-- every sibling before the row is checked, so the new keys and NOT NULLs
-- see a complete row. Nothing is dropped or renamed here.
--
-- NO GRANT IS ISSUED HERE: no table is added, and a foreign key needs no
-- privilege of the role that writes the referencing row.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- 1. The backfill of V18 again, as a safety net.
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
            'ADR-0042 stage R2 refused: references whose parent is missing or lives in '
            'another scope: %. Nothing was changed.', unfilled;
    END IF;
END $$;


-- ---------------------------------------------------------------------------
-- 2. The item's selector, filled by the store.
--
-- The function is replaced whole; it is V18's with one step in front.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION worklist.item_sync_references() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER SET search_path = worklist, pg_temp AS $$
DECLARE
    o RECORD;
BEGIN
    IF TG_OP = 'INSERT' AND NEW.selector_id IS NULL
       AND NOT worklist.row_is_refused_by_the_policy(NEW.tenant_id) THEN
        SELECT id INTO NEW.selector_id FROM worklist.selector
         WHERE tenant_id = NEW.tenant_id AND scope_id = NEW.scope_id AND token = 'item';
        IF NEW.selector_id IS NULL THEN
            RAISE EXCEPTION
                'ADR-0042 sync: scope % of tenant % declares no selector item, so item % '
                'has no address space to stand in.', NEW.scope_id, NEW.tenant_id, NEW.number;
        END IF;
    END IF;
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


-- ---------------------------------------------------------------------------
-- 3. The keys.
-- ---------------------------------------------------------------------------
ALTER TABLE worklist.item ADD CONSTRAINT fk_item_status_pk
    FOREIGN KEY (tenant_id, status_pk) REFERENCES worklist.item_status (tenant_id, pk) NOT VALID;
ALTER TABLE worklist.item ADD CONSTRAINT fk_item_milestone_number
    FOREIGN KEY (tenant_id, scope_id, milestone_number)
    REFERENCES worklist.milestone (tenant_id, scope_id, number) NOT VALID;
ALTER TABLE worklist.item ADD CONSTRAINT fk_item_workstream_number
    FOREIGN KEY (tenant_id, scope_id, workstream_number)
    REFERENCES worklist.workstream (tenant_id, scope_id, number) NOT VALID;
ALTER TABLE worklist.attribute_option ADD CONSTRAINT fk_attribute_option_definition_pk
    FOREIGN KEY (tenant_id, definition_pk)
    REFERENCES worklist.attribute_definition (tenant_id, pk) NOT VALID;
ALTER TABLE worklist.number_space ADD CONSTRAINT fk_number_space_selector_pk
    FOREIGN KEY (tenant_id, selector_pk) REFERENCES worklist.selector (tenant_id, pk) NOT VALID;
ALTER TABLE worklist.item_reference ADD CONSTRAINT fk_item_reference_item_number
    FOREIGN KEY (tenant_id, scope_id, item_number)
    REFERENCES worklist.item (tenant_id, scope_id, number) NOT VALID;
ALTER TABLE worklist.item_relation ADD CONSTRAINT fk_item_relation_from_number
    FOREIGN KEY (tenant_id, scope_id, from_item_number)
    REFERENCES worklist.item (tenant_id, scope_id, number) NOT VALID;
ALTER TABLE worklist.item_relation ADD CONSTRAINT fk_item_relation_to_number
    FOREIGN KEY (tenant_id, scope_id, to_item_number)
    REFERENCES worklist.item (tenant_id, scope_id, number) NOT VALID;
ALTER TABLE worklist.item_relation ADD CONSTRAINT fk_item_relation_type_pk
    FOREIGN KEY (tenant_id, relation_type_pk)
    REFERENCES worklist.relation_type (tenant_id, pk) NOT VALID;
ALTER TABLE worklist.iteration_membership ADD CONSTRAINT fk_iteration_membership_iteration_number
    FOREIGN KEY (tenant_id, scope_id, iteration_number)
    REFERENCES worklist.iteration (tenant_id, scope_id, number) NOT VALID;
ALTER TABLE worklist.iteration_membership ADD CONSTRAINT fk_iteration_membership_item_number
    FOREIGN KEY (tenant_id, scope_id, item_number)
    REFERENCES worklist.item (tenant_id, scope_id, number) NOT VALID;
ALTER TABLE worklist.claim ADD CONSTRAINT fk_claim_item_number
    FOREIGN KEY (tenant_id, scope_id, item_number)
    REFERENCES worklist.item (tenant_id, scope_id, number) NOT VALID;
ALTER TABLE worklist.scope_setting ADD CONSTRAINT fk_scope_setting_iteration_number
    FOREIGN KEY (tenant_id, scope_id, current_iteration_number)
    REFERENCES worklist.iteration (tenant_id, scope_id, number) NOT VALID;

ALTER TABLE worklist.item                 VALIDATE CONSTRAINT fk_item_status_pk;
ALTER TABLE worklist.item                 VALIDATE CONSTRAINT fk_item_milestone_number;
ALTER TABLE worklist.item                 VALIDATE CONSTRAINT fk_item_workstream_number;
ALTER TABLE worklist.attribute_option     VALIDATE CONSTRAINT fk_attribute_option_definition_pk;
ALTER TABLE worklist.number_space         VALIDATE CONSTRAINT fk_number_space_selector_pk;
ALTER TABLE worklist.item_reference       VALIDATE CONSTRAINT fk_item_reference_item_number;
ALTER TABLE worklist.item_relation        VALIDATE CONSTRAINT fk_item_relation_from_number;
ALTER TABLE worklist.item_relation        VALIDATE CONSTRAINT fk_item_relation_to_number;
ALTER TABLE worklist.item_relation        VALIDATE CONSTRAINT fk_item_relation_type_pk;
ALTER TABLE worklist.iteration_membership VALIDATE CONSTRAINT fk_iteration_membership_iteration_number;
ALTER TABLE worklist.iteration_membership VALIDATE CONSTRAINT fk_iteration_membership_item_number;
ALTER TABLE worklist.claim                VALIDATE CONSTRAINT fk_claim_item_number;
ALTER TABLE worklist.scope_setting        VALIDATE CONSTRAINT fk_scope_setting_iteration_number;


-- ---------------------------------------------------------------------------
-- 4. NOT NULL where the uuid column it mirrors is NOT NULL.
--
-- `item.milestone_number` and `scope_setting.current_iteration_number` stay
-- nullable, as `milestone_id` and `current_iteration_id` are.
-- ---------------------------------------------------------------------------
ALTER TABLE worklist.item                 ALTER COLUMN status_pk         SET NOT NULL,
                                          ALTER COLUMN workstream_number SET NOT NULL;
ALTER TABLE worklist.attribute_option     ALTER COLUMN definition_pk     SET NOT NULL;
ALTER TABLE worklist.number_space         ALTER COLUMN selector_pk       SET NOT NULL;
ALTER TABLE worklist.item_reference       ALTER COLUMN item_number       SET NOT NULL;
ALTER TABLE worklist.item_relation        ALTER COLUMN from_item_number  SET NOT NULL,
                                          ALTER COLUMN to_item_number    SET NOT NULL,
                                          ALTER COLUMN relation_type_pk  SET NOT NULL;
ALTER TABLE worklist.iteration_membership ALTER COLUMN iteration_number  SET NOT NULL,
                                          ALTER COLUMN item_number       SET NOT NULL;
ALTER TABLE worklist.claim                ALTER COLUMN item_number       SET NOT NULL;
