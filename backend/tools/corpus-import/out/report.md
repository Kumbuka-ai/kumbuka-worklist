# Trockenlauf-Bericht (Sprint 177.5)

steering commit: `004c79f2b2b9a87e2f66bcff8ec9570be18c8f15`

Quellzeilen im Backlog: **505**

## Verteilung auf Zielscope

- kumbuka: 351
- REFUSED: 138
- jbaconsult: 16

## Verteilung auf kumbuka-Straenge

- Produktlinie: 247
- Betrieb: 58
- Architektur: 31
- GTM: 14
- Agenten-Entwicklung: 1

default: 0 (Nachbedingung, muss so bleiben)

## Zuordnung nach Regel-Zweig

- empty-cluster: 138
- cluster:CORE: 110
- hk-heuristic:default-hygiene: 70
- cluster:DEPLOY: 58
- cluster:SEC: 41
- hk-heuristic:arch-keyword: 24
- hk-heuristic:jba-keyword: 16
- cluster:GTM: 14
- cluster:CONN: 13
- cluster:EE: 10
- named-exception:architektur: 7
- cluster:BETA: 3
- named-exception:agenten: 1

## Heuristische Zuordnungen: 110

Diese Zuordnungen sind aus Titeltext/Ref abgeleitet und nicht mechanisch aus einer Spalte. Sie brauchen Concept-Ratifikation vor dem Echtlauf.

- hk-heuristic:default-hygiene: 70
- hk-heuristic:arch-keyword: 24
- hk-heuristic:jba-keyword: 16

## Refusal-Bilanz

Cluster-Refusal (leerer/unbekannter Cluster): **138**

- status=done: 121
- status=new: 13
- status=dissolved: 4

Milestone/Workstream-Konflikte (M1..M7 in Nicht-Produktlinie-Strang): **15**

- Nr 504 [DEPLOY]: M5 ->Betrieb: Steuerungskorpus aus dem Python-Logbuch in das Schema dispatch migrieren: SQL-Im
- Nr 497 [DEPLOY]: M5 ->Betrieb: Worklist-Dienst, Betriebsstrang: Matrixzeile, Compose-Dienst, Rollen und Schlues
- Nr 487 [CORE]: M5 ->Architektur: Innenansicht der Plattform-Komponente festhalten: Revision 2 von platform-layer.
- Nr 478 [HK]: M1 ->Architektur: Graph-Validator fuer den Architektur-Knotenkorpus bauen — das in Schritt 5 vorge
- Nr 464 [CORE]: M5 ->Architektur: Restarbeit der Extraktion: Artefaktveroeffentlichung von kumbuka-memory und Zuor
- Nr 463 [DEPLOY]: M5 ->Betrieb: Deployment des leeren Gedaechtnisdienstes: Container-Image, Health-Endpunkt, Gra
- Nr 459 [DEPLOY]: M1 ->Betrieb: release.sh haertet: Umgebungs-Waechter vor dem Rendern, Gesundheit im Effect Gua
- Nr 442 [HK]: M5 ->Architektur: Zielbild der Worklist pflegen und in einen ADR zurueckspiegeln
- Nr 440 [HK]: M5 ->Architektur: Zielbild des Dispatch-Dienstes pflegen und in ADR-0042 und ADR-0038 zurueckspieg
- Nr 438 [HK]: M5 ->Architektur: M5-Gatter: Architekturreview und Gap-Analyse nach beiden Dienstspezifikationen, 
- Nr 434 [DEPLOY]: M5 ->Betrieb: Abschaltung des Vorgaengers: REGISTRY.md, state/, alter Dienststapel und Steueru
- Nr 433 [DEPLOY]: M5 ->Betrieb: Umzug des Bestands: Git-Korpus, Agent-Run-SQLite und die unversionierten Zustand
- Nr 427 [HK]: M5 ->Architektur: Spezifikation Worklist Manager (Stufe 2): 25 Verbenvertraege, veraenderliche Zei
- Nr 426 [HK]: M5 ->Architektur: Spezifikation Logbuch (Stufe 2): 20 Verbenvertraege, Statusmaschine, Invarianten
- Nr 424 [HK]: M2 ->Architektur: INDEX-Dateien auf Zeigergroesse zuruecknehmen: Prosa gehoert in das Dokument, de

## Nachbedingung Echtlauf

**Echtlauf NICHT zulaessig.** 153 Zeilen sind nicht platzierbar. Der Echtlauf bricht per Design ab, wenn die Refusal-Liste nicht leer ist (REA-0007 Abschnitt 3, dispatch 177.5 Abschnitt 'Ablauf').
