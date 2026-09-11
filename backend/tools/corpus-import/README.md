# Steuerungskorpus-Import (Sprint 177.5 → 178.2)

Ueberfuehrung des Bestands der Vorgaenger-Worklist in den Worklist-Dienst.
Ausschliesslich per SQL, kein Importeur ueber die Verbflaeche. Aktueller
Zustand (178.2): der Echtlauf ist entsperrt; der Betreiber-Aufruf steht
unter "Was 178.2 gegenueber 178.1 geaendert hat".

## Bauteile

- `snapshot.env` — pin des eingefrorenen steering-Stands (commit-SHA, byte
  count, sha256). Jeder Lauf validiert die Quelle dagegen und weigert sich
  auf Drift. Nicht ueberschreibbar. Aktueller Pin: `7b3c7302...` = Kopf
  von `Kumbuka-ai/steering:main` am 2026-09-09.
- `dry_run.py` — Trockenlauf: liest die Quelle per `git show <SHA>:...`
  aus dem steering-Objektspeicher (der Checkout-HEAD ist egal), wendet
  die REA-0007-Regel an, prueft die drei Ausgabemengen maschinell auf
  Disjunktheit und Vollstaendigkeit, schreibt nichts an die Datenbank.
  Ausgabe unter `out/`.
- `seed_workstreams.sql` — Straenge-Saat: legt die fuenf Straenge im
  Zielscope an. Der Meilenstein-Zaehler ist seit V12 (177.7) wieder
  scope-weit; die Saat hebt die Zaehler-Zeile per UPDATE auf die
  hoechste vergebene Milestone-Nummer.
- `verify.sql` — Waechter des Ist-Bestands, seit 178.2 mit V0..V9
  (V0 Identitaet vor Inhalt, V1a/b Verteilung/Auffang, V2 Dichte 1..N,
  V3 Zaehler, V4 ID-Referenzen, V5 gekappte Titel, V6 Kantenzahl, V7
  keine nackte Nr im Titel, V8 Straenge, V9 Prosa-Regel). Jede Pruefung
  RAISE'd bei Verletzung; V0 rot verhindert jede weitere Pruefung.
- `reset-scope.sql` — leert einen Scope aus V4..V13-Tabellen in FK-
  safer Reihenfolge; Selbstbestaetigung am Ende.
- `verify_import.sh` — Mechanismus-Probe: Import + verify.sql, Reset +
  Rerun, und die roten Proben R1..R6 (R6 seit 178.2: Prosa als
  Referenz -> V9 rot oder btree-Deckel).
- `out/` — Ausgabe des Trockenlaufs (assignment.tsv, deferred.txt,
  report.md, diff-to-177-5.md, diff-to-177-6.md, diff-to-177-7.md). In
  git eingecheckt zum Nachlesen; wird bei jedem Lauf ueberschrieben.
  Seit 177.8 gibt es keine `refusal.txt` mehr — die Refusal-Kategorie
  `empty-cluster` ist aufgeloest.

## Ablauf

1. **Trockenlauf.**
   ```
   python3 dry_run.py \
       --steering /path/to/steering \
       --snapshot ./snapshot.env \
       --out-dir  ./out
   ```
   Kein Datenbankzugriff. Verifiziert die Quelle gegen den Pin (commit
   erreichbar, byte count und sha256 exakt) und prueft nach dem
   Zuordnen mechanisch, dass die drei Ausgabemengen (assigned/deferred/
   refused) disjunkt sind und ihre Summe die Quellzeilenzahl trifft. Bei
   Verletzung: Abbruch mit Wortlaut der Verletzung.

2. **Roter Probelauf der Selbstpruefung.**
   ```
   python3 dry_run.py \
       --steering /path/to/steering \
       --snapshot ./snapshot.env \
       --out-dir  ./out \
       --red-probe
   ```
   Faehrt Kontrolllauf gruen, baut eine absichtliche Doppelzuordnung
   ein, misst die Pruefung rot mit Wortlaut, Kontrolllauf gruen danach.
   Beweist, dass die Selbstpruefung ein Gate ist (rot gesehen) statt
   nur ein grundgruener Textbaustein.

3. **Echtlauf** (nur bei leerer Refusal-Liste). Ist im gegenwaertigen
   Stand nicht ausfuehrbar; siehe unten.
   ```
   psql -v tenant_id="'<uuid>'" -v scope_id="'<uuid>'" \
        -f seed_workstreams.sql
   # <Insert-Skript fuer Items/Milestones — noch nicht gebaut, blockiert>
   psql -v tenant_id="'<uuid>'" -v scope_id="'<uuid>'" \
        -f verify.sql
   ```

## Warum die Quelle gepinnt ist, und aus dem Objektspeicher gelesen

Die Datei `WORKLIST.md` im steering-Repo wird laufend fortgeschrieben. Ein
Import gegen "die aktuelle Datei" ist nicht wiederholbar; der Auftrag
verlangt Wiederholbarkeit ausdruecklich, weil der erste Lauf scheitern
wird und der zweite bis auf eine Variable identisch sein muss.

Das Lesen per `git show <SHA>:WORKLIST.md` entkoppelt den Import vom
Zustand des steering-Checkouts: kein `git checkout` faellt an, der HEAD
kann irgendwo stehen. Der einzige Vorbedingungs-Fehler ist, dass der
gepinnte Commit im Objektspeicher fehlt — dann schlaegt das Skript vor,
`git fetch origin main` zu fahren, und bricht ab.

## RLS und der Migrator

Alle Domaenentabellen tragen `FORCE ROW LEVEL SECURITY` (V3). Das bindet
auch den Migrator. Jedes DML im Import laeuft unter einem gesetzten
`app.tenant_id`, sonst schreibt es nichts oder das Falsche — beides ohne
Fehlermeldung.

Muster (identisch in V8:159, V9:68, V9:151):
```sql
SELECT set_config('app.tenant_id', :tenant_id, true);
INSERT INTO worklist.workstream (...) VALUES (...);
```

## Zustand nach 177.8 — Echtlauf zulaessig

Der Trockenlauf gegen den aktuellen Pin liefert **0 refused Zeilen**
(507 total): 488 assigned, 19 deferred (jbaconsult), 0 refused. Die
`empty-cluster`-Kategorie ist mit 177.8 aufgeloest (REA-0007 §3
Unterabschnitt): 13 offene Zurufe wandern per Titel-Prefix (10 in
`kumbuka`, 3 nach `jbaconsult`), 125 terminale Zeilen (121 done, 4
dissolved) landen im Auffang `default`.

**Damit ist der Echtlauf entsperrt.** Was Concept vor dem Echtlauf noch
ratifizieren mag, sind die 110 HK-heuristischen Zuordnungen — im
`assignment.tsv` mit `heuristic=yes` markiert. Sie sind Vorschlaege des
Trockenlaufs, keine Blocker.

Die geschaerfte §8-Nachbedingung ("keine offene Zeile im Auffang") faellt
konstruktiv gruen: alle 125 Zeilen im Auffang sind terminal. Der rote
Probelauf `--red-probe` beweist, dass die Nachbedingung eine offene
Zeile im Auffang tatsaechlich findet — die Schaerfung ist von einer
Abschaltung unterscheidbar.

## Was 177.6 gegenueber 177.5 geaendert hat

Details unter `out/diff-to-177-5.md`. Kurzfassung:

1. **Neuer Pin.** `snapshot.env` zeigt jetzt auf den Kopf von
   `steering:main` (`7b3c7302...`, 271735 Bytes) statt auf den
   veralteten `004c79f2...` (268619 Bytes). Zwei neue Backlog-Zeilen
   (509 CHORE-358, 510 BUG-53) sind dadurch im Bestand; beide fallen in
   Produktlinie und aendern keine Refusal-Klasse.
2. **Disjunkte Mengen.** Die 15 Milestone-Konflikte waren in 177.5
   sowohl als assigned als auch als refused gezaehlt. In 177.6 sind sie
   ausschliesslich refused; assigned bleibt sauber.
3. **Maschinelle Selbstpruefung.** `self_check` prueft nach jedem Lauf
   Disjunktheit paarweise und Gleichheit der Summe zur Quellzeilenzahl.
   Verletzung bricht den Lauf sofort ab, ohne Ausgabe zu schreiben. Der
   rote Probelauf (`--red-probe`) beweist, dass die Pruefung Verletzungen
   findet, bevor sie leise mitlaufen.
4. **Lesen per `git show`.** dry_run.py greift die Quelle direkt aus
   dem steering-Objektspeicher ab; der Checkout-HEAD ist egal geworden.

## Was 177.7 gegenueber 177.6 geaendert hat

Details unter `out/diff-to-177-6.md`. Kurzfassung:

1. **V12 rueckt die Meilenstein-Workstream-Kante zurueck.** TAR-0002 §4
   und REQ-0148 (obsolete) ordnen: keine der drei Achsen um das Item
   traegt eine Kante zu einer anderen. Der Meilenstein-Zaehler ist
   wieder scope-weit; die per-workstream Uniqueness auf `milestone`
   weicht der wiederhergestellten scope-weiten. `milestone.workstream_id`
   und `number_space.workstream_id` bleiben stehen mit
   COMMENT-Verfallshinweis.
2. **Die Java-Invariante `refuseCrossWorkstreamMilestone` faellt.**
   ItemService und MilestoneService lassen Cross-Workstream-Zuweisungen
   jetzt zu; WorkstreamService legt beim Anlegen eines Workstreams keine
   per-workstream Milestone-Zaehler-Zeile mehr an; MilestoneService
   allokiert Nummern scope-weit.
3. **Umkehr-Test in beiden Zustaenden gefahren.**
   `MilestoneWorkstreamDecouplingIT` beweist, dass eine
   Cross-Workstream-Milestone-Zuweisung, die vor dem Rueckbau mit
   `WORKSTREAM_MILESTONE_MISMATCH` refused wurde, jetzt passiert.
4. **Bauplatz nachgezogen.** `seed_workstreams.sql` legt keine
   per-Strang Milestone-Zaehlerzeilen mehr an. `verify.sql` hat fuenf
   Nachbedingungen statt sechs. `red_probe.sql` faellt ersatzlos, weil
   die Invariante, die er probte, nicht mehr existiert. `dry_run.py`
   entfernt `apply_milestone_invariant`, und die
   `milestone-workstream-conflict`-Refusal-Kategorie verschwindet.

## Was 178.2 gegenueber 178.1 geaendert hat — Import laeuft durch

Konzept-Entscheidung vom 2026-09-10 (Sprint 178): **Prosa geht nach
`item.description`, Zeiger bleiben in `worklist.item_reference`.** Ein
Ref-Segment mit einer UTF-8-Laenge > 2000 Byte ist Prosa; die Zahl ist
eine runde Zahl mit Abstand zum btree-v4-Deckel des Ziel-Index, keine
Klassifikation nach Inhalt. Damit ist die Stoppbedingung aus 178.1
aufgeloest, und der Echtlauf ist zur Betreiber-Anwendung entsperrt.

Aenderungen dieses Sprints:

1. **Prosa-Regel im Generator** (`generate_import.py`)
   - `build_reference_targets(row)` liefert jetzt zwei Listen:
     `references` (die kurzen Zeiger) und `prose_segments` (jedes Ref-
     Segment > 2000 Byte). Nur `references` gehen in `item_reference`;
     die ID sitzt weiterhin auf `ordinal 0`, `component: <Scope>` bleibt
     am Ende, und die Ordinals sind lueckenlos.
   - `build_description(...)` baut `item.description` nach der 178.2-
     Fall-Tabelle: (Titel gekappt, keine Prosa) → voller Originaltitel;
     (keine Kappung, Prosa) → Prosa-Segmente durch Leerzeile getrennt;
     (Titel gekappt und Prosa) → Titel, Leerzeile, `---`, Leerzeile,
     dann Prosa; sonst `NULL`.
   - `_assert_reference_targets_fit` prueft jetzt die *tatsaechlich* als
     Referenz vorgesehenen Targets — nach der Regel muss er still bleiben.
     Feuert er trotzdem, ist die Regel oder ihre Anwendung defekt, und
     die Rueckgabe nennt es.
   - Header und stderr nennen zwei neue Zahlen: `expected_prose_rows`
     (Items mit Prosa in description) und `expected_reference_entries`
     (Gesamtzahl der `item_reference`-Zeilen).
2. **V9 in `verify.sql`**
   - (a) `count(item_reference) = expected_reference_entries`,
   - (b) `octet_length(target) > 2000` ist nirgends erfuellt (kein
     Ref-Eintrag ist laenger als die Prosa-Grenze),
   - (c) `octet_length(description) > 2000` gleich `expected_prose_rows`.
     `octet_length` als Kriterium ist gewaehlt, weil die drei
     Nicht-Prosa-Faelle (NULL, gekappter Titel = Originaltitel <= 436
     Byte, leer) alle unter 2000 Byte liegen — die Grenze ist damit
     unabhaengig vom Trenner `---` und vom Titelvergleich.
3. **R1 neu geschnitten in `verify_import.sh`**
   - Statt Duplikat: das erste Item bekommt Nummer N+5. Das erzeugt
     eine Luecke ohne Duplikat; der Import laeuft gruen, V2 wird mit
     eigenem Wortlaut rot.
4. **R6 neu in `verify_import.sh`**
   - Zusaetzlicher `item_reference`-INSERT mit ordinal 99 und einem
     2500-Byte-target; V9 muss rot werden, oder — wenn der btree-Deckel
     zuschlaegt — der Import selbst mit `index row size ... exceeds
     btree version 4`. Die Rueckgabe nennt den beobachteten Fall.
5. **R5 auf `attribute_definition` umgestellt**
   - Die 178.1-Variante (scope_setting nicht loeschen) bricht den
     zweiten Bootstrap nicht: bootstrap-scope.sql schreibt
     scope_setting nicht direkt. 178.2 nimmt den Marker im
     `attribute_definition`: bleibt er stehen, refused der zweite
     Import typisiert mit "corpus-import already applied".
6. **Aufrufblock als Shell-Variablen** in README und Harness identisch
   (siehe **Betreiber-Aufruf** unten).

**Betreiber-Aufruf** — die verify_import.sh im Harness benutzt exakt
diese Aufrufform ($MG steht dort fuer `docker exec ... psql ...` gegen
den ephemeren Test-Container; im Wirt-Aufruf ist $MG einfach `psql`).

```bash
# Deklarierte Werte oben, keine Platzhalter inline.
TENANT_ID="<uuid>"
SCOPE_ID="<uuid>"
EXPECTED_DIGEST="<sha256>"
EXPECTED_ROWS="<N>"
EXPECTED_ID_REFS="<N>"
EXPECTED_TRUNC="<N>"
EXPECTED_EDGES="<N>"
EXPECTED_PROSE="<N>"
EXPECTED_REFS="<N>"
BOOTSTRAP=/path/to/src/main/resources/db/bootstrap/bootstrap-scope.sql
IMPORT=./out/import.sql

# 1) Bootstrap — set_config vor dem Skript, per Pipe (das Skript traegt
#    kein eigenes BEGIN/COMMIT-Wrapper drumherum).
{ echo "SELECT set_config('app.tenant_id', '$TENANT_ID', false);"; cat "$BOOTSTRAP"; } | \
    psql -v ON_ERROR_STOP=on \
         -v tenant_id="$TENANT_ID" \
         -v scope_id="$SCOPE_ID"

# 2) Import — die Datei traegt ihr eigenes BEGIN/COMMIT.
psql -v ON_ERROR_STOP=on \
     -v tenant_id="$TENANT_ID" \
     -v scope_id="$SCOPE_ID" \
     -f "$IMPORT"

# 3) Verify.
psql -v ON_ERROR_STOP=on \
     -v tenant_id="$TENANT_ID" \
     -v scope_id="$SCOPE_ID" \
     -v expected_digest="$EXPECTED_DIGEST" \
     -v expected_rows="$EXPECTED_ROWS" \
     -v expected_id_refs="$EXPECTED_ID_REFS" \
     -v expected_truncated_titles="$EXPECTED_TRUNC" \
     -v expected_edges="$EXPECTED_EDGES" \
     -v expected_prose_rows="$EXPECTED_PROSE" \
     -v expected_reference_entries="$EXPECTED_REFS" \
     -f verify.sql

# 4) Reset (fuer Neuversuch).
psql -v ON_ERROR_STOP=on \
     -v tenant_id="$TENANT_ID" \
     -v scope_id="$SCOPE_ID" \
     -f reset-scope.sql
```

**Schemastand-Probe vor dem Anwenden** (erwartet 13):

```sql
SELECT max(installed_rank) AS latest_rank, max(version) AS latest_version
  FROM worklist.flyway_schema_history
 WHERE success = true;
```

Der `\set tenant_id '''<uuid>'''`-Weg aus 178.1 ist verworfen: `\set`
mit Anfuehrungszeichen im Wert kombiniert mit `:'tenant_id'` quotet ein
zweites Mal und der UUID-Cast scheitert.

## Was 178.1 gegenueber 177.10 geaendert hat

Ratifiziert im Sprint 178: die alte Nr des Predecessors ist Sediment aus
Markdown und verschwindet vollstaendig aus dem Ziel. Die Items werden dicht
neunummeriert (1..N in aufsteigender alter-Nr-Reihenfolge); die sprechende
ID bleibt als Referenz auffindbar; die Ref-Spalte wandert in die V4-Tabelle
`worklist.item_reference`. Zusaetzlich wird `dropped` terminal, `verify.sql`
wird ein lauffaehiger Waechter, und `reset-scope.sql` haengt sich als
Neuversuchs-Werkzeug an das Regal.

**Stoppbedingung damals** (2026-09-10, dann durch 178.2 aufgeloest):
Aenderung 3 (Ref-Segmente in `item_reference`) traf auf den btree-v4-
Deckel am Index `idx_item_reference_target`: 13 der 466 vorgesehenen
Ref-Segmente am gepinnten Bestand sind laenger als 2704 Byte. Die
Konzept-Seite hat 178.2 mit der Prosa-Regel geoeffnet (> 2000 Byte in
Ref → nach `item.description`).

Aenderungen dieses Sprints:

1. **Generator** (`generate_import.py`)
   - `emit_body` sortiert die zugewiesenen Zeilen nach der alten Nr und
     vergibt 1..N. Eine interne Zuordnung `nr → new_number` treibt Item-
     Insert-Nummer und die Umschreibung der Deps-Kanten.
   - `truncate_title_at_word_boundary` kappt Titel > 200 Zeichen an der
     letzten Wortgrenze; das angehaengte `…` haelt `char_length` auf
     genau 200. `description` traegt in diesem Fall den vollen
     Original-Titel; sonst ist sie NULL.
   - `build_reference_targets` bildet je Zeile die geordnete Referenz-
     Liste (ID / Ref-Segmente / `component: <Scope>`) und ist die
     Grundlage der `item_reference`-Emission.
   - Vor der Emission: `_assert_reference_targets_fit` misst alle
     Targets gegen den btree-v4-Deckel. Ein Vorfall > 2704 Byte bricht
     laut ab; die Ausgabe nennt Nr, Ident und Byte-Groesse jeder
     betroffenen Zeile.
   - Der Header nennt die erwarteten Zahlen (`expected_rows`,
     `expected_id_refs`, `expected_truncated_titles`, `expected_edges`)
     als Kommentarzeile; `verify.sql` liest sie als psql-Variablen.
2. **Trockenlauf** (`dry_run.py`)
   - `TERMINAL_STATUSES` bekommt `dropped` dazu. Am Pin wandern drei
     `dropped`-Zeilen in den Auffang; die Verteilung und der Digest
     aendern sich, das ist gewollt.
3. **Wachter** (`verify.sql`)
   - Vollstaendig neu geschrieben gegen das V4-Schema (`item_status.name`
     statt `status.token`), Aufrufkonvention wie die Importdatei
     (`-v tenant_id=<uuid> -v scope_id=<uuid> -v expected_digest=<sha>` +
     erwartete Zahlen), transaktionale GUCs (`kw_verify.*`) statt
     `:'tenant_id'` in DO-Bloecken, terminale Liste `('done','dissolved',
     'dropped')`.
   - V0 Identitaet vor Inhalt (Marker existiert, sha/pin/rows treffen).
   - V1a/V1b Verteilung/Auffang; V2 dichte 1..N; V3 Zaehler=N; V4 Anzahl
     Items mit ID als erstem Referenz-Eintrag; V5 gekappte Titel mit
     description; V6 Kantenzahl; V7 kein Titel ist eine nackte Nr; V8
     Straenge-Saat.
4. **Reset** (`reset-scope.sql`, neu)
   - Loescht in einer Transaktion alles scope-gebundene aus V4 bis V13
     (item_relation, item_reference, iteration_membership, claim,
     view_preference, attribute_option, scope_setting, item, milestone,
     iteration, number_space, attribute_definition, workstream,
     selector, item_status, relation_type). Selbstbestaetigung am Ende
     (Scope leer). Laeuft unter Migrator-Rolle, bindet `app.tenant_id`
     selbst; scheitert ein DELETE am Privileg (ADR-0005), bricht die
     Transaktion laut ab und nennt die Luecke — kein GRANT wird ergaenzt.
5. **Mechanik** (`verify_import.sh`)
   - PROBE 1 Import + `verify.sql` gruen; PROBE 2 Idempotenz; PROBE 3
     Selbstidentifikation; PROBE 4 Transaktionsgrenze; PROBE 5 Reset +
     Bootstrap+Import erneut + `verify.sql` gruen; PROBE R1..R5 rot mit
     Wortlaut (Dichte / ID-Refs / Titel-Deckel / V0-Marker /
     Reset-Luecke).

Die Betreiber-Aufrufblocks aus 178.1 waren defekt (`\set tenant_id
'''<uuid>'''` quotet ein zweites Mal; Bootstrap-Block hatte ein
zusaetzliches `BEGIN`). 178.2 ersetzt sie durch die Shell-Variablen-
Form oben im 178.2-Abschnitt.

## Was 177.8 gegenueber 177.7 geaendert hat

Details unter `out/diff-to-177-7.md`. Kurzfassung:

1. **Die letzte Refusal-Kategorie `empty-cluster` ist aufgeloest.**
   REA-0007 §3 traegt einen neuen Unterabschnitt "Rows the cluster
   cannot place" — zwei Faelle, unterschieden am Status:
   - 13 **offene Zurufe** (status=new) wandern per Titel-Prefix. Zehn
     in kumbuka-Straenge (5 Architektur, 3 Produktlinie, 2 Betrieb),
     drei nach jbaconsult (Skills/handover). Als benannte Ausnahmen
     `heuristic=no`.
   - 125 **terminale Zeilen** (121 done, 4 dissolved) wandern in den
     Auffang `default`. Der Grund ist ratifiziert: der Arbeitsstrang
     ist eine Planungsachse, und fuer eine Zeile die niemand mehr
     plant, ist die Frage nicht offen sondern gegenstandslos.
2. **REA-0007 §8 verschaerft** in `dry_run.py` und `verify.sql`: nicht
   mehr "default leer", sondern "keine offene Zeile im default".
   Terminale sind dort erwartet.
3. **Roter Probelauf der Schaerfung** in `--red-probe`: eine offene
   Zeile wird kuenstlich in den Auffang verschoben, das Gatter findet
   sie mit Wortlaut. Ohne diesen Beleg waere die Schaerfung von einer
   Abschaltung nicht zu unterscheiden.
4. **Trockenlauf entsperrt Echtlauf.** 507 = 488 assigned + 19 deferred
   + 0 refused. Auffang: 125 terminal, 0 offen. Selbstpruefung gruen,
   beide roten Probeläufe gruen-rot-gruen.
