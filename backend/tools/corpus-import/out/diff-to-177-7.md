# Differenz zum Trockenlauf aus Sprint 177.7

Beide Laeufe gegen den gleichen Steering-Pin
(`7b3c7302585dc80e9adc31ca25e432f5b429d90f`, WORKLIST.md 271735 Bytes).
Die Diffe kommen ausschliesslich aus der Regeländerung: `empty-cluster`
wird per REA-0007 §3 Unterabschnitt in zwei Fällen aufgelöst.

## Quellzeilen

Unveraendert **507**.

## Ausgabemengen

| Bucket | 177.7 | 177.8 | Diff |
|---|---|---|---|
| assigned | 353 | **488** | **+135** |
| deferred | 16 | **19** | **+3** |
| refused | 138 | **0** | **-138** |
| Summe | 507 | 507 | 0 |

Die 138 verweigerten Zeilen sind aufgelöst: 13 offene Zurufe wandern
per Titel-Prefix (10 in kumbuka, 3 nach jbaconsult), 125 terminale
Zeilen in den Auffang `default`.

## Verteilung der assigned-Zeilen auf kumbuka-Straenge

| Strang | 177.7 | 177.8 | Diff | Woher |
|---|---|---|---|---|
| Produktlinie | 249 | 252 | **+3** | 3 offene Zurufe (ee-Topologie, Log-Fehler, ee-Kopfkommentar) |
| default | 0 | **125** | **+125** | 121 done + 4 dissolved (terminale Zeilen ohne Cluster) |
| Betrieb | 58 | 60 | **+2** | 2 offene Zurufe (CI-Realm-Import, Redirect-URI-Wildcard) |
| Architektur | 31 | 36 | **+5** | 5 offene Zurufe (2 ADR-Themen, 2 Index-Duplikate, 1 Plattform-Datenschicht) |
| GTM | 14 | 14 | 0 | — |
| Agenten-Entwicklung | 1 | 1 | 0 | — |
| Summe | 353 | 488 | +135 | 10 Zurufe + 125 Terminale |

**Auffang `default`:** 125 insgesamt, **alle 125 terminal** (121 done,
4 dissolved), **0 offen**. Die geschaerfte §8-Nachbedingung ist grün:
keine offene Zeile im Auffang.

## Deferred-Zuwachs (jbaconsult)

Drei neue Zurueckstellungen aus der Zuruf-Tabelle:

- 367 · Skills liegen ausserhalb jeder Ground-Truth-Messung
- 366 · Vier Skills sind nach dem Umbau von Sprint 132 sachlich falsch
- 364 · code-handover-Skill nachschaerfen

Die vorherigen 16 (aus HK-Heuristik) sind unveraendert.

## Refusal-Bilanz

| Grund | 177.7 | 177.8 | Diff |
|---|---|---|---|
| empty-cluster | 138 | **0** | **-138** |
| Summe | 138 | 0 | -138 |

Die Refusal-Kategorie `empty-cluster` ist vollstaendig aufgeloest.
`dry_run.py` traegt sie nicht mehr; sie kann in keinem Lauf mehr
entstehen.

## Regel-Zweig-Zuwachs

Neu ab 177.8:

| Regel | Anzahl |
|---|---|
| terminal-to-catchall:done | 121 |
| terminal-to-catchall:dissolved | 4 |
| open-call-in:* (13 Prefixe, je 1 Match) | 13 |

Die 13 offenen Zurufe sind **nicht heuristisch** (assignment.tsv:
`heuristic=no`) — sie sind eine benannte Ausnahme wie die drei
bestehenden `named-exception:*`.

## Heuristische Zuordnungen

Unveraendert 110 (70 default-hygiene + 24 arch-keyword + 16 jba-keyword).
Die 13 Zurufe zaehlen NICHT als heuristisch.

## Nachbedingung Echtlauf

**Zulaessig.** Die Refusal-Liste ist leer. Das Vollstaendigkeitsgatter
(507 = 488 + 19 + 0) faellt jetzt gruen aus, und die geschaerfte §8-
Nachbedingung (keine offene Zeile im Auffang) faellt konstruktiv gruen
aus, weil die 125 Zeilen im Auffang alle terminal sind.

Was ein Concept vor Echtlauf noch ratifizieren mag, sind die 110
HK-heuristischen Zuordnungen — das sind aber Vorschlaege des Trockenlaufs
und keine Blocker.
