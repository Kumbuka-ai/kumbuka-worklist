# Differenz zum Trockenlauf aus Sprint 177.6

Beide Laeufe gehen gegen den gleichen Steering-Pin
(`7b3c7302585dc80e9adc31ca25e432f5b429d90f`, WORKLIST.md 271735 Bytes).
Die Diffe kommen ausschliesslich aus der Regeländerung: V12 rueckt die
Meilenstein-Workstream-Kante zurueck, und
`apply_milestone_invariant` in `dry_run.py` faellt weg.

## Quellzeilen

Unveraendert **507**. Keine neue oder entfernte Zeile im Backlog.

## Ausgabemengen

| Bucket | 177.6 | 177.7 | Diff |
|---|---|---|---|
| assigned | 338 | **353** | **+15** |
| deferred | 16 | 16 | 0 |
| refused | 153 | **138** | **-15** |
| Summe | 507 | 507 | 0 |

Die 15 Zeilen sind exakt die Milestone/Workstream-Konflikte, die 177.6
als refused fuehrte. In 177.7 stehen sie in ihrem urspruenglichen
Cluster-Strang: die Milestone-Invariante gibt es nicht mehr, also gibt
es diese Refusal-Kategorie nicht mehr.

## Verteilung der assigned-Zeilen auf kumbuka-Straenge

| Strang | 177.6 | 177.7 | Diff |
|---|---|---|---|
| Produktlinie | 249 | 249 | 0 |
| Betrieb | 52 | 58 | **+6** |
| Architektur | 22 | 31 | **+9** |
| GTM | 14 | 14 | 0 |
| Agenten-Entwicklung | 1 | 1 | 0 |
| Summe | 338 | 353 | +15 |

Die +6 Betrieb sind die DEPLOY-Zeilen mit echtem Milestone (433, 434,
459, 463, 497, 504). Die +9 Architektur sind sieben HK-Zeilen mit
`arch-keyword`-Heuristik (424, 426, 427, 438, 440, 442, 478) und zwei
CORE-Zeilen mit `named-exception:architektur` (464, 487) — jeweils mit
Milestone.

## Refusal-Bilanz

| Grund | 177.6 | 177.7 | Diff |
|---|---|---|---|
| empty-cluster | 138 | 138 | 0 |
| milestone-workstream-conflict | 15 | **entfaellt** | -15 |

## Heuristische Zuordnungen

Die HK-Klassifikation aendert sich nicht. Die Zahl waechst leicht, weil
die 9 heuristischen Zeilen (7 arch-keyword + 2 named-exception, s.o.),
die 177.6 als Milestone-Konflikte umgebucht hatte, in 177.7 als
heuristische Zuweisungen zaehlen.

| Regel | 177.6 | 177.7 | Diff |
|---|---|---|---|
| hk-heuristic:default-hygiene | 70 | 70 | 0 |
| hk-heuristic:arch-keyword | 17 | 24 | **+7** |
| hk-heuristic:jba-keyword | 16 | 16 | 0 |
| named-exception:architektur | 5 | 7 | **+2** |
| Heuristik-Summe | 103 | 110 | +7 |

## Nachbedingung Echtlauf

Weiterhin **nicht zulaessig**. 138 Zeilen mit leerem Cluster stehen der
Refusal-Liste voran — die REA-0007-Regel hat fuer sie keinen Zweig, und
Concept muss entscheiden, wohin sie wandern.

Die zwei anderen Klassen aus 177.6 (Milestone-Konflikte, HK-Heuristik)
sind entweder aufgeloest (Konflikte) oder unveraendert (Heuristik
braucht noch Ratifikation).
