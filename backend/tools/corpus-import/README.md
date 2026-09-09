# Steuerungskorpus-Import (Sprint 177.5 + 177.6)

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
  Zielscope an und traegt die Meilenstein-Zaehler nach.
- `verify.sql` — sechs Verifikationsabfragen (V1..V6) fuer die
  Nachbedingungen.
- `red_probe.sql` — roter Probelauf fuer die Milestone-Invariante (V3),
  laeuft in einer Wegwerf-Transaktion und rollt zurueck.
- `out/` — Ausgabe des Trockenlaufs (assignment.tsv, deferred.txt,
  refusal.txt, report.md, diff-to-177-5.md). In git eingecheckt zum
  Nachlesen; wird bei jedem Lauf ueberschrieben.

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
   psql -v tenant_id="'<uuid>'" -v scope_id="'<uuid>'" \
        -f red_probe.sql
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

Der Trockenlauf gegen den aktuellen Pin liefert 153 refused Zeilen (aus
507 total):

- **138 leerer Cluster** — die REA-0007-Regel ordnet primaer nach
  Cluster zu; ohne Cluster gibt es keinen Zweig. Verteilung: 121 done,
  13 new (unratifizierte Zurufe), 4 dissolved.
- **15 Milestone/Workstream-Konflikte** — Items, deren Cluster sie NICHT
  in den Produktlinie-Strang schickt, aber die einen echten Milestone
  (M1..M7) tragen. REA-0007 verortet Milestones ausschliesslich in
  Produktlinie. Der SQL-Import wuerde die in ItemService.java:844
  durchgesetzte Cross-Workstream-Milestone-Invariante brechen.

Der Auftrag (dispatch 177.5, Abschnitt "Ablauf") verlangt Abbruch des
Echtlaufs bei nicht leerer Refusal-Liste. Der Trockenlauf respektiert
das: er benennt die 153 Zeilen und laesst den Echtlauf nicht laufen.

Concept muss entscheiden:

- Ob die 138 empty-cluster-Zeilen einen Sonderzweig bekommen (call-in-
  Workstream, terminal-Auffang, oder etwas anderes) oder ob die
  REA-0007-Regel erweitert wird.
- Ob die 15 Milestone-Konflikte den Milestone verlieren (Datenverlust),
  in Produktlinie umgeschrieben werden (Cluster-Regel ueberrumpelt),
  oder einzeln geklaert werden.

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
