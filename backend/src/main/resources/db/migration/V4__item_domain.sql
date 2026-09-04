-- ===========================================================================
-- V4: the item domain — the address space, the declared vocabularies, the
-- item itself, the planning layer, the claim and the scope's own settings.
--
-- V1 built one table with a title on it and said so: "status, the declared
-- attribute set, relations and planning membership are NOT here. They are the
-- domain half, and an item that carries a status column before the vocabulary
-- mechanism exists would be an item whose status means whatever the first
-- writer assumed." This is that half, and the vocabulary mechanism arrives
-- with it rather than after it.
--
-- WHERE EVERY TABLE BELOW COMES FROM
--
-- `study-worklist-target-schema.md`, sections 2 to 8, which derives from
-- `concept-worklist-service-items-predicates-and-readiness.md` revision 2.
-- Nothing here is invented at the migration: the study states which tables
-- exist and which columns they carry, and this file is its execution. Where a
-- shape below has a reason, the reason is the study's and is repeated here
-- because a migration that only states WHAT is a migration nobody can argue
-- with.
--
-- THIS FILE REPLACES ITS OWN PREVIOUS CONTENT, AND THAT IS DELIBERATE
--
-- The standing rule is that a migration is additive and compatible with the
-- previous image. That rule protects a RUNNING system from meeting a schema
-- its previous image does not know. There is no running system here to
-- protect: the store holds no row, the service runs in no topology — measured
-- 2026-09-04 against `infra/compose.prod.yml` and
-- `local-dev/docker-compose.dev.yml`, neither of which carries this service —
-- and the local development database holds no `worklist` schema at all, which
-- was measured before this file was rewritten rather than assumed.
--
-- The alternative was a V5 layering the target model on top of the
-- predecessor's, and it is worse in the one way that matters: it would leave
-- the fixed attribute axes and the literal status vocabulary in the history
-- as a shape somebody can restore, and every future reader would have to
-- learn both. What is replaced here is not a partial implementation of the
-- target schema. It is the predecessor's column model carried one layer down:
-- four attribute axes fixed in the schema, status as a set of literals in a
-- check constraint, an untyped dependency edge, one free-text reference
-- field, no description, no carrier for a customer's own attributes and no
-- planning layer.
--
-- WHAT CHANGED AGAINST THE PREVIOUS CONTENT OF THIS FILE, AND WHY
--
--   1. `term` IS GONE, and three tables stand where it stood. It held four
--      vocabularies discriminated by an `axis` check constraint over four
--      literals, so a fifth axis was a migration. Here an attribute is
--      DECLARED — `attribute_definition` plus `attribute_option` — and the
--      item carries its values in one `jsonb` column keyed by the
--      definition's identity. A fifth attribute is a row.
--
--   2. `item.status` WAS A LITERAL SET and is now `status_id`, a reference to
--      a declared value that carries the four predicates. That is the
--      construction concept 7.2 says must not be repeated and which was
--      repeated — not by decision, but because the order that built the item
--      domain cited the guardrail clauses and never this design.
--
--   3. `item_dependency` IS `item_relation` AND CARRIES A TYPE. The previous
--      content deferred the type deliberately, on the ground that the moment
--      types exist something has to interpret them. `relation_type.blocks` is
--      that interpretation, and it is the ONE property the platform reasons
--      about; everything else a type means belongs to the scope.
--
--   4. THE FOUR ATTRIBUTE COLUMNS AND `component` ARE GONE from `item`. All
--      five are declared attributes and live in `attributes`.
--
--   5. `reference` WAS ONE NULLABLE TEXT COLUMN and is now `item_reference`,
--      an ordered list. Measured on the estate being migrated: that one field
--      came to hold, simultaneously, an item's rationale, a withdrawn
--      decision, a build source path and a warning that the path was wrong.
--
--   6. THE PLANNING LAYER IS HERE — milestone, iteration, membership — and
--      its VERBS are not. The tables are here because a planning layer added
--      later would be a second replacement of this migration; the verbs, the
--      cardinality warnings and the derivation of `planned` are their own
--      piece of work.
--
--   7. `number_space` CARRIES BOTH COUNTERS. One row per selector, and one
--      row per scope with a null selector. Both are maintained whatever the
--      allocation mode says, which is what makes the mode a setting rather
--      than a migration: switching it is a read against a counter that was
--      kept all along.
--
--   8. `updated_at` IS `changed_at` on `item`, as the study names it. The
--      column keeps its type and its meaning; only the name follows the
--      target.
--
-- PRIVILEGES TRAVEL WITH THE TABLE THAT NEEDS THEM
--
-- V2 keeps ownership with the migrator and enumerates what the runtime role
-- may do, table by table. That arrangement only holds if every migration that
-- adds a relation adds its grants too — a table added without them produces a
-- service that cannot read it, loudly and immediately, which is the failure
-- this arrangement chose over the silent one. Each CREATE TABLE below is
-- therefore followed by its own GRANT, in the same migration, and
-- `flyway_schema_history` is named in none of them.
--
-- No DELETE is granted anywhere. That is not an oversight: withdrawal is a
-- status on every object in this schema that can stop being asserted, and
-- "no verb deletes" is held by the GRANTS rather than by a rule somebody has
-- to remember. One exception would cost the whole of it. No TRUNCATE, no
-- TRIGGER, no REFERENCES either — see V2 for why TRUNCATE in particular is
-- the one that matters.
--
-- EVERY TABLE CARRIES THE TENANCY PAIR AND ITS OWN POLICY
--
-- `tenant_id` and `scope_id` on every table, without exception (study section
-- 2), row-level security ENABLED and FORCED, and a policy carrying both
-- USING and WITH CHECK. V3 set that up for the tables that existed then; a
-- table added without it is the gap no whole-schema check finds, because the
-- schema as a whole still looks like it has row-level security.
--
-- Foreign keys are COMPOSITE on `(tenant_id, id)` throughout, so a reference
-- cannot cross a tenant. A plain `REFERENCES t(id)` is checked by the system
-- with row-level security bypassed, and would therefore happily bind a row to
-- a foreign tenant's row. That is why every table below carries a redundant
-- `UNIQUE (tenant_id, id)`: it is not a second primary key, it is the target
-- such a foreign key needs.
-- ===========================================================================


-- ===========================================================================
-- THE DECLARED VOCABULARIES
--
-- The concept describes ONE kind of object: a declared value with a stable
-- identity, a display name, a rank and an optional description (3.4). The
-- schema nevertheless carries THREE tables for it — `item_status`,
-- `attribute_option` and `relation_type` — plus `attribute_definition` for
-- the declaration an option belongs to.
--
-- WHY NOT ONE TABLE. The common shape is four columns. The differences are
-- the platform properties: a status carries the four predicates, a relation
-- type carries `blocks`, an option carries neither and instead belongs to a
-- definition. In one table those become two blocks of columns that are null
-- for two of the three kinds and mandatory for the third — a state no check
-- constraint expresses without enumerating the kinds, which is a
-- discriminator by another name. Three tables state the same rule as three
-- NOT NULLs.
--
-- THE COST, RECORDED RATHER THAN GLOSSED: the four common columns are written
-- three times, and a later fifth kind of declared value is a fourth table
-- rather than a row. What survives of "these are one kind of object" is a
-- naming and behaviour convention — the same column names, the same rank
-- semantics, the same withdrawal rule — and not one table.
--
-- ON THE COLUMN NAMED `status` IN A TABLE NAMED `item_status`. It is the
-- declaration's own lifecycle — declared or withdrawn — and not the value the
-- row defines. A declared value is withdrawn and never deleted, because a
-- value that was written onto items has to stay resolvable or those items
-- become unreadable in their own history. The name is kept rather than made
-- unique to this table so that all four vocabularies read the same way.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- item_status — a status value, and the four predicates it maps onto.
--
-- This is the whole of the meaning the platform guarantees about a status,
-- and it is deliberately small: a predicate is an obligation every scope must
-- be able to answer.
--
--   closed       is there nothing further to do?
--   successful   was it achieved, as opposed to abandoned?
--   in_progress  is someone working on it now?
--   actionable   is it worked out well enough to be taken up?
--
-- `successful` is the one that is not obvious and it is load-bearing.
-- Finished is not achieved: a dropped item and an obsolete one are both
-- terminal and delivered nothing. Without the distinction, a blocking
-- relation pointing at an ABANDONED item would unblock, and an agent would
-- build on sand.
--
-- `actionable` separates a raw call-in from a worked-out item. No standard
-- carries it; it is the criterion that keeps an uncharacterised item out of
-- an iteration.
--
-- TWO COHERENCE RULES, AND ONLY ONE OF THEM IS EXPRESSIBLE HERE. That
-- `closed` and `in_progress` exclude each other is a statement about a row
-- and is the check constraint below. That a scope declares at least one
-- actionable and at least one closed status is a statement about a SET of
-- rows; no constraint expresses it, it is enforced at declaration time in the
-- domain, and it is listed with the other non-constraints in study section 8.
--
-- `successful` is meaningful only where `closed` holds, and it is NOT
-- constrained to be false elsewhere. A scope that records the eventual
-- outcome on a non-terminal value is not wrong; the platform simply does not
-- read the field there.
-- ---------------------------------------------------------------------------
CREATE TABLE worklist.item_status (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL,
    scope_id     UUID         NOT NULL,
    name         TEXT         NOT NULL,
    description  TEXT,
    rank         INTEGER      NOT NULL DEFAULT 0,
    actionable   BOOLEAN      NOT NULL,
    in_progress  BOOLEAN      NOT NULL,
    closed       BOOLEAN      NOT NULL,
    successful   BOOLEAN      NOT NULL DEFAULT false,
    status       TEXT         NOT NULL DEFAULT 'declared',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_item_status_name   CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_item_status_status CHECK (status IN ('declared', 'withdrawn')),

    -- Concept 3.3: finished and being-worked-on are not both true. The other
    -- coherence rule is about a set of rows and lives in the domain.
    CONSTRAINT ck_item_status_terminal_or_running
        CHECK (NOT (closed AND in_progress)),

    CONSTRAINT uq_item_status_tenant_id UNIQUE (tenant_id, id)
);

CREATE INDEX idx_item_status_scope ON worklist.item_status (tenant_id, scope_id);

ALTER TABLE worklist.item_status ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.item_status FORCE  ROW LEVEL SECURITY;

CREATE POLICY item_status_tenant_isolation ON worklist.item_status
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON worklist.item_status TO kumbuka_worklist;


-- ---------------------------------------------------------------------------
-- attribute_definition — a customer-declared attribute, as a declaration.
--
-- This is the single most important structural change from the predecessor,
-- whose specification fixes an item at exactly sixteen cells: a seventeenth
-- column was a schema change there, and here it is a row.
--
-- THE SEVEN TYPES ARE CLOSED AT THE PLATFORM LEVEL and the set is concept
-- 3.7's. Which type a field gets is the SCOPE's choice and the platform asks
-- no question about it: the same business field is `choice` in one scope,
-- `text` in the next and `number` in a third. No rule narrows the admissible
-- types of a particular field, and none could — the service does not know a
-- declared attribute by what it means, only that it was declared.
--
-- `sortable` IS A REAL COLUMN AND NOT DOCUMENTATION. The containment index on
-- `item.attributes` answers every filter over every declared attribute with
-- one index and no schema change per attribute, which is the property that
-- makes a declaration cheap. It does NOT order and does NOT answer ranges.
-- Ordering by a declared attribute therefore needs an expression index of its
-- own, created when the attribute is declared — so declaring an attribute
-- sortable is what CAUSES that index to exist, and the surface can tell a
-- reader that a column is not orderable before they click its header.
--
-- `key` is the stable name a caller addresses the attribute by, and it is
-- unique in its scope. The DISPLAY name is `name` and may be changed freely:
-- what an item stores is the definition's identity, so a rename is not a data
-- migration. That separation is the whole of concept 3.4 and the predecessor
-- cannot do it, because there the value IS the identifier.
--
-- A CAP ON THE NUMBER OF DECLARED ATTRIBUTES PER SCOPE IS OWED AND IS NOT SET.
-- It bounds the width of `item.attributes`, the size of the containment index
-- and the size of every machine answer. It is an open point in both the
-- concept and the study, and a number guessed at this line would be a
-- decision nobody made.
-- ---------------------------------------------------------------------------
CREATE TABLE worklist.attribute_definition (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL,
    scope_id     UUID         NOT NULL,
    key          TEXT         NOT NULL,
    name         TEXT         NOT NULL,
    description  TEXT,
    type         TEXT         NOT NULL,
    rank         INTEGER      NOT NULL DEFAULT 0,
    sortable     BOOLEAN      NOT NULL DEFAULT false,
    status       TEXT         NOT NULL DEFAULT 'declared',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- The same shape a selector token has: a leading letter, then
    -- alphanumerics and interior hyphens or underscores. A key is addressed
    -- by callers and appears in a machine answer, so it is a token and not a
    -- sentence.
    CONSTRAINT ck_attribute_definition_key
        CHECK (key ~ '^[a-z][a-z0-9]*(_[a-z0-9]+)*$'),
    CONSTRAINT ck_attribute_definition_name CHECK (length(btrim(name)) > 0),

    -- Concept 3.7. Closed at the platform level, and the platform asks no
    -- question about which one a scope picks.
    CONSTRAINT ck_attribute_definition_type CHECK (type IN (
        'text', 'number', 'date', 'boolean', 'choice', 'multi_choice',
        'item_reference')),

    CONSTRAINT ck_attribute_definition_status
        CHECK (status IN ('declared', 'withdrawn')),

    -- A withdrawn definition keeps its key, which is what stops the key from
    -- being re-declared to mean something else — the same rule the selector
    -- token carries, for the same reason.
    CONSTRAINT uq_attribute_definition_key UNIQUE (tenant_id, scope_id, key),

    CONSTRAINT uq_attribute_definition_tenant_id UNIQUE (tenant_id, id)
);

CREATE INDEX idx_attribute_definition_scope
    ON worklist.attribute_definition (tenant_id, scope_id);

ALTER TABLE worklist.attribute_definition ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.attribute_definition FORCE  ROW LEVEL SECURITY;

CREATE POLICY attribute_definition_tenant_isolation ON worklist.attribute_definition
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON worklist.attribute_definition TO kumbuka_worklist;


-- ---------------------------------------------------------------------------
-- attribute_option — one option of a `choice` or `multi_choice` attribute.
--
-- A declared value under concept 3.4, so an option can be renamed and
-- withdrawn without touching a single item: what an item stores is the
-- option's identity.
--
-- NO UNIQUENESS ON THE NAME WITHIN A DEFINITION, and that is a decision that
-- was left open rather than one taken here. The concept makes the name a
-- display property, which argues for permitting two options to share one; a
-- reader confronted with two identical labels argues against. Study section
-- 10 carries it as an open point, and a constraint added at this line would
-- settle it silently — which is the more expensive of the two mistakes,
-- because a uniqueness rule cannot be switched on again once two rows have
-- shared a name.
-- ---------------------------------------------------------------------------
CREATE TABLE worklist.attribute_option (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID         NOT NULL,
    scope_id       UUID         NOT NULL,
    definition_id  UUID         NOT NULL,
    name           TEXT         NOT NULL,
    description    TEXT,
    rank           INTEGER      NOT NULL DEFAULT 0,
    status         TEXT         NOT NULL DEFAULT 'declared',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_attribute_option_name   CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_attribute_option_status CHECK (status IN ('declared', 'withdrawn')),

    CONSTRAINT fk_attribute_option_definition FOREIGN KEY (tenant_id, definition_id)
        REFERENCES worklist.attribute_definition (tenant_id, id),

    CONSTRAINT uq_attribute_option_tenant_id UNIQUE (tenant_id, id)
);

CREATE INDEX idx_attribute_option_definition
    ON worklist.attribute_option (tenant_id, definition_id);

ALTER TABLE worklist.attribute_option ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.attribute_option FORCE  ROW LEVEL SECURITY;

CREATE POLICY attribute_option_tenant_isolation ON worklist.attribute_option
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON worklist.attribute_option TO kumbuka_worklist;


-- ---------------------------------------------------------------------------
-- relation_type — the declared type of an edge, and whether it blocks.
--
-- `blocks` is the ONE property of a type the platform reasons about.
-- Everything else the type means belongs to the scope, and the platform never
-- asks. That single boolean is what makes readiness answerable at all: the
-- predecessor's dependency column carries no type, so every machine reader
-- has to guess whether an edge blocks, and a reader that guesses "blocking"
-- resolves at least one real edge wrongly.
--
-- WHETHER A TYPE MAY BE DECLARED BLOCKING AFTER ITEMS ALREADY CARRY IT is an
-- open point of the concept (15.4): flipping the property retroactively
-- changes the readiness of items nobody touched. The column is therefore
-- ordinary and updatable, and the answer belongs where the verb lives.
-- ---------------------------------------------------------------------------
CREATE TABLE worklist.relation_type (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL,
    scope_id     UUID         NOT NULL,
    name         TEXT         NOT NULL,
    description  TEXT,
    rank         INTEGER      NOT NULL DEFAULT 0,
    blocks       BOOLEAN      NOT NULL DEFAULT false,
    status       TEXT         NOT NULL DEFAULT 'declared',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_relation_type_name   CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_relation_type_status CHECK (status IN ('declared', 'withdrawn')),

    CONSTRAINT uq_relation_type_tenant_id UNIQUE (tenant_id, id)
);

CREATE INDEX idx_relation_type_scope ON worklist.relation_type (tenant_id, scope_id);

ALTER TABLE worklist.relation_type ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.relation_type FORCE  ROW LEVEL SECURITY;

CREATE POLICY relation_type_tenant_isolation ON worklist.relation_type
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON worklist.relation_type TO kumbuka_worklist;


-- ===========================================================================
-- IDENTITY: THE SELECTOR AND THE NUMBER SPACE
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- selector — the declared, immutable head of an address.
--
-- `FEAT-51` is a selector and a number. The selector half is DECLARED: it is
-- created by an explicit act and never by the first use of an address that
-- mentions it. That is the whole difference between a namespace and a typo:
-- a service that creates a selector on first use answers a misspelt address
-- by inventing a second address space, and the two then look equally real.
-- So an undeclared selector is a typed refusal, and there is no code path
-- that inserts here except the one whose entire purpose is to declare.
--
-- IMMUTABLE, and the reason is that addresses escape. Every `FEAT-51` ever
-- written into a commit message, a document or another service's reference
-- field resolves through this row. Renaming it would break every one of them
-- silently, so there is no rename — the trigger below refuses one at the
-- level below the domain, because a rule that only lives in a service method
-- is a rule that the next raw UPDATE walks past.
--
-- THE SELECTOR DISCRIMINATES AND IT DESCRIBES, and the second is not a
-- leftover. Under the scope-wide allocation mode it no longer distinguishes
-- two items, but it still tells a reader what stands at the other end of an
-- address without resolving it (concept 9).
--
-- Withdrawal is a STATUS, for the same reason there is no delete anywhere in
-- this schema: a withdrawn selector must keep occupying its token so that the
-- token cannot be re-declared to mean something else.
-- ---------------------------------------------------------------------------
CREATE TABLE worklist.selector (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL,
    scope_id    UUID         NOT NULL,
    token       TEXT         NOT NULL,
    status      TEXT         NOT NULL DEFAULT 'declared',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- The predecessor's id form, minus the number: a leading letter, then
    -- alphanumerics and interior hyphens. `FEAT`, `CHORE`, `BUG`, `F`,
    -- `D-GTM` all pass; a leading digit, a trailing hyphen or an empty token
    -- does not.
    CONSTRAINT ck_selector_token  CHECK (token ~ '^[A-Za-z][A-Za-z0-9]*(-[A-Za-z0-9]+)*$'),
    CONSTRAINT ck_selector_status CHECK (status IN ('declared', 'withdrawn')),

    -- One token per scope. A withdrawn selector still holds its token, which
    -- is what stops the token being re-declared to mean something else.
    CONSTRAINT uq_selector_token UNIQUE (tenant_id, scope_id, token),

    -- Not redundant with the primary key: it is the target a composite
    -- foreign key needs, so that a row referencing a selector can only
    -- reference one of its OWN tenant. A plain `REFERENCES selector(id)`
    -- would be checked by the system with row-level security bypassed, and
    -- would therefore happily bind a row to a foreign tenant's selector.
    CONSTRAINT uq_selector_tenant_id UNIQUE (tenant_id, id)
);

CREATE INDEX idx_selector_scope ON worklist.selector (tenant_id, scope_id);

ALTER TABLE worklist.selector ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.selector FORCE  ROW LEVEL SECURITY;

CREATE POLICY selector_tenant_isolation ON worklist.selector
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON worklist.selector TO kumbuka_worklist;


-- ---------------------------------------------------------------------------
-- The immutability guard.
--
-- Owned by the migrator, so the runtime role cannot drop it — it holds no
-- TRIGGER privilege on any table in this schema (V2), and dropping a trigger
-- requires ownership.
--
-- It guards the address, not the row: `status` and `updated_at` move, the
-- token and the scope do not. A guard that refused every update would make
-- withdrawal impossible and would be a lock rather than an invariant.
-- ---------------------------------------------------------------------------
CREATE FUNCTION worklist.selector_address_is_immutable() RETURNS trigger AS $$
BEGIN
    IF NEW.token <> OLD.token OR NEW.scope_id <> OLD.scope_id
       OR NEW.tenant_id <> OLD.tenant_id THEN
        RAISE EXCEPTION
            'the address of a selector is immutable: % in scope % may not become % in '
            'scope %. Every address ever issued under this selector resolves through '
            'this row, so a rename breaks all of them at once and silently. Declare a '
            'new selector and withdraw this one.',
            OLD.token, OLD.scope_id, NEW.token, NEW.scope_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER selector_address_is_immutable
    BEFORE UPDATE ON worklist.selector
    FOR EACH ROW EXECUTE FUNCTION worklist.selector_address_is_immutable();


-- ---------------------------------------------------------------------------
-- number_space — the high-water marks, and BOTH of them exist at all times.
--
-- One row per selector, for the per-selector position of the allocation mode,
-- and one row per scope with a NULL selector, for the scope-wide position.
-- The allocator reads the row the mode names and advances BOTH.
--
-- THAT IS WHAT MAKES THE MODE A SETTING RATHER THAN A MIGRATION. Switching it
-- is a read against a counter that was maintained all along; if only the
-- active counter were kept, switching would mean reconstructing the other one
-- from rows that no longer say what was handed out.
--
-- A PARTIAL UNIQUE INDEX ENFORCES EXACTLY ONE SCOPE-WIDE ROW PER SCOPE. A
-- plain unique constraint over `(tenant_id, scope_id, selector_id)` would
-- not: in SQL two NULLs are not equal, so it would admit any number of
-- scope-wide rows — which is the defect this index exists against and the
-- reason the primary key here is a surrogate rather than the selector.
--
-- WHY A TABLE AND NOT A SEQUENCE
--
-- A sequence is per-object, so this would need one sequence per tenant per
-- selector — created at runtime, by a role that holds no CREATE on its own
-- schema and must not. A sequence also survives rollback by design, which is
-- the right behaviour for a surrogate key and the wrong one here: the
-- guarantee is that a number is never REUSED, and that guarantee is cheaper
-- to keep and far easier to observe when the mark is an ordinary row updated
-- in the allocating transaction.
--
-- WHY IT IS PERSISTED RATHER THAN DERIVED
--
-- `max(number) + 1` over the live rows is the obvious implementation and it
-- is wrong, because it hands a number back the moment the row that held it
-- stops being live. Nothing in this schema deletes, so there is no row that
-- stops existing — but there is a burnt number: one allocated to a
-- transaction that then rolled back, and one skipped by a mark that jumped
-- forward. Neither is visible in `max(number)`, and both must stay burnt. The
-- mark is the record of what was HANDED OUT, which is not the same set as
-- what exists. Measured on the predecessor, where a removed item's task
-- number became allocatable again.
--
-- SETTABLE, deliberately. The predecessor's corpus will be moved here one
-- day, carrying numbers that were allocated years before this table existed,
-- and an import that could not tell the mark where the corpus had got to
-- would start handing out numbers that are already in use. The capability
-- belongs to this migration; using it does not.
-- ---------------------------------------------------------------------------
CREATE TABLE worklist.number_space (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID         NOT NULL,
    scope_id         UUID         NOT NULL,
    -- NULL is the scope-wide counter. Not a missing value: it is the row that
    -- belongs to no selector because it belongs to all of them.
    selector_id      UUID,
    high_water_mark  BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Never negative, and never lower than it has been: monotonicity is
    -- checked in the domain, where the previous value is known, and this is
    -- the floor under it.
    CONSTRAINT ck_number_space_mark CHECK (high_water_mark >= 0),

    -- Composite, so a number space can only belong to a selector of its own
    -- tenant. See the note on `uq_selector_tenant_id`.
    CONSTRAINT fk_number_space_selector FOREIGN KEY (tenant_id, selector_id)
        REFERENCES worklist.selector (tenant_id, id),

    CONSTRAINT uq_number_space_tenant_id UNIQUE (tenant_id, id)
);

-- One counter per selector …
CREATE UNIQUE INDEX uq_number_space_selector
    ON worklist.number_space (tenant_id, scope_id, selector_id)
    WHERE selector_id IS NOT NULL;

-- … and exactly one scope-wide counter beside them.
CREATE UNIQUE INDEX uq_number_space_scope_wide
    ON worklist.number_space (tenant_id, scope_id)
    WHERE selector_id IS NULL;

ALTER TABLE worklist.number_space ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.number_space FORCE  ROW LEVEL SECURITY;

CREATE POLICY number_space_tenant_isolation ON worklist.number_space
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON worklist.number_space TO kumbuka_worklist;


-- ===========================================================================
-- THE PLANNING LAYER
--
-- Milestone and iteration hang INDEPENDENTLY on the item and there is no edge
-- between them. A milestone answers "which goal does this serve"; an
-- iteration answers "when is it being worked". They are not a hierarchy.
--
-- The tables are here and their VERBS are not. They are here because a
-- planning layer added later would mean a second replacement of this
-- migration, in a store that by then holds rows. What is deferred is the
-- fachlichkeit: entering and leaving, activation and the close, the draw, the
-- cardinality warnings, and the derivation of `planned` as a view over
-- membership.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- milestone — the goal axis, WITH ITS THREE MARKERS AS ROWS.
--
-- A milestone is an object and not a label: identity, number, title, status,
-- a VISION of one line and a MISSION of some length. The vision is the north
-- star in a sentence; the mission is what the milestone actually contains and
-- why it is cut where it is cut.
--
-- THE THREE MARKERS BECOME ROWS, distinguished by `kind`: not yet assessed,
-- off the product path, and on the product path but covered by no vision.
-- The predecessor keeps them OUT of its milestone table and therefore needs
-- an exemption in its reference check — so that the third marker does not
-- carry a violation nobody can ever fix.
--
-- As rows they need no exemption: `item.milestone_id` always resolves, the
-- existence check has no special case, and the markers gain what every
-- declared value has — a name, a rank and a place to say what they mean. The
-- cost is that a marker must not be deletable and must not accept a vision or
-- a mission. The first is the absent DELETE privilege; the second is
-- `ck_milestone_marker_carries_no_goal` below, and it is the whole cost.
--
-- ONE ACTIVE MILESTONE PER SCOPE is a partial unique index. Setting one
-- active demotes the current one in the SAME statement, so the invariant
-- never has to hold across two writes — a refusal would make the operator
-- perform two writes to express one intention.
--
-- NUMBERS ARE NOT REUSED. A closed milestone stays in the table, so the
-- allocator counts past it.
--
-- LENGTH CAPS ARE NOT HERE, and their absence is deliberate. They bind the
-- WRITE PATH only: a read must never refuse a value already stored. The
-- predecessor learnt this the hard way — a cap on the read path seals the
-- store against its own existing content, and the content cannot then be
-- repaired through the service. A check constraint is on both paths at once,
-- so it is the wrong mechanism for a cap.
-- ---------------------------------------------------------------------------
CREATE TABLE worklist.milestone (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL,
    scope_id    UUID         NOT NULL,
    number      BIGINT       NOT NULL,
    title       TEXT         NOT NULL,
    kind        TEXT         NOT NULL DEFAULT 'milestone',
    status      TEXT         NOT NULL DEFAULT 'planned',
    vision      TEXT,
    mission     TEXT,
    rank        INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_milestone_title  CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_milestone_number CHECK (number > 0),

    -- A real milestone, or one of the three positions on the axis that never
    -- carry a goal.
    CONSTRAINT ck_milestone_kind CHECK (
        kind IN ('milestone', 'not_assessed', 'off_path', 'no_vision')),

    CONSTRAINT ck_milestone_status CHECK (status IN ('planned', 'active', 'closed')),

    -- The whole cost of making the markers rows. A marker is a position and
    -- not a goal, so it carries neither a vision nor a mission; a real
    -- milestone may carry both, either, or neither while it is being worked
    -- out.
    CONSTRAINT ck_milestone_marker_carries_no_goal CHECK (
        kind = 'milestone' OR (vision IS NULL AND mission IS NULL)),

    CONSTRAINT uq_milestone_number    UNIQUE (tenant_id, scope_id, number),
    CONSTRAINT uq_milestone_tenant_id UNIQUE (tenant_id, id)
);

-- At most one active milestone per scope.
CREATE UNIQUE INDEX uq_milestone_active
    ON worklist.milestone (tenant_id, scope_id)
    WHERE status = 'active';

CREATE INDEX idx_milestone_scope ON worklist.milestone (tenant_id, scope_id);

ALTER TABLE worklist.milestone ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.milestone FORCE  ROW LEVEL SECURITY;

CREATE POLICY milestone_tenant_isolation ON worklist.milestone
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON worklist.milestone TO kumbuka_worklist;


-- ---------------------------------------------------------------------------
-- iteration — the time axis. IT CARRIES NO STATUS COLUMN.
--
-- Complete is DERIVED from its memberships and current is a POINTER, so
-- neither is a column here (concept 6.2). The pointer lives on
-- `scope_setting.current_iteration_id`: a boolean on the iteration would
-- allow two current ones and would then need a partial unique index to forbid
-- what a single nullable pointer cannot express in the first place.
--
-- THE DESCRIPTION AND THE MOTTO ARE MANDATORY, and that is not decoration.
-- They are the only machine-readable criterion by which an agent can REFUSE
-- an item as out of scope for the current iteration.
--
-- `closed_at` is the closing timestamp and is null while the iteration is
-- open. It is a fact and not a status: what "complete" means is a question
-- about memberships, and answering it from a timestamp would be the stored
-- copy the concept refuses.
--
-- ITERATION NUMBERS ARE NEVER REUSED, and the mechanism is the same persisted
-- mark as everywhere else rather than the highest number present.
-- ---------------------------------------------------------------------------
CREATE TABLE worklist.iteration (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL,
    scope_id     UUID         NOT NULL,
    number       BIGINT       NOT NULL,
    motto        TEXT         NOT NULL,
    description  TEXT         NOT NULL,
    rank         INTEGER      NOT NULL DEFAULT 0,
    closed_at    TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_iteration_motto       CHECK (length(btrim(motto)) > 0),
    CONSTRAINT ck_iteration_description CHECK (length(btrim(description)) > 0),
    CONSTRAINT ck_iteration_number      CHECK (number > 0),

    CONSTRAINT uq_iteration_number    UNIQUE (tenant_id, scope_id, number),
    CONSTRAINT uq_iteration_tenant_id UNIQUE (tenant_id, id)
);

CREATE INDEX idx_iteration_scope ON worklist.iteration (tenant_id, scope_id);

ALTER TABLE worklist.iteration ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.iteration FORCE  ROW LEVEL SECURITY;

CREATE POLICY iteration_tenant_isolation ON worklist.iteration
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON worklist.iteration TO kumbuka_worklist;


-- ===========================================================================
-- THE ITEM
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- item — the small fixed core, and one column for everything a scope declared.
--
-- V1 created this table with a title on it. What follows is the rest of the
-- core, and what it does NOT contain is as load-bearing as what it does.
--
-- FOUR THINGS THAT ARE NOT COLUMNS, each because the concept puts them
-- elsewhere:
--
--   NO `planned`. It is a view over `iteration_membership` (concept 6.2). The
--   orphan class — an item reading planned with no membership — is thereby
--   not merely forbidden but inexpressible. It was observed twice in the
--   predecessor.
--
--   NO `iteration` AND NO `sprint` COLUMN. Membership is an entity; a column
--   would be a second copy of it, and the predecessor's pair of columns
--   exists only because a markdown table cannot join.
--
--   NO cluster, type, priority, size OR component COLUMN. They are declared
--   attributes and live in `attributes`. This is the change that makes a
--   fifth axis a declaration rather than a migration.
--
--   NO `deps` COLUMN. Relations are rows with a type.
--
-- THE STATUS IS A REFERENCE AND NOT A LITERAL. `status_id` points at a
-- declared value that carries the four predicates, and the predicates
-- themselves are NEVER stored here: the answer to "is this closed" is a join.
-- A stored copy would be a second truth that drifts, which is exactly what a
-- check constraint over five literals was.
--
-- `status_id` is NOT NULL and there is no default. An item always has a
-- status, and which statuses exist is the scope's declaration — so a scope
-- declares its vocabulary before it holds an item, which is the same order in
-- which a selector has to be declared before an address can be allocated.
--
-- THE ADDRESS IS NULLABLE IN BOTH HALVES AND NEVER IN ONE. `selector_id` and
-- `number` are null on a raw call-in and set together when the row is
-- admitted into an address space. That is not a weakened invariant, it is the
-- intake state: a call-in exists before anybody has decided what kind of
-- thing it is. A HALF address is the state that would be a defect, and
-- `ck_item_address` rejects it.
--
-- `attributes` IS ONE JSONB COLUMN KEYED BY THE DEFINITION'S IDENTITY, never
-- by its name — a rename would otherwise be a data migration, which the
-- separation of identity from name exists to prevent.
--
-- NOT AN ENTITY-ATTRIBUTE-VALUE TABLE, and the reason is the read path rather
-- than elegance. Under EAV a single item read fans out to one row per
-- attribute, filtering on two attributes needs two joins, and the item's own
-- row stops being the item. One document column keeps the item one row and
-- one read.
--
-- `changed_at` IS A TIMESTAMP AND NOT A DATE. The predecessor carried a date,
-- so two changes on one day were indistinguishable in the store — a defect
-- that costs nothing to avoid at the point where the column is defined. V1
-- created the column as `updated_at`; the rename below is the only change
-- this migration makes to a column V1 wrote, and it changes the name and
-- nothing else.
-- ---------------------------------------------------------------------------
ALTER TABLE worklist.item RENAME COLUMN updated_at TO changed_at;

ALTER TABLE worklist.item
    ADD COLUMN selector_id     UUID,
    ADD COLUMN number          BIGINT,
    ADD COLUMN description     TEXT,
    ADD COLUMN status_id       UUID    NOT NULL,
    ADD COLUMN milestone_id    UUID,
    ADD COLUMN attributes      JSONB   NOT NULL DEFAULT '{}'::jsonb,
    -- Opaque by contract. A uuid is what it happens to be; nothing may read
    -- structure into it, and a caller that parses it is a caller that breaks
    -- when the generator changes.
    ADD COLUMN conflict_token  TEXT    NOT NULL DEFAULT gen_random_uuid()::text;

-- A selector without a number, or a number without a selector, is half an
-- address and can be neither resolved nor re-allocated.
ALTER TABLE worklist.item
    ADD CONSTRAINT ck_item_address CHECK (
        (selector_id IS NULL AND number IS NULL)
        OR (selector_id IS NOT NULL AND number IS NOT NULL AND number > 0));

-- Keyed by definition id, so the column is an object and never an array or a
-- scalar. Without this a caller could store `[1,2,3]` under `attributes` and
-- every containment query over it would silently answer nothing.
ALTER TABLE worklist.item
    ADD CONSTRAINT ck_item_attributes_are_an_object
        CHECK (jsonb_typeof(attributes) = 'object');

ALTER TABLE worklist.item
    ADD CONSTRAINT fk_item_selector FOREIGN KEY (tenant_id, selector_id)
        REFERENCES worklist.selector (tenant_id, id);

ALTER TABLE worklist.item
    ADD CONSTRAINT fk_item_status FOREIGN KEY (tenant_id, status_id)
        REFERENCES worklist.item_status (tenant_id, id);

ALTER TABLE worklist.item
    ADD CONSTRAINT fk_item_milestone FOREIGN KEY (tenant_id, milestone_id)
        REFERENCES worklist.milestone (tenant_id, id);

-- The composite-foreign-key target on the item itself, for the satellites
-- below.
ALTER TABLE worklist.item
    ADD CONSTRAINT uq_item_tenant_id UNIQUE (tenant_id, id);

-- THE IDENTITY IS THE TRIPLE SCOPE, SELECTOR AND NUMBER — never the pair
-- without the selector. A store constraining the pair cannot later admit
-- per-selector numbering, and once two selectors have shared a number the
-- constraint can never be switched on again. It holds under BOTH allocation
-- modes, which is why the address is three-part in the first place.
--
-- Partial, because a raw row has no address and any number of raw rows may
-- exist at once.
CREATE UNIQUE INDEX uq_item_address
    ON worklist.item (tenant_id, scope_id, selector_id, number)
    WHERE number IS NOT NULL;

CREATE INDEX idx_item_status    ON worklist.item (tenant_id, scope_id, status_id);
CREATE INDEX idx_item_milestone ON worklist.item (tenant_id, scope_id, milestone_id);

-- ONE INDEX CARRIES EVERY DECLARED ATTRIBUTE. Containment and existence for
-- all of them at once, with no schema change per attribute — which is the
-- property that makes a declaration cheap. "Every item whose cluster is
-- CORE", "every item that carries a priority at all" and "cluster CORE and
-- priority P1" are all served by it, and that is the overwhelming majority of
-- what a backlog view does.
--
-- `jsonb_path_ops` rather than the default operator class: it indexes the
-- hashed path-plus-value rather than every key and every value separately,
-- which makes it smaller and faster for containment — and containment is the
-- only question this index is here to answer.
--
-- THE COST IS NAMED RATHER THAN DISCOVERED: it does not order and does not
-- answer ranges. Sorting by a declared attribute, or asking for every item
-- with a date before a bound, needs an expression index per attribute — which
-- IS a schema change per attribute, and is why `attribute_definition.sortable`
-- exists.
CREATE INDEX idx_item_attributes
    ON worklist.item USING GIN (attributes jsonb_path_ops);


-- ---------------------------------------------------------------------------
-- item_reference — the external pointers, as an ORDERED LIST.
--
-- A single free-text field was the predecessor's shape and it is the wrong
-- one for a measured reason: in the estate being migrated that one field came
-- to hold, simultaneously, the item's rationale, a withdrawn decision, a
-- build source path and a warning that the path was wrong. A list separates
-- the pointers; the description takes the prose that was never a reference.
--
-- AN ENTRY HAS NO TYPE AND NO RESOLUTION. A pointer to a dispatch object, a
-- pointer to a document, a URL and a citation are all the same kind of thing
-- to this service: a string it positions and does not follow. Typing them
-- would make the service responsible for something on the other side of a
-- boundary it deliberately does not cross.
--
-- The service validates the FORM of a target and never resolves it, so the
-- constraint here is non-emptiness and nothing more.
--
-- The ordinal is the reader's order and is DENSE ON WRITE — the whole list is
-- rewritten rather than patched. Density is a domain property; what the
-- schema holds is that two entries of one item cannot share a position.
--
-- THE `status` COLUMN IS NOT IN THE STUDY'S COLUMN LIST AND IS HERE ANYWAY.
-- The study names it only for the relation edge, and it states separately —
-- as a property of the whole schema — that there is no delete privilege and
-- that withdrawal is a status everywhere. A reference list has to be able to
-- get SHORTER; without a withdrawal status it cannot, because `target` is
-- NOT NULL and nothing deletes. The two statements cannot both hold with the
-- column absent, so the general rule is followed and the omission is reported
-- rather than worked around: a shrinking list rewrites positions 0..n-1 and
-- withdraws everything above n, which keeps the ordinal dense.
-- ---------------------------------------------------------------------------
CREATE TABLE worklist.item_reference (
    tenant_id   UUID         NOT NULL,
    scope_id    UUID         NOT NULL,
    item_id     UUID         NOT NULL,
    ordinal     INTEGER      NOT NULL,
    label       TEXT,
    target      TEXT         NOT NULL,
    status      TEXT         NOT NULL DEFAULT 'asserted',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_item_reference PRIMARY KEY (tenant_id, item_id, ordinal),

    CONSTRAINT ck_item_reference_ordinal CHECK (ordinal >= 0),
    CONSTRAINT ck_item_reference_target  CHECK (length(btrim(target)) > 0),
    CONSTRAINT ck_item_reference_status  CHECK (status IN ('asserted', 'withdrawn')),

    CONSTRAINT fk_item_reference_item FOREIGN KEY (tenant_id, item_id)
        REFERENCES worklist.item (tenant_id, id)
);

CREATE INDEX idx_item_reference_scope ON worklist.item_reference (tenant_id, scope_id);

ALTER TABLE worklist.item_reference ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.item_reference FORCE  ROW LEVEL SECURITY;

CREATE POLICY item_reference_tenant_isolation ON worklist.item_reference
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON worklist.item_reference TO kumbuka_worklist;


-- ---------------------------------------------------------------------------
-- item_relation — the edge, WITH A DECLARED TYPE.
--
-- Many to many in both directions, DIRECTED, and stored ONCE: the inverse is
-- a query and never a second row — the same conclusion OSLC arrived at after
-- deprecating its own back-links.
--
-- THE KEY IS THE TRIPLE from, to, type. So two items may carry two edges of
-- different types and never two of the same, which is the rule the untyped
-- predecessor edge could not state at all.
--
-- THREE REFUSALS ARE CONSTRAINTS HERE AND ONE IS NOT.
--
--   a relation to a non-existent item   -> a foreign key
--   a relation of an undeclared type    -> a foreign key
--   a self-relation                     -> the check below
--   A CYCLE OVER BLOCKING RELATIONS     -> NONE OF THESE
--
-- No constraint expresses "this graph is acyclic". It is enforced in the
-- domain at write time and it needs its own red probe, because a rule with no
-- mechanism is exactly the class this project keeps finding — and a cycle
-- makes every item on it permanently unready, which is a deadlock the caller
-- cannot see.
--
-- `metadata` is the type's own business and the platform reads nothing out of
-- it. WITHDRAWAL RATHER THAN DELETION here too: an edge that is no longer
-- asserted keeps its row and changes its status.
-- ---------------------------------------------------------------------------
CREATE TABLE worklist.item_relation (
    tenant_id         UUID         NOT NULL,
    scope_id          UUID         NOT NULL,
    from_item_id      UUID         NOT NULL,
    to_item_id        UUID         NOT NULL,
    relation_type_id  UUID         NOT NULL,
    metadata          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status            TEXT         NOT NULL DEFAULT 'asserted',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- The triple. The tenancy axis is deliberately not part of it: an item id
    -- is already unique across tenants, and the composite foreign keys below
    -- make a cross-tenant edge impossible in the other direction anyway.
    CONSTRAINT pk_item_relation
        PRIMARY KEY (from_item_id, to_item_id, relation_type_id),

    CONSTRAINT ck_item_relation_status CHECK (status IN ('asserted', 'withdrawn')),

    -- The one cycle a single row can express, and the only one a constraint
    -- can see.
    CONSTRAINT ck_item_relation_not_self CHECK (from_item_id <> to_item_id),

    CONSTRAINT ck_item_relation_metadata_is_an_object
        CHECK (jsonb_typeof(metadata) = 'object'),

    CONSTRAINT fk_item_relation_from FOREIGN KEY (tenant_id, from_item_id)
        REFERENCES worklist.item (tenant_id, id),
    CONSTRAINT fk_item_relation_to FOREIGN KEY (tenant_id, to_item_id)
        REFERENCES worklist.item (tenant_id, id),
    CONSTRAINT fk_item_relation_type FOREIGN KEY (tenant_id, relation_type_id)
        REFERENCES worklist.relation_type (tenant_id, id)
);

-- The inverse direction, which is a query rather than a row and therefore
-- needs an index to be one.
CREATE INDEX idx_item_relation_target
    ON worklist.item_relation (tenant_id, to_item_id);

CREATE INDEX idx_item_relation_scope ON worklist.item_relation (tenant_id, scope_id);

ALTER TABLE worklist.item_relation ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.item_relation FORCE  ROW LEVEL SECURITY;

CREATE POLICY item_relation_tenant_isolation ON worklist.item_relation
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON worklist.item_relation TO kumbuka_worklist;


-- ---------------------------------------------------------------------------
-- iteration_membership — MEMBERSHIP IS AN ENTITY, not a column on the item.
--
-- That is what makes `planned` a view rather than a status value, and the
-- orphan class — an item reading planned with no membership — structurally
-- impossible rather than merely forbidden.
--
-- EXACTLY ONE ACTIVE MEMBERSHIP PER ITERATION is the partial unique index
-- below, and it is what makes concept 6.4 a property of the STORE rather than
-- a rule two verbs have to agree about. They did not agree in the
-- predecessor: one verb refused a fresh activation on the ground that only
-- the draw may activate, while a second verb set the same value without
-- comment. Two verbs disagreeing about who may write a field is a defect
-- regardless of which of them is right.
--
-- THE MEMBERSHIP STATUS AND THE ITEM STATUS ARE DECOUPLED and mean different
-- things. `done` on a membership means completed IN THIS ITERATION; `done` on
-- an item means finished. They carry different field names and different
-- vocabularies so that the two cannot be confused in a call — which is also
-- why the membership's status is a small fixed set here while the item's is a
-- declared value: the four states of a membership are the platform's own
-- planning mechanism, not a scope's vocabulary.
--
-- POSITION IS DENSE AND REWRITTEN AS A WHOLE ON REORDER. A membership is
-- addressed by its item and never by its position, so a reorder moves nothing
-- a caller is holding.
-- ---------------------------------------------------------------------------
CREATE TABLE worklist.iteration_membership (
    tenant_id     UUID         NOT NULL,
    scope_id      UUID         NOT NULL,
    iteration_id  UUID         NOT NULL,
    item_id       UUID         NOT NULL,
    position      INTEGER      NOT NULL,
    status        TEXT         NOT NULL DEFAULT 'todo',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_iteration_membership
        PRIMARY KEY (tenant_id, iteration_id, item_id),

    CONSTRAINT ck_iteration_membership_position CHECK (position >= 0),
    CONSTRAINT ck_iteration_membership_status
        CHECK (status IN ('todo', 'active', 'done', 'dropped')),

    CONSTRAINT fk_iteration_membership_iteration FOREIGN KEY (tenant_id, iteration_id)
        REFERENCES worklist.iteration (tenant_id, id),
    CONSTRAINT fk_iteration_membership_item FOREIGN KEY (tenant_id, item_id)
        REFERENCES worklist.item (tenant_id, id)
);

-- At most one active membership per iteration.
CREATE UNIQUE INDEX uq_iteration_membership_active
    ON worklist.iteration_membership (tenant_id, iteration_id)
    WHERE status = 'active';

CREATE INDEX idx_iteration_membership_item
    ON worklist.iteration_membership (tenant_id, item_id);

ALTER TABLE worklist.iteration_membership ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.iteration_membership FORCE  ROW LEVEL SECURITY;

CREATE POLICY iteration_membership_tenant_isolation ON worklist.iteration_membership
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON worklist.iteration_membership TO kumbuka_worklist;


-- ---------------------------------------------------------------------------
-- claim — the lease, AS A ROW UNDER THE TRANSACTION.
--
-- One row per item, and three defects of the predecessor fall out of that
-- alone:
--
--   A CLAIM THAT SURVIVED ITS ITEM. Claims lived outside the store entirely,
--   in a file beside it, so a claim outlived the deletion of the item it
--   held. Here the foreign key makes that impossible.
--
--   TWO CONCURRENT CLAIMS BOTH SUCCEEDING. Claims were load-modify-saved
--   without a lock, so the last writer won — the exact race the verb exists
--   to prevent. One row per item under a transaction is the lock.
--
--   A ZERO DURATION REPORTING SUCCESS for a lease that was already inert.
--   `ck_claim_duration` below is that refusal, and it is a check constraint
--   because it is a statement about one row.
--
-- THE HOLDER IS AN OPAQUE RECEIPT MINTED BY THE SERVICE, never a
-- caller-supplied name: a caller-chosen holder is an identity assertion the
-- service cannot check. `actor` beside it is who the service derived from the
-- write channel, and it is recorded rather than accepted.
--
-- EXPIRY IS LAZY AND WRITES NOTHING. An expired row is inert on read and is
-- overwritten by the next claimant. Nothing sweeps it, because a sweeper
-- would need an actor for its audit entry and there is none — every audit
-- entry in this platform has a verb call and an actor behind it.
--
-- A CLAIM IS NOT A STATUS. Claimed and in-progress are different assertions:
-- a lease has an expiry, a status does not.
-- ---------------------------------------------------------------------------
CREATE TABLE worklist.claim (
    tenant_id   UUID         NOT NULL,
    scope_id    UUID         NOT NULL,
    item_id     UUID         NOT NULL,
    receipt     TEXT         NOT NULL,
    actor       TEXT         NOT NULL,
    granted_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- One row per item. Not one per item and holder: a second live lease on
    -- one item is the state the verb exists to prevent, and a key that
    -- admitted it would leave the prevention to whoever writes the verb.
    CONSTRAINT pk_claim PRIMARY KEY (item_id),

    CONSTRAINT ck_claim_receipt CHECK (length(btrim(receipt)) > 0),
    CONSTRAINT ck_claim_actor   CHECK (length(btrim(actor)) > 0),

    -- A non-positive duration: a lease that is inert the moment it is
    -- granted, reported as a success.
    CONSTRAINT ck_claim_duration CHECK (expires_at > granted_at),

    CONSTRAINT fk_claim_item FOREIGN KEY (tenant_id, item_id)
        REFERENCES worklist.item (tenant_id, id)
);

CREATE INDEX idx_claim_scope ON worklist.claim (tenant_id, scope_id);

ALTER TABLE worklist.claim ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.claim FORCE  ROW LEVEL SECURITY;

CREATE POLICY claim_tenant_isolation ON worklist.claim
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON worklist.claim TO kumbuka_worklist;


-- ---------------------------------------------------------------------------
-- scope_setting — one row per scope, and everything the scope decides.
--
-- THE ALLOCATION MODE has two positions. In the default position each
-- selector draws from its own counter, so two selectors may carry the same
-- number; in the other the allocator draws from the scope-wide counter, so a
-- number is not repeated across selectors. The two differ ONLY in which
-- counter the allocator reads — neither adds nor removes an assurance,
-- because uniqueness is the triple either way, and `number_space` maintains
-- both counters regardless. That is what makes this a setting.
--
-- THE CURRENT ITERATION IS A POINTER AND LIVES HERE rather than as a boolean
-- on the iteration, which would allow two current ones and would then need a
-- partial unique index to forbid what a single nullable pointer cannot
-- express in the first place.
--
-- THE FOUR CARDINALITY NUMBERS ARE SETTINGS AND NOT CONSTANTS, because they
-- are a scope's working style and not a platform property. A hard limit on
-- planned iterations and on memberships per iteration, and an advisory
-- warning well below each — the warning exists so that the limit is met
-- deliberately rather than discovered at the moment it refuses.
--
-- THERE ARE NO DEFAULTS ON THE FOUR, deliberately. A number written at this
-- line would be a platform constant wearing a setting's clothes, and the
-- estate's own numbers are carried in the estate's own declaration.
--
-- THE DEFAULT COLUMN SET IS DECLARED AND NOT COMPILED INTO THE CONSOLE.
-- Otherwise the predecessor's disease reappears one level up: the vocabulary
-- is free, but which of it a reader sees is code again.
-- ---------------------------------------------------------------------------
CREATE TABLE worklist.scope_setting (
    tenant_id                       UUID         NOT NULL,
    scope_id                        UUID         NOT NULL,
    allocation_mode                 TEXT         NOT NULL DEFAULT 'per_selector',
    current_iteration_id            UUID,
    max_planned_iterations          INTEGER      NOT NULL,
    warn_planned_iterations         INTEGER      NOT NULL,
    max_memberships_per_iteration   INTEGER      NOT NULL,
    warn_memberships_per_iteration  INTEGER      NOT NULL,
    default_columns                 TEXT[]       NOT NULL DEFAULT '{}',
    created_at                      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_scope_setting PRIMARY KEY (tenant_id, scope_id),

    CONSTRAINT ck_scope_setting_allocation_mode
        CHECK (allocation_mode IN ('per_selector', 'scope_wide')),

    -- A limit of zero forbids what the setting exists to bound, and a
    -- negative one is not a limit at all. The relation between a warning and
    -- its limit is NOT constrained: "well below" is a judgement, and a scope
    -- that wants its warning at the limit is making a choice rather than a
    -- mistake.
    CONSTRAINT ck_scope_setting_cardinality CHECK (
        max_planned_iterations > 0
        AND warn_planned_iterations > 0
        AND max_memberships_per_iteration > 0
        AND warn_memberships_per_iteration > 0),

    CONSTRAINT fk_scope_setting_iteration FOREIGN KEY (tenant_id, current_iteration_id)
        REFERENCES worklist.iteration (tenant_id, id)
);

ALTER TABLE worklist.scope_setting ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.scope_setting FORCE  ROW LEVEL SECURITY;

CREATE POLICY scope_setting_tenant_isolation ON worklist.scope_setting
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON worklist.scope_setting TO kumbuka_worklist;


-- ---------------------------------------------------------------------------
-- view_preference — the per-reader column layout.
--
-- WHICH ATTRIBUTES A SCOPE HAS IS A DECLARATION; WHICH COLUMNS A READER SEES
-- IS A PREFERENCE. The declaration is scope-level, administered and identical
-- for everybody; the column set, its order and its visibility are per reader,
-- because two people working the same list legitimately want different views
-- of it and neither is more right. The two are stored in different places for
-- that reason, and the surface must not be able to change a declaration by
-- hiding a column.
--
-- THIS IS THE ONE TABLE WHOSE HOME IS GENUINELY OPEN, and it is recorded here
-- rather than settled. It holds no item data and no vocabulary; it is console
-- state that happens to be scoped. It sits in this schema because the
-- declaration and the preference belong in different places and both need
-- somewhere to live, and because a console without a backing store cannot
-- remember a layout across devices. If the console acquires its own store,
-- this table moves there and nothing else in this schema changes.
--
-- Nothing is built on it in this migration's sprint. It is created because
-- creating it later would be a second replacement of a migration, and it is
-- otherwise left alone.
-- ---------------------------------------------------------------------------
CREATE TABLE worklist.view_preference (
    tenant_id   UUID         NOT NULL,
    scope_id    UUID         NOT NULL,
    -- The reader, as the service derived them from the write channel. Never a
    -- display name and never a client-supplied flag.
    actor       TEXT         NOT NULL,
    columns     TEXT[]       NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_view_preference PRIMARY KEY (tenant_id, scope_id, actor),
    CONSTRAINT ck_view_preference_actor CHECK (length(btrim(actor)) > 0)
);

ALTER TABLE worklist.view_preference ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.view_preference FORCE  ROW LEVEL SECURITY;

CREATE POLICY view_preference_tenant_isolation ON worklist.view_preference
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON worklist.view_preference TO kumbuka_worklist;
