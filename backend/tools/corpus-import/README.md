# Steuerungskorpus-Import (Sprint 177.5 + 177.6 + 177.7)

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
- `verify.sql` — fuenf Verifikationsabfragen (V1..V5) fuer die
  Nachbedingungen. Die vormalige V3 (cross-workstream milestones) ist
  mit V12 entfallen — die Invariante existiert nicht mehr.
- `out/` — Ausgabe des Trockenlaufs (assignment.tsv, deferred.txt,
  refusal.txt, report.md, diff-to-177-5.md, diff-to-177-6.md). In git
  eingecheckt zum Nachlesen; wird bei jedem Lauf ueberschrieben.

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

## Warum der Echtlauf jetzt nicht laeuft

Der Trockenlauf gegen den aktuellen Pin liefert 138 refused Zeilen (aus
507 total), alle mit leerem Cluster (121 done, 13 new als unratifizierte
Zurufe, 4 dissolved). Die REA-0007-Regel ordnet primaer nach Cluster
zu; ohne Cluster gibt es keinen Zweig.

Der Auftrag (dispatch 177.5, Abschnitt "Ablauf") verlangt Abbruch des
Echtlaufs bei nicht leerer Refusal-Liste. Der Trockenlauf respektiert
das: er benennt die 138 Zeilen und laesst den Echtlauf nicht laufen.

Concept muss entscheiden, ob die 138 empty-cluster-Zeilen einen
Sonderzweig bekommen (call-in-Workstream, terminal-Auffang, oder etwas
anderes) oder ob die REA-0007-Regel erweitert wird.

Die vormaligen 15 Milestone/Workstream-Konflikte sind mit V12 (Sprint
177.7) entfallen: die Kante zwischen Meilenstein und Arbeitsstrang wurde
zurueckgebaut (TAR-0002 §4, REQ-0148 obsolete), und diese 15 Zeilen
stehen jetzt in ihrem urspruenglichen Cluster-Strang.

Ausserdem sind 103 HK-Zuordnungen heuristisch: die REA-0007-Regel
spaltet HK in drei Richtungen semantisch, nicht mechanisch. Der
Trockenlauf schlaegt eine Klassifikation aus Titeltext-Schluesselwoertern
vor; jede dieser Zuordnungen ist im `assignment.tsv` mit `heuristic=yes`
markiert und braucht Concept-Ratifikation.

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
