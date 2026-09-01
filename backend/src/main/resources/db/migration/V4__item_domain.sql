-- ===========================================================================
-- V4: the item domain — the address space, the vocabularies, and the item
-- itself carrying the fields it was always going to carry.
--
-- V1 built one table with a title on it and said so: "status, the declared
-- attribute set, relations and planning membership are NOT here. They are the
-- domain half, and an item that carries a status column before the vocabulary
-- mechanism exists would be an item whose status means whatever the first
-- writer assumed." This is that half. The vocabulary mechanism is below, and
-- the status column arrives with it rather than before it.
--
-- GREEN FIELD. Nothing is carried over. The predecessor is a Markdown corpus
-- on git (`WORKLIST.md`), and moving its contents here is a separate exercise
-- with its own risks. What this migration takes from the predecessor is the
-- SHAPE, and only after each part of that shape was asked one question.
--
-- THE QUESTION, ASKED OF EVERY COLUMN
--
-- Would this property exist if the store had always been a database? Where
-- the answer is no, the property is a fact about Markdown-on-git and not a
-- decision, and it is restated rather than migrated. Six properties failed
-- that test:
--
--   1. THE ZERO-PADDED FINDING NUMBER (`F-0079`). A text file sorts
--      lexically and offers nothing else, so the sort key had to equal the
--      numeric one. An integer column sorts numerically for free. Gone,
--      without replacement.
--
--   2. THE FILE-WIDE CONFLICT TOKEN. It covered everything because every
--      write rewrote the whole file. Here it is a column ON THE ROW
--      (`conflict_token`): still an opaque string from the moment of reading,
--      but scoped to what a writer actually touched.
--
--   3. `planned` IN THE STATUS VOCABULARY. It was there because there was no
--      membership table to derive it from. There is no membership table here
--      either — the planning layer is a separate piece of work — so it is not
--      derivable YET, and the honest form of "not derivable yet" is ABSENT,
--      not a sixth status value that somebody would start writing. The check
--      constraint below does not accept it.
--
--   4. THE ITERATION AND SPRINT COLUMNS. One cannot join in Markdown, so
--      membership had to be written onto the row. Both belong to the planning
--      layer and are not columns here. The MILESTONE column goes with them
--      for the same reason: it is the planning axis, its values are allocated
--      by a milestone table, and a column pointing at a table that does not
--      exist is a column whose values nothing can check.
--
--   5. `Nr` — THE CORPUS-WIDE RUNNING NUMBER. It existed because a row needs
--      an address from its first moment and the typed number (`FEAT-51`) is
--      only allocated later, at ratification; a text file has no other
--      candidate. A database does: `id` is present from the insert, immutable,
--      and unique without a high-water mark. So `Nr` is not a column here, and
--      what the predecessor called `ID` is what the address space below
--      allocates.
--
--   6. `TBD` AS THE MANDATORY VALUE OF `Ref`. A cell in a Markdown table
--      cannot distinguish "empty" from "absent", so a required column needed
--      a filler token for "nothing on file". SQL has NULL. `reference` is
--      nullable and there is no magic string.
--
-- ONE MORE PROPERTY FAILED IT, AND IT IS THE MULTI-VALUED CELL
--
-- `Scope` held space-separated tokens and `Deps` held comma-separated ones,
-- because a cell is a string. Neither survives as a string. They part company
-- below, though, because they are not the same kind of thing: a component tag
-- has no identity and no attributes of its own, so it is an array-valued
-- FIELD of the item; a dependency is an edge between two items and gets a
-- relation, which is also what makes a dangling reference structurally
-- impossible rather than a whole-inventory check somebody has to run.
--
-- A NAME COLLISION HAD TO BE RESOLVED, AND IT IS NOT A COSMETIC ONE
--
-- The predecessor's `Scope` column holds component tags — `e2e`, `ee-srv`,
-- `n8n`, `ee-console`. In this platform `scope` is the TENANCY UNIT, and
-- `scope_id` on this very table already means that. Two different things
-- cannot carry one name in one schema, and the platform's meaning is the one
-- that is fixed elsewhere. So the predecessor's column is `component` here.
-- The rename is recorded rather than assumed: nothing about the corpus
-- changes, only what the column is called in a schema where `scope` was
-- already taken.
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
-- No DELETE is granted anywhere, and that is not an oversight but the whole
-- of section "withdrawal instead of deletion": nothing in this domain deletes.
-- No TRUNCATE, no TRIGGER, no REFERENCES either — see V2 for why TRUNCATE in
-- particular is the one that matters.
--
-- ADDITIVE, BECAUSE AN IMAGE ROLLBACK IS A REAL EVENT
--
-- Every column added to `worklist.item` is nullable or carries a default, so
-- the previous image — whose `ItemStore.create` writes `scope_id` and `title`
-- and nothing else — still inserts successfully against this schema. Nothing
-- is dropped and nothing is renamed.
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
-- number_space — one high-water mark per address space.
--
-- The address space is (scope, selector), and since a selector belongs to
-- exactly one scope, the selector identifies it. `tenant_id` and `scope_id`
-- are carried anyway: the first because row-level security filters on a
-- column of the row it is filtering and cannot follow a foreign key, the
-- second because a per-scope read should not have to join to be answerable.
--
-- WHY A TABLE AND NOT A SEQUENCE
--
-- A sequence is per-object, so this would need one sequence per tenant per
-- selector — created at runtime, by a role that holds no CREATE on its own
-- schema and must not. A sequence also survives rollback by design, which is
-- the right behaviour for a surrogate key and the wrong one here: the
-- guarantee below is that a number is never REUSED, and that guarantee is
-- cheaper to keep and far easier to observe when the mark is an ordinary row
-- updated in the allocating transaction.
--
-- WHY IT IS PERSISTED RATHER THAN DERIVED
--
-- `max(number) + 1` over the live rows is the obvious implementation and it
-- is wrong, because it hands a number back the moment the row that held it
-- stops being live. Nothing in this schema deletes, so there is no such thing
-- as a row that stops existing — but there is a burnt number: one allocated
-- to a transaction that then rolled back, and one skipped by a
-- `set_high_water_mark` that jumped forward. Neither is visible in
-- `max(number)`, and both must stay burnt. The mark is the record of what was
-- HANDED OUT, which is not the same set as what exists.
--
-- SETTABLE, deliberately. The predecessor's corpus will be moved here one
-- day, carrying numbers that were allocated years before this table existed,
-- and an import that could not tell the mark where the corpus had got to
-- would start handing out numbers that are already in use. The capability
-- belongs to this migration; using it does not.
-- ---------------------------------------------------------------------------
CREATE TABLE worklist.number_space (
    selector_id      UUID         PRIMARY KEY,
    tenant_id        UUID         NOT NULL,
    scope_id         UUID         NOT NULL,
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
        REFERENCES worklist.selector (tenant_id, id)
);

CREATE INDEX idx_number_space_scope ON worklist.number_space (tenant_id, scope_id);

ALTER TABLE worklist.number_space ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.number_space FORCE  ROW LEVEL SECURITY;

CREATE POLICY number_space_tenant_isolation ON worklist.number_space
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON worklist.number_space TO kumbuka_worklist;


-- ---------------------------------------------------------------------------
-- term — the vocabularies, as tenant data rather than as service code.
--
-- Cluster, type, priority and size are hard-coded in the predecessor and gate
-- every row it accepts. That makes a customer's own way of characterising
-- work a release of this service, which is the wrong place for it: the
-- vocabulary is a property of how a scope organises itself, and the service's
-- business is that a value IS in the declared vocabulary, not which values
-- are.
--
-- THE AXIS IS NOT DATA, AND THE DISTINCTION IS LOAD-BEARING
--
-- The four axes are structure: each one is a distinct column on the item, and
-- adding a fifth is a schema change either way. The VALUES on an axis are
-- data. So `axis` is a check constraint over four literals and `token` is
-- whatever the scope declares.
--
-- `ordinal` is the scope's own ranking within an axis — `P1` before `P2`,
-- `S` before `M` before `L`. Held as data for the same reason as the tokens:
-- a service that knows `S` is smaller than `M` is a service that has to be
-- released when somebody uses `XS`. Nothing in this migration reads it; it is
-- here because a token without its rank is a vocabulary that cannot be sorted
-- by anything except its spelling, and adding the column later would mean
-- backfilling a judgement nobody recorded at the time.
--
-- Withdrawal is a status here too: a term that was used on a row must stay
-- resolvable, otherwise the row's own history becomes unreadable.
-- ---------------------------------------------------------------------------
CREATE TABLE worklist.term (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL,
    scope_id    UUID         NOT NULL,
    axis        TEXT         NOT NULL,
    token       TEXT         NOT NULL,
    ordinal     INTEGER      NOT NULL DEFAULT 0,
    status      TEXT         NOT NULL DEFAULT 'declared',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_term_axis   CHECK (axis IN ('cluster', 'type', 'priority', 'size')),
    CONSTRAINT ck_term_token  CHECK (length(btrim(token)) > 0 AND token !~ '\s'),
    CONSTRAINT ck_term_status CHECK (status IN ('declared', 'withdrawn')),

    CONSTRAINT uq_term_token UNIQUE (tenant_id, scope_id, axis, token),

    -- The composite-foreign-key target again, so an item can only carry a
    -- term of its own tenant.
    CONSTRAINT uq_term_tenant_id UNIQUE (tenant_id, id)
);

CREATE INDEX idx_term_axis ON worklist.term (tenant_id, scope_id, axis);

ALTER TABLE worklist.term ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.term FORCE  ROW LEVEL SECURITY;

CREATE POLICY term_tenant_isolation ON worklist.term
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON worklist.term TO kumbuka_worklist;


-- ---------------------------------------------------------------------------
-- item — the columns V1 deliberately left out.
--
-- All additive. `status` and `conflict_token` carry defaults so the previous
-- image's two-column insert still works; everything else is nullable because
-- a raw call-in genuinely has none of it.
--
-- ON THE ADDRESS BEING NULLABLE
--
-- `selector_id` and `number` are null on a raw row and set together when the
-- row is admitted into an address space. That is not a weakened invariant, it
-- is the intake state: a call-in exists before anybody has decided what kind
-- of thing it is, and forcing an address at that moment would mean either
-- guessing a selector or refusing to record the call-in. The pairing IS
-- enforced — `ck_item_address` below rejects a half-address, which is the
-- state that would actually be a defect.
-- ---------------------------------------------------------------------------
ALTER TABLE worklist.item
    ADD COLUMN selector_id       UUID,
    ADD COLUMN number            BIGINT,
    ADD COLUMN status            TEXT NOT NULL DEFAULT 'new',
    ADD COLUMN cluster_term_id   UUID,
    ADD COLUMN type_term_id      UUID,
    ADD COLUMN priority_term_id  UUID,
    ADD COLUMN size_term_id      UUID,
    -- The component tags. An array rather than a relation: a tag has no
    -- identity, no attributes and no lifecycle of its own, so a relation
    -- would buy a join and a delete privilege and nothing else. Lower-case
    -- tokens, as the corpus has them — `e2e`, `ee-srv`, `none`.
    ADD COLUMN component         TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN reference         TEXT,
    -- Opaque by contract. A uuid is what it happens to be; nothing may read
    -- structure into it, and a caller that parses it is a caller that breaks
    -- when the generator changes.
    ADD COLUMN conflict_token    TEXT NOT NULL DEFAULT gen_random_uuid()::text;

-- The five values a row may hold, and `planned` is not among them. It is
-- derivable from iteration membership the moment a membership table exists,
-- and until then it would be a value somebody writes and nothing maintains.
-- `withdrawn` is the sixth and is not from the predecessor's vocabulary: it
-- is what the predecessor's `delete` becomes.
ALTER TABLE worklist.item
    ADD CONSTRAINT ck_item_status CHECK (
        status IN ('new', 'open', 'done', 'dropped', 'obsolete', 'withdrawn'));

-- A selector without a number, or a number without a selector, is half an
-- address and can be neither resolved nor re-allocated.
ALTER TABLE worklist.item
    ADD CONSTRAINT ck_item_address CHECK (
        (selector_id IS NULL AND number IS NULL)
        OR (selector_id IS NOT NULL AND number IS NOT NULL AND number > 0));

-- Component tokens: lower-case, as the corpus has them, and no empty string.
--
-- Through a function because a CHECK constraint may not contain a subquery,
-- and every set-returning way of walking an array is one. IMMUTABLE is
-- required rather than decorative: PostgreSQL refuses to use a function in a
-- constraint otherwise, and correctly — a constraint whose verdict could
-- change between two calls would be enforced at insert and false thereafter.
CREATE FUNCTION worklist.component_tokens_are_well_formed(tags TEXT[])
    RETURNS boolean AS $$
    SELECT bool_and(tag ~ '^[a-z][a-z0-9-]*$') IS NOT FALSE FROM unnest(tags) AS tag;
$$ LANGUAGE sql IMMUTABLE;

-- `bool_and` over an empty array is NULL, and the `IS NOT FALSE` above turns
-- that into true: a row carrying no tags is well-formed, which is the normal
-- state of a raw call-in.
ALTER TABLE worklist.item
    ADD CONSTRAINT ck_item_component
        CHECK (worklist.component_tokens_are_well_formed(component));

ALTER TABLE worklist.item
    ADD CONSTRAINT fk_item_selector FOREIGN KEY (tenant_id, selector_id)
        REFERENCES worklist.selector (tenant_id, id);

ALTER TABLE worklist.item
    ADD CONSTRAINT fk_item_cluster FOREIGN KEY (tenant_id, cluster_term_id)
        REFERENCES worklist.term (tenant_id, id);
ALTER TABLE worklist.item
    ADD CONSTRAINT fk_item_type FOREIGN KEY (tenant_id, type_term_id)
        REFERENCES worklist.term (tenant_id, id);
ALTER TABLE worklist.item
    ADD CONSTRAINT fk_item_priority FOREIGN KEY (tenant_id, priority_term_id)
        REFERENCES worklist.term (tenant_id, id);
ALTER TABLE worklist.item
    ADD CONSTRAINT fk_item_size FOREIGN KEY (tenant_id, size_term_id)
        REFERENCES worklist.term (tenant_id, id);

-- The composite-foreign-key target on the item itself, for the dependency
-- edge below.
ALTER TABLE worklist.item
    ADD CONSTRAINT uq_item_tenant_id UNIQUE (tenant_id, id);

-- An address is unique in its space. Partial, because a raw row has no
-- address and any number of raw rows may exist at once.
CREATE UNIQUE INDEX uq_item_address
    ON worklist.item (tenant_id, selector_id, number)
    WHERE number IS NOT NULL;

CREATE INDEX idx_item_status ON worklist.item (tenant_id, scope_id, status);


-- ---------------------------------------------------------------------------
-- item_dependency — the `Deps` cell, as an edge.
--
-- A relation rather than an array, and the reason is the one property the
-- predecessor could not have: a foreign key makes a DANGLING REFERENCE
-- IMPOSSIBLE. The contract lists dangling references as a whole-inventory
-- violation that `validate` reports, because a comma-separated list of
-- numbers in a text cell can point anywhere. Here it cannot point anywhere,
-- and the violation class is not carried over — it stops being a thing that
-- can happen, which is a stronger outcome than a check that finds it.
--
-- WHAT DOES NOT FOLLOW: a cycle is still possible and is still a
-- whole-inventory question. No constraint expresses "the graph is acyclic",
-- and the walk that answers it belongs with the other whole-inventory checks
-- rather than on the write path — a write path that re-validates the
-- inventory is the shape that once sealed the predecessor's writes against
-- four unmigrated rows.
--
-- NO RELATIONSHIP TYPE, deliberately, and the contract's reason holds
-- unchanged: the moment types exist, something has to interpret them, and the
-- vocabulary needs its own definition and its own guard.
--
-- WITHDRAWAL RATHER THAN DELETION, here too. An edge that is no longer
-- asserted keeps its row and changes its status. That is not tidiness: it is
-- what lets this schema hold "no verb deletes" as a property of the GRANTS
-- rather than as a rule somebody has to remember, and a single exception
-- would cost the whole of it.
-- ---------------------------------------------------------------------------
CREATE TABLE worklist.item_dependency (
    tenant_id      UUID         NOT NULL,
    item_id        UUID         NOT NULL,
    depends_on_id  UUID         NOT NULL,
    status         TEXT         NOT NULL DEFAULT 'asserted',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_item_dependency PRIMARY KEY (item_id, depends_on_id),
    CONSTRAINT ck_item_dependency_status CHECK (status IN ('asserted', 'withdrawn')),

    -- The one cycle a single row can express, and the only one a constraint
    -- can see.
    CONSTRAINT ck_item_dependency_not_self CHECK (item_id <> depends_on_id),

    CONSTRAINT fk_item_dependency_item FOREIGN KEY (tenant_id, item_id)
        REFERENCES worklist.item (tenant_id, id),
    CONSTRAINT fk_item_dependency_target FOREIGN KEY (tenant_id, depends_on_id)
        REFERENCES worklist.item (tenant_id, id)
);

CREATE INDEX idx_item_dependency_target
    ON worklist.item_dependency (tenant_id, depends_on_id);

ALTER TABLE worklist.item_dependency ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.item_dependency FORCE  ROW LEVEL SECURITY;

CREATE POLICY item_dependency_tenant_isolation ON worklist.item_dependency
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON worklist.item_dependency TO kumbuka_worklist;
