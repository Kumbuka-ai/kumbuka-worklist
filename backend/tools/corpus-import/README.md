# Steuerungskorpus-Import (Sprint 177.5 → 177.8)

Ueberfuehrung des Bestands der Vorgaenger-Worklist in den Worklist-Dienst.
Ausschliesslich per SQL, kein Importeur ueber die Verbflaeche.

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
- `verify.sql` — fuenf Verifikationsabfragen (V1..V5). V1 ist seit
  177.8 geschaerft: keine OFFENE Zeile im Auffang (terminale sind dort
  erwartet). Die vormalige cross-workstream-milestone-Abfrage ist mit
  V12 entfallen.
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
