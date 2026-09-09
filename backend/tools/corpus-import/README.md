# Steuerungskorpus-Import (Sprint 177.5)

Ueberfuehrung des Bestands der Vorgaenger-Worklist in den Worklist-Dienst.
Ausschliesslich per SQL, kein Importeur ueber die Verbflaeche.

## Bauteile

- `snapshot.env` — pin des eingefrorenen steering-Stands (commit-SHA, byte
  count, sha256). Jeder Lauf validiert die Quelle dagegen und weigert sich
  auf Drift. Nicht ueberschreibbar.
- `dry_run.py` — Trockenlauf: liest die Quelle, wendet die REA-0007-Regel
  an, schreibt nichts. Ausgabe unter `out/`.
- `seed_workstreams.sql` — Straenge-Saat: legt die fuenf Straenge im
  Zielscope an und traegt die Meilenstein-Zaehler nach.
- `verify.sql` — sechs Verifikationsabfragen (V1..V6) fuer die
  Nachbedingungen.
- `red_probe.sql` — roter Probelauf fuer die Milestone-Invariante (V3),
  laeuft in einer Wegwerf-Transaktion und rollt zurueck.
- `out/` — Ausgabe des Trockenlaufs (assignment.tsv, deferred.txt,
  refusal.txt, report.md). Nicht in git, wird bei jedem Lauf ueberschrieben.

## Ablauf

1. **Trockenlauf.**
   ```
   python3 dry_run.py \
       --steering /path/to/steering \
       --snapshot ./snapshot.env \
       --out-dir  ./out
   ```
   Kein Datenbankzugriff. Liest `snapshot.env`, verifiziert die
   steering-Quelle gegen die drei pins (commit-SHA, byte count, sha256).
   Bei Drift: Abbruch mit Meldung, welches Feld drifted.

2. **Echtlauf** (nur bei leerer Refusal-Liste). Ist im gegenwaertigen
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

## Warum die Quelle gepinnt ist

Die Datei `WORKLIST.md` im steering-Repo wird laufend fortgeschrieben. Ein
Import gegen "die aktuelle Datei" ist nicht wiederholbar, und der Auftrag
(dispatch 177.5) verlangt Wiederholbarkeit ausdruecklich: der erste Lauf
wird scheitern und der zweite muss bis auf eine Variable identisch sein.
Der Pin friert die Quelle relativ zu diesem Import ein.

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

Der Trockenlauf gegen den gepinnten steering-Stand liefert
153 nicht-platzierbare Zeilen (aus 505 total):

- 138 leerer Cluster — die REA-0007-Regel ordnet primaer nach Cluster
  zu; ohne Cluster gibt es keinen Zweig. Verteilung: 121 done, 13 new
  (unratified call-ins), 4 dissolved.
- 15 Milestone/Workstream-Konflikte — Items, deren Cluster sie NICHT in
  den Produktlinie-Strang schickt, aber die einen echten Milestone
  (M1..M7) tragen. REA-0007 verortet Milestones aber ausschliesslich in
  Produktlinie. Der Bruch waere die in ItemService.java:844
  durchgesetzte Cross-Workstream-Milestone-Invariante.

Der Auftrag (dispatch 177.5, Abschnitt "Ablauf") verlangt Abbruch des
Echtlaufs, wenn die Refusal-Liste nicht leer ist. Der Trockenlauf
respektiert das: er benennt die 153 Zeilen und laesst den Echtlauf nicht
laufen.

Concept muss entscheiden:

- Ob die 138 empty-cluster-Zeilen einen Sonderzweig bekommen (call-in-
  Workstream, terminal-Auffang, oder etwas anderes) oder ob die REA-0007
  Regel erweitert wird.
- Ob die 15 Milestone-Konflikte den Milestone verlieren (Datenverlust),
  in Produktlinie umgeschrieben werden (Cluster-Regel ueberrumpelt), oder
  einzeln geklaert werden.

Ausserdem sind die HK-Zuordnungen (110 Zeilen) heuristisch: die
REA-0007-Regel spaltet HK in drei Richtungen semantisch, nicht
mechanisch. Der Trockenlauf schlaegt eine Klassifikation aus
Titeltext-Schluesselwoertern vor; jede dieser Zuordnungen braucht
Concept-Ratifikation.
