-- ===========================================================================
-- bootstrap-scope.sql — declare a scope so the service can be used in it.
--
-- WHY THIS FILE EXISTS AND IS NOT A MIGRATION
--
-- A scope arrives with nothing declared: no selectors, no statuses, no
-- attributes, no relation types. Every write verb refuses against such a
-- scope, and correctly so — a vocabulary is declared deliberately and never
-- minted by first use. The declaration is therefore an act, and today there
-- is no verb for it: the surface projection of `scope_setting` is deferred,
-- and vocabulary is by contract never declared through a machine verb. The
-- console that will carry it does not exist yet.
--
-- So this file is the bootstrap path, and it is named as one. It is NOT a
-- fallback and must not become one: the day the console can declare, this
-- file is for opening a scope before there is anyone to click.
--
-- It lives OUTSIDE `db/migration` on purpose. Flyway collects everything
-- under its migration path, and a bootstrap that ran as a migration would run
-- for every scope of every deployment, including scopes belonging to somebody
-- else.
--
-- IT CARRIES NO INSTANCE VALUES. Scope and tenant are psql variables. This
-- repository is public and a scope identifier is a deployment fact.
--
--   psql -v scope_id=... -v tenant_id=... -f bootstrap-scope.sql
--
-- IT WILL NOT RUN WITHOUT A BOUND TENANCY AXIS. `app.tenant_id` is a
-- transaction-local setting and nothing here sets it: the script cannot know
-- which axis it is being run under, and a value it chose for itself would
-- defeat the point of the setting. Bind it in the same session, ahead of the
-- file:
--
--   { echo "SET app.tenant_id = '<uuid>';"; cat bootstrap-scope.sql; } | psql ...
--
-- Run without it, the first insert is refused with
--
--   ERROR: new row violates row-level security policy for table "scope_setting"
--
-- and that refusal is the isolation working rather than a misconfiguration.
-- V3 sets FORCE ROW LEVEL SECURITY, which binds the OWNER as well, precisely
-- so a later DML statement cannot quietly write across the tenant boundary.
-- The migrator owns the schema and is bound like everyone else.
--
-- Run it as the MIGRATOR role, not as the service role: the service role
-- holds SELECT, INSERT, UPDATE on `worklist.item` and nothing else, so every
-- statement below would be refused for want of a privilege. That refusal is
-- the isolation working too.
--
-- IDEMPOTENT. Every insert carries ON CONFLICT DO NOTHING, so a second run
-- changes nothing and fails nowhere. A bootstrap that cannot be repeated gets
-- taken apart by hand the second time.
--
-- WHAT IT DELIBERATELY DOES NOT DO
--
--   It does not create the scope. The scope lives in the platform's own table
--   and is resolved through the read contract; a row written here against an
--   identifier the contract does not carry would be invisible to the service
--   while looking perfectly healthy in the store.
--
--   It declares no milestone other than the three markers. A goal milestone
--   is planning work and belongs to a verb.
--
--   It declares no options for `component`. See the block.
-- ===========================================================================

\set ON_ERROR_STOP on

\if :{?scope_id}
\else
\echo 'FATAL: -v scope_id=<uuid> is required'
\quit 1
\endif

\if :{?tenant_id}
\else
\echo 'FATAL: -v tenant_id=<uuid> is required'
\quit 1
\endif

BEGIN;

-- ---------------------------------------------------------------------------
-- The scope settings row, and the three views with it.
--
-- Creating this row is what opens the scope: the service seeds the three
-- selectors — `item`, `iteration`, `milestone` — as part of the same act. They
-- are the classes of thing this service reasons about, they are fixed by the
-- platform, and they are not the estate's own vocabulary. What an item is
-- ABOUT is a declared attribute further down, never a selector.
--
-- The four cardinality thresholds are NOT NULL with no default, so they are
-- stated here rather than inherited. The values are the predecessor's ratified
-- limits: at most five planned iterations with a warning from three, at most
-- fifteen memberships per iteration with a warning from ten.
--
-- `default_columns` stays empty. It is a display preference and an empty list
-- is a legitimate value; guessing a column set here would put a presentation
-- decision into a bootstrap.
-- ---------------------------------------------------------------------------
INSERT INTO worklist.scope_setting
    (tenant_id, scope_id,
     max_planned_iterations, warn_planned_iterations,
     max_memberships_per_iteration, warn_memberships_per_iteration,
     default_columns)
VALUES
    (:'tenant_id', :'scope_id', 5, 3, 15, 10, '{}')
ON CONFLICT DO NOTHING;

INSERT INTO worklist.selector (tenant_id, scope_id, token)
VALUES
    (:'tenant_id', :'scope_id', 'item'),
    (:'tenant_id', :'scope_id', 'iteration'),
    (:'tenant_id', :'scope_id', 'milestone'),
    (:'tenant_id', :'scope_id', 'workstream')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- The default workstream.
--
-- Ratified 2026-09-08: the fourth view. Every scope opens with a default
-- workstream that catches everything not explicitly filed elsewhere; its
-- identity is fixed by the `is_default` flag and only its description is
-- settable. Number 1 is reserved for it, and the number space for the
-- workstream selector is advanced to match.
--
-- No milestone counter is opened here for the default workstream — the
-- existing per-scope milestone counter is repurposed as the default's per
-- V9's backfill logic, which for a scope opened after V9 needs the same
-- shape from the start: the milestone counter belongs to the default
-- workstream from the outset.
-- ---------------------------------------------------------------------------
INSERT INTO worklist.workstream
    (tenant_id, scope_id, number, token, description, is_default)
VALUES
    (:'tenant_id', :'scope_id', 1, 'default',
     'The default workstream. Everything that names no other lands here; '
        || 'its identity is fixed and only the description is settable.',
     true)
ON CONFLICT DO NOTHING;

-- Advance the workstream selector's own counter so the next declare
-- allocates 2 rather than colliding at 1.
UPDATE worklist.number_space ns
SET high_water_mark = GREATEST(ns.high_water_mark, 1)
FROM worklist.selector s
WHERE ns.tenant_id   = :'tenant_id'
  AND ns.scope_id    = :'scope_id'
  AND ns.selector_id = s.id
  AND s.token        = 'workstream';

-- The milestone counter for the default workstream: created here so a
-- scope opened after V9 has the same per-(scope, workstream) shape the
-- backfill left behind for scopes opened before. Bind the counter to the
-- default's id and open it at zero.
INSERT INTO worklist.number_space
    (tenant_id, scope_id, selector_id, workstream_id, high_water_mark)
SELECT :'tenant_id', :'scope_id', s.id, w.id, 0
FROM worklist.selector s
CROSS JOIN worklist.workstream w
WHERE s.tenant_id = :'tenant_id'
  AND s.scope_id  = :'scope_id'
  AND s.token     = 'milestone'
  AND w.tenant_id = :'tenant_id'
  AND w.scope_id  = :'scope_id'
  AND w.is_default = true
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- The status vocabulary, and its four predicates.
--
-- A status is a declared value carrying platform-readable properties, which is
-- why it is its own table and not an attribute: the platform computes over
-- `actionable`, `in_progress`, `closed` and `successful`, and over nothing
-- else.
--
-- FIVE VALUES, NOT SIX. The predecessor's vocabulary is `new`, `open`,
-- `planned`, `done`, `dropped`, `obsolete`. `planned` is absent here on
-- purpose: it means "in an iteration", and that is derived from the membership
-- rather than stored. A stored `planned` would be a second authority over the
-- same question, and the two would drift.
--
-- `successful` separates the two closed values that look alike from outside:
-- `done` is work carried out, `dropped` and `obsolete` are work that will not
-- be. `obsolete` exists so an agent can terminate stock without claiming
-- execution, and no agent ever writes `done`.
--
-- `rank` is the display order, not a ranking of importance.
-- ---------------------------------------------------------------------------
INSERT INTO worklist.item_status
    (tenant_id, scope_id, name, description, rank,
     actionable, in_progress, closed, successful)
VALUES
    (:'tenant_id', :'scope_id', 'new',
     'Roher Eingang: aufgenommen, aber noch nicht charakterisiert.',
     10, false, false, false, false),
    (:'tenant_id', :'scope_id', 'open',
     'Charakterisierte Arbeit, die noch niemand angefangen hat.',
     20, true, false, false, false),
    (:'tenant_id', :'scope_id', 'done',
     'Ausgefuehrt. Eine Aussage ueber Ausfuehrung, die nur ein Mensch trifft.',
     30, false, false, true, true),
    (:'tenant_id', :'scope_id', 'dropped',
     'Verworfen: die Arbeit wird nicht gemacht.',
     40, false, false, true, false),
    (:'tenant_id', :'scope_id', 'obsolete',
     'Gegenstandslos: die Arbeit trifft nicht mehr zu.',
     50, false, false, true, false)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- The declared attributes.
--
-- Five axes, and the values live in the item's JSONB keyed by the definition's
-- identity. A sixth axis later is a declaration and not a migration — that is
-- the whole point of the layer.
--
-- The KEY is what a caller writes and what a filter narrows on; the NAME is a
-- label and may read in any language. Keys are English because everything
-- outside the session material is.
--
-- `component` was called `Scope` in the predecessor and is renamed here.
-- `scope` is taken: the item already carries a `scope_id` as its tenancy axis,
-- and a declared attribute of the same name would mean two different things in
-- one row.
-- ---------------------------------------------------------------------------
INSERT INTO worklist.attribute_definition
    (tenant_id, scope_id, key, name, description, type, rank, sortable)
VALUES
    (:'tenant_id', :'scope_id', 'cluster', 'Cluster',
     'Strategische Einordnung. Der Rang traegt die Gewichtung.',
     'choice', 10, true),
    (:'tenant_id', :'scope_id', 'type', 'Typ',
     'Art der Arbeit. Traegt keine Planungsbedeutung.',
     'choice', 20, false),
    (:'tenant_id', :'scope_id', 'priority', 'Prioritaet',
     'Beratend auf jeder Art. Ein leerer Wert ist vollstaendig, nicht unvollstaendig.',
     'choice', 30, true),
    (:'tenant_id', :'scope_id', 'size', 'Groesse',
     'Grobe Aufwandsschaetzung.',
     'choice', 40, true),
    (:'tenant_id', :'scope_id', 'component', 'Komponente',
     'Welche Bauteile eine Zeile betrifft. Mehrwertig, offenes Vokabular.',
     'multi_choice', 50, false)
ON CONFLICT DO NOTHING;

-- Cluster, in the predecessor's strategic order. This ordering was
-- configuration in the predecessor's code (`steering.schema.sort_key`); here
-- it is data in the scope, and revisable without a release.
INSERT INTO worklist.attribute_option (tenant_id, scope_id, definition_id, name, description, rank)
SELECT :'tenant_id', :'scope_id', d.id, v.name, v.description, v.rank
  FROM worklist.attribute_definition d
  JOIN (VALUES
        ('SEC',    'Sicherheit: Invarianten, Haertungen, Zusicherungen mit Waechter.', 10),
        ('BETA',   'Was die geschlossene Beta traegt.',                                20),
        ('CORE',   'Der offene Kern.',                                                 30),
        ('DEPLOY', 'Auslieferung, Wirt, Topologie.',                                   40),
        ('CONN',   'Konnektoren und fremde Flaechen.',                                 50),
        ('EE',     'Enterprise-Funktionalitaet.',                                      60),
        ('GTM',    'Go-to-Market.',                                                    70),
        ('HK',     'Haushalt: Wartung, Aufraeumen, Werkzeug.',                         80)
       ) AS v(name, description, rank) ON true
 WHERE d.tenant_id = :'tenant_id' AND d.scope_id = :'scope_id' AND d.key = 'cluster'
ON CONFLICT DO NOTHING;

INSERT INTO worklist.attribute_option (tenant_id, scope_id, definition_id, name, description, rank)
SELECT :'tenant_id', :'scope_id', d.id, v.name, v.description, v.rank
  FROM worklist.attribute_definition d
  JOIN (VALUES
        ('feature', 'Neue Produkt- oder Plattformfunktionalitaet.', 10),
        ('bugfix',  'Defekt in bestehendem Verhalten.',             20),
        ('chore',   'Wartung, Prozess, Doku, Infrastruktur.',       30),
        ('test',    'Pruefstand und Abdeckung.',                    40),
        ('doc',     'Dokumentation.',                               50)
       ) AS v(name, description, rank) ON true
 WHERE d.tenant_id = :'tenant_id' AND d.scope_id = :'scope_id' AND d.key = 'type'
ON CONFLICT DO NOTHING;

-- P1 is absolute in the predecessor's sort order; the rank records that.
INSERT INTO worklist.attribute_option (tenant_id, scope_id, definition_id, name, description, rank)
SELECT :'tenant_id', :'scope_id', d.id, v.name, v.description, v.rank
  FROM worklist.attribute_definition d
  JOIN (VALUES
        ('P1', 'Absolut: geht jeder anderen Zeile vor.', 10),
        ('P2', 'Erhoeht.',                               20),
        ('P3', 'Nachrangig.',                            30)
       ) AS v(name, description, rank) ON true
 WHERE d.tenant_id = :'tenant_id' AND d.scope_id = :'scope_id' AND d.key = 'priority'
ON CONFLICT DO NOTHING;

INSERT INTO worklist.attribute_option (tenant_id, scope_id, definition_id, name, description, rank)
SELECT :'tenant_id', :'scope_id', d.id, v.name, v.description, v.rank
  FROM worklist.attribute_definition d
  JOIN (VALUES
        ('S', 'Klein.',  10),
        ('M', 'Mittel.', 20),
        ('L', 'Gross.',  30)
       ) AS v(name, description, rank) ON true
 WHERE d.tenant_id = :'tenant_id' AND d.scope_id = :'scope_id' AND d.key = 'size'
ON CONFLICT DO NOTHING;

-- NO OPTIONS FOR `component`, and that is a decision rather than an omission.
--
-- The predecessor's contract calls it an open vocabulary: lowercase tokens,
-- at least one per row, and the corpus carries roughly two dozen of them
-- (`e2e`, `ee-srv`, `n8n`, `ee-console`, and `none` as the honest queryable
-- name for "no component recorded"). Declaring a closed set here would either
-- guess that list or freeze it at the moment of least knowledge.
--
-- UNVERIFIED: whether the domain admits a `multi_choice` definition with no
-- declared options. The schema does not forbid it; the service might. If a
-- write against this attribute is refused for want of an option, that refusal
-- is the answer, and the values are then declared from the migrated corpus
-- rather than invented here.

-- ---------------------------------------------------------------------------
-- The relation type.
--
-- The predecessor recorded dependencies WITHOUT a type, deliberately: the
-- moment types exist, something has to interpret them. This model requires a
-- named type, and `blocks` is the property the readiness computation reads.
--
-- ONE type, `depends_on`, blocking. That is the faithful reading of the
-- predecessor's `Deps` — "the target must be done first" — but it does name a
-- meaning the predecessor refused to name, and it is recorded here as such.
-- ---------------------------------------------------------------------------
INSERT INTO worklist.relation_type (tenant_id, scope_id, name, description, rank, blocks)
VALUES
    (:'tenant_id', :'scope_id', 'depends_on',
     'Die Zeile haengt von der Zielzeile ab: das Ziel muss zuerst erledigt sein.',
     10, true)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- The three markers.
--
-- These are the predecessor's three non-numeric milestone tokens, and the
-- mapping is exact rather than interpreted:
--
--   M?  not yet assessed              -> not_assessed
--   M0  off the product path          -> off_path
--   Mx  on the path, no vision covers -> no_vision
--
-- They are rows and positions on the axis rather than exceptions, which is why
-- they live in the milestone table. A marker carries neither vision nor
-- mission — the schema enforces that — because a marker is the statement that
-- no goal applies.
--
-- WHY THIS IS WRITTEN AGAINST THE TABLE. `MilestoneService.create` offers no
-- route to set `kind`; markers are not creatable through a verb. That was a
-- decision in the milestone build, and it is the reason a bootstrap has to
-- reach past the surface here.
--
-- The numbers 1..3 are allocated by this file, so the high water mark is
-- carried forward in the same transaction below. Without that the service
-- allocates 1 again at the first real milestone and collides — silently,
-- because this script would have completed cleanly.
-- ---------------------------------------------------------------------------
INSERT INTO worklist.milestone (tenant_id, scope_id, number, title, kind, status, rank)
VALUES
    (:'tenant_id', :'scope_id', 1, 'Noch nicht bewertet',      'not_assessed', 'planned', 10),
    (:'tenant_id', :'scope_id', 2, 'Abseits des Produktpfads', 'off_path',     'planned', 20),
    (:'tenant_id', :'scope_id', 3, 'Keine Vision deckt dies',  'no_vision',    'planned', 30)
ON CONFLICT DO NOTHING;

-- Carry the milestone counter past the three markers. `GREATEST` keeps this
-- idempotent and keeps a second run from lowering a mark the service has since
-- advanced.
--
-- Only the milestone counter is written. `item` and `iteration` allocate
-- nothing here, and a counter row absent is the same as a counter at zero —
-- the service creates the row at its first allocation.
INSERT INTO worklist.number_space (tenant_id, scope_id, selector_id, high_water_mark)
SELECT :'tenant_id', :'scope_id', s.id, 3
  FROM worklist.selector s
 WHERE s.tenant_id = :'tenant_id' AND s.scope_id = :'scope_id' AND s.token = 'milestone'
ON CONFLICT (tenant_id, scope_id, selector_id)
DO UPDATE SET high_water_mark = GREATEST(worklist.number_space.high_water_mark, 3),
              updated_at = now();

COMMIT;

-- ---------------------------------------------------------------------------
-- Read back what is actually there. A bootstrap that reports success without
-- showing its result is a bootstrap nobody checks.
-- ---------------------------------------------------------------------------
\echo ''
\echo '--- selectors ---'
SELECT token, status FROM worklist.selector
 WHERE tenant_id = :'tenant_id' AND scope_id = :'scope_id' ORDER BY token;

\echo '--- statuses ---'
SELECT name, actionable, in_progress, closed, successful FROM worklist.item_status
 WHERE tenant_id = :'tenant_id' AND scope_id = :'scope_id' ORDER BY rank;

\echo '--- attributes ---'
SELECT d.key, d.type, count(o.id) AS options
  FROM worklist.attribute_definition d
  LEFT JOIN worklist.attribute_option o ON o.definition_id = d.id
 WHERE d.tenant_id = :'tenant_id' AND d.scope_id = :'scope_id'
 GROUP BY d.key, d.type, d.rank ORDER BY d.rank;

\echo '--- relation types ---'
SELECT name, blocks FROM worklist.relation_type
 WHERE tenant_id = :'tenant_id' AND scope_id = :'scope_id' ORDER BY rank;

\echo '--- milestones ---'
SELECT number, kind, title FROM worklist.milestone
 WHERE tenant_id = :'tenant_id' AND scope_id = :'scope_id' ORDER BY number;

\echo '--- counters ---'
SELECT s.token, n.high_water_mark
  FROM worklist.number_space n JOIN worklist.selector s ON s.id = n.selector_id
 WHERE n.tenant_id = :'tenant_id' AND n.scope_id = :'scope_id' ORDER BY s.token;
