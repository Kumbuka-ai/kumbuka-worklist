# Trockenlauf-Bericht (Sprint 177.8)

steering commit: `7b3c7302585dc80e9adc31ca25e432f5b429d90f`

Quellzeilen im Backlog: **507**

## Die drei disjunkten Ausgabemengen

- **assigned** (kumbuka): 488
- **deferred** (jbaconsult): 19
- **refused**: 0

Summe: 507 — muss 507 sein.

Die Disjunktheit dieser drei Mengen und die Gleichheit der Summe zur Quellzeilenzahl werden **maschinell** von `self_check` gepruft und der Lauf bricht bei jeder Verletzung sofort ab. Diese Zeile im Bericht ist keine Zusicherung, sondern der Verweis auf die Pruefung.

## Verteilung der assigned-Zeilen auf kumbuka-Straenge

- Produktlinie: 252
- default: 125
- Betrieb: 60
- Architektur: 36
- GTM: 14
- Agenten-Entwicklung: 1

Auffang `default` insgesamt: 125 (davon 125 terminal, 0 offen). REA-0007 §8 (verschaerft): offene Zeilen im Auffang sind ein Fehler; terminale sind dort erwartet.

## Zuordnung/Verweigerung nach Regel-Zweig

- terminal-to-catchall:done: 121
- cluster:CORE: 111
- hk-heuristic:default-hygiene: 70
- cluster:DEPLOY: 58
- cluster:SEC: 41
- hk-heuristic:arch-keyword: 24
- hk-heuristic:jba-keyword: 16
- cluster:CONN: 14
- cluster:GTM: 14
- cluster:EE: 10
- named-exception:architektur: 7
- terminal-to-catchall:dissolved: 4
- cluster:BETA: 3
- open-call-in:ADR fuer den Verbprozess: 1
- open-call-in:Plattform-Datenschicht und der Schnitt Team gegen Mandant: 1
- open-call-in:ee-Topologie schneiden: 1
- open-call-in:ADR-0041 Schema-Menge gegen ADR-0043 in Deckung bringen: 1
- open-call-in:Fehler der Gedaechtnis-Maschine hinterlassen keine Spur im L: 1
- open-call-in:Falscher Kopfkommentar in ee-server: 1
- open-call-in:Generierter Index ueber docs/adr/ und docs/decisions/: 1
- open-call-in:Index ueber ADRs und Entscheidungs-Ledger mechanisch generie: 1
- named-exception:agenten: 1
- open-call-in:Skills liegen ausserhalb jeder Ground-Truth-Messung: 1
- open-call-in:Vier Skills sind nach dem Umbau von Sprint 132 sachlich fals: 1
- open-call-in:Kein CI-Job uebt die Realm-Import-Skip-Bedingung: 1
- open-call-in:code-handover-Skill nachschaerfen: 1
- open-call-in:Redirect-URI des wlm-mcp-Clients traegt einen Wildcard-Stern: 1

## Heuristische Zuordnungen: 110

Aus Titeltext/Ref abgeleitet, nicht mechanisch aus einer Spalte. Brauchen Concept-Ratifikation. Die 13 offenen Zurufe (REA-0007 §3 Unterabschnitt) sind **nicht** heuristisch — sie sind namentlich ratifiziert und im assignment.tsv mit `heuristic=no` markiert.

- hk-heuristic:default-hygiene: 70
- hk-heuristic:arch-keyword: 24
- hk-heuristic:jba-keyword: 16

## Refusal-Bilanz

Insgesamt refused: **0**

Die Refusal-Kategorie `empty-cluster` ist mit 177.8 aufgeloest (REA-0007 §3 Unterabschnitt). Offene Zurufe wandern per Titel-Prefix, terminale Zeilen in den Auffang.

## Nachbedingung Echtlauf

**Echtlauf zulaessig.** Refusal-Liste ist leer.

---

# Roter Probelauf der Selbstpruefung (Disjunktheit)

1. Kontrolllauf davor: **gruen** (drei Mengen disjunkt, Summe = Quellzeilen).
2. Verletzung eingebaut: Nr 510 zusaetzlich als deferred dupliziert (steht damit in assigned UND deferred).
3. Selbstpruefung: **rot** — Wortlaut: `assigned ∩ deferred nicht leer: ['510']`.
4. Kontrolllauf danach: **gruen** (Injection war lokal, produktive Zuweisung unverunreinigt).

**Ergebnis:** die Selbstpruefung ist rot beobachtet und wieder gruen.

---

# Roter Probelauf der geschaerften Auffang-Nachbedingung

1. Kontrolllauf davor: **gruen** (keine offenen Zeilen im Auffang 'default').
2. Verletzung eingebaut: Nr 510 (status='open') in den Auffang verschoben — damit eine offene Zeile im default.
3. Nachbedingung: **rot** — Wortlaut: `offene Zeilen im Auffang 'default': ['510']`.
4. Kontrolllauf danach: **gruen** (Injection war lokal).

**Ergebnis:** die geschaerfte Auffang-Nachbedingung ist rot beobachtet und wieder gruen. Sie ist damit ein Gate und keine Abschaltung.
