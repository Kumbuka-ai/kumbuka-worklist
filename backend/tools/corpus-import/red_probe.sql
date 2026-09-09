-- red_probe.sql
--
-- Roter Probelauf fuer die Milestone-Invariante (Falle 5 aus dispatch 177.5).
--
-- Der Verifikations-Query in verify.sql (V3) findet cross-workstream
-- Milestones. Ein Query, der nie etwas gefunden hat, koennte leer sein weil
-- er falsch geschrieben ist. Dieser Probelauf setzt in einer Wegwerf-
-- Transaktion GENAU EINE Zeile absichtlich falsch, misst V3 rot, und rollt
-- zurueck.
--
-- Muster: BEGIN + Verletzung + Query + Rollback. Kein COMMIT.
--
-- Aufruf im Migrator-Rollenkontext, nach Echtlauf:
--   psql -v ON_ERROR_STOP=on \
--        -v tenant_id="'<uuid>'" \
--        -v scope_id="'<uuid>'"  \
--        -f red_probe.sql
--
-- Erwartete Ausgabe:
--   NOTICE:  V3 vor Verletzung (erwartet 0): 0
--   NOTICE:  V3 nach Verletzung (erwartet 1): 1
-- Und danach ROLLBACK, sodass der Datenbestand unangetastet bleibt.

BEGIN;
SELECT set_config('app.tenant_id', :tenant_id, true);

DO $$
DECLARE
    v_item_id uuid;
    v_wrong_milestone_id uuid;
    v_before int;
    v_after int;
BEGIN
    -- V3 vor der Verletzung.
    SELECT count(*) INTO v_before
      FROM worklist.item i
      JOIN worklist.milestone m ON m.id = i.milestone_id
     WHERE i.tenant_id = :tenant_id AND i.scope_id = :scope_id
       AND i.workstream_id <> m.workstream_id;
    RAISE NOTICE 'V3 vor Verletzung (erwartet 0): %', v_before;

    IF v_before <> 0 THEN
        RAISE EXCEPTION 'Vor der Verletzung stand V3 nicht auf 0 (%). '
                        'Der Bestand ist bereits verletzt; die Probe ist '
                        'nicht aussagekraeftig.', v_before;
    END IF;

    -- Kandidatenwahl: ein Item, das kein Milestone hat, in einem
    -- Nicht-Produktlinie-Strang. Sein workstream_id kollidiert dann mit
    -- jedem Milestone-workstream_id (weil Milestones nur in Produktlinie).
    SELECT i.id INTO v_item_id
      FROM worklist.item i
      JOIN worklist.workstream ws ON ws.id = i.workstream_id
     WHERE i.tenant_id = :tenant_id AND i.scope_id = :scope_id
       AND i.milestone_id IS NULL
       AND ws.token <> 'produktlinie'
     LIMIT 1;

    IF v_item_id IS NULL THEN
        RAISE EXCEPTION 'Kein geeignetes Kandidaten-Item gefunden.';
    END IF;

    -- Erster verfuegbarer Milestone (jeder liegt in Produktlinie nach dem
    -- Import).
    SELECT id INTO v_wrong_milestone_id
      FROM worklist.milestone
     WHERE tenant_id = :tenant_id AND scope_id = :scope_id
     LIMIT 1;

    IF v_wrong_milestone_id IS NULL THEN
        RAISE EXCEPTION 'Kein Milestone gefunden.';
    END IF;

    -- Verletzung: setze den Milestone auf ein Item in einem anderen
    -- Workstream. Der Schema-Constraint erlaubt das (Milestone-Invariante
    -- lebt in Java, nicht in der DB).
    UPDATE worklist.item
       SET milestone_id = v_wrong_milestone_id
     WHERE id = v_item_id;

    -- V3 nach der Verletzung.
    SELECT count(*) INTO v_after
      FROM worklist.item i
      JOIN worklist.milestone m ON m.id = i.milestone_id
     WHERE i.tenant_id = :tenant_id AND i.scope_id = :scope_id
       AND i.workstream_id <> m.workstream_id;
    RAISE NOTICE 'V3 nach Verletzung (erwartet 1): %', v_after;

    IF v_after <> 1 THEN
        RAISE EXCEPTION 'Rote Probe hat den Bruch nicht gefunden. '
                        'V3 ist unwirksam. Erwartet 1, gemessen %.', v_after;
    END IF;
END $$;

ROLLBACK;
