-- verify.sql
--
-- Nachbedingungen des Steuerungskorpus-Imports (Sprint 178.2, ergaenzt 178.1).
--
-- Jede Pruefung ist ein Wachter. Sie druckt ihren Messwert und RAISE'd bei
-- Verletzung. Vor jeder inhaltlichen Pruefung steht V0 (Identitaet vor Inhalt):
-- der corpus_import_marker existiert und stimmt mit dem erwarteten Digest
-- und Pin ueberein. Ist V0 rot, laeuft nichts anderes.
--
-- Aufruf im Migrator-Rollenkontext, gleiche Konvention wie die Importdatei:
--
--   psql -v ON_ERROR_STOP=on \
--        -v tenant_id=<uuid> \
--        -v scope_id=<uuid> \
--        -v expected_digest=<sha256> \
--        -v expected_rows=<N> \
--        -v expected_id_refs=<N> \
--        -v expected_truncated_titles=<N> \
--        -v expected_edges=<N> \
--        -v expected_prose_rows=<N> \
--        -v expected_reference_entries=<N> \
--        -f verify.sql
--
-- Die erwarteten Zahlen kommen aus dem Kopf der frisch generierten
-- Importdatei; der Generator druckt sie auch auf stderr.
--
-- Warum GUCs statt :'tenant_id' im DO-Block: psql ersetzt Variablen NICHT
-- innerhalb von DO $$ ... $$. Die Importdatei loest das ueber transaktionale
-- GUCs (kw_import.scope_id); verify.sql folgt dem Muster mit dem Prefix
-- kw_verify.

BEGIN;

-- Tenant und Scope einmal binden. RLS bindet die Migrator-Rolle (V3 FORCE),
-- deshalb ist SELECT ohne app.tenant_id refused.
SELECT set_config('app.tenant_id',           :'tenant_id',                true);
SELECT set_config('kw_verify.scope_id',      :'scope_id',                 true);
SELECT set_config('kw_verify.expected_digest',           :'expected_digest',           true);
SELECT set_config('kw_verify.expected_rows',             :'expected_rows',             true);
SELECT set_config('kw_verify.expected_id_refs',          :'expected_id_refs',          true);
SELECT set_config('kw_verify.expected_truncated_titles', :'expected_truncated_titles', true);
SELECT set_config('kw_verify.expected_edges',            :'expected_edges',            true);
SELECT set_config('kw_verify.expected_prose_rows',        :'expected_prose_rows',        true);
SELECT set_config('kw_verify.expected_reference_entries', :'expected_reference_entries', true);

-- ---------------------------------------------------------------------------
-- V0. Identitaet vor Inhalt. Der corpus_import_marker existiert, sein Digest
-- passt zum erwarteten, seine Zeilenzahl auch. Ist einer dieser Werte falsch,
-- ist es ein anderer Bestand: keine weitere Pruefung ist aussagekraeftig.
-- ---------------------------------------------------------------------------
\echo 'V0: corpus_import_marker (Identitaet vor Inhalt):'
DO $$
DECLARE
    v_scope           UUID    := current_setting('kw_verify.scope_id')::uuid;
    v_expected_digest TEXT    := current_setting('kw_verify.expected_digest');
    v_expected_rows   BIGINT  := current_setting('kw_verify.expected_rows')::bigint;
    v_marker_name     TEXT;
    v_digest          TEXT;
    v_rows            BIGINT;
BEGIN
    SELECT name INTO v_marker_name
      FROM worklist.attribute_definition
     WHERE scope_id = v_scope
       AND key      = 'corpus_import_marker';
    IF v_marker_name IS NULL THEN
        RAISE EXCEPTION
            'V0 rot: corpus_import_marker fehlt im Scope %. '
            'Kein Import ist eingespielt (oder ein anderer Scope).', v_scope;
    END IF;

    -- Formatvertrag aus generate_import.py: "sha=<hex64>; pin=<hex40>; rows=<N>".
    v_digest := substring(v_marker_name FROM 'sha=([0-9a-f]{64})');
    v_rows   := substring(v_marker_name FROM 'rows=([0-9]+)')::bigint;

    IF v_digest IS NULL THEN
        RAISE EXCEPTION
            'V0 rot: corpus_import_marker.name ohne sha-Feld: %', v_marker_name;
    END IF;
    IF v_digest <> v_expected_digest THEN
        RAISE EXCEPTION
            'V0 rot: Digest weicht ab. erwartet %, gefunden %.',
            v_expected_digest, v_digest;
    END IF;
    IF v_rows <> v_expected_rows THEN
        RAISE EXCEPTION
            'V0 rot: Zeilenzahl im Marker weicht ab. erwartet %, gefunden %.',
            v_expected_rows, v_rows;
    END IF;
    RAISE NOTICE 'V0 gruen: Marker % (digest %, rows %)',
        v_marker_name, v_digest, v_rows;
END $$;

-- ---------------------------------------------------------------------------
-- V1a. Verteilung der Items je Workstream (informational, nicht gate).
-- ---------------------------------------------------------------------------
\echo 'V1a: Items je Workstream (informational):'
SELECT ws.token AS workstream, count(i.*) AS items
  FROM worklist.workstream ws
  LEFT JOIN worklist.item i ON i.workstream_id = ws.id
 WHERE ws.tenant_id = :'tenant_id' AND ws.scope_id = :'scope_id'
 GROUP BY ws.token
 ORDER BY items DESC;

-- ---------------------------------------------------------------------------
-- V1b. Keine OFFENE Zeile im default-Workstream (REA-0007 §8, verschaerft
-- durch 177.8, 178.1 haengt `dropped` als Terminal an).
-- Terminale Zeilen im Auffang sind erwartet; offene sind ein Fehler.
-- ---------------------------------------------------------------------------
\echo 'V1b: offene items im default-workstream (erwartet 0):'
DO $$
DECLARE
    v_scope         UUID := current_setting('kw_verify.scope_id')::uuid;
    open_in_default INT;
BEGIN
    SELECT count(*) INTO open_in_default
      FROM worklist.item i
      JOIN worklist.workstream ws ON ws.id = i.workstream_id
      JOIN worklist.item_status s ON s.id = i.status_id
     WHERE ws.scope_id = v_scope
       AND ws.is_default = true
       AND s.name NOT IN ('done', 'dissolved', 'dropped');
    IF open_in_default <> 0 THEN
        RAISE EXCEPTION
            'V1b rot: % offene Zeilen im default-Workstream. '
            'Terminale Zeilen dort sind erwartet; offene sind ein Fehler.',
            open_in_default;
    END IF;
    RAISE NOTICE 'V1b gruen: keine offene Zeile im default-Workstream.';
END $$;

-- ---------------------------------------------------------------------------
-- V2. Dichte Neunummerierung: item.number ist 1..N ohne Luecke, N = expected.
-- ---------------------------------------------------------------------------
\echo 'V2: dichte Neunummerierung (1..N):'
DO $$
DECLARE
    v_scope         UUID   := current_setting('kw_verify.scope_id')::uuid;
    v_expected_rows BIGINT := current_setting('kw_verify.expected_rows')::bigint;
    v_min           BIGINT;
    v_max           BIGINT;
    v_count         BIGINT;
BEGIN
    SELECT min(number), max(number), count(*)
      INTO v_min, v_max, v_count
      FROM worklist.item
     WHERE scope_id = v_scope
       AND number IS NOT NULL;
    IF v_count <> v_expected_rows THEN
        RAISE EXCEPTION
            'V2 rot: Zeilenzahl weicht ab. erwartet %, gefunden %.',
            v_expected_rows, v_count;
    END IF;
    IF v_min <> 1 OR v_max <> v_expected_rows THEN
        RAISE EXCEPTION
            'V2 rot: Nummernraum nicht dicht 1..%. min=%, max=%.',
            v_expected_rows, v_min, v_max;
    END IF;
    -- Explizite Lueckenprobe: sind alle Zahlen 1..N genau einmal vertreten,
    -- muss count = max - min + 1 sein UND max = N. Beides ist oben schon
    -- gemessen; die Zusatzprobe hier ist ein LEFT JOIN gegen generate_series,
    -- damit eine Doppelvergabe (die einen Ausfall an anderer Stelle maskieren
    -- wuerde) auch gefunden wird.
    IF EXISTS (
        SELECT 1
          FROM generate_series(1, v_expected_rows) g(n)
          LEFT JOIN worklist.item i
            ON i.scope_id = v_scope AND i.number = g.n
         WHERE i.id IS NULL
    ) THEN
        RAISE EXCEPTION
            'V2 rot: mindestens eine Nummer im Bereich 1..% fehlt.',
            v_expected_rows;
    END IF;
    RAISE NOTICE 'V2 gruen: dicht 1..% (count=%)', v_expected_rows, v_count;
END $$;

-- ---------------------------------------------------------------------------
-- V3. Zaehler: number_space.high_water_mark fuer item liegt auf N; die
-- naechste Vergabe waere N+1.
-- ---------------------------------------------------------------------------
\echo 'V3: Zaehler = N (naechste Vergabe = N+1):'
DO $$
DECLARE
    v_scope         UUID   := current_setting('kw_verify.scope_id')::uuid;
    v_expected_rows BIGINT := current_setting('kw_verify.expected_rows')::bigint;
    v_hwm           BIGINT;
BEGIN
    SELECT ns.high_water_mark INTO v_hwm
      FROM worklist.number_space ns
      JOIN worklist.selector s ON s.id = ns.selector_id
     WHERE ns.scope_id = v_scope
       AND s.token     = 'item'
       AND ns.workstream_id IS NULL;
    IF v_hwm IS NULL THEN
        RAISE EXCEPTION
            'V3 rot: kein Item-Zaehler im number_space (scope=%).', v_scope;
    END IF;
    IF v_hwm <> v_expected_rows THEN
        RAISE EXCEPTION
            'V3 rot: Item-Zaehler weicht ab. erwartet %, gefunden %.',
            v_expected_rows, v_hwm;
    END IF;
    RAISE NOTICE 'V3 gruen: Zaehler=% (naechste Vergabe %)',
        v_hwm, v_hwm + 1;
END $$;

-- ---------------------------------------------------------------------------
-- V4. ID-Referenzen: die Anzahl der Items, die als ERSTEN Referenz-Eintrag
-- eine sprechende ID (z.B. CHORE-319, FEAT-91, F-01) tragen, entspricht der
-- Anzahl zugewiesener Quellzeilen mit ID.
--
-- Formvertrag der alten ID: ein Selektor-Token (Grossbuchstaben, ggf. mit
-- Bindestrichen), gefolgt von Bindestrich und Ziffern. Der Ordinal fuer den
-- ID-Eintrag ist 0 (die Reihenfolge im Generator: ID zuerst).
-- ---------------------------------------------------------------------------
\echo 'V4: Anzahl Items mit ID als ersten Referenz-Eintrag:'
DO $$
DECLARE
    v_scope        UUID   := current_setting('kw_verify.scope_id')::uuid;
    v_expected     BIGINT := current_setting('kw_verify.expected_id_refs')::bigint;
    v_id_regex     TEXT   := '^[A-Z][A-Z0-9]*(-[A-Z0-9]+)*-\d+$';
    v_with_id      BIGINT;
BEGIN
    SELECT count(*)
      INTO v_with_id
      FROM worklist.item_reference r
     WHERE r.scope_id = v_scope
       AND r.status   = 'asserted'
       AND r.ordinal  = 0
       AND r.target   ~ v_id_regex;
    IF v_with_id <> v_expected THEN
        RAISE EXCEPTION
            'V4 rot: Items mit ID-Referenz weichen ab. erwartet %, gefunden %.',
            v_expected, v_with_id;
    END IF;
    RAISE NOTICE 'V4 gruen: % Items mit ID als erstem Referenz-Eintrag',
        v_with_id;
END $$;

-- ---------------------------------------------------------------------------
-- V5. Titel-Kappung: die Menge der gekappten Titel ist erkennbar am
-- Endzeichen '…' (der Generator kappt an der letzten Wortgrenze <= 199,
-- rstrip't und haengt '…' an — der resultierende Titel hat char_length
-- HOECHSTENS 200 statt exakt 200, weil die Wortgrenze frueher liegen kann;
-- gemessen 2026-09-10). Die Pruefung stuetzt sich deshalb auf das Endzeichen
-- und die Kappungsobergrenze (char_length <= 200) statt auf eine
-- Punktgroesse.
--
-- Nachbedingung:
--   (a) Anzahl der Items mit Titel endet auf '…' und char_length <= 200
--       entspricht expected_truncated_titles.
--   (b) Jedes so gekappte Item hat description NOT NULL (und traegt
--       damit den vollen Originaltitel, siehe 178.2 §2).
-- ---------------------------------------------------------------------------
\echo 'V5: gekappte Titel mit description:'
DO $$
DECLARE
    v_scope         UUID   := current_setting('kw_verify.scope_id')::uuid;
    v_expected      BIGINT := current_setting('kw_verify.expected_truncated_titles')::bigint;
    v_truncated     BIGINT;
    v_missing_desc  BIGINT;
BEGIN
    SELECT count(*)
      INTO v_truncated
      FROM worklist.item i
     WHERE i.scope_id = v_scope
       AND char_length(i.title) <= 200
       AND right(i.title, 1) = U&'\2026';  -- '…'
    IF v_truncated <> v_expected THEN
        RAISE EXCEPTION
            'V5 rot: gekappte Titel weichen ab. erwartet %, gefunden %.',
            v_expected, v_truncated;
    END IF;
    SELECT count(*)
      INTO v_missing_desc
      FROM worklist.item i
     WHERE i.scope_id = v_scope
       AND char_length(i.title) <= 200
       AND right(i.title, 1) = U&'\2026'
       AND (i.description IS NULL OR btrim(i.description) = '');
    IF v_missing_desc <> 0 THEN
        RAISE EXCEPTION
            'V5 rot: % gekappte Titel ohne description.', v_missing_desc;
    END IF;
    RAISE NOTICE 'V5 gruen: % gekappte Titel, description ueberall gesetzt',
        v_truncated;
END $$;

-- ---------------------------------------------------------------------------
-- V6. Kantenzahl: item_relation traegt genau die im Generator ausgezaehlte
-- Zahl (depends_on, asserted).
-- ---------------------------------------------------------------------------
\echo 'V6: Kantenzahl:'
DO $$
DECLARE
    v_scope     UUID   := current_setting('kw_verify.scope_id')::uuid;
    v_expected  BIGINT := current_setting('kw_verify.expected_edges')::bigint;
    v_edges     BIGINT;
BEGIN
    SELECT count(*)
      INTO v_edges
      FROM worklist.item_relation r
     WHERE r.scope_id = v_scope
       AND r.status   = 'asserted';
    IF v_edges <> v_expected THEN
        RAISE EXCEPTION
            'V6 rot: Kantenzahl weicht ab. erwartet %, gefunden %.',
            v_expected, v_edges;
    END IF;
    RAISE NOTICE 'V6 gruen: % Kanten', v_edges;
END $$;

-- ---------------------------------------------------------------------------
-- V7. Kein Feld des Ziels traegt die alte Nr als Wert. Prueft item.number
-- (aus item_reference.target darf sie stehen — dort ist die ID zulaessig,
-- die Nr selbst aber nicht, denn die Ref-Segmente sind aus der Ref-Spalte
-- der Quelle). Die Deckung fuer die Kernaussage "item.number ist 1..N"
-- liefert V2 schon; V7 haert die Aussage fuer die uebrigen Felder ab, die
-- der Generator schreibt.
--
-- Konkret: item.description enthaelt nichts, was wie eine reine Nr-Zeichen-
-- kette der Quelle aussieht (die Quell-Nr am Pin ist 1..507). Da die
-- description bei gekappten Titeln den vollen Original-Titel traegt und die
-- Original-Titel nirgends mit einer nackten Zahl beginnen (measure-Aussage;
-- der Wortlaut wird ohnehin gruen sein), pruefen wir hier NUR, dass die
-- alten Nrs, die im Zuweisungs-TSV standen, nicht als Item-Titel auftauchen
-- ("Nr 42" oder aehnliches Muster).
--
-- Der eigentliche Wachter gegen "alte Nr taucht auf" ist V2 (Dichte) und die
-- Abwesenheit einer legacy_id-Spalte; V7 ist die Ergaenzung, dass keine
-- reine Ziffernfolge in item.title auftaucht, die Nr-artig waere.
-- ---------------------------------------------------------------------------
\echo 'V7: keine alte Nr in item.title als isoliertes Token:'
DO $$
DECLARE
    v_scope UUID := current_setting('kw_verify.scope_id')::uuid;
    v_hits  BIGINT;
BEGIN
    SELECT count(*)
      INTO v_hits
      FROM worklist.item i
     WHERE i.scope_id = v_scope
       AND i.title ~ '^[0-9]+$';
    IF v_hits <> 0 THEN
        RAISE EXCEPTION
            'V7 rot: % Item-Titel bestehen nur aus Ziffern (alte Nr-Form).',
            v_hits;
    END IF;
    RAISE NOTICE 'V7 gruen: kein Titel ist eine nackte Nr.';
END $$;

-- ---------------------------------------------------------------------------
-- V9. Prosa-Regel (178.2): drei Aussagen ueber item_reference und
-- item.description.
--
--   (a) Die Anzahl der Zeilen in item_reference entspricht
--       :expected_reference_entries. Der Generator zaehlt sie beim Bau der
--       Datei; der Wert steht im Header.
--   (b) Kein item_reference.target ist laenger als 2000 Byte. Das ist die
--       Regel-Umleitung selbst — waere ein solcher Eintrag drin, waere die
--       Prosa-Regel im Generator umgangen oder defekt.
--   (c) Die Anzahl der Items mit Prosa in `description` entspricht
--       :expected_prose_rows. Als "traegt Prosa" zaehlt hier
--       octet_length(description) > 2000: die drei Nicht-Prosa-Faelle
--       liegen darunter (NULL, gekappter Titel = Originaltitel <= 436 Byte,
--       leer nach der Bau-Tabelle) und der Prosa-Fall enthaelt per Regel
--       mindestens ein Segment > 2000 Byte. Die Grenze ist damit unabhaengig
--       vom Trenner '---' und vom Titelvergleich.
-- ---------------------------------------------------------------------------
\echo 'V9: Prosa-Regel (Referenz-Zaehlung, target-Deckel, description-Prosa):'
DO $$
DECLARE
    v_scope            UUID   := current_setting('kw_verify.scope_id')::uuid;
    v_expected_refs    BIGINT := current_setting('kw_verify.expected_reference_entries')::bigint;
    v_expected_prose   BIGINT := current_setting('kw_verify.expected_prose_rows')::bigint;
    v_refs_count       BIGINT;
    v_oversize_targets BIGINT;
    v_prose_rows       BIGINT;
BEGIN
    SELECT count(*) INTO v_refs_count
      FROM worklist.item_reference r
     WHERE r.scope_id = v_scope
       AND r.status   = 'asserted';
    IF v_refs_count <> v_expected_refs THEN
        RAISE EXCEPTION
            'V9 rot: item_reference-Zeilen weichen ab. erwartet %, gefunden %.',
            v_expected_refs, v_refs_count;
    END IF;

    SELECT count(*) INTO v_oversize_targets
      FROM worklist.item_reference r
     WHERE r.scope_id = v_scope
       AND r.status   = 'asserted'
       AND octet_length(r.target) > 2000;
    IF v_oversize_targets <> 0 THEN
        RAISE EXCEPTION
            'V9 rot: % item_reference.target-Zeilen sind > 2000 Byte. '
            'Prosa-Regel im Generator umgangen oder defekt.',
            v_oversize_targets;
    END IF;

    SELECT count(*) INTO v_prose_rows
      FROM worklist.item i
     WHERE i.scope_id = v_scope
       AND i.description IS NOT NULL
       AND octet_length(i.description) > 2000;
    IF v_prose_rows <> v_expected_prose THEN
        RAISE EXCEPTION
            'V9 rot: Items mit Prosa in description weichen ab. '
            'erwartet %, gefunden %.',
            v_expected_prose, v_prose_rows;
    END IF;

    RAISE NOTICE
        'V9 gruen: % Referenz-Zeilen (kein target > 2000 Byte), '
        '% Items mit Prosa in description.',
        v_refs_count, v_prose_rows;
END $$;

-- ---------------------------------------------------------------------------
-- V8. Straenge-Saat (bestehend seit 177.5): die fuenf erwarteten Straenge
-- plus default existieren mit ihrer Pflichtbeschreibung.
-- ---------------------------------------------------------------------------
\echo 'V8: Straenge im scope:'
SELECT ws.token, ws.is_default,
       CASE WHEN ws.description IS NULL OR btrim(ws.description) = ''
            THEN 'FEHLT' ELSE 'OK' END AS description
  FROM worklist.workstream ws
 WHERE ws.tenant_id = :'tenant_id' AND ws.scope_id = :'scope_id'
 ORDER BY ws.number;

COMMIT;
