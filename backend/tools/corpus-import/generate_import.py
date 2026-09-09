#!/usr/bin/env python3
"""Generate the transactional corpus-import SQL file (Sprint 177.9).

Reads the pinned source (snapshot.env + WORKLIST.md via git show) and the
177.8 assignment rule (imported from dry_run.py), emits a single-file
one-transaction SQL script that the operator applies on the host where
Flyway runs.

The script does NOT connect to any database. It only writes a file.

REA-0007 §5 "The form of the delivery": one transaction, safe to reapply
or refused, and the file identifies itself in the target. This
implementation chose the *refused* variant — a second application with
the same digest raises a typed error, and with a different digest also
raises. Silent doubling of the stock is neither.

The self-identification lives in `worklist.attribute_definition` under
key `corpus_import_marker` with status `withdrawn` — a spot that
requires no schema change (the auftrag forbids one). The definition's
`name` field carries `sha=...; pin=...; rows=N` and the idempotency
check reads it back on second application.

Invocation:
    python3 generate_import.py \\
        --steering /path/to/steering \\
        --snapshot ./snapshot.env \\
        --tenant-id <uuid> \\
        --scope-id  <uuid> \\
        --out       ./out/import.sql
"""
from __future__ import annotations

import argparse
import dataclasses
import hashlib
import re
import sys
from pathlib import Path

# Reuse the dry_run.py parsing + assignment rule so the two never drift.
sys.path.insert(0, str(Path(__file__).parent))
from dry_run import (  # noqa: E402
    OPEN_CALL_IN_EXCEPTIONS,
    TERMINAL_STATUSES,
    CATCH_ALL_WORKSTREAM,
    Assignment,
    assign_all,
    git_show_bytes,
    load_snapshot_env,
    parse_worklist_bytes,
    verify_snapshot,
    check_no_open_row_in_catchall,
    self_check,
)


# ---------------------------------------------------------------------------
# Vocabulary that goes into scope kumbuka on top of what bootstrap seeds.
#
# The bootstrap (bootstrap-scope.sql:190-209) declares five statuses:
#     new, open, done, dropped, obsolete
# The predecessor source carries seven:
#     new, open, done, dropped, dissolved, planned, observing
#
# The three additional ones need a declaration under REA-0007 §3 rules —
# derived, not guessed. Derivation per value (see return):
#
# - dissolved  → analog to bootstrap's `obsolete` (closed=T, successful=F).
#                The source uses `dissolved` where the bootstrap language
#                uses `obsolete`; the four predicates align exactly.
# - planned    → analog to `open` (actionable=T). Under TAR-0002 §4
#                `planned` is a VIEW over iteration_membership, not a
#                status value. The row's status carrier still needs a
#                declared value — actionable-not-in-progress is the
#                closest fit. The `planned` semantic (has membership)
#                does not travel with this import — no iterations are
#                moved. This is Concept's to ratify.
# - observing  → analog to `new` (all four predicates false). The row
#                is neither actionable nor closed; a mediator between
#                open and terminal. Concept's to ratify.
# ---------------------------------------------------------------------------

STATUS_PREDICATES: dict[str, tuple[bool, bool, bool, bool]] = {
    # token         : (actionable, in_progress, closed, successful)
    "new":            (False, False, False, False),
    "open":           (True,  False, False, False),
    "done":           (False, False, True,  True),
    "dropped":        (False, False, True,  False),
    "obsolete":       (False, False, True,  False),
    # New declarations 177.9 (derivations named above; Concept ratifies):
    "dissolved":      (False, False, True,  False),
    "planned":        (True,  False, False, False),
    "observing":      (False, False, False, False),
}

# Source status → target status token. Where the source uses a word the
# target already knows, the mapping is identity. Where a new declaration
# lands, the source keeps its own token (so provenance is preserved).
STATUS_MAPPING: dict[str, str] = {
    "new":       "new",
    "open":      "open",
    "done":      "done",
    "dropped":   "dropped",
    "dissolved": "dissolved",
    "planned":   "planned",
    "observing": "observing",
}


# ---------------------------------------------------------------------------
# Body markers so a verifier can compute the digest by extracting the
# region between them (and skipping the header comment and the digest
# INSERT which BOTH carry the digest and would otherwise cycle).
# ---------------------------------------------------------------------------

BODY_BEGIN_MARKER = "-- corpus-import-body-begin"
BODY_END_MARKER = "-- corpus-import-body-end"


# ---------------------------------------------------------------------------
# SQL escaping helpers
# ---------------------------------------------------------------------------

def sql_quote(s: str | None) -> str:
    """Postgres SQL literal quoting. NULL for None or empty string."""
    if s is None or s == "":
        return "NULL"
    return "'" + s.replace("'", "''") + "'"


def sql_bool(b: bool) -> str:
    return "TRUE" if b else "FALSE"


# ---------------------------------------------------------------------------
# The source's deps column: comma-separated Nrs, or a single Nr, or empty.
# ---------------------------------------------------------------------------

def parse_deps(deps: str) -> list[str]:
    if not deps:
        return []
    return [d.strip() for d in deps.split(",") if d.strip()]


# ---------------------------------------------------------------------------
# Milestone extraction: the source has M1..M7 as real milestones; the
# markers (M?, Mx, M0, empty) mean "no milestone" (V12 removed them).
#
# The M1..M7 titles/visions are in the WORKLIST.md header section
# `<!-- wlm:milestones -->` and up to the backlog marker. We do NOT parse
# that section here: the bootstrap already seeded three milestone markers
# with kinds `not_assessed | off_path | no_vision`, and the four real
# milestones (M1..M7 -> 4..10 after shift? no — bootstrap high-water is
# 3, so real M1..M7 land at numbers 4..10) get created here with their
# titles copied from the source's milestone section.
# ---------------------------------------------------------------------------

MILESTONE_PATTERN = re.compile(r"^### (M[0-9]+) — (.+)$", re.MULTILINE)
MILESTONE_STATUS_PATTERN = re.compile(r"^status:\s*(\w+)$", re.MULTILINE)
MILESTONE_VISION_PATTERN = re.compile(r"^vision:\s*(.+)$", re.MULTILINE)


def parse_milestones(worklist_text: str) -> list[dict]:
    """Return [{marker, title, status, vision}, ...] for M1..Mn in the
    source's milestones section (between `<!-- wlm:milestones -->` and
    the first iteration marker)."""
    start = worklist_text.find("<!-- wlm:milestones -->")
    end = worklist_text.find("<!-- wlm:iteration:")
    if start < 0 or end < 0:
        raise SystemExit("milestones section markers not found in WORKLIST.md")
    section = worklist_text[start:end]
    milestones = []
    # Split on the `### Mx — Title` headings.
    parts = re.split(r"^### (M\d+) — (.+)$", section, flags=re.MULTILINE)
    # parts = [preamble, marker1, title1, body1, marker2, title2, body2, ...]
    for i in range(1, len(parts), 3):
        marker = parts[i].strip()
        title = parts[i + 1].strip()
        body = parts[i + 2] if i + 2 < len(parts) else ""
        status_m = MILESTONE_STATUS_PATTERN.search(body)
        vision_m = MILESTONE_VISION_PATTERN.search(body)
        milestones.append({
            "marker": marker,
            "number": int(marker[1:]),
            "title": title,
            "status": status_m.group(1) if status_m else "planned",
            "vision": vision_m.group(1).strip() if vision_m else "",
        })
    return milestones


# ---------------------------------------------------------------------------
# SQL emitters
# ---------------------------------------------------------------------------

def emit_header(sha_placeholder: str, pin_sha: str, row_count: int) -> str:
    return f"""\
-- ===========================================================================
-- Corpus import for scope kumbuka (Sprint 177.9)
--
-- Generated by generate_import.py from
--   steering source: {pin_sha}
--   dry-run rule:    177.5 → 177.8 (REA-0007 §3 as of 2026-09-09)
--
-- Digest of body (sha256):
--   {sha_placeholder}
-- Row count: {row_count} kumbuka assignments
--
-- Digest recipe (the reason: the digest is embedded in the body, so a
-- naive sha of the body would cycle):
--   1. Extract everything between {BODY_BEGIN_MARKER!r}
--      and {BODY_END_MARKER!r} (inclusive).
--   2. Replace the 64-hex-digit digest string (the one after "sha=" in
--      the corpus_import_marker INSERT below) with 64 zeros.
--   3. sha256 that; it equals the digest above.
--
-- The same digest is embedded inside the transaction (in the
-- self-identification INSERT) and compared on second application.
-- Two applications with the same digest raise a typed exception; two
-- applications with different digests do the same. Silent doubling is
-- neither.
--
-- REA-0007 §5 "The form of the delivery" (2026-09-09) is the ratified
-- source for these properties.
-- ===========================================================================

"""


def emit_body(
    tenant_id: str,
    scope_id: str,
    milestones: list[dict],
    assignments: list[Assignment],
    pin_sha: str,
    digest: str,
    row_count: int,
) -> str:
    """Emit the transactional body between BODY_BEGIN_MARKER and
    BODY_END_MARKER. Called twice: once with digest='<sha256_placeholder>'
    to compute the actual sha, once with the real digest."""
    lines: list[str] = []
    ap = lines.append

    tenant_lit = sql_quote(tenant_id)
    scope_lit = sql_quote(scope_id)

    ap(BODY_BEGIN_MARKER)
    ap("")
    ap("BEGIN;")
    ap("")
    ap("-- RLS binds the migrator too (V3 FORCE), so every DML in this file")
    ap(f"-- runs under app.tenant_id = {tenant_id}.")
    ap(f"SELECT set_config('app.tenant_id', {tenant_lit}, true);")
    ap("")

    # -- Idempotency check ---------------------------------------------------
    ap("-- ----------------------------------------------------------------")
    ap("-- Idempotency: refuse a second application (typed error).")
    ap("-- ----------------------------------------------------------------")
    ap("DO $$")
    ap("DECLARE")
    ap("    existing_name TEXT;")
    ap("BEGIN")
    ap(f"    PERFORM set_config('app.tenant_id', {tenant_lit}, true);")
    ap("    SELECT name INTO existing_name")
    ap("      FROM worklist.attribute_definition")
    ap(f"     WHERE tenant_id = {tenant_lit}")
    ap(f"       AND scope_id  = {scope_lit}")
    ap("       AND key       = 'corpus_import_marker';")
    ap("    IF existing_name IS NOT NULL THEN")
    ap("        RAISE EXCEPTION")
    ap("            'corpus-import already applied to this scope: %. "
       "Second application refused (REA-0007 §5).', existing_name;")
    ap("    END IF;")
    ap("END $$;")
    ap("")

    # -- Vocabulary: statuses ------------------------------------------------
    ap("-- ----------------------------------------------------------------")
    ap("-- Vocabulary: item_status. Idempotent per (scope, name) via")
    ap("-- WHERE NOT EXISTS — bootstrap-seeded values stay, the three")
    ap("-- 177.9-additions (dissolved, planned, observing) are added.")
    ap("-- ----------------------------------------------------------------")
    for rank, (token, (act, ip, cl, ok)) in enumerate(STATUS_PREDICATES.items(), 1):
        ap("INSERT INTO worklist.item_status")
        ap("    (id, tenant_id, scope_id, name, description, rank, "
           "actionable, in_progress, closed, successful, status)")
        ap("SELECT gen_random_uuid(),")
        ap(f"       {tenant_lit}, {scope_lit},")
        ap(f"       {sql_quote(token)},")
        ap(f"       {sql_quote(f'imported for scope kumbuka (Sprint 177.9)')},")
        ap(f"       {rank}, {sql_bool(act)}, {sql_bool(ip)}, "
           f"{sql_bool(cl)}, {sql_bool(ok)}, 'declared'")
        ap(" WHERE NOT EXISTS (SELECT 1 FROM worklist.item_status")
        ap(f"                    WHERE tenant_id = {tenant_lit}")
        ap(f"                      AND scope_id  = {scope_lit}")
        ap(f"                      AND name      = {sql_quote(token)});")
    ap("")

    # -- Workstreams (five bodies of work) -----------------------------------
    ap("-- ----------------------------------------------------------------")
    ap("-- Straenge-Saat. `default` is planted by bootstrap; the five")
    ap("-- new ones land here. Idempotent via WHERE NOT EXISTS.")
    ap("-- ----------------------------------------------------------------")
    workstreams = [
        ("produktlinie", "Der Bau des Produkts selbst: Dienste, Konsolen, "
         "Konnektor-Oberflaeche, Tenant-Isolation, Enterprise-Module. "
         "Traegt auch die Dokumentation, die die Umsetzung erzeugt."),
        ("architektur", "Konzept- und Architekturarbeit vor der Umsetzung: "
         "Zielarchitekturen, Realisierungskonzepte, Anforderungen, "
         "Leitplanken, Entscheidungen."),
        ("betrieb", "Host, Compose, Edge, Release-Kette, Backup und Restore, "
         "Deployment-Gates."),
        ("agenten-entwicklung", "Der Agenten-Apparat als Entwicklungsgegenstand, "
         "nicht die Nutzung von Agenten als Arbeitsmethode."),
        ("gtm", "Preisgestaltung, Vertragsbedingungen, Recht, "
         "Zahlungsdienstleister, Positionierung, marktseitiges Material."),
    ]
    for i, (token, desc) in enumerate(workstreams, start=2):
        ap("INSERT INTO worklist.workstream")
        ap("    (id, tenant_id, scope_id, number, token, description, status, "
           "is_default, conflict_token)")
        ap("SELECT gen_random_uuid(),")
        ap(f"       {tenant_lit}, {scope_lit},")
        ap(f"       {i}, {sql_quote(token)},")
        ap(f"       {sql_quote(desc)},")
        ap("       'declared', FALSE, gen_random_uuid()")
        ap(" WHERE NOT EXISTS (SELECT 1 FROM worklist.workstream")
        ap(f"                    WHERE tenant_id = {tenant_lit}")
        ap(f"                      AND scope_id  = {scope_lit}")
        ap(f"                      AND token     = {sql_quote(token)});")
    ap("")

    # -- Milestones (real M1..M7 from source) --------------------------------
    ap("-- ----------------------------------------------------------------")
    ap("-- Milestones M1..Mn from the source's milestone section. Numbers")
    ap("-- are scope-wide (V12), starting after the bootstrap-seeded")
    ap("-- markers (which occupy 1..3). Source M1 lands at number 4, M2 at")
    ap("-- 5, etc. — a shift is unavoidable because the marker numbers are")
    ap("-- already taken. Idempotent via WHERE NOT EXISTS on title.")
    ap("-- ----------------------------------------------------------------")
    milestone_shift = 3  # bootstrap markers hold 1..3
    default_ws_lookup = (
        f"(SELECT id FROM worklist.workstream WHERE tenant_id = {tenant_lit} "
        f"AND scope_id = {scope_lit} AND is_default = true)"
    )
    for m in milestones:
        target_number = m["number"] + milestone_shift
        ap("INSERT INTO worklist.milestone")
        ap("    (id, tenant_id, scope_id, number, title, kind, status, "
           "vision, mission, rank, workstream_id, conflict_token)")
        ap("SELECT gen_random_uuid(),")
        ap(f"       {tenant_lit}, {scope_lit},")
        ap(f"       {target_number}, {sql_quote(m['title'])},")
        ap("       'milestone',")
        ap(f"       {sql_quote(m['status'])},")
        ap(f"       {sql_quote(m['vision'])},")
        ap(f"       NULL, 0, {default_ws_lookup}, gen_random_uuid()")
        ap(" WHERE NOT EXISTS (SELECT 1 FROM worklist.milestone")
        ap(f"                    WHERE tenant_id = {tenant_lit}")
        ap(f"                      AND scope_id  = {scope_lit}")
        ap(f"                      AND title     = {sql_quote(m['title'])});")
    ap("")

    # -- Items ---------------------------------------------------------------
    ap("-- ----------------------------------------------------------------")
    ap("-- Items (kumbuka assignments from the 177.8 dry-run). The")
    ap("-- source's Nr is preserved as item.number; workstream/milestone/")
    ap("-- status references are resolved via lookup subqueries against")
    ap("-- the rows just planted above.")
    ap("-- ----------------------------------------------------------------")

    item_selector_lookup = (
        f"(SELECT id FROM worklist.selector "
        f"WHERE tenant_id = {tenant_lit} AND scope_id = {scope_lit} "
        f"AND token = 'item')"
    )

    kumbuka_items = [a for a in assignments if a.bucket == "assigned"]
    for a in kumbuka_items:
        row = a.row
        target_status = STATUS_MAPPING.get(row.status.strip())
        if target_status is None:
            raise SystemExit(
                f"row nr {row.nr!r} has unmapped status {row.status!r}. "
                f"The generator refuses to guess."
            )
        title = row.title.strip()
        # Cap title at 200 chars (V7 length cap).
        if len(title) > 200:
            title = title[:197] + "..."

        # Description: the Ref column carries useful narrative; take it
        # if present, otherwise blank.
        description = row.ref.strip() if row.ref else None

        # Workstream lookup: default for the catch-all, else by token.
        ws_token = _workstream_token_for_assignment(a)
        ws_lookup = (
            f"(SELECT id FROM worklist.workstream "
            f"WHERE tenant_id = {tenant_lit} AND scope_id = {scope_lit} "
            f"AND token = {sql_quote(ws_token)})"
        )
        status_lookup = (
            f"(SELECT id FROM worklist.item_status "
            f"WHERE tenant_id = {tenant_lit} AND scope_id = {scope_lit} "
            f"AND name = {sql_quote(target_status)})"
        )
        milestone_lookup = "NULL"
        if a.milestone:
            target_milestone_number = a.milestone + milestone_shift
            milestone_lookup = (
                f"(SELECT id FROM worklist.milestone "
                f"WHERE tenant_id = {tenant_lit} AND scope_id = {scope_lit} "
                f"AND number = {target_milestone_number})"
            )

        # Attributes JSONB: cluster, type, priority, size — via lookup.
        attributes_expr = _build_attributes_jsonb(
            row, tenant_lit, scope_lit
        )

        ap("INSERT INTO worklist.item")
        ap("    (id, tenant_id, scope_id, selector_id, number, title, "
           "description, status_id, milestone_id, workstream_id, "
           "attributes, conflict_token)")
        ap("SELECT gen_random_uuid(),")
        ap(f"       {tenant_lit}, {scope_lit},")
        ap(f"       {item_selector_lookup},")
        ap(f"       {row.nr},")
        ap(f"       {sql_quote(title)},")
        ap(f"       {sql_quote(description)},")
        ap(f"       {status_lookup},")
        ap(f"       {milestone_lookup},")
        ap(f"       {ws_lookup},")
        ap(f"       {attributes_expr},")
        ap("       gen_random_uuid()")
        ap(" WHERE NOT EXISTS (SELECT 1 FROM worklist.item")
        ap(f"                    WHERE tenant_id   = {tenant_lit}")
        ap(f"                      AND scope_id    = {scope_lit}")
        ap(f"                      AND selector_id = {item_selector_lookup}")
        ap(f"                      AND number      = {row.nr});")
    ap("")

    # -- Edges (deps) --------------------------------------------------------
    ap("-- ----------------------------------------------------------------")
    ap("-- Edges: item_relation with type 'depends_on' (bootstrap-declared).")
    ap("-- Only edges where BOTH ends land in kumbuka are emitted; a dep on")
    ap("-- a deferred (jbaconsult) row is filtered rather than lost silently.")
    ap("-- ----------------------------------------------------------------")
    kumbuka_nrs = {a.row.nr for a in kumbuka_items}
    edges_emitted = 0
    edges_filtered = 0
    for a in kumbuka_items:
        deps = parse_deps(a.row.deps)
        for dep in deps:
            if dep not in kumbuka_nrs:
                edges_filtered += 1
                continue
            # Both ends are here; write the edge.
            from_lookup = (
                f"(SELECT id FROM worklist.item "
                f"WHERE tenant_id = {tenant_lit} AND scope_id = {scope_lit} "
                f"AND selector_id = {item_selector_lookup} "
                f"AND number = {a.row.nr})"
            )
            to_lookup = (
                f"(SELECT id FROM worklist.item "
                f"WHERE tenant_id = {tenant_lit} AND scope_id = {scope_lit} "
                f"AND selector_id = {item_selector_lookup} "
                f"AND number = {dep})"
            )
            type_lookup = (
                f"(SELECT id FROM worklist.relation_type "
                f"WHERE tenant_id = {tenant_lit} AND scope_id = {scope_lit} "
                f"AND name = 'depends_on')"
            )
            ap("INSERT INTO worklist.item_relation")
            ap("    (tenant_id, scope_id, from_item_id, to_item_id, "
               "relation_type_id, metadata, status)")
            ap("SELECT")
            ap(f"       {tenant_lit}, {scope_lit},")
            ap(f"       {from_lookup},")
            ap(f"       {to_lookup},")
            ap(f"       {type_lookup},")
            ap("       '{}'::jsonb, 'asserted'")
            ap(" WHERE NOT EXISTS (SELECT 1 FROM worklist.item_relation")
            ap(f"                    WHERE tenant_id      = {tenant_lit}")
            ap(f"                      AND from_item_id   = {from_lookup}")
            ap(f"                      AND to_item_id     = {to_lookup}")
            ap(f"                      AND relation_type_id = {type_lookup});")
            edges_emitted += 1
    ap("")
    ap(f"-- (edges emitted: {edges_emitted}; edges filtered "
       f"[dep target not in kumbuka]: {edges_filtered})")
    ap("")

    # -- Counters ------------------------------------------------------------
    ap("-- ----------------------------------------------------------------")
    ap("-- Counters: lift the item and milestone number_space rows to the")
    ap("-- highest allocated number so the next `create` gives N+1.")
    ap("-- ----------------------------------------------------------------")
    ap("UPDATE worklist.number_space ns")
    ap("   SET high_water_mark = GREATEST(")
    ap("           ns.high_water_mark,")
    ap("           COALESCE((SELECT MAX(number) FROM worklist.item")
    ap(f"                      WHERE tenant_id = {tenant_lit}")
    ap(f"                        AND scope_id  = {scope_lit}), 0))")
    ap(f" WHERE ns.tenant_id = {tenant_lit}")
    ap(f"   AND ns.scope_id  = {scope_lit}")
    ap(f"   AND ns.workstream_id IS NULL")
    ap("   AND ns.selector_id = (SELECT id FROM worklist.selector")
    ap(f"                          WHERE tenant_id = {tenant_lit}")
    ap(f"                            AND scope_id  = {scope_lit}")
    ap("                            AND token     = 'item');")
    ap("")
    ap("UPDATE worklist.number_space ns")
    ap("   SET high_water_mark = GREATEST(")
    ap("           ns.high_water_mark,")
    ap("           COALESCE((SELECT MAX(number) FROM worklist.milestone")
    ap(f"                      WHERE tenant_id = {tenant_lit}")
    ap(f"                        AND scope_id  = {scope_lit}), 0))")
    ap(f" WHERE ns.tenant_id = {tenant_lit}")
    ap(f"   AND ns.scope_id  = {scope_lit}")
    ap(f"   AND ns.workstream_id IS NULL")
    ap("   AND ns.selector_id = (SELECT id FROM worklist.selector")
    ap(f"                          WHERE tenant_id = {tenant_lit}")
    ap(f"                            AND scope_id  = {scope_lit}")
    ap("                            AND token     = 'milestone');")
    ap("")

    # -- Self-identification INSERT -----------------------------------------
    ap("-- ----------------------------------------------------------------")
    ap("-- Self-identification: the digest + pin + row count live in a")
    ap("-- withdrawn attribute_definition (see the file header for the")
    ap("-- reason no dedicated table exists).")
    ap("-- ----------------------------------------------------------------")
    marker_name = f"sha={digest}; pin={pin_sha}; rows={row_count}"
    ap("INSERT INTO worklist.attribute_definition")
    ap("    (id, tenant_id, scope_id, key, name, type, rank, sortable, status)")
    ap("VALUES (")
    ap("    gen_random_uuid(),")
    ap(f"    {tenant_lit}, {scope_lit},")
    ap("    'corpus_import_marker',")
    ap(f"    {sql_quote(marker_name)},")
    ap("    'text', 999999, FALSE, 'withdrawn'")
    ap(");")
    ap("")

    ap("COMMIT;")
    ap("")
    ap(BODY_END_MARKER)
    return "\n".join(lines) + "\n"


def _workstream_token_for_assignment(a: Assignment) -> str:
    """Map an Assignment.workstream string to the actual token used in
    worklist.workstream. Case-folded, matches the seed above."""
    if a.workstream == CATCH_ALL_WORKSTREAM:
        return "default"
    ws_map = {
        "Produktlinie": "produktlinie",
        "Architektur": "architektur",
        "Betrieb": "betrieb",
        "Agenten-Entwicklung": "agenten-entwicklung",
        "GTM": "gtm",
    }
    token = ws_map.get(a.workstream or "")
    if not token:
        raise SystemExit(
            f"workstream {a.workstream!r} has no known token mapping."
        )
    return token


def _build_attributes_jsonb(row, tenant_lit: str, scope_lit: str) -> str:
    """Build the JSONB attribute map for one item.

    Uses jsonb_build_object with sub-selects on attribute_definition/
    attribute_option so we never hard-code UUIDs. If a source attribute
    value has no matching option under its bootstrap-seeded definition,
    the subquery returns NULL and the key gets a NULL value — a data
    problem the operator sees on verify.sql, not silently absorbed.
    """
    entries = []

    def entry(def_key: str, opt_name: str) -> str:
        if not opt_name:
            return ""
        return (
            f"(SELECT ad.id::text FROM worklist.attribute_definition ad "
            f"WHERE ad.tenant_id = {tenant_lit} AND ad.scope_id = {scope_lit} "
            f"AND ad.key = {sql_quote(def_key)}), "
            f"(SELECT ao.id::text FROM worklist.attribute_option ao "
            f"JOIN worklist.attribute_definition ad ON ao.definition_id = ad.id "
            f"WHERE ao.tenant_id = {tenant_lit} AND ao.scope_id = {scope_lit} "
            f"AND ad.key = {sql_quote(def_key)} "
            f"AND ao.name = {sql_quote(opt_name)})"
        )

    cluster = row.cluster.strip()
    if cluster:
        entries.append(entry("cluster", cluster))
    typ = row.typ.strip()
    if typ and " " not in typ:  # skip multi-word (component-like)
        entries.append(entry("type", typ))
    prio = row.prio.strip()
    if prio:
        entries.append(entry("priority", prio))
    groesse = row.groesse.strip()
    if groesse:
        entries.append(entry("size", groesse))

    entries = [e for e in entries if e]
    if not entries:
        return "'{}'::jsonb"
    return f"jsonb_build_object({', '.join(entries)})"


# ---------------------------------------------------------------------------
# Digest computation and two-pass emission
# ---------------------------------------------------------------------------

DIGEST_PLACEHOLDER = "0" * 64  # 64 hex chars; distinguishable in practice


def compute_digest(body: str) -> str:
    """sha256 of the body between the body markers (inclusive)."""
    start = body.find(BODY_BEGIN_MARKER)
    end = body.find(BODY_END_MARKER) + len(BODY_END_MARKER)
    if start < 0 or end < len(BODY_END_MARKER):
        raise SystemExit("body markers missing after emission")
    region = body[start:end]
    return hashlib.sha256(region.encode("utf-8")).hexdigest()


def generate(
    steering: Path,
    snapshot: Path,
    tenant_id: str,
    scope_id: str,
    out: Path,
) -> tuple[str, int]:
    snap = load_snapshot_env(snapshot)
    pin_sha = verify_snapshot(steering, snap)

    worklist_bytes = git_show_bytes(steering, pin_sha, "WORKLIST.md")
    worklist_text = worklist_bytes.decode("utf-8")
    rows = parse_worklist_bytes(worklist_bytes)
    assignments = assign_all(rows)
    self_check(rows, assignments)
    check_no_open_row_in_catchall(assignments)

    milestones = parse_milestones(worklist_text)

    kumbuka_count = sum(1 for a in assignments if a.bucket == "assigned")

    # Pass 1: emit body with placeholder digest.
    body_pass1 = emit_body(tenant_id, scope_id, milestones, assignments,
                           pin_sha, DIGEST_PLACEHOLDER, kumbuka_count)
    real_digest = compute_digest(
        body_pass1.replace(DIGEST_PLACEHOLDER, DIGEST_PLACEHOLDER)
    )

    # Compute the digest that will hold after substitution: the digest is
    # over the body with the FINAL digest embedded, not the placeholder.
    # We solve this by defining "the digest excludes the digest string
    # itself" — the body is hashed with the placeholder still in it, and
    # then substituted. The digest thus represents the *shape* of the
    # transaction, not the substituted content. Verifier reproduces the
    # same trick: reads the body region, replaces the recorded digest
    # (extracted from the marker INSERT) with the placeholder, then
    # hashes.
    body_final = body_pass1.replace(DIGEST_PLACEHOLDER, real_digest)

    header = emit_header(real_digest, pin_sha, kumbuka_count)
    full = header + body_final

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(full)
    return real_digest, kumbuka_count


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--steering", required=True, type=Path)
    ap.add_argument("--snapshot", required=True, type=Path)
    ap.add_argument("--tenant-id", required=True)
    ap.add_argument("--scope-id", required=True)
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()

    digest, rows = generate(
        args.steering, args.snapshot,
        args.tenant_id, args.scope_id, args.out,
    )
    print(f"wrote {args.out}", file=sys.stderr)
    print(f"  digest: {digest}", file=sys.stderr)
    print(f"  rows:   {rows}", file=sys.stderr)
    print(f"  bytes:  {args.out.stat().st_size}", file=sys.stderr)


if __name__ == "__main__":
    main()
