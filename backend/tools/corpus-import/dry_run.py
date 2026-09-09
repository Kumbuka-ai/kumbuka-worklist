#!/usr/bin/env python3
"""Trockenlauf des Steuerungskorpus-Imports (Sprint 177.5 + 177.6).

Reads the frozen source (WORKLIST.md + REGISTRY.md at the pinned steering
commit), applies the REA-0007 assignment rule, and writes the report
plus the three disjoint output lists (assigned, deferred, refused).
Writes nothing to any database.

Reading is via `git -C <steering> show <SHA>:<file>`, so the checkout's
HEAD is irrelevant — only the object needs to be present. The pin's
sha256 is verified against the actual byte content of that object before
parsing.

Invocation:
    python3 dry_run.py \\
        --steering /path/to/steering \\
        --snapshot /path/to/snapshot.env \\
        --out-dir  /path/to/output

Optional:
    --red-probe   run the self-check red probe and exit (no output files).

The script refuses to proceed if the steering object store does not
carry a WORKLIST.md/REGISTRY.md whose bytes match the pin. That is not
overridable: an import that ran against "the current file" is not
repeatable.
"""
from __future__ import annotations

import argparse
import dataclasses
import hashlib
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path
from typing import Optional


# ---------------------------------------------------------------------------
# REA-0007 assignment rule
# ---------------------------------------------------------------------------

PRODUKTLINIE_CLUSTERS = {"CORE", "SEC", "CONN", "EE", "BETA"}
BETRIEB_CLUSTERS = {"DEPLOY"}
GTM_CLUSTERS = {"GTM"}

MILESTONE_MARKERS_NO_MILESTONE = {"", "M?", "Mx", "M0"}

HK_JBA_KEYWORDS = [
    "apparat", "apparatus",
    "method.md", "methode", "methodik",
    "process.md", "prozess",
    "skill", "skills",
    "return-vertrag", "return-obligation",
    "dispatch-methodik", "dispatch-automat",
    "auftragsform", "auftragsvertrag",
    "code-review-methodik",
    "arbeitsdisziplin", "working-discipline",
    "session-start", "session-closure",
]

HK_ARCH_KEYWORDS = [
    "guardrail", "leitplanke", "leitplanken",
    "corpus", "korpus",
    "spec/docs", "spec/",
    "index generation", "index generieren", "index ueber", "generierten index",
    "edge backfill", "kante", "edges",
    "target architecture", "zielarchitektur",
    "realization", "realisierung",
    "adr-", "adrs",
]


@dataclasses.dataclass
class Row:
    nr: str
    ident: str
    cluster: str
    title: str
    scope: str
    typ: str
    prio: str
    milestone: str
    groesse: str
    status: str
    angelegt: str
    geaendert: str
    iteration: str
    sprint: str
    deps: str
    ref: str
    raw_line: str = ""


@dataclasses.dataclass
class Assignment:
    row: Row
    bucket: str                # "assigned" | "deferred" | "refused"
    target_scope: str          # "kumbuka" | "jbaconsult" | "-"
    workstream: Optional[str]
    milestone: Optional[int]
    rule: str                  # which branch placed this (or refused it)
    heuristic: bool            # HK assignments are heuristic
    conflict_reason: str = ""  # non-empty when refused


# ---------------------------------------------------------------------------
# Snapshot pin
# ---------------------------------------------------------------------------

def load_snapshot_env(path: Path) -> dict[str, str]:
    env = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        k, _, v = line.partition("=")
        env[k.strip()] = v.strip()
    return env


def git_show_bytes(steering: Path, sha: str, path: str) -> bytes:
    """Read one file at one commit from the steering object store."""
    return subprocess.check_output(
        ["git", "-C", str(steering), "show", f"{sha}:{path}"]
    )


def verify_snapshot(steering: Path, snap: dict[str, str]) -> str:
    """Verify the pinned files exist and match. Returns the SHA."""
    sha = snap["STEERING_COMMIT_SHA"]
    # Confirm the commit is reachable (fetched).
    try:
        subprocess.check_output(
            ["git", "-C", str(steering), "cat-file", "-e", f"{sha}^{{commit}}"],
            stderr=subprocess.STDOUT,
        )
    except subprocess.CalledProcessError:
        raise SystemExit(
            f"pinned commit {sha} not reachable in {steering}. Run "
            f"`git -C {steering} fetch origin main` and retry."
        )
    for fname, byte_key, sha_key in [
        ("WORKLIST.md", "WORKLIST_MD_BYTES", "WORKLIST_MD_SHA256"),
        ("REGISTRY.md", "REGISTRY_MD_BYTES", "REGISTRY_MD_SHA256"),
    ]:
        raw = git_show_bytes(steering, sha, fname)
        if len(raw) != int(snap[byte_key]):
            raise SystemExit(
                f"{fname}@{sha[:12]} byte drift: pinned {snap[byte_key]}, "
                f"found {len(raw)}"
            )
        got_hash = hashlib.sha256(raw).hexdigest()
        if got_hash != snap[sha_key]:
            raise SystemExit(
                f"{fname}@{sha[:12]} sha256 drift: pinned {snap[sha_key]}, "
                f"found {got_hash}"
            )
    return sha


# ---------------------------------------------------------------------------
# WORKLIST.md parser
# ---------------------------------------------------------------------------

BACKLOG_MARKER = "<!-- wlm:backlog -->"
HEADER_RE = re.compile(r"^\| Nr \|")
SEPARATOR_RE = re.compile(r"^\|[- |]+\|$")


def parse_worklist_bytes(raw: bytes) -> list[Row]:
    text = raw.decode("utf-8")
    idx = text.find(BACKLOG_MARKER)
    if idx < 0:
        raise SystemExit(f"marker {BACKLOG_MARKER!r} not found in WORKLIST.md")
    tail = text[idx:]
    rows: list[Row] = []
    for line in tail.splitlines():
        if not line.startswith("|"):
            continue
        if HEADER_RE.match(line):
            continue
        if SEPARATOR_RE.match(line):
            continue
        parts = line.split("|")
        if parts and parts[0] == "":
            parts = parts[1:]
        if parts and parts[-1] == "":
            parts = parts[:-1]
        parts = [p.strip() for p in parts]
        if len(parts) < 16:
            continue
        if len(parts) > 16:
            parts = parts[:15] + [" | ".join(parts[15:])]
        rows.append(Row(
            nr=parts[0], ident=parts[1], cluster=parts[2], title=parts[3],
            scope=parts[4], typ=parts[5], prio=parts[6], milestone=parts[7],
            groesse=parts[8], status=parts[9], angelegt=parts[10],
            geaendert=parts[11], iteration=parts[12], sprint=parts[13],
            deps=parts[14], ref=parts[15], raw_line=line,
        ))
    return rows


# ---------------------------------------------------------------------------
# Assignment
# ---------------------------------------------------------------------------

def map_milestone(marker: str) -> Optional[int]:
    m = marker.strip()
    if m in MILESTONE_MARKERS_NO_MILESTONE:
        return None
    match = re.match(r"^M(\d+)$", m)
    if match:
        n = int(match.group(1))
        if 1 <= n <= 7:
            return n
    return None


def classify_hk(row: Row) -> tuple[str, str, str]:
    text = " ".join([row.ident, row.scope, row.title, row.ref]).lower()
    if any(kw in text for kw in HK_JBA_KEYWORDS):
        return ("jbaconsult", "n/a", "hk-heuristic:jba-keyword")
    if any(kw in text for kw in HK_ARCH_KEYWORDS):
        return ("kumbuka", "Architektur", "hk-heuristic:arch-keyword")
    return ("kumbuka", "Produktlinie", "hk-heuristic:default-hygiene")


def is_named_exception_architektur(row: Row) -> bool:
    text = " ".join([row.title, row.ref]).lower()
    return any(kw in text for kw in [
        "target architecture", "zielarchitektur",
        "leitplanke", "guardrail",
        "edge backfill", "backfill der kanten",
    ])


def is_named_exception_agenten(row: Row) -> bool:
    text = " ".join([row.title, row.ref]).lower()
    return any(kw in text for kw in [
        "agent commissioning", "agentenapparat",
        "agent-controller", "vier agenten",
    ])


def is_named_exception_produkt(row: Row) -> bool:
    text = " ".join([row.title, row.ref]).lower()
    return any(kw in text for kw in [
        "subject access", "auskunftsanspruch", "datenschutz-export",
        "dsgvo-export", "data protection subject",
    ])


def initial_assign(row: Row) -> Assignment:
    """First-pass placement by cluster + named exceptions.

    Milestone/workstream compatibility is checked in a second pass, and
    a row that fails it is refused (not silently downgraded).
    """
    milestone = map_milestone(row.milestone)
    cluster = row.cluster.strip()

    if is_named_exception_architektur(row):
        return _assigned(row, "Architektur", milestone,
                         "named-exception:architektur", heuristic=False)
    if is_named_exception_agenten(row):
        return _assigned(row, "Agenten-Entwicklung", milestone,
                         "named-exception:agenten", heuristic=False)
    if is_named_exception_produkt(row):
        return _assigned(row, "Produktlinie", milestone,
                         "named-exception:produkt", heuristic=False)

    if cluster in PRODUKTLINIE_CLUSTERS:
        return _assigned(row, "Produktlinie", milestone,
                         f"cluster:{cluster}", heuristic=False)
    if cluster in BETRIEB_CLUSTERS:
        return _assigned(row, "Betrieb", milestone,
                         f"cluster:{cluster}", heuristic=False)
    if cluster in GTM_CLUSTERS:
        return _assigned(row, "GTM", milestone,
                         f"cluster:{cluster}", heuristic=False)
    if cluster == "HK":
        target, ws, rule = classify_hk(row)
        if target == "jbaconsult":
            return Assignment(row, bucket="deferred", target_scope="jbaconsult",
                              workstream=None, milestone=None,
                              rule=rule, heuristic=True)
        return _assigned(row, ws, milestone, rule, heuristic=True)
    if cluster == "":
        return _refused(row, "empty-cluster")
    return _refused(row, f"unknown-cluster:{cluster}")


def _assigned(row: Row, workstream: str, milestone: Optional[int],
              rule: str, heuristic: bool) -> Assignment:
    return Assignment(row, bucket="assigned", target_scope="kumbuka",
                      workstream=workstream, milestone=milestone,
                      rule=rule, heuristic=heuristic)


def _refused(row: Row, reason: str,
             prev_workstream: Optional[str] = None,
             prev_milestone: Optional[int] = None) -> Assignment:
    return Assignment(row, bucket="refused", target_scope="-",
                      workstream=None, milestone=None,
                      rule=reason, heuristic=False,
                      conflict_reason=reason
                      + (f" (would-have-been:{prev_workstream}, M{prev_milestone})"
                         if prev_workstream else ""))


def assign_all(rows: list[Row]) -> list[Assignment]:
    # V12 (2026-09-09) retracted the milestone-workstream edge
    # (TAR-0002 section 4, REQ-0148 obsolete). The former
    # `apply_milestone_invariant` — which refused rows whose milestone
    # was not in Produktlinie — is gone; several workstreams reach one
    # milestone together, and a milestone stays with the item wherever
    # the item's workstream lies.
    return [initial_assign(r) for r in rows]


# ---------------------------------------------------------------------------
# Self-check: disjoint set logic
# ---------------------------------------------------------------------------

class SelfCheckFailed(Exception):
    """Raised when the disjoint/complete invariants of the output are
    violated. Message carries the offending nrs."""


def self_check(rows: list[Row], assignments: list[Assignment]) -> None:
    """Assert the three output buckets are disjoint and cover the source.

    A ZeileNr may appear in EXACTLY ONE of assigned/deferred/refused.
    The union must equal the set of source nrs. Any violation raises
    SelfCheckFailed with the offending nrs. No text-only assertion:
    this is the mechanism the report describes.
    """
    a = {x.row.nr for x in assignments if x.bucket == "assigned"}
    d = {x.row.nr for x in assignments if x.bucket == "deferred"}
    r = {x.row.nr for x in assignments if x.bucket == "refused"}
    if a & d:
        raise SelfCheckFailed(
            f"assigned ∩ deferred nicht leer: {sorted(a & d)[:20]}"
        )
    if a & r:
        raise SelfCheckFailed(
            f"assigned ∩ refused nicht leer: {sorted(a & r)[:20]}"
        )
    if d & r:
        raise SelfCheckFailed(
            f"deferred ∩ refused nicht leer: {sorted(d & r)[:20]}"
        )
    all_nr = {row.nr for row in rows}
    total = len(a) + len(d) + len(r)
    if total != len(all_nr):
        raise SelfCheckFailed(
            f"Summe |assigned|+|deferred|+|refused| = {total} != Quellzeilen "
            f"{len(all_nr)}. Doppelvorkommen oder Bucket-Fehler."
        )
    missing = all_nr - (a | d | r)
    extra = (a | d | r) - all_nr
    if missing:
        raise SelfCheckFailed(f"nicht abgedeckt: {sorted(missing)[:20]}")
    if extra:
        raise SelfCheckFailed(f"nicht in Quelle: {sorted(extra)[:20]}")


# ---------------------------------------------------------------------------
# Red probe of the self-check
# ---------------------------------------------------------------------------

def red_probe(rows: list[Row], assignments: list[Assignment]) -> str:
    """Kontrolllauf → Verletzung → Kontrolllauf. Returns a report."""
    log: list[str] = []
    log.append("# Roter Probelauf der Selbstpruefung")
    log.append("")

    # 1. Kontrolllauf davor: die echten Zuweisungen laufen gruen.
    try:
        self_check(rows, assignments)
    except SelfCheckFailed as e:
        log.append(f"KONTROLLLAUF DAVOR: ROT ({e}) — der Ist-Zustand ist "
                   f"bereits verletzt, Probelauf ist nicht aussagekraeftig.")
        return "\n".join(log)
    log.append("1. Kontrolllauf davor: **gruen** (drei Mengen disjunkt, "
               "Summe = Quellzeilen).")

    # 2. Verletzung einbauen: eine refused-Zeile ADDITIONALLY als assigned
    # duplizieren. Diese Zeile ist dann in zwei Buckets.
    refused_examples = [a for a in assignments if a.bucket == "refused"]
    if not refused_examples:
        log.append("2. VERLETZUNG NICHT EINBAUBAR: keine refused-Zeile "
                   "vorhanden. Probelauf abgebrochen.")
        return "\n".join(log)
    victim = refused_examples[0]
    poisoned = list(assignments) + [Assignment(
        row=victim.row, bucket="assigned", target_scope="kumbuka",
        workstream="Produktlinie", milestone=None,
        rule="red-probe:injected-duplicate", heuristic=False,
    )]
    log.append(f"2. Verletzung eingebaut: Nr {victim.row.nr} zusaetzlich "
               f"als assigned dupliziert (steht damit in refused UND "
               f"assigned).")

    # 3. Pruefung erwartet rot.
    try:
        self_check(rows, poisoned)
    except SelfCheckFailed as e:
        log.append(f"3. Selbstpruefung: **rot** — Wortlaut: `{e}`.")
    else:
        log.append("3. Selbstpruefung: **GRUEN — DEFEKT**. Die Pruefung "
                   "hat die absichtliche Doppelzuordnung nicht gefunden. "
                   "Sie ist unwirksam.")
        return "\n".join(log)

    # 4. Kontrolllauf danach: das echte Ergebnis ist unangetastet, laeuft
    # gruen. Das beweist, dass die Injection lokal war und nicht den
    # produktiven Bestand befleckt hat.
    try:
        self_check(rows, assignments)
    except SelfCheckFailed as e:
        log.append(f"4. Kontrolllauf danach: ROT ({e}) — der Ist-Zustand "
                   f"wurde durch die Injection verunreinigt. Das ist ein "
                   f"Bug im Probelauf, nicht in der Pruefung.")
        return "\n".join(log)
    log.append("4. Kontrolllauf danach: **gruen** (Injection war lokal, "
               "produktive Zuweisung unverunreinigt).")
    log.append("")
    log.append("**Ergebnis:** die Selbstpruefung ist rot beobachtet und "
               "wieder gruen. Sie ist damit ein Gate im Wortsinn — nicht "
               "nur ein grundgruener Textbaustein.")
    return "\n".join(log)


# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------

def write_report(rows: list[Row], assignments: list[Assignment],
                 out_dir: Path, steering_sha: str, probe_report: str) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)

    assigned = [a for a in assignments if a.bucket == "assigned"]
    deferred = [a for a in assignments if a.bucket == "deferred"]
    refused = [a for a in assignments if a.bucket == "refused"]

    # 1. Assignment TSV: one row per source row.
    tsv = out_dir / "assignment.tsv"
    with tsv.open("w") as f:
        f.write("nr\tid\tcluster\tstatus\tbucket\ttarget_scope\tworkstream\t"
                "milestone\trule\theuristic\ttitle_head\n")
        for a in assignments:
            title_head = a.row.title[:80].replace("\t", " ")
            f.write("\t".join([
                a.row.nr, a.row.ident, a.row.cluster, a.row.status,
                a.bucket, a.target_scope, a.workstream or "",
                str(a.milestone or ""), a.rule,
                "yes" if a.heuristic else "no", title_head,
            ]) + "\n")

    # 2. Deferred list (jbaconsult).
    with (out_dir / "deferred.txt").open("w") as f:
        f.write(f"# Zurueckgestellt fuer scope jbaconsult ({len(deferred)} Zeilen)\n")
        f.write(f"# HK-Split: method/apparatus/skills/working-discipline gehen "
                f"nach jbaconsult (REA-0007 Abschnitt 3).\n")
        f.write(f"# Alle Zuordnungen hier sind HEURISTISCH und brauchen "
                f"Concept-Ratifikation.\n#\n")
        for a in deferred:
            f.write(f"{a.row.nr}\t{a.row.ident}\t{a.row.status}\t"
                    f"{a.rule}\t{a.row.title[:100]}\n")

    # 3. Refusal list.
    with (out_dir / "refusal.txt").open("w") as f:
        f.write(f"# Nicht zuordenbar ({len(refused)} Zeilen)\n")
        f.write(f"# Eine nicht platzierbare Zeile bricht den Echtlauf ab "
                f"(REA-0007 Abschnitt 3).\n#\n")
        by_reason = Counter(a.rule for a in refused)
        for reason, n in by_reason.most_common():
            f.write(f"# {reason}: {n}\n")
        f.write("#\n")
        for reason, _ in by_reason.most_common():
            f.write(f"\n## {reason}\n")
            for a in refused:
                if a.rule != reason:
                    continue
                f.write(f"{a.row.nr}\t{a.row.ident or '(no-id)'}\t"
                        f"{a.row.cluster}\t{a.row.status}\t"
                        f"{a.conflict_reason}\t{a.row.title[:100]}\n")

    # 4. Human-readable report.
    stats_by_ws = Counter()
    stats_by_rule = Counter()
    stats_heuristic = Counter()
    for a in assigned:
        stats_by_ws[a.workstream or "(none)"] += 1
    for a in assignments:
        stats_by_rule[a.rule] += 1
        if a.heuristic:
            stats_heuristic[a.rule] += 1

    with (out_dir / "report.md").open("w") as f:
        f.write("# Trockenlauf-Bericht (Sprint 177.7)\n\n")
        f.write(f"steering commit: `{steering_sha}`\n\n")
        f.write(f"Quellzeilen im Backlog: **{len(rows)}**\n\n")
        f.write("## Die drei disjunkten Ausgabemengen\n\n")
        f.write(f"- **assigned** (kumbuka): {len(assigned)}\n")
        f.write(f"- **deferred** (jbaconsult): {len(deferred)}\n")
        f.write(f"- **refused**: {len(refused)}\n")
        f.write(f"\nSumme: {len(assigned) + len(deferred) + len(refused)} — "
                f"muss {len(rows)} sein.\n\n")
        f.write("Die Disjunktheit dieser drei Mengen und die Gleichheit der "
                "Summe zur Quellzeilenzahl werden **maschinell** von "
                "`self_check` gepruft und der Lauf bricht bei jeder Verletzung "
                "sofort ab. Diese Zeile im Bericht ist keine Zusicherung, "
                "sondern der Verweis auf die Pruefung.\n\n")
        f.write("## Verteilung der assigned-Zeilen auf kumbuka-Straenge\n\n")
        for ws, n in stats_by_ws.most_common():
            f.write(f"- {ws}: {n}\n")
        f.write(f"\ndefault: 0 (Nachbedingung; die Straenge-Saat legt keine "
                f"Items an, deshalb konstruktiv).\n")
        f.write("\n## Zuordnung/Verweigerung nach Regel-Zweig\n\n")
        for rule, n in stats_by_rule.most_common():
            f.write(f"- {rule}: {n}\n")
        f.write(f"\n## Heuristische Zuordnungen: {sum(stats_heuristic.values())}\n\n")
        f.write("Aus Titeltext/Ref abgeleitet, nicht mechanisch aus einer "
                "Spalte. Brauchen Concept-Ratifikation.\n\n")
        for rule, n in stats_heuristic.most_common():
            f.write(f"- {rule}: {n}\n")
        f.write("\n## Refusal-Bilanz\n\n")
        f.write(f"Insgesamt refused: **{len(refused)}**\n\n")
        by_reason = Counter(a.rule for a in refused)
        for reason, n in by_reason.most_common():
            f.write(f"- {reason}: {n}\n")
        refused_status = Counter(a.row.status for a in refused
                                 if a.rule == "empty-cluster")
        if refused_status:
            f.write(f"\n### empty-cluster nach status\n\n")
            for s, n in refused_status.most_common():
                f.write(f"- {s or '(leer)'}: {n}\n")
        f.write("\n## Nachbedingung Echtlauf\n\n")
        if len(refused) == 0:
            f.write("**Echtlauf zulaessig.** Refusal-Liste ist leer.\n")
        else:
            f.write(f"**Echtlauf NICHT zulaessig.** {len(refused)} Zeilen "
                    f"sind refused. Der Echtlauf bricht per Design ab, wenn "
                    f"die Refusal-Liste nicht leer ist (REA-0007 Abschnitt 3, "
                    f"dispatch 177.5 Ablauf).\n")
        f.write("\n---\n\n")
        f.write(probe_report)
        f.write("\n")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--steering", required=True, type=Path,
                    help="path to the Kumbuka-ai/steering checkout")
    ap.add_argument("--snapshot", required=True, type=Path,
                    help="path to snapshot.env (the pin)")
    ap.add_argument("--out-dir", required=True, type=Path,
                    help="directory to write the report and lists into")
    ap.add_argument("--red-probe", action="store_true",
                    help="run only the self-check red probe and print it")
    args = ap.parse_args()

    snap = load_snapshot_env(args.snapshot)
    sha = verify_snapshot(args.steering, snap)
    print(f"snapshot verified: steering@{sha[:12]}", file=sys.stderr)

    worklist_bytes = git_show_bytes(args.steering, sha, "WORKLIST.md")
    rows = parse_worklist_bytes(worklist_bytes)
    print(f"parsed {len(rows)} backlog rows", file=sys.stderr)

    assignments = assign_all(rows)

    if args.red_probe:
        print(red_probe(rows, assignments))
        return

    # Hard invariant: the disjoint/complete self-check must pass. Every
    # normal run rides on this gate; a failure aborts before any file is
    # written.
    try:
        self_check(rows, assignments)
    except SelfCheckFailed as e:
        raise SystemExit(f"SELF CHECK FAILED: {e}")
    print("self-check green", file=sys.stderr)

    probe_report = red_probe(rows, assignments)

    write_report(rows, assignments, args.out_dir, sha, probe_report)
    print(f"wrote report to {args.out_dir}", file=sys.stderr)


if __name__ == "__main__":
    main()
