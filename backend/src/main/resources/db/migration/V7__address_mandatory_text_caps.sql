-- ===========================================================================
-- V7: the address is mandatory, and every text field has a written ceiling.
--
-- Two assertions the target state carries as prose but no mechanism guarded
-- until now. This migration attaches each of them to the layer where a caller
-- cannot go around it: the database.
--
-- WHY IT IS ADDITIVE AND NOT AN EDIT OF V4
--
-- The chain is applied on the host. `worklist.flyway_schema_history` carries
-- checksums for V1 through V6 -- measured 2026-09-08 06:14Z, right after the
-- rollout that made the schema live. Editing a file already recorded there
-- would fail Flyway's checksum on the next boot; that is a refused start, not
-- a text change. So the two additions come as V7 and touch no earlier file.
--
-- WHY EVERY STATEMENT BELOW IS EITHER `SET NOT NULL` OR `ADD CONSTRAINT`
--
-- Both are online operations on an empty table: `worklist.item` carries no
-- row, and `worklist.milestone` carries the three markers seeded during
-- bootstrap -- each with `number` set and `title` well under any cap this
-- migration proposes. Nothing here rewrites a row, drops a column, or issues
-- a grant. The V4 comment about the address being "nullable in both halves"
-- becomes stale with this migration; that is a comment defect and it is
-- carried forward on its own record, because a text edit to V4 is what this
-- migration structurally cannot make.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- 1. THE ADDRESS IS NEVER HALF-STATED, AND NEVER ABSENT.
--
-- V4 admitted (NULL, NULL) as an "intake" state -- the raw call-in that no
-- verb had yet accepted into an address space. V6 turned the selector into
-- the view: `create` allocates the pair transactionally, and `accept`
-- refuses. No verb reaches a state where a row exists with either half of
-- the address unset. The columns are the last statement of that fact, and
-- they are `NOT NULL`.
--
-- The old `ck_item_address` expressed the pair-or-neither invariant plus
-- `number > 0`. With both columns NOT NULL, the pair-or-neither half is
-- structurally satisfied and the check reduces to `number > 0`, which is
-- restated below as `ck_item_number` so a reader finds the positive-number
-- floor by name at the item.
-- ---------------------------------------------------------------------------

ALTER TABLE worklist.item ALTER COLUMN selector_id SET NOT NULL;
ALTER TABLE worklist.item ALTER COLUMN number      SET NOT NULL;

ALTER TABLE worklist.item DROP CONSTRAINT ck_item_address;

ALTER TABLE worklist.item
    ADD CONSTRAINT ck_item_number CHECK (number > 0);


-- ---------------------------------------------------------------------------
-- 2. TEXT FIELDS CARRY AN UPPER BOUND, WRITTEN ON THE WRITE PATH ONLY.
--
-- A `CHECK` binds INSERT and UPDATE; a SELECT is untouched. That is the whole
-- point: a row already carrying an over-cap value stays readable, and the
-- store never seals itself against its own content. The predecessor learnt
-- this the hard way -- a cap on the read path made the content unrepairable
-- through the service.
--
-- Nullable fields (`milestone.vision`, `milestone.mission`) admit NULL and
-- only cap the string; NOT NULL fields (`item.title`, `milestone.title`) cap
-- the string directly. `char_length` counts characters, not bytes: the caller
-- writes text and would misread a byte cap on multi-byte input.
--
-- `item.description` is DELIBERATELY NOT CAPPED. It is where the
-- predecessor's over-long descriptive prose lands when the corpus is moved
-- here, and a cap on it would be the trap `title` is being capped against.
-- ---------------------------------------------------------------------------

ALTER TABLE worklist.item
    ADD CONSTRAINT ck_item_title_length
        CHECK (char_length(title) <= 200);

ALTER TABLE worklist.milestone
    ADD CONSTRAINT ck_milestone_title_length
        CHECK (char_length(title) <= 200);

ALTER TABLE worklist.milestone
    ADD CONSTRAINT ck_milestone_vision_length
        CHECK (vision IS NULL OR char_length(vision) <= 200);

ALTER TABLE worklist.milestone
    ADD CONSTRAINT ck_milestone_mission_length
        CHECK (mission IS NULL OR char_length(mission) <= 1500);


-- ---------------------------------------------------------------------------
-- No GRANT is issued here, and its absence is not an oversight.
--
-- V4 granted SELECT, INSERT and UPDATE on `item` and on `milestone` without a
-- column list, and a table-level privilege covers every column and every
-- constraint the table ever acquires. Nothing above adds a column or a
-- privileged object, so there is nothing a grant could even be about.
-- `ServiceRolePrivilegeIT` asserts the exact privilege set in both directions
-- and would report a change here either way.
-- ---------------------------------------------------------------------------
