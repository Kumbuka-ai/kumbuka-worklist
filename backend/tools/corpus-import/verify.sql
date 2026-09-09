-- verify.sql
--
-- Verifikationsabfragen fuer den Steuerungskorpus-Import (Sprint 177.5,
-- 177.6, 177.7).
--
-- Jede Abfrage prueft eine Nachbedingung aus dispatch 177.5 / REA-0007. Die
-- erwartete Zeilenzahl steht im Kommentar direkt darueber; eine Abweichung
-- ist ein Fehler, keine Warnung.
--
-- V12 (Sprint 177.7) rueckt die Meilenstein-Workstream-Kante zurueck. Die
-- vormalige V3-Abfrage (cross-workstream milestones) faellt ersatzlos, weil
-- die Invariante nicht mehr existiert (TAR-0002 §4 nennt 5 Pruefungen, nicht
-- mehr 6). Fuenf Nachbedingungen bleiben.
--
-- Aufruf im Migrator-Rollenkontext:
--   psql -v ON_ERROR_STOP=on \
--        -v tenant_id="'<uuid>'" \
--        -v scope_id="'<uuid>'"  \
--        -f verify.sql

BEGIN;

SELECT set_config('app.tenant_id', :tenant_id, true);

-- ---------------------------------------------------------------------------
-- V1. Keine OFFENE Zeile im Auffang (REA-0007 §8, verschaerft durch 177.8).
-- Terminale Zeilen (done/dissolved beim Vorgaenger) sind im Auffang
-- erwartet — sie sind keine offene Planungsfrage. Eine offene Zeile
-- dort ist ein Fehler, keine Warnung.
--
-- Die Terminalitaet wird ueber den deklarierten Status-Token gepruef.
-- Der Kumbuka-Scope deklariert 'done' und 'dissolved' als terminal;
-- andere Werte gelten als offen. Wenn ein anderer Scope andere Terminal-
-- Werte hat, ist die Liste unten zu ergaenzen.
-- ---------------------------------------------------------------------------
\echo 'V1a: items im default-workstream, gruppiert nach status (informational):'
SELECT s.token AS status, count(i.*) AS items_in_default
  FROM worklist.item i
  JOIN worklist.workstream ws ON ws.id = i.workstream_id
  JOIN worklist.status s ON s.id = i.status_id
 WHERE ws.tenant_id = :tenant_id AND ws.scope_id = :scope_id
   AND ws.is_default = true
 GROUP BY s.token
 ORDER BY items_in_default DESC;

\echo 'V1b: OFFENE items im default-workstream (erwartet 0, RAISE bei Verletzung):'
DO $$
DECLARE
    open_in_default int;
BEGIN
    PERFORM set_config('app.tenant_id', :tenant_id, true);
    SELECT count(*) INTO open_in_default
      FROM worklist.item i
      JOIN worklist.workstream ws ON ws.id = i.workstream_id
      JOIN worklist.status s ON s.id = i.status_id
     WHERE ws.tenant_id = :tenant_id AND ws.scope_id = :scope_id
       AND ws.is_default = true
       AND s.token NOT IN ('done', 'dissolved');
    IF open_in_default <> 0 THEN
        RAISE EXCEPTION
            'REA-0007 §8 (verschaerft): % offene Zeilen im default-Workstream. '
            'Terminale Zeilen dort sind erwartet; offene sind ein Fehler.',
            open_in_default;
    END IF;
    RAISE NOTICE 'V1b gruen: keine offene Zeile im default-Workstream.';
END $$;

-- ---------------------------------------------------------------------------
-- V2. Vollstaendigkeit: jede erwartete Zielzeile ist im Item-Bestand.
-- Nachbedingung: die Zeilenzahl je Strang matcht den Trockenlauf-Report
-- (siehe backend/tools/corpus-import/out/report.md, Abschnitt
-- "Verteilung der assigned-Zeilen").
-- ---------------------------------------------------------------------------
\echo 'V2: Zeilenzahl je workstream:'
SELECT ws.token AS workstream, count(i.*) AS items
  FROM worklist.workstream ws
  LEFT JOIN worklist.item i ON i.workstream_id = ws.id
 WHERE ws.tenant_id = :tenant_id AND ws.scope_id = :scope_id
 GROUP BY ws.token
 ORDER BY items DESC;

-- ---------------------------------------------------------------------------
-- V3. Zaehlerstaende: je Selektor steht der Zaehler auf der hoechsten
-- vergebenen Nummer. Nach V12 sind item, iteration und milestone alle
-- scope-weit (workstream_id IS NULL im number_space).
-- Nachbedingung: kein Zaehler liegt unter seinem Maximum.
-- Probe im Anschluss: ein create ueber die Domaenenverbe vergibt N+1.
-- ---------------------------------------------------------------------------
\echo 'V3: number_space vs. tatsaechliches Maximum:'
WITH observed AS (
    SELECT 'item' AS sel, MAX(number) AS max_num
      FROM worklist.item
     WHERE tenant_id = :tenant_id AND scope_id = :scope_id
    UNION ALL
    SELECT 'milestone', MAX(number)
      FROM worklist.milestone
     WHERE tenant_id = :tenant_id AND scope_id = :scope_id
), spaces AS (
    SELECT s.token AS sel, ns.high_water_mark
      FROM worklist.number_space ns
      JOIN worklist.selector s ON s.id = ns.selector_id
     WHERE ns.tenant_id = :tenant_id AND ns.scope_id = :scope_id
       AND ns.workstream_id IS NULL
)
SELECT COALESCE(sp.sel, o.sel) AS selector,
       sp.high_water_mark AS counter,
       o.max_num AS observed_max,
       CASE
           WHEN sp.high_water_mark IS NULL AND o.max_num IS NOT NULL THEN 'FEHLT'
           WHEN sp.high_water_mark < o.max_num THEN 'HINTERHER'
           ELSE 'OK'
       END AS diagnose
  FROM observed o
  FULL JOIN spaces sp ON sp.sel = o.sel
 ORDER BY selector;

-- ---------------------------------------------------------------------------
-- V4. Kein Item ohne Workstream (V10 hat NOT NULL gesetzt, sollte durch das
-- Schema fallen; hier trotzdem geprueft, weil die Zusicherung im Kern der
-- Zielsemantik liegt).
-- ---------------------------------------------------------------------------
\echo 'V4: items ohne workstream (erwartet 0):'
SELECT count(*) AS items_ohne_workstream
  FROM worklist.item
 WHERE tenant_id = :tenant_id AND scope_id = :scope_id
   AND workstream_id IS NULL;

-- ---------------------------------------------------------------------------
-- V5. Straenge-Saat: die fuenf erwarteten Straenge existieren mit ihrer
-- Pflichtbeschreibung.
-- ---------------------------------------------------------------------------
\echo 'V5: Straenge im scope kumbuka:'
SELECT ws.token, ws.is_default,
       CASE WHEN ws.description IS NULL OR btrim(ws.description) = ''
            THEN 'FEHLT' ELSE 'OK' END AS description
  FROM worklist.workstream ws
 WHERE ws.tenant_id = :tenant_id AND ws.scope_id = :scope_id
 ORDER BY ws.number;

COMMIT;
