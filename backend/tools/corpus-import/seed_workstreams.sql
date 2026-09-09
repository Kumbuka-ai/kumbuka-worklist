-- seed_workstreams.sql
--
-- Straenge-Saat fuer den Import des Steuerungskorpus im Scope kumbuka.
--
-- REA-0007 Abschnitt 5: "The bodies of work are seeded rather than created.
-- A body of work comes into being by declaration and never through a verb."
-- Das Muster ist V8__workstream.sql (die default-Zeile beim Scope-Bootstrap):
-- direktes INSERT unter gesetztem app.tenant_id, mit Pflichtbeschreibung, plus
-- Meilenstein-Zaehlerzeile je Strang.
--
-- Voraussetzung: der Scope 'kumbuka' existiert bereits in worklist.scope_setting
-- mit einer tenant_id, die als psql-Variable :tenant_id gebunden ist. Ebenso ist
-- :scope_id die kumbuka-scope_id.
--
-- Aufruf:
--   psql -v ON_ERROR_STOP=on \
--        -v tenant_id="'<uuid>'" \
--        -v scope_id="'<uuid>'"  \
--        -f seed_workstreams.sql
--
-- Wird als Migrator-Rolle ausgefuehrt (non-superuser, FORCE RLS greift, deshalb
-- das SET vor jedem Block).

BEGIN;

-- RLS bindet auch den Migrator, weil FORCE ROW LEVEL SECURITY auf allen
-- Domaenentabellen steht. Ohne diesen SET landet jedes INSERT im Nichts.
SELECT set_config('app.tenant_id', :tenant_id, true);

-- Die vier neuen Straenge. 'Produktlinie' entfaellt hier: der existierende
-- default-Workstream wird spaeter (in der Import-Phase) durch ein UPDATE auf
-- token='produktlinie', is_default=false umbenannt, weil die bestehenden
-- Milestones seine sind (REA-0007 Abschnitt 4).
--
-- Namensregel des tokens: kleinbuchstaben, keine Umlaute (V8:59 ck_workstream_token).

INSERT INTO worklist.workstream
    (id, tenant_id, scope_id, number, token, description, status, is_default,
     conflict_token, created_at, updated_at)
VALUES
    (gen_random_uuid(), :tenant_id, :scope_id, 2, 'architektur',
     'Konzept- und Architekturarbeit vor der Umsetzung: Zielarchitekturen, '
     'Realisierungskonzepte, Anforderungen, Leitplanken, Entscheidungen. Eine '
     'Zeile gehoert hierher, wenn ihr Ergebnis ein Korpusknoten oder eine '
     'ratifizierte Entscheidung ist. Ziele sind Zustaende des Korpus, nicht '
     'des Produkts, und gelten unabhaengig davon ob Code folgt.',
     'active', false, gen_random_uuid(), now(), now()),

    (gen_random_uuid(), :tenant_id, :scope_id, 3, 'agenten-entwicklung',
     'Der Agenten-Apparat als Entwicklungsgegenstand, nicht die Nutzung von '
     'Agenten als Arbeitsmethode.',
     'active', false, gen_random_uuid(), now(), now()),

    (gen_random_uuid(), :tenant_id, :scope_id, 4, 'betrieb',
     'Host, Compose, Edge, Release-Kette, Backup und Restore, '
     'Deployment-Gates.',
     'active', false, gen_random_uuid(), now(), now()),

    (gen_random_uuid(), :tenant_id, :scope_id, 5, 'gtm',
     'Preisgestaltung, Vertragsbedingungen, Recht, Zahlungsdienstleister, '
     'Positionierung, marktseitiges Material.',
     'active', false, gen_random_uuid(), now(), now());

-- Umbenennung des default-Workstreams auf 'produktlinie'.
-- Vor dem UPDATE: number bleibt 1 (default hat number 1 aus V8:222), aber der
-- Token wechselt und is_default faellt weg. Der partielle Unique-Index
-- uq_workstream_default_per_scope (V8:118) verlangt genau EINEN Default pro
-- Scope, was hier gebrochen wuerde. Loesung: der Auftrag verlangt in seiner
-- Nachbedingung "Zahl der Items im default-Workstream ist 0", was den default
-- als leeren Auffang belaesst. Also KEINE Umbenennung: 'produktlinie' wird
-- ein ZUSAETZLICHER Workstream (number 6), und der default bleibt leer.
--
-- Damit ist es dann ein Sechs-Straenge-Modell mit default als leerem Auffang.

INSERT INTO worklist.workstream
    (id, tenant_id, scope_id, number, token, description, status, is_default,
     conflict_token, created_at, updated_at)
VALUES
    (gen_random_uuid(), :tenant_id, :scope_id, 6, 'produktlinie',
     'Der Bau des Produkts selbst: die Dienste, die Konsolen, die '
     'Konnektor-Oberflaeche, die Tenant-Isolation, die Enterprise-Module. Traegt '
     'auch die Dokumentation, die die Umsetzung erzeugt — Benutzerhilfe, '
     'Leitfaeden, Repository-Readmes — weil diese Dokumentation eine Ausgabe '
     'der Arbeit ist und nicht ihre Vorbedingung.',
     'active', false, gen_random_uuid(), now(), now());

-- Meilenstein-Zaehler je neuem Strang. V9:184 verlangt einen Zaehler-Eintrag
-- pro Workstream fuer den milestone-Selektor, sonst vergibt das naechste
-- create eine schon belegte Nummer. Fuer den bestehenden default steht der
-- Zaehler schon (V8:230-234).
--
-- Der Import setzt sie initial auf 0; die einzige Ausnahme ist 'produktlinie',
-- die die bestehenden Milestones erbt. Wir setzen produktlinie sofort auf die
-- hoechste existierende Milestone-Nummer (heute 7 fuer M7), damit ein
-- anschliessendes create den naechsten freien Wert liefert. Alle vier anderen
-- Straenge starten mit 0 leer.

INSERT INTO worklist.number_space
    (id, tenant_id, scope_id, selector_id, workstream_id, high_water_mark,
     created_at, updated_at)
SELECT gen_random_uuid(), :tenant_id, :scope_id,
       (SELECT id FROM worklist.selector
         WHERE tenant_id = :tenant_id AND scope_id = :scope_id
           AND token = 'milestone'),
       ws.id, 0, now(), now()
  FROM worklist.workstream ws
 WHERE ws.tenant_id = :tenant_id AND ws.scope_id = :scope_id
   AND ws.token IN ('architektur', 'agenten-entwicklung', 'betrieb', 'gtm');

-- Produktlinie erbt die bestehenden Milestones und braucht deshalb einen
-- Zaehler auf der hoechsten vergebenen Nummer. Der Import (nicht dieses
-- Skript) verschiebt existierende Milestones per UPDATE workstream_id
-- auf 'produktlinie'; der Zaehler folgt dieser Verschiebung.
INSERT INTO worklist.number_space
    (id, tenant_id, scope_id, selector_id, workstream_id, high_water_mark,
     created_at, updated_at)
SELECT gen_random_uuid(), :tenant_id, :scope_id,
       (SELECT id FROM worklist.selector
         WHERE tenant_id = :tenant_id AND scope_id = :scope_id
           AND token = 'milestone'),
       ws.id,
       COALESCE((SELECT MAX(number) FROM worklist.milestone
                  WHERE tenant_id = :tenant_id AND scope_id = :scope_id),
                0),
       now(), now()
  FROM worklist.workstream ws
 WHERE ws.tenant_id = :tenant_id AND ws.scope_id = :scope_id
   AND ws.token = 'produktlinie';

-- Zaehlerprobe: nach der Saat existieren fuer jeden nicht-default-Strang
-- genau eine milestone-number_space-Zeile. Fehlt eine, waere die naechste
-- Milestone-Nummer eine schon belegte.
DO $$
DECLARE
    expected int := 5;  -- produktlinie, architektur, agenten-entwicklung, betrieb, gtm
    actual int;
BEGIN
    PERFORM set_config('app.tenant_id', :tenant_id, true);
    SELECT count(*) INTO actual
      FROM worklist.number_space ns
      JOIN worklist.selector s ON s.id = ns.selector_id
     WHERE ns.tenant_id = :tenant_id AND ns.scope_id = :scope_id
       AND s.token = 'milestone'
       AND ns.workstream_id IN (
           SELECT id FROM worklist.workstream
            WHERE tenant_id = :tenant_id AND scope_id = :scope_id
              AND token IN ('produktlinie', 'architektur',
                            'agenten-entwicklung', 'betrieb', 'gtm')
       );
    IF actual <> expected THEN
        RAISE EXCEPTION 'milestone number_space seed drift: expected %, got %',
                        expected, actual;
    END IF;
END $$;

COMMIT;
