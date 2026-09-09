# Differenz zum Trockenlauf aus Sprint 177.5

Der Trockenlauf 177.5 lief gegen den Pin `004c79f2b2b9a87e2f66bcff8ec9570be18c8f15`
(268619 Bytes). Der Trockenlauf 177.6 laeuft gegen den Pin
`7b3c7302585dc80e9adc31ca25e432f5b429d90f` (271735 Bytes, der Kopf von
`Kumbuka-ai/steering:main` am 2026-09-09). Diese Datei stellt die Diffe
gegenueber.

## Quellzeilen

- 177.5: 505 Backlog-Zeilen.
- 177.6: 507 Backlog-Zeilen.
- **Differenz: +2 neu, 0 entfernt.**

Die zwei neuen Zeilen kommen aus den steering-Commits, die zwischen den
beiden Laeufen landeten (#509 Planungsschicht create+ratify+update, #510
Discovery-Schicht create+ratify+update):

| Nr | ID | Cluster | Milestone | Bucket | Strang | Titelspitze |
|---|---|---|---|---|---|---|
| 509 | CHORE-358 | CORE | M5 | assigned | Produktlinie | Planungsschicht der Worklist: Arbeitsstrang als vierte Adressklasse, Meilenstein... |
| 510 | BUG-53 | CONN | M5 | assigned | Produktlinie | Discovery-Schicht von log.jbaconsult.com reparieren... |

Beide sind CORE/CONN mit Milestone M5 — sie fallen sauber in Produktlinie
und tragen keine neue Refusal-Klasse in den Bestand.

## Ausgabemengen

Die 177.5-Rueckgabe nannte `351 + 16 + 138 = 505` und daneben "153 nicht
platzierbare Zeilen". Beides zugleich stimmt nicht: die 15
Milestone-Konflikte staken in den 351, wurden also assigned UND refused
gezaehlt. Das ist der Zaehlwiderspruch, den 177.6 mit disjunkten Mengen
und maschineller Selbstpruefung repariert.

Zaehlung in derselben (disjunkten) Systematik, damit die Werte
vergleichbar sind:

| Bucket | 177.5 (korrigiert) | 177.6 | Diff |
|---|---|---|---|
| assigned | 336 | 338 | +2 |
| deferred | 16 | 16 | 0 |
| refused | 153 | 153 | 0 |
| Summe | 505 | 507 | +2 |

Refused zerfaellt in 177.6 in zwei Grundgruppen (in 177.5 identisch):

| Grund | 177.5 | 177.6 | Diff |
|---|---|---|---|
| empty-cluster | 138 | 138 | 0 |
| milestone-workstream-conflict | 15 | 15 | 0 |

## Verteilung der assigned-Zeilen auf kumbuka-Straenge

Vergleichbar gemacht: die 177.5-Verteilung enthielt die 15
Milestone-Konflikte in ihren jeweiligen Straengen; hier steht daneben,
wie sie sich verteilen wuerden ohne die Refusal-Umbuchung.

| Strang | 177.5 (mit Konflikten) | 177.5 (ohne Konflikte) | 177.6 | Diff (ohne) |
|---|---|---|---|---|
| Produktlinie | 247 | 247 | 249 | +2 |
| Betrieb | 58 | 52 | 52 | 0 |
| Architektur | 31 | 22 | 22 | 0 |
| GTM | 14 | 14 | 14 | 0 |
| Agenten-Entwicklung | 1 | 1 | 1 | 0 |
| Summe | 351 | 336 | 338 | +2 |

Beide neuen Zeilen (509, 510) landen in Produktlinie — die Diff ist rein
additiv, keine Umbuchung.

## Milestone/Workstream-Konflikte, unveraendert

Die 15 Konflikte sind exakt dieselben Zeilen wie in 177.5. Verteilung
nach urspruenglicher Landestelle (die Regel-Zuweisung vor der
Milestone-Invariante):

- 6 aus Cluster DEPLOY, prev-workstream Betrieb: Nrn 433, 434, 459, 463,
  497, 504.
- 7 aus HK via `hk-heuristic:arch-keyword`, prev-workstream Architektur:
  Nrn 424, 426, 427, 438, 440, 442, 478.
- 2 aus CORE via `named-exception:architektur`, prev-workstream
  Architektur: Nrn 464, 487.

## Heuristische Zuordnungen

Die HK-Zeilen und ihre Klassifikation aendern sich nicht. Die Gesamtzahl
sinkt scheinbar (177.5: 110 → 177.6: 103), aber das ist eine Folge der
Umbuchung: 7 arch-keyword-Zeilen und 2 named-exception-Zeilen wurden von
assigned nach refused umgebucht (Milestone-Konflikt), damit werden sie in
der 177.6-Statistik nicht mehr als heuristisch mitgezaehlt (sie sind
refused, nicht heuristisch zugewiesen).

Zerlegung:

| Regel | 177.5 | 177.6 | Diff |
|---|---|---|---|
| hk-heuristic:default-hygiene | 70 | 70 | 0 |
| hk-heuristic:arch-keyword | 24 | 17 | -7 (nach refused) |
| hk-heuristic:jba-keyword | 16 | 16 | 0 |
| Summe heuristisch | 110 | 103 | -7 |
| named-exception:architektur | 7 | 5 | -2 (nach refused) |

Die zwei neuen Zeilen (509 CORE, 510 CONN) sind nicht heuristisch — sie
fallen unter `cluster:CORE` bzw. `cluster:CONN`.

## Nachbedingung Echtlauf

Unveraendert **nicht zulaessig**: die Refusal-Liste ist mit 153 Zeilen
nicht leer. Die drei offenen Klassen bleiben unentschieden und liegen bei
Concept:

- 138 empty-cluster-Zeilen (121 done, 13 new, 4 dissolved).
- 15 Milestone/Workstream-Konflikte.
- 103 HK-heuristische Zuordnungen (in `assignment.tsv` mit
  `heuristic=yes` markiert).
