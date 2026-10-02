-- ===========================================================================
-- V20: ADR-0042 stage R3 -- contract. The uuid references go.
--
-- Stage R1 (V18) put a number or surrogate beside every uuid reference,
-- stage R2 (V19) declared those as the foreign keys and moved the image onto
-- them. Nothing the image of stage R2 maps reads a column dropped here, so
-- this migration is the contract half of the pair and removes:
--
--   1. The sync triggers of V18 and their functions -- the scaffolding that
--      kept both sides in step while two images wrote different ones. The
--      immutability trigger on the four primary tables stays: it is what
--      makes a number fit to be a key target, permanently.
--   2. The uuid form of `item.attributes`. Keys become the definition's
--      surrogate and option values the option's surrogate, per scope, before
--      the uuids they translate from are dropped.
--   3. Every uuid reference column, and with it every composite uuid key of
--      V4 and V9 and every index over such a column; the indexes and checks
--      that guard an invariant are rebuilt on the readable columns under the
--      names they had.
--   4. `item.selector_id`. Every item hangs on the view selector `item`; the
--      item's number is unique within its scope (V18) and is its address.
--   5. The uuid identity of every support row. Its surrogate `pk` becomes the
--      primary key. The link tables take their primary key from the
--      readable columns.
--
-- What stays a uuid: `id` of item, iteration, milestone and workstream -- the
-- outward identity of a primary object -- and `tenant_id` and `scope_id`,
-- which are identities owned by the platform (ADR-0014).
--
-- The dead milestone and counter workstream columns this stage was also to
-- drop were dropped already, by V17.
--
-- WHY THIS IS SAFE FOR THE PREVIOUS IMAGE
--
-- The stage-R2 image maps none of the dropped columns. It reads the uuid of a
-- vocabulary row through the row's JSON form, which answers NULL once the
-- column is gone, and then writes `item.attributes` in the surrogate form
-- this migration produces.
--
-- RLS: FORCE ROW LEVEL SECURITY binds the migrator too, so the rewrite binds
-- `app.tenant_id` per scope before its DML -- the pattern of V8, V12, V15.
--
-- NO GRANT IS ISSUED HERE: no table is added. A primary key moved onto an
-- identity column needs no privilege beyond the table-level ones of V4.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- 1. The scaffolding.
-- ---------------------------------------------------------------------------
DROP TRIGGER item_sync_references                 ON worklist.item;
DROP TRIGGER attribute_option_sync_references     ON worklist.attribute_option;
DROP TRIGGER number_space_sync_references         ON worklist.number_space;
DROP TRIGGER item_reference_sync_references       ON worklist.item_reference;
DROP TRIGGER item_relation_sync_references        ON worklist.item_relation;
DROP TRIGGER iteration_membership_sync_references ON worklist.iteration_membership;
DROP TRIGGER claim_sync_references                ON worklist.claim;
DROP TRIGGER scope_setting_sync_references        ON worklist.scope_setting;

DROP FUNCTION worklist.item_sync_references();
DROP FUNCTION worklist.attribute_option_sync_references();
DROP FUNCTION worklist.number_space_sync_references();
DROP FUNCTION worklist.item_reference_sync_references();
DROP FUNCTION worklist.item_relation_sync_references();
DROP FUNCTION worklist.iteration_membership_sync_references();
DROP FUNCTION worklist.claim_sync_references();
DROP FUNCTION worklist.scope_setting_sync_references();
DROP FUNCTION worklist.sync_number_reference(TEXT, TEXT, UUID, UUID, UUID, UUID, BIGINT, BIGINT);
DROP FUNCTION worklist.sync_pk_reference(TEXT, TEXT, UUID, UUID, UUID, BIGINT, BIGINT);
DROP FUNCTION worklist.row_is_refused_by_the_policy(UUID);


-- ---------------------------------------------------------------------------
-- 2. item.attributes in the surrogate form.
--
-- A key that names a definition by uuid becomes its surrogate; a choice value
-- or a multi_choice element that names an option by uuid becomes the
-- option's surrogate. Anything that does not resolve -- a key already in the
-- surrogate form, a value under a withdrawn or unknown declaration, a value
-- of a type that names no option -- is kept as it is, so a second run
-- changes nothing.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    s     RECORD;
    prior TEXT := current_setting('app.tenant_id', true);
BEGIN
    FOR s IN SELECT DISTINCT tenant_id, scope_id FROM worklist.item
             WHERE attributes <> '{}'::jsonb
    LOOP
        PERFORM set_config('app.tenant_id', s.tenant_id::text, true);

        UPDATE worklist.item i SET attributes = (
            SELECT coalesce(jsonb_object_agg(
                coalesce(d.pk::text, e.key),
                CASE
                    WHEN d.type = 'choice' THEN coalesce(
                        (SELECT to_jsonb(o.pk::text) FROM worklist.attribute_option o
                          WHERE o.tenant_id = i.tenant_id AND o.definition_pk = d.pk
                            AND o.id::text = e.value #>> '{}'),
                        e.value)
                    WHEN d.type = 'multi_choice' AND jsonb_typeof(e.value) = 'array' THEN (
                        SELECT coalesce(jsonb_agg(coalesce(
                            (SELECT to_jsonb(o.pk::text) FROM worklist.attribute_option o
                              WHERE o.tenant_id = i.tenant_id AND o.definition_pk = d.pk
                                AND o.id::text = a.v #>> '{}'),
                            a.v) ORDER BY a.ord), '[]'::jsonb)
                        FROM jsonb_array_elements(e.value) WITH ORDINALITY AS a(v, ord))
                    ELSE e.value
                END), '{}'::jsonb)
            FROM jsonb_each(i.attributes) AS e
            LEFT JOIN worklist.attribute_definition d
                   ON d.tenant_id = i.tenant_id AND d.id::text = e.key)
        WHERE i.tenant_id = s.tenant_id AND i.scope_id = s.scope_id
          AND i.attributes <> '{}'::jsonb;
    END LOOP;
    PERFORM set_config('app.tenant_id', coalesce(prior, ''), true);
END $$;


-- ---------------------------------------------------------------------------
-- 3. and 4. The uuid reference columns. Dropping a column drops every key,
-- index and check built over it; the ones that guard an invariant are rebuilt
-- on the readable columns, under the names they had, in this same
-- transaction.
-- ---------------------------------------------------------------------------
ALTER TABLE worklist.item                 DROP COLUMN status_id,
                                          DROP COLUMN milestone_id,
                                          DROP COLUMN workstream_id,
                                          DROP COLUMN selector_id;
ALTER TABLE worklist.attribute_option     DROP COLUMN definition_id;
ALTER TABLE worklist.number_space         DROP COLUMN selector_id;
ALTER TABLE worklist.item_reference       DROP COLUMN item_id;
ALTER TABLE worklist.item_relation        DROP COLUMN from_item_id,
                                          DROP COLUMN to_item_id,
                                          DROP COLUMN relation_type_id;
ALTER TABLE worklist.iteration_membership DROP COLUMN iteration_id,
                                          DROP COLUMN item_id;
ALTER TABLE worklist.claim                DROP COLUMN item_id;
ALTER TABLE worklist.scope_setting        DROP COLUMN current_iteration_id;

-- The link tables' primary keys went with their columns; they are rebuilt
-- from the readable ones. A number names an item only within its scope, so
-- the scope is part of each.
ALTER TABLE worklist.item_relation ADD CONSTRAINT pk_item_relation
    PRIMARY KEY (tenant_id, scope_id, from_item_number, to_item_number, relation_type_pk);
ALTER TABLE worklist.iteration_membership ADD CONSTRAINT pk_iteration_membership
    PRIMARY KEY (tenant_id, scope_id, iteration_number, item_number);
ALTER TABLE worklist.claim ADD CONSTRAINT pk_claim
    PRIMARY KEY (tenant_id, scope_id, item_number);

ALTER TABLE worklist.item_relation ADD CONSTRAINT ck_item_relation_not_self
    CHECK (from_item_number <> to_item_number);
ALTER TABLE worklist.attribute_option ADD CONSTRAINT uq_attribute_option_name
    UNIQUE (tenant_id, definition_pk, name);

CREATE UNIQUE INDEX uq_item_reference_ordinal ON worklist.item_reference
    (tenant_id, scope_id, item_number, ordinal) WHERE status <> 'withdrawn';
CREATE UNIQUE INDEX uq_iteration_membership_active ON worklist.iteration_membership
    (tenant_id, scope_id, iteration_number) WHERE status = 'active';
CREATE UNIQUE INDEX uq_number_space_selector ON worklist.number_space
    (tenant_id, scope_id, selector_pk);

CREATE INDEX idx_item_status ON worklist.item (tenant_id, scope_id, status_pk);
CREATE INDEX idx_item_milestone ON worklist.item (tenant_id, scope_id, milestone_number);
CREATE INDEX idx_attribute_option_definition ON worklist.attribute_option
    (tenant_id, definition_pk);
CREATE INDEX idx_item_relation_target ON worklist.item_relation
    (tenant_id, scope_id, to_item_number);
CREATE INDEX idx_iteration_membership_item ON worklist.iteration_membership
    (tenant_id, scope_id, item_number);


-- ---------------------------------------------------------------------------
-- 5. The support rows' identity: the surrogate.
--
-- The uuid identity goes with its column, and with it the UNIQUE
-- (tenant_id, id) every uuid key above pointed at. The surrogate becomes the
-- primary key; UNIQUE (tenant_id, pk) of V18 stays as the key target.
-- ---------------------------------------------------------------------------
ALTER TABLE worklist.item_status          DROP COLUMN id;
ALTER TABLE worklist.relation_type        DROP COLUMN id;
ALTER TABLE worklist.selector             DROP COLUMN id;
ALTER TABLE worklist.attribute_definition DROP COLUMN id;
ALTER TABLE worklist.attribute_option     DROP COLUMN id;
ALTER TABLE worklist.number_space         DROP COLUMN id;
ALTER TABLE worklist.item_reference       DROP COLUMN id;
ALTER TABLE worklist.scope_setting        DROP COLUMN id;

ALTER TABLE worklist.item_status          ADD CONSTRAINT item_status_pkey          PRIMARY KEY (pk);
ALTER TABLE worklist.relation_type        ADD CONSTRAINT relation_type_pkey        PRIMARY KEY (pk);
ALTER TABLE worklist.selector             ADD CONSTRAINT selector_pkey             PRIMARY KEY (pk);
ALTER TABLE worklist.attribute_definition ADD CONSTRAINT attribute_definition_pkey PRIMARY KEY (pk);
ALTER TABLE worklist.attribute_option     ADD CONSTRAINT attribute_option_pkey     PRIMARY KEY (pk);
ALTER TABLE worklist.number_space         ADD CONSTRAINT number_space_pkey         PRIMARY KEY (pk);
ALTER TABLE worklist.item_reference       ADD CONSTRAINT item_reference_pkey       PRIMARY KEY (pk);
