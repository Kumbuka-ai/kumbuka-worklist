-- ===========================================================================
-- V5: the planning layer's conflict tokens, and the high-water marks of the
-- two planning axes.
--
-- V4 built the planning tables and said so in its own header: "The tables are
-- here and their VERBS are not." This migration is the half that arrives with
-- the verbs, and it is ADDITIVE — new columns with defaults on tables V4
-- created. Nothing is renamed, nothing is narrowed, and V4 is not touched.
--
-- WHY THIS IS NOT ANOTHER REPLACEMENT
--
-- V4 replaced its own previous content once, and the header records the
-- measurement that made it admissible: the store held no row and the service
-- ran in no topology. That was a one-off. Measured again 2026-09-04 against
-- `infra/compose.prod.yml` and `local-dev/docker-compose.dev.yml`, this
-- service still runs nowhere — every `worklist` string in the estate's
-- topology belongs to the Python predecessor, `worklist-manager-service` —
-- so a replacement would still be POSSIBLE. It is refused anyway: what made
-- the first one admissible was that the whole target schema landed in one
-- file, and repeating it for two column groups would turn a measured
-- exception into a habit.
--
-- ===========================================================================
-- PART 1 — A CONFLICT TOKEN ON EVERY AGGREGATE ROOT
-- ===========================================================================
--
-- Optimistic locking is PER AGGREGATE. Item, iteration and milestone are
-- aggregate roots; `scope_setting` is a configuration object with a token of
-- its own. A write presents the token of its ROOT: a reorder writes the
-- iteration whose membership list happens to be twelve rows and presents that
-- iteration's one token, never twelve.
--
-- `item` already carries the column, from V4. The three below are what it
-- left out, because a token with no verb to present it is a column nobody
-- writes.
--
-- THERE IS DELIBERATELY NO TOKEN ON `iteration_membership`, and this is the
-- most tempting column in the whole migration. A membership IS addressed at
-- its own address — the mapping table of the verb catalogue says `update`
-- carries membership status addressed at the membership — so a column of its
-- own looks natural. It would invert the ratification exactly: the aggregate
-- would be the row again, and the reorder above would be back to presenting
-- twelve tokens. Addressing and token ownership are two different things, and
-- here they come apart.
--
-- THE DEFAULT IS WHAT MAKES THE COLUMN ADDABLE AT ALL. `gen_random_uuid()`
-- is volatile, so every existing row gets its OWN value rather than one value
-- shared by all of them — a shared token would mean any write to any row
-- invalidated every other reader of the table. There are no rows here today;
-- the property is stated because it is the property that would matter if
-- there were.
-- ---------------------------------------------------------------------------

ALTER TABLE worklist.milestone
    ADD COLUMN conflict_token TEXT NOT NULL DEFAULT gen_random_uuid()::text;

ALTER TABLE worklist.iteration
    ADD COLUMN conflict_token TEXT NOT NULL DEFAULT gen_random_uuid()::text;

ALTER TABLE worklist.scope_setting
    ADD COLUMN conflict_token TEXT NOT NULL DEFAULT gen_random_uuid()::text;


-- ===========================================================================
-- PART 2 — THE HIGH-WATER MARKS OF THE MILESTONE AND ITERATION AXES
-- ===========================================================================
--
-- Both axes number their objects, and both concepts say the numbers are never
-- reused: "a closed milestone stays in the table, so the allocator counts past
-- it" (concept 6.1), and "iteration numbers are never reused, and the
-- mechanism is a persisted high-water mark rather than the highest number
-- present" (concept 6.2).
--
-- V4 provides no carrier for either mark, and this is the one gap in it that
-- the planning verbs cannot route around. `number_space` is the item
-- allocator and cannot hold them: its rows hang off a selector by foreign
-- key, and its one selector-less row per scope is already taken by the
-- scope-wide ITEM counter, held there by a partial unique index. An axis row
-- with a null selector would collide with that index, and widening the index
-- would mean rewriting a constraint V4 created — which is exactly the
-- narrowing this migration promised not to do.
--
-- SO THE MARKS SIT ON `scope_setting`, one row per scope, as two columns.
-- That table already carries running state beside its settings —
-- `current_iteration_id` is a pointer, not a decision — so the mixture is the
-- ratified design's and not this migration's invention.
--
-- A MARK ADVANCE DOES NOT ROTATE THE SETTING'S TOKEN, and that is the point
-- of putting it in writing. Creating an iteration is a write on the ITERATION
-- aggregate; it advances the mark as an allocator side effect, exactly as
-- `accept` advances `number_space` while rotating only the item's token.
-- Rotating the setting's token here would reproduce the defect measured in
-- sprint 169 — a token that moves between two calls with no write of the
-- caller's own in between — with a single user and no concurrency at all.
--
-- ZERO IS THE FLOOR AND THE STARTING POSITION. The first allocation returns
-- 1, which is what `ck_milestone_number` and `ck_iteration_number` already
-- require. A mark is carried forward and never back; that is a domain check,
-- where the previous value is known, and the constraint below is the floor
-- under it.
-- ---------------------------------------------------------------------------

ALTER TABLE worklist.scope_setting
    ADD COLUMN milestone_high_water_mark BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN iteration_high_water_mark BIGINT NOT NULL DEFAULT 0;

ALTER TABLE worklist.scope_setting
    ADD CONSTRAINT ck_scope_setting_marks CHECK (
        milestone_high_water_mark >= 0
        AND iteration_high_water_mark >= 0);


-- ---------------------------------------------------------------------------
-- A SURROGATE IDENTITY ON `scope_setting`, AND WHY THE TABLE KEY IS NOT
-- TOUCHED.
--
-- `pk_scope_setting` is `(tenant_id, scope_id)` and stays exactly that: the
-- row is one per scope and the key says so. What the column below adds is an
-- identity the ORM can key the entity on WITHOUT drawing the tenancy axis
-- into the Java key.
--
-- The tenancy pair is mapped once, in a superclass every entity in this
-- schema inherits, and `tenant_id` there is the aspect's column — set by the
-- tenant resolver, filtered by Hibernate, never named by a caller. A
-- composite key over it would put the aspect's column into every lookup
-- signature in the planning layer and make the one mapping that has to be
-- identical everywhere an exception on one table.
--
-- `iteration_membership` needs nothing like this: its key beyond the tenant
-- is `(iteration_id, item_id)`, both of them ordinary columns of the entity,
-- so a composite key over those two costs nothing. `scope_setting` is the one
-- table whose only key column outside the tenant is `scope_id`, which the
-- superclass owns.
--
-- Unique rather than primary: the primary key is the statement about what a
-- row IS, and that statement is still "one per scope".
-- ---------------------------------------------------------------------------

ALTER TABLE worklist.scope_setting
    ADD COLUMN id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE worklist.scope_setting
    ADD CONSTRAINT uq_scope_setting_id UNIQUE (id);


-- ---------------------------------------------------------------------------
-- No GRANT is issued here, and its absence is not an oversight.
--
-- V4 granted SELECT, INSERT and UPDATE on each of these tables WITHOUT a
-- column list, and a table-level privilege covers every column the table ever
-- acquires. A column-level grant added here would be narrower than the one
-- already in force and would say something false about how access to this
-- schema is arranged.
--
-- The service role owns nothing and reaches its tables through enumerated
-- privileges; the migrator owns the tables and therefore owns these columns.
-- `ServiceRolePrivilegeIT` asserts the exact privilege set, in both
-- directions, and would report a change here either way.
-- ---------------------------------------------------------------------------
