# Trockenlauf-Bericht (Sprint 177.6)

steering commit: `7b3c7302585dc80e9adc31ca25e432f5b429d90f`

Quellzeilen im Backlog: **507**

## Die drei disjunkten Ausgabemengen

- **assigned** (kumbuka): 338
- **deferred** (jbaconsult): 16
- **refused**: 153

Summe: 507 — muss 507 sein.

Die Disjunktheit dieser drei Mengen und die Gleichheit der Summe zur Quellzeilenzahl werden **maschinell** von `self_check` gepruft und der Lauf bricht bei jeder Verletzung sofort ab. Diese Zeile im Bericht ist keine Zusicherung, sondern der Verweis auf die Pruefung.

## Verteilung der assigned-Zeilen auf kumbuka-Straenge

- Produktlinie: 249
- Betrieb: 52
- Architektur: 22
- GTM: 14
- Agenten-Entwicklung: 1

default: 0 (Nachbedingung; die Straenge-Saat legt keine Items an, deshalb konstruktiv).

## Zuordnung/Verweigerung nach Regel-Zweig

- empty-cluster: 138
- cluster:CORE: 111
- hk-heuristic:default-hygiene: 70
- cluster:DEPLOY: 52
- cluster:SEC: 41
- hk-heuristic:arch-keyword: 17
- hk-heuristic:jba-keyword: 16
- milestone-workstream-conflict: 15
- cluster:CONN: 14
- cluster:GTM: 14
- cluster:EE: 10
- named-exception:architektur: 5
- cluster:BETA: 3
- named-exception:agenten: 1

## Heuristische Zuordnungen: 103

Aus Titeltext/Ref abgeleitet, nicht mechanisch aus einer Spalte. Brauchen Concept-Ratifikation.

- hk-heuristic:default-hygiene: 70
- hk-heuristic:arch-keyword: 17
- hk-heuristic:jba-keyword: 16

## Refusal-Bilanz

Insgesamt refused: **153**

- empty-cluster: 138
- milestone-workstream-conflict: 15

### empty-cluster nach status

- done: 121
- new: 13
- dissolved: 4

## Nachbedingung Echtlauf

**Echtlauf NICHT zulaessig.** 153 Zeilen sind refused. Der Echtlauf bricht per Design ab, wenn die Refusal-Liste nicht leer ist (REA-0007 Abschnitt 3, dispatch 177.5 Ablauf).

---

# Roter Probelauf der Selbstpruefung

1. Kontrolllauf davor: **gruen** (drei Mengen disjunkt, Summe = Quellzeilen).
2. Verletzung eingebaut: Nr 504 zusaetzlich als assigned dupliziert (steht damit in refused UND assigned).
3. Selbstpruefung: **rot** — Wortlaut: `assigned ∩ refused nicht leer: ['504']`.
4. Kontrolllauf danach: **gruen** (Injection war lokal, produktive Zuweisung unverunreinigt).

**Ergebnis:** die Selbstpruefung ist rot beobachtet und wieder gruen. Sie ist damit ein Gate im Wortsinn — nicht nur ein grundgruener Textbaustein.
