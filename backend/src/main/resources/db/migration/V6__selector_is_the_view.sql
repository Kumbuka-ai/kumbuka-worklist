-- ===========================================================================
-- V6: the selector token is lower case.
--
-- V4 built the selector as the item's FAMILY -- FEAT, CHORE, BUG -- and gave
-- each family an address space of its own. The address form ratified since
-- makes the selector the VIEW instead: `worklist://<scope>/item/562`,
-- `.../iteration/27`, `.../milestone/9`, and a membership as a second id
-- segment under its iteration.
--
-- WHY THIS IS ADDITIVE AND NOT A THIRD REPLACEMENT
--
-- V4 replaced its own previous content once and recorded the measurement that
-- made it admissible; V5 refused to repeat it and said why. This one does not
-- reopen the question. Nothing below drops a table, a column or a row: it
-- narrows one check constraint, expressed as an ordinary ALTER, and does not
-- touch the two files.
--
-- THE STORE IS STILL EMPTY, and that is measured rather than assumed. Every
-- `worklist` string in `infra/compose.prod.yml` and
-- `local-dev/docker-compose.dev.yml` belongs to the Python predecessor,
-- `worklist-manager-service` -- measured 2026-09-04, confirmed 2026-09-05.
-- That is what makes a narrowing constraint safe to add: there is no row that
-- could fail it, and no running caller whose next write would.
--
-- ===========================================================================
-- THE SELECTOR TOKEN IS LOWER CASE
-- ===========================================================================
--
-- The old expression admitted upper case, because the families it was written
-- for were spelled FEAT and CHORE. The three views are spelled `item`,
-- `iteration` and `milestone`, and the address they sit in is lower case
-- throughout: the scope is a DNS label, and upper case there is REJECTED
-- rather than folded, because folding would make two strings resolve to one
-- object.
--
-- ADR-0009 fixes the four address parts and says nothing about the case of
-- the selector. So this is a DECISION and not a discovery, and it is taken in
-- the direction the rest of the address already settles: reject, never fold.
--
-- WHY THE CONSTRAINT IS NOT `token IN ('item','iteration','milestone')`.
-- That is the stronger statement and it is the wrong layer for it. Which
-- tokens name views is the platform's object model; a check constraint here
-- would put it in a per-scope table, and it would move the refusal from a
-- typed domain error a caller can read into a constraint violation that
-- reaches them as a 500. `SelectorRegistry.declare` refuses a token outside
-- the three, by name, and that refusal is what the red probe removes to watch
-- the guard fail. A constraint doing the same job would make that probe
-- impossible to write: the database would catch what the probe needs to
-- observe getting through.
--
-- What stays here is therefore the FORM -- decidable without knowing any
-- scope, which is what a constraint is good at -- and the two expressions
-- must not drift: `Selector.TOKEN_PATTERN` in Java is the same language.
-- ---------------------------------------------------------------------------

ALTER TABLE worklist.selector
    DROP CONSTRAINT ck_selector_token;

ALTER TABLE worklist.selector
    ADD CONSTRAINT ck_selector_token
        CHECK (token ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');


-- ---------------------------------------------------------------------------
-- No GRANT is issued here, and its absence is not an oversight.
--
-- V4 granted SELECT, INSERT and UPDATE on the selector table WITHOUT a column
-- list, and a table-level privilege covers every column the table ever
-- acquires. Nothing above adds a column, so there is nothing a grant could
-- even be about. `ServiceRolePrivilegeIT` asserts the exact privilege set in
-- both directions and would report a change here either way.
-- ---------------------------------------------------------------------------
