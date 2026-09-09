-- verify.sql
--
-- Verifikationsabfragen fuer den Steuerungskorpus-Import (Sprint 177.5).
--
-- Jede Abfrage prueft eine Nachbedingung aus dispatch 177.5 / REA-0007. Die
-- erwartete Zeilenzahl steht im Kommentar direkt darueber; eine Abweichung
-- ist ein Fehler, keine Warnung.
--
-- Aufruf im Migrator-Rollenkontext:
--   psql -v ON_ERROR_STOP=on \
--        -v tenant_id="'<uuid>'" \
--        -v scope_id="'<uuid>'"  \
--        -f verify.sql

BEGIN;

SELECT set_config('app.tenant_id', :tenant_id, true);

-- ---------------------------------------------------------------------------
-- V1. default ist leer.
-- Nachbedingung: 0 Items im default-Workstream (REA-0007 Abschnitt 8).
-- ---------------------------------------------------------------------------
\echo 'V1: items im default-workstream (erwartet 0):'
SELECT count(*) AS items_in_default
  FROM worklist.item i
  JOIN worklist.workstream ws ON ws.id = i.workstream_id
 WHERE ws.tenant_id = :tenant_id AND ws.scope_id = :scope_id
   AND ws.is_default = true;

-- ---------------------------------------------------------------------------
-- V2. Vollstaendigkeit: jede erwartete Zielzeile ist im Item-Bestand.
-- Nachbedingung: die Zeilenzahl je Strang matcht den Trockenlauf-Report.
-- Erwartet (aus dry_run.py output):
--   produktlinie: 247, betrieb: 58, architektur: 31, gtm: 14,
--   agenten-entwicklung: 1, default: 0
-- ---------------------------------------------------------------------------
\echo 'V2: Zeilenzahl je workstream:'
SELECT ws.token AS workstream, count(i.*) AS items
  FROM worklist.workstream ws
  LEFT JOIN worklist.item i ON i.workstream_id = ws.id
 WHERE ws.tenant_id = :tenant_id AND ws.scope_id = :scope_id
 GROUP BY ws.token
 ORDER BY items DESC;

-- ---------------------------------------------------------------------------
-- V3. Milestone-Invariante: jeder Item.milestone_id liegt in Item.workstream_id.
-- Nachbedingung: 0 Zeilen. Diese Invariante lebt in ItemService.java
-- (refuseCrossWorkstreamMilestone, Zeile 844-867) und ist in der Datenbank
-- NICHT durchgesetzt. Der SQL-Import kann sie deshalb durch einen Fehler
-- brechen; die Abfrage findet den Bruch.
-- ---------------------------------------------------------------------------
\echo 'V3: cross-workstream milestones (erwartet 0):'
SELECT count(*) AS cross_workstream_milestones
  FROM worklist.item i
  JOIN worklist.milestone m ON m.id = i.milestone_id
 WHERE i.tenant_id = :tenant_id AND i.scope_id = :scope_id
   AND i.workstream_id <> m.workstream_id;

-- ---------------------------------------------------------------------------
-- V4. Zaehlerstaende: je Strang und Selektor steht der Zaehler auf der
-- hoechsten vergebenen Nummer.
-- Nachbedingung: kein Zaehler liegt unter seinem Maximum.
-- Probe im Anschluss: ein create ueber die Domaenenverbe vergibt N+1.
-- ---------------------------------------------------------------------------
\echo 'V4: number_space vs. tatsaechliches Maximum:'
WITH observed AS (
    SELECT 'item' AS sel, workstream_id, MAX(number) AS max_num
      FROM worklist.item
     WHERE tenant_id = :tenant_id AND scope_id = :scope_id
     GROUP BY workstream_id
    UNION ALL
    SELECT 'milestone', workstream_id, MAX(number)
      FROM worklist.milestone
     WHERE tenant_id = :tenant_id AND scope_id = :scope_id
     GROUP BY workstream_id
), spaces AS (
    SELECT s.token AS sel, ns.workstream_id, ns.high_water_mark
      FROM worklist.number_space ns
      JOIN worklist.selector s ON s.id = ns.selector_id
     WHERE ns.tenant_id = :tenant_id AND ns.scope_id = :scope_id
)
SELECT COALESCE(sp.sel, o.sel) AS selector,
       ws.token AS workstream,
       sp.high_water_mark AS counter,
       o.max_num AS observed_max,
       CASE
           WHEN sp.high_water_mark IS NULL AND o.max_num IS NOT NULL THEN 'FEHLT'
           WHEN sp.high_water_mark < o.max_num THEN 'HINTERHER'
           ELSE 'OK'
       END AS diagnose
  FROM observed o
  FULL JOIN spaces sp ON sp.sel = o.sel AND sp.workstream_id = o.workstream_id
  LEFT JOIN worklist.workstream ws ON ws.id = COALESCE(sp.workstream_id,
                                                        o.workstream_id)
 ORDER BY selector, workstream;

-- ---------------------------------------------------------------------------
-- V5. Kein Item ohne Workstream (V10 hat NOT NULL gesetzt, sollte durch das
-- Schema fallen; hier trotzdem geprueft, weil die Zusicherung im Kern der
-- Zielsemantik liegt).
-- ---------------------------------------------------------------------------
\echo 'V5: items ohne workstream (erwartet 0):'
SELECT count(*) AS items_ohne_workstream
  FROM worklist.item
 WHERE tenant_id = :tenant_id AND scope_id = :scope_id
   AND workstream_id IS NULL;

-- ---------------------------------------------------------------------------
-- V6. Straenge-Saat: die fuenf erwarteten Straenge existieren mit ihrer
-- Pflichtbeschreibung.
-- ---------------------------------------------------------------------------
\echo 'V6: Straenge im scope kumbuka:'
SELECT ws.token, ws.is_default,
       CASE WHEN ws.description IS NULL OR btrim(ws.description) = ''
            THEN 'FEHLT' ELSE 'OK' END AS description
  FROM worklist.workstream ws
 WHERE ws.tenant_id = :tenant_id AND ws.scope_id = :scope_id
 ORDER BY ws.number;

COMMIT;
