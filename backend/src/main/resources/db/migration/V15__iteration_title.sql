-- ===========================================================================
-- V15: the iteration carries a title.
--
-- Ratified 2026-09-12 as part of SPRINT_180.5. Every planning root except the
-- iteration already carried one — {@link Item#title} and
-- {@link Milestone#title} are the axis position's handle in every listing —
-- and the iteration alone had no first-line name of its own. Motto and
-- description are what an iteration is ABOUT (the refusal criterion), and
-- neither is the handle a listing needs. The title is the third and it lines
-- the four planning views up on ONE canonical name for the first-line handle:
-- item, milestone and iteration all take TITLE now, and the field catalogue
-- says so.
--
-- WHY IT IS ADDITIVE
--
-- The column is NULLABLE. Existing iteration rows carry no title and are not
-- rewritten by this migration; a caller may set the title through
-- `update` afterwards, and a scope that opened its axis before this migration
-- stays readable exactly as it is. Making the column NOT NULL is a separate
-- act that requires deciding what to backfill with, and this migration does
-- not decide it.
--
-- WHY THE 200-CHARACTER CAP
--
-- V7 caps {@code item.title} and {@code milestone.title} at 200; the third
-- handle is capped the same way, so an operator cannot present a title that
-- one of the other two would refuse. The domain enforces the same bound as a
-- typed refusal (INVALID_VALUE naming the field) before the row reaches
-- flush; the check constraint is the mechanism, and the Java check is the
-- message.
-- ===========================================================================

ALTER TABLE worklist.iteration
    ADD COLUMN title TEXT;

ALTER TABLE worklist.iteration
    ADD CONSTRAINT ck_iteration_title_length
        CHECK (title IS NULL OR char_length(title) <= 200);

-- ---------------------------------------------------------------------------
-- No GRANT is issued here, and its absence is not an oversight.
--
-- V4 granted SELECT, INSERT and UPDATE on `worklist.iteration` without a
-- column list, and a table-level privilege covers every column and every
-- constraint the table ever acquires. Nothing above adds a privileged
-- object, so there is nothing a grant could even be about.
-- `ServiceRolePrivilegeIT` asserts the exact privilege set in both
-- directions and would report a change here either way.
-- ---------------------------------------------------------------------------
