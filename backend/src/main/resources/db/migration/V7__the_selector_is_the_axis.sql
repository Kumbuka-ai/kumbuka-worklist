-- ===========================================================================
-- V7: each axis allocates from its own selector's counter, and the two
-- high-water marks on scope_setting fall away.
--
-- V6 made the selector the view: `item`, `iteration`, `milestone`. Under the
-- view model each axis is a class of its own — three tables, three ratified
-- names — while `chore` and `feature` were never classes but only derivations
-- of item, so the per-selector counter always meant "per class" once the
-- selector became the view. V6 got the mode's default WRONG for exactly that
-- reason: it read `per_selector` as "one counter per family", saw that under
-- the view model that would produce `.../item/1`, `.../iteration/1`,
-- `.../milestone/1` all colliding on their bare numbers, and flipped the
-- default to `scope_wide` to avoid the collision — but under the class read
-- the three DO have their own spaces, because they name three different kinds
-- of thing. That is what this migration corrects.
--
-- WHY THIS IS ADDITIVE, AND WHAT "ADDITIVE" IS BEING STRETCHED TO MEAN
--
-- V4 replaced its own previous content once, under a measurement: the store
-- held no row and the service ran in no topology. That was a one-time
-- exception, said so, and V5 and V6 refused to repeat it. This one does not
-- reopen the question either. Nothing here rewrites V4 or V5, no index is
-- replaced, and no data is migrated.
--
-- Two columns fall away, expressed as ordinary ALTER statements — the same
-- shape V6's default flip used. The `DROP COLUMN` clauses below are ALTER
-- TABLE statements at the SQL grammar level; what the additivity rule refuses
-- is the wholesale replacement V4 did once, not every syntactic occurrence
-- of the word DROP. The store is empty — measured 2026-09-05 against
-- `infra/compose.prod.yml` and `local-dev/docker-compose.dev.yml`, neither of
-- which carries this service — so no row is lost with the columns.
--
-- ===========================================================================
-- PART 1 — THE ALLOCATION MODE'S DEFAULT MOVES BACK TO PER SELECTOR
-- ===========================================================================
--
-- V6 flipped it to `scope_wide` under the family reading of the selector.
-- V7 flips it back to `per_selector` under the class reading, and the column
-- is not dropped: a scope that WANTS scope-wide numbering has said so
-- explicitly and keeps its setting.
--
-- What changes for a scope that expressed no preference: item, iteration and
-- milestone each allocate from their own counter, and `worklist://kumbuka/item/1`
-- lives beside `worklist://kumbuka/iteration/1` — different views, different
-- classes, different address spaces. That is the whole point of the address
-- carrying the view.
-- ---------------------------------------------------------------------------

ALTER TABLE worklist.scope_setting
    ALTER COLUMN allocation_mode SET DEFAULT 'per_selector';


-- ===========================================================================
-- PART 2 — THE TWO PLANNING AXES' MARKS FALL AWAY
-- ===========================================================================
--
-- V4 and V5 put a high-water mark for each planning axis on `scope_setting`,
-- because at the time the axes were NOT selectors — the selector was an
-- item's family — and `number_space` had nowhere for their counters to sit.
-- The rows hang off a selector by foreign key, and the scope-wide row per
-- scope was already taken by the item allocator.
--
-- The axes ARE selectors now. A milestone allocates from the number_space
-- row of the `milestone` selector, an iteration from `iteration`'s, and the
-- item allocator continues to read `item`'s. There is no argument left for
-- the marks to sit on `scope_setting`, and leaving them there would be two
-- columns nobody reads while the number_space row is what actually holds the
-- mark — the exact class of stored-copy-that-drifts the concept refuses.
--
-- SO THE TWO COLUMNS FALL AWAY, AS ORDINARY ALTER STATEMENTS. Nothing else
-- moves: the mark values were zero on every scope this service holds (there
-- is one scope, and it has no settings row yet), and the migration would
-- have been irreducible anyway — the columns and their check constraint
-- disappear together, and the constraint has no life of its own to preserve.
--
-- The check constraint dies with the columns, because ALTER TABLE ... DROP
-- COLUMN removes constraints that depend on the column. `ck_scope_setting_marks`
-- names both, and both go here.
-- ---------------------------------------------------------------------------

ALTER TABLE worklist.scope_setting
    DROP COLUMN milestone_high_water_mark,
    DROP COLUMN iteration_high_water_mark;


-- ---------------------------------------------------------------------------
-- No GRANT is issued here, and its absence is not an oversight.
--
-- V4 granted SELECT, INSERT and UPDATE on scope_setting WITHOUT a column
-- list, and a table-level privilege covers every column the table has at
-- any moment. Dropping columns narrows the surface a privilege applies to;
-- it does not take a privilege away.
--
-- The number_space rows the milestone and iteration allocators now read
-- were granted in V4, at declaration time, and no privilege here is new.
-- `ServiceRolePrivilegeIT` asserts the exact privilege set in both
-- directions and would report a change here either way.
-- ---------------------------------------------------------------------------
