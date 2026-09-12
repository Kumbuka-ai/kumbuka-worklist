-- ===========================================================================
-- V16: `iteration.title` — the one-line handle every listing needs.
--
-- The two axes now agree on this field. A milestone has carried `title` since
-- V4; an iteration does not, and asking "which iteration?" against the
-- current shape has to fall back to the motto — a sentence, not a handle.
-- V4 built the iteration table with `motto` and `description` as the only
-- required text fields, both mandatory, both non-blank. This migration adds
-- a THIRD required field beside them: a title, mandatory, non-blank, and
-- capped for the same reason V7 caps the item's and the milestone's title.
--
-- WHY IT IS ADDITIVE AND NOT AN EDIT OF V4 OR V5
--
-- `worklist.flyway_schema_history` carries checksums for V1 through V15 on
-- the estate that already runs this schema — measured 2026-09-12 against the
-- `kumbuka` scope. Editing an applied file fails Flyway's checksum on the
-- next boot; that is a refused start, not a text change. So the column
-- comes as V16 and touches no earlier file.
--
-- BACKFILL WITHOUT A CALLER-SUPPLIED VALUE
--
-- Existing rows carry no title. The store measured today holds zero
-- iterations; a hypothetical scope with a hand-written iteration takes the
-- placeholder below, which reads plainly and points at the number the
-- caller can already see — the operator renames it the first time they
-- notice. The alternative — refusing to migrate until every iteration has
-- one — bounds a schema change on the state of an unrelated table and is
-- exactly the mechanism this migration is here to avoid.
--
-- WHY THE COLUMN COMES NULLABLE AND IS NARROWED IN THE SAME FILE
--
-- ADD COLUMN with a stored DEFAULT and NOT NULL would fill every existing
-- row with the same string; the UPDATE below produces a distinct label per
-- row before the NOT NULL narrows the column. The intermediate nullable
-- state is confined to this migration and no verb sees it.
--
-- CAPS
--
-- The check constraint below caps the WRITE path only — a read is
-- untouched, exactly as V7's caps on `item.title` and `milestone.title`.
-- 200 characters, matching the two siblings. `char_length` counts
-- characters and not bytes: the caller writes text, and a byte cap would
-- misread multi-byte input.
--
-- WHY NO GRANT IS ISSUED HERE
--
-- V4 granted SELECT, INSERT, UPDATE on `worklist.iteration` without a
-- column list; a table-level privilege covers every column the table ever
-- acquires. `ServiceRolePrivilegeIT` asserts the exact privilege set in
-- both directions.
-- ===========================================================================

-- Nullable at first: the backfill below is the population step and cannot
-- run with the column already narrowed.
ALTER TABLE worklist.iteration ADD COLUMN title TEXT;

-- The placeholder names the number the row already carries, so a reader
-- finds the iteration by its address without help from the operator, and
-- has an obvious hook to replace with the real handle.
UPDATE worklist.iteration
   SET title = 'iteration ' || number::text
 WHERE title IS NULL;

ALTER TABLE worklist.iteration ALTER COLUMN title SET NOT NULL;

ALTER TABLE worklist.iteration
    ADD CONSTRAINT ck_iteration_title
        CHECK (length(btrim(title)) > 0);

ALTER TABLE worklist.iteration
    ADD CONSTRAINT ck_iteration_title_length
        CHECK (char_length(title) <= 200);
