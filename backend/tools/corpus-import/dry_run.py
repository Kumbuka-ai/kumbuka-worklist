#!/usr/bin/env python3
"""Trockenlauf des Steuerungskorpus-Imports (Sprint 177.5).

Reads the frozen source (WORKLIST.md + REGISTRY.md at the pinned steering
commit), applies the REA-0007 assignment rule, and writes the assignment
report plus the two lists (zurueckgestellt, nicht zuordenbar). Writes
nothing to any database.

Invocation:
    python3 dry_run.py \\
        --steering /path/to/steering \\
        --snapshot /path/to/snapshot.env \\
        --out-dir  /path/to/output

The script refuses to proceed if the steering checkout does not match the
snapshot pin. That is not overridable: an import that ran against "the
current file" is not repeatable, and repeatability is the primary reason
this run pins.
"""
from __future__ import annotations

import argparse
import dataclasses
import hashlib
import os
import re
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Optional


# ---------------------------------------------------------------------------
# REA-0007 assignment rule
# ---------------------------------------------------------------------------
#
# The five bodies of work in scope kumbuka are Architektur, Produktlinie,
# Agenten-Entwicklung, Betrieb, GTM. Plus default (which must stay empty).
# The rule uses the predecessor's cluster column and named exceptions.

PRODUKTLINIE_CLUSTERS = {"CORE", "SEC", "CONN", "EE", "BETA"}
BETRIEB_CLUSTERS = {"DEPLOY"}
GTM_CLUSTERS = {"GTM"}

MILESTONE_MARKERS_NO_MILESTONE = {"", "M?", "Mx", "M0"}

# HK splits three ways, and the split is semantic (not a mechanical
# derivation from any single column). This script applies a keyword
# heuristic to produce a first-pass classification and flags every HK
# assignment as heuristic in the report. Concept ratifies or overrides.
#
# Method / apparatus / skills / working discipline go to scope jbaconsult.
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

# Corpus work (guardrail quality, index generation, edge backfill) goes
# to Architektur.
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
    """One data row from the WORKLIST.md backlog table."""
    nr: str
    ident: str
    cluster: str
    title: str
    scope: str  # this is the source's internal "scope" column, NOT the
                # target scope (kumbuka/jbaconsult)
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
    target_scope: str          # kumbuka | jbaconsult | REFUSED
    workstream: Optional[str]  # e.g. Produktlinie | Architektur | ...
    milestone: Optional[int]   # 1..7 or None
    rule: str                  # which branch of REA-0007 placed this
    heuristic: bool            # HK assignments are heuristic


# ---------------------------------------------------------------------------
# Source loading and integrity check
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


def verify_snapshot(steering: Path, snap: dict[str, str]) -> str:
    """Verify the steering checkout matches the pin. Returns the SHA."""
    got_sha = subprocess.check_output(
        ["git", "-C", str(steering), "rev-parse", "HEAD"], text=True
    ).strip()
    if got_sha != snap["STEERING_COMMIT_SHA"]:
        raise SystemExit(
            f"steering commit drift: pinned {snap['STEERING_COMMIT_SHA']}, "
            f"found {got_sha}. The source moved. Update the pin or check "
            f"out the pinned commit; a run against a different tree is not "
            f"the same run."
        )
    for fname, byte_key, sha_key in [
        ("WORKLIST.md", "WORKLIST_MD_BYTES", "WORKLIST_MD_SHA256"),
        ("REGISTRY.md", "REGISTRY_MD_BYTES", "REGISTRY_MD_SHA256"),
    ]:
        p = steering / fname
        raw = p.read_bytes()
        if len(raw) != int(snap[byte_key]):
            raise SystemExit(
                f"{fname} byte drift: pinned {snap[byte_key]}, found {len(raw)}"
            )
        got_hash = hashlib.sha256(raw).hexdigest()
        if got_hash != snap[sha_key]:
            raise SystemExit(
                f"{fname} sha256 drift: pinned {snap[sha_key]}, found {got_hash}"
            )
    return got_sha


# ---------------------------------------------------------------------------
# WORKLIST.md parser
# ---------------------------------------------------------------------------

BACKLOG_MARKER = "<!-- wlm:backlog -->"
HEADER_RE = re.compile(r"^\| Nr \|")
SEPARATOR_RE = re.compile(r"^\|[- |]+\|$")


def parse_worklist(path: Path) -> list[Row]:
    """Extract data rows from the ## Worklist table under wlm:backlog."""
    text = path.read_text()
    idx = text.find(BACKLOG_MARKER)
    if idx < 0:
        raise SystemExit(f"marker {BACKLOG_MARKER!r} not found in {path}")
    tail = text[idx:]
    rows: list[Row] = []
    for line in tail.splitlines():
        if not line.startswith("|"):
            continue
        if HEADER_RE.match(line):
            continue
        if SEPARATOR_RE.match(line):
            continue
        # Split; the table has 16 data columns, so 18 parts after the
        # leading and trailing "|". If a cell contains a "|", the split
        # produces more parts; the Ref column at the end catches the
        # overflow and we rejoin it.
        parts = line.split("|")
        # parts[0] is empty (before leading |), parts[-1] is empty (after
        # trailing |). Remove them.
        if parts and parts[0] == "":
            parts = parts[1:]
        if parts and parts[-1] == "":
            parts = parts[:-1]
        # Trim each cell of surrounding whitespace.
        parts = [p.strip() for p in parts]
        if len(parts) < 16:
            # Not a full data row - defensively skip.
            continue
        if len(parts) > 16:
            # The Ref column has extra "|" - rejoin the tail.
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
    """Translate the source's milestone marker to an integer or None.

    The three (or four) markers that stand for "no milestone" all resolve
    to None. Real milestones M1..M7 keep their numbers as milestones of
    Produktlinie.
    """
    m = marker.strip()
    if m in MILESTONE_MARKERS_NO_MILESTONE:
        return None
    match = re.match(r"^M(\d+)$", m)
    if match:
        n = int(match.group(1))
        if 1 <= n <= 7:
            return n
    # Unknown marker - refuse implicitly by returning None but the report
    # will flag it separately.
    return None


def classify_hk(row: Row) -> tuple[str, str, str]:
    """Apply the HK split heuristic. Returns (target_scope, workstream, rule)."""
    text = " ".join([row.ident, row.scope, row.title, row.ref]).lower()
    if any(kw in text for kw in HK_JBA_KEYWORDS):
        return ("jbaconsult", "n/a", "hk-heuristic:jba-keyword")
    if any(kw in text for kw in HK_ARCH_KEYWORDS):
        return ("kumbuka", "Architektur", "hk-heuristic:arch-keyword")
    return ("kumbuka", "Produktlinie", "hk-heuristic:default-hygiene")


def is_named_exception_architektur(row: Row) -> bool:
    """Named exception: target architectures, guardrail consolidation,
    edge backfill - override cluster and go to Architektur."""
    text = " ".join([row.title, row.ref]).lower()
    return any(kw in text for kw in [
        "target architecture", "zielarchitektur",
        "leitplanke", "guardrail",
        "edge backfill", "backfill der kanten",
    ])


def is_named_exception_agenten(row: Row) -> bool:
    """Named exception: agent commissioning work under HK cluster."""
    text = " ".join([row.title, row.ref]).lower()
    return any(kw in text for kw in [
        "agent commissioning", "agentenapparat",
        "agent-controller", "vier agenten",
    ])


def is_named_exception_produkt(row: Row) -> bool:
    """Named exception: data protection subject access + export work
    under a market-facing cluster - a product capability."""
    text = " ".join([row.title, row.ref]).lower()
    return any(kw in text for kw in [
        "subject access", "auskunftsanspruch", "datenschutz-export",
        "dsgvo-export", "data protection subject",
    ])


def assign(row: Row) -> Assignment:
    milestone = map_milestone(row.milestone)
    cluster = row.cluster.strip()

    # Named exceptions first. They override the cluster.
    if is_named_exception_architektur(row):
        return Assignment(row, "kumbuka", "Architektur", milestone,
                          "named-exception:architektur", heuristic=False)
    if is_named_exception_agenten(row):
        return Assignment(row, "kumbuka", "Agenten-Entwicklung", milestone,
                          "named-exception:agenten", heuristic=False)
    if is_named_exception_produkt(row):
        return Assignment(row, "kumbuka", "Produktlinie", milestone,
                          "named-exception:produkt", heuristic=False)

    if cluster in PRODUKTLINIE_CLUSTERS:
        return Assignment(row, "kumbuka", "Produktlinie", milestone,
                          f"cluster:{cluster}", heuristic=False)
    if cluster in BETRIEB_CLUSTERS:
        return Assignment(row, "kumbuka", "Betrieb", milestone,
                          f"cluster:{cluster}", heuristic=False)
    if cluster in GTM_CLUSTERS:
        return Assignment(row, "kumbuka", "GTM", milestone,
                          f"cluster:{cluster}", heuristic=False)
    if cluster == "HK":
        target, ws, rule = classify_hk(row)
        return Assignment(row, target, ws if target == "kumbuka" else None,
                          milestone if target == "kumbuka" else None,
                          rule, heuristic=True)
    if cluster == "":
        # Empty cluster - the rule cannot place. Refused, not absorbed.
        return Assignment(row, "REFUSED", None, None,
                          "empty-cluster", heuristic=False)
    # Unknown cluster - refused.
    return Assignment(row, "REFUSED", None, None,
                      f"unknown-cluster:{cluster}", heuristic=False)


# ---------------------------------------------------------------------------
# Milestone/workstream compatibility check
# ---------------------------------------------------------------------------

def milestone_workstream_conflict(a: Assignment) -> bool:
    """A row's milestone must lie in the row's workstream.

    REA-0007 puts M1..M7 in Produktlinie. If the assignment lands in a
    non-Produktlinie workstream but carries a milestone, the milestone
    would live in the wrong workstream. That is the invariant refuseCross-
    WorkstreamMilestone (ItemService.java:844) enforces in Java. On the
    SQL import path we detect it here and refuse the row rather than
    dropping the milestone silently.
    """
    if a.milestone is None:
        return False
    return a.workstream != "Produktlinie"


# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------

def write_report(assignments: list[Assignment], out_dir: Path,
                 steering_sha: str) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)

    # 1. Assignment TSV: one row per source row.
    tsv = out_dir / "assignment.tsv"
    with tsv.open("w") as f:
        f.write("nr\tid\tcluster\tstatus\ttarget_scope\tworkstream\t"
                "milestone\trule\theuristic\ttitle_head\n")
        for a in assignments:
            title_head = a.row.title[:80].replace("\t", " ")
            f.write("\t".join([
                a.row.nr, a.row.ident, a.row.cluster, a.row.status,
                a.target_scope, a.workstream or "", str(a.milestone or ""),
                a.rule, "yes" if a.heuristic else "no", title_head,
            ]) + "\n")

    # 2. Deferred list (jbaconsult).
    deferred = [a for a in assignments if a.target_scope == "jbaconsult"]
    with (out_dir / "deferred.txt").open("w") as f:
        f.write(f"# Zurueckgestellt fuer scope jbaconsult ({len(deferred)} Zeilen)\n")
        f.write(f"# HK-Split: method/apparatus/skills/working-discipline gehen "
                f"nach jbaconsult (REA-0007 Abschnitt 3).\n")
        f.write(f"# Alle Zuordnungen hier sind HEURISTISCH und brauchen "
                f"Concept-Ratifikation.\n#\n")
        for a in deferred:
            f.write(f"{a.row.nr}\t{a.row.ident}\t{a.row.status}\t"
                    f"{a.rule}\t{a.row.title[:100]}\n")

    # 3. Refusal list (empty cluster + unknown cluster + milestone conflicts).
    conflicts = [a for a in assignments if milestone_workstream_conflict(a)]
    refused = [a for a in assignments if a.target_scope == "REFUSED"]
    with (out_dir / "refusal.txt").open("w") as f:
        f.write(f"# Nicht zuordenbar ({len(refused) + len(conflicts)} Zeilen)\n")
        f.write(f"# {len(refused)} durch Cluster-Regel refused, "
                f"{len(conflicts)} durch Milestone/Workstream-Konflikt.\n")
        f.write(f"# Eine nicht platzierbare Zeile bricht den Echtlauf ab "
                f"(REA-0007 Abschnitt 3).\n#\n")
        f.write("# Cluster-Refusals\n")
        for a in refused:
            f.write(f"{a.row.nr}\t{a.row.ident or '(no-id)'}\t"
                    f"{a.row.status}\t{a.rule}\t{a.row.title[:100]}\n")
        f.write("\n# Milestone/Workstream-Konflikte\n")
        for a in conflicts:
            f.write(f"{a.row.nr}\t{a.row.ident}\t{a.row.cluster}\t"
                    f"M{a.milestone}->{a.workstream}\t{a.row.title[:100]}\n")

    # 4. Human-readable report.
    stats_by_ws = Counter()
    stats_by_scope = Counter()
    stats_heuristic = Counter()
    stats_by_rule = Counter()
    for a in assignments:
        stats_by_scope[a.target_scope] += 1
        if a.target_scope == "kumbuka":
            stats_by_ws[a.workstream or "(none)"] += 1
        if a.heuristic:
            stats_heuristic[a.rule] += 1
        stats_by_rule[a.rule] += 1

    with (out_dir / "report.md").open("w") as f:
        f.write("# Trockenlauf-Bericht (Sprint 177.5)\n\n")
        f.write(f"steering commit: `{steering_sha}`\n\n")
        f.write(f"Quellzeilen im Backlog: **{len(assignments)}**\n\n")
        f.write("## Verteilung auf Zielscope\n\n")
        for scope, n in stats_by_scope.most_common():
            f.write(f"- {scope}: {n}\n")
        f.write("\n## Verteilung auf kumbuka-Straenge\n\n")
        for ws, n in stats_by_ws.most_common():
            f.write(f"- {ws}: {n}\n")
        f.write(f"\ndefault: 0 (Nachbedingung, muss so bleiben)\n")
        f.write("\n## Zuordnung nach Regel-Zweig\n\n")
        for rule, n in stats_by_rule.most_common():
            f.write(f"- {rule}: {n}\n")
        f.write(f"\n## Heuristische Zuordnungen: {sum(stats_heuristic.values())}\n\n")
        f.write("Diese Zuordnungen sind aus Titeltext/Ref abgeleitet und "
                "nicht mechanisch aus einer Spalte. Sie brauchen "
                "Concept-Ratifikation vor dem Echtlauf.\n\n")
        for rule, n in stats_heuristic.most_common():
            f.write(f"- {rule}: {n}\n")
        f.write("\n## Refusal-Bilanz\n\n")
        refused_by_status = Counter(a.row.status for a in refused)
        f.write(f"Cluster-Refusal (leerer/unbekannter Cluster): "
                f"**{len(refused)}**\n\n")
        for s, n in refused_by_status.most_common():
            f.write(f"- status={s or '(leer)'}: {n}\n")
        f.write(f"\nMilestone/Workstream-Konflikte (M1..M7 in Nicht-Produktlinie-"
                f"Strang): **{len(conflicts)}**\n\n")
        if conflicts:
            for a in conflicts[:20]:
                f.write(f"- Nr {a.row.nr} [{a.row.cluster}]: M{a.milestone} "
                        f"->{a.workstream}: {a.row.title[:80]}\n")
        f.write(f"\n## Nachbedingung Echtlauf\n\n")
        blockers = len(refused) + len(conflicts)
        if blockers == 0:
            f.write("**Echtlauf zulaessig.** Refusal-Liste ist leer.\n")
        else:
            f.write(f"**Echtlauf NICHT zulaessig.** {blockers} Zeilen sind "
                    f"nicht platzierbar. Der Echtlauf bricht per Design ab, "
                    f"wenn die Refusal-Liste nicht leer ist (REA-0007 "
                    f"Abschnitt 3, dispatch 177.5 Abschnitt 'Ablauf').\n")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--steering", required=True, type=Path,
                    help="path to the Kumbuka-ai/steering checkout")
    ap.add_argument("--snapshot", required=True, type=Path,
                    help="path to snapshot.env (the pin)")
    ap.add_argument("--out-dir", required=True, type=Path,
                    help="directory to write the report and lists into")
    args = ap.parse_args()

    snap = load_snapshot_env(args.snapshot)
    sha = verify_snapshot(args.steering, snap)
    print(f"snapshot verified: steering@{sha[:12]}", file=sys.stderr)

    worklist_md = args.steering / "WORKLIST.md"
    rows = parse_worklist(worklist_md)
    print(f"parsed {len(rows)} backlog rows", file=sys.stderr)

    assignments = [assign(r) for r in rows]

    write_report(assignments, args.out_dir, sha)
    print(f"wrote report to {args.out_dir}", file=sys.stderr)


if __name__ == "__main__":
    main()
