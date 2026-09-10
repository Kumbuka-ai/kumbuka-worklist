#!/usr/bin/env python3
"""Generate the transactional corpus-import SQL file (Sprint 177.9,
sharpened 177.10).

Reads the pinned source (snapshot.env + WORKLIST.md via git show) and the
177.8 assignment rule (imported from dry_run.py), emits a single-file
one-transaction SQL script that the operator applies on the host where
Flyway runs.

The script does NOT connect to any database. It only writes a file.

177.10: the emitted file uses `psql` variables `:'tenant_id'` and
`:'scope_id'` for tenant and scope, so the file's digest is fixed and
independent of which scope it is applied to. The operator passes both
UUIDs at the psql invocation.

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

Invocation of the generator (produces the file; no UUIDs):
    python3 generate_import.py \\
        --steering /path/to/steering \\
        --snapshot ./snapshot.env \\
        --out       ./out/import.sql

Invocation of the file (the operator, on the host):
    psql -v ON_ERROR_STOP=on \\
         -v tenant_id=<uuid> \\
         -v scope_id=<uuid> \\
         -f import.sql
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
# - planned    → mapped to `open` (actionable=T). Ratified 177.10:
#                the running iteration does NOT travel with this import
#                — the operator opens a fresh iteration in the new
#                service — so a row that was `planned` in the
#                predecessor arrives with no iteration membership, and
#                the derived `planned` view (TAR-0002 §4: "planned is
#                derived and never stored") holds nothing new. Mapping
#                to `open` is therefore correct and not a loss: there
#                is nothing to derive from.
# - observing  → analog to `new` (all four predicates false). The row
#                is neither actionable nor closed; a mediator between
#                open and terminal. Ratified 177.10.
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

def emit_header(
    sha_placeholder: str,
    pin_sha: str,
    row_count: int,
    id_ref_count: int,
    truncated_title_count: int,
    edge_count: int,
    prose_row_count: int,
    reference_entry_count: int,
) -> str:
    return f"""\
-- ===========================================================================
-- Corpus import for scope kumbuka (Sprint 178.2)
--
-- Generated by generate_import.py from
--   steering source: {pin_sha}
--   dry-run rule:    177.5 → 177.8 → 178.1 (REA-0007 §3, `dropped` terminal)
--                    → 178.2 (Prosa nach description, Zeiger in item_reference)
--
-- Digest of body (sha256):
--   {sha_placeholder}
--
-- ---------------------------------------------------------------------------
-- Erwartete Zahlen — verify.sql liest sie als psql-Variablen und vergleicht.
-- Ein Wert, der hier steht und dort abweicht, ist ein Fehler, keine Warnung.
-- ---------------------------------------------------------------------------
--   expected_rows              = {row_count}
--   expected_id_refs           = {id_ref_count}
--   expected_truncated_titles  = {truncated_title_count}
--   expected_edges             = {edge_count}
--   expected_prose_rows        = {prose_row_count}
--   expected_reference_entries = {reference_entry_count}
--
-- ---------------------------------------------------------------------------
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


@dataclasses.dataclass
class BodyStats:
    row_count: int
    id_ref_count: int
    truncated_title_count: int
    edge_count: int
    prose_row_count: int          # 178.2: Items, deren description Prosa traegt
    reference_entry_count: int    # 178.2: Gesamtzahl der item_reference-Zeilen


TITLE_CAP = 200
TITLE_ELLIPSIS = "…"  # single-character ellipsis, char_length = 1


def truncate_title_at_word_boundary(title: str) -> tuple[str, bool]:
    """Return (kept_title, was_truncated).

    Cap follows V7 (`char_length`, so one grapheme per char). If the
    title fits inside TITLE_CAP unchanged, the caller writes NULL as
    the description. If it does not, the title is cut at the last
    whitespace on the left of TITLE_CAP - 1 so that the appended
    ellipsis brings the result up to at most TITLE_CAP characters.
    Word boundaries are whitespace; if none is available in the
    admissible prefix, the title is cut hard at TITLE_CAP - 1.
    """
    if len(title) <= TITLE_CAP:
        return title, False
    # Room for the ellipsis (one char). Find the last whitespace <=
    # TITLE_CAP - 1 so that <prefix> + "…" has char_length <= TITLE_CAP.
    prefix_limit = TITLE_CAP - 1
    prefix = title[:prefix_limit]
    cut = prefix.rfind(" ")
    if cut <= 0:
        # No word boundary in the admissible prefix — cut hard.
        kept = prefix.rstrip()
    else:
        kept = prefix[:cut].rstrip()
    return kept + TITLE_ELLIPSIS, True


REFERENCE_SEGMENT_SEPARATOR = " · "  # " · "


def split_ref_segments(ref: str) -> list[str]:
    """Split a Ref cell on ` · `, trim each, drop empties."""
    if not ref:
        return []
    segs = ref.split(REFERENCE_SEGMENT_SEPARATOR)
    return [s.strip() for s in segs if s and s.strip()]


# btree v4 packt eine Zeile in max. 1/3 einer 8k-Page. `target` ist Teil des
# btree-Index `idx_item_reference_target (tenant_id, scope_id, target)` und
# reisst spaetestens hier — bevor er es tut, halten wir laut an (Auftrag
# 178.1, Stoppbedingung). Die Zahl ist keine Wahl: sie ist der gemessene
# Postgres-Fehler `index row size N exceeds btree version 4 maximum 2704`.
BTREE_V4_MAX_TARGET_BYTES = 2704


class ReferenceTargetTooLarge(SystemExit):
    """Ein target uebersteigt den btree-Index-Deckel des Zielschemas.
    Enthaelt die Liste der Vorfaelle fuer die Rueckgabe.
    """


# 178.2 Prosa-Regel: ein Ref-Segment mit einer UTF-8-Laenge ueber diesem
# Schwellenwert ist Prosa und wandert nach `item.description`. Alles darunter
# bleibt Referenz-Eintrag in `worklist.item_reference`. Die Zahl ist eine
# runde Zahl mit Abstand zum btree-v4-Deckel (2704 Byte); sie ist eine Regel
# ueber Groesse, keine Klassifikation ueber Inhalt.
PROSE_THRESHOLD_BYTES = 2000


def build_reference_targets(row) -> tuple[list[str], list[str]]:
    """Return (references, prose_segments) for one source row.

    Assembly order (dispatch 178.1 §Referenzen, verschaerft 178.2):
      a) the old id (`CHORE-319`, …) if the source carries one — always
         short, so always a reference. Sits at ordinal 0.
      b) the segments of the Ref column, split on ` · `, trimmed, empties
         dropped. A segment whose UTF-8 length exceeds
         PROSE_THRESHOLD_BYTES is Prosa and moves into the returned
         `prose_segments` list, IN SOURCE ORDER. Every other segment
         stays a reference, IN SOURCE ORDER, on the following ordinals.
      c) if the Scope column is non-empty, one entry
         `component: <raw tokens>` — always short, so always a reference.

    The returned reference list is contiguous and forms the emitted
    item_reference rows with `ordinal = 0..k-1`. The returned prose list
    is used by emit_body to compose `description` under the four-case
    table of 178.2.
    """
    references: list[str] = []
    prose: list[str] = []
    ident = (row.ident or "").strip()
    if ident:
        references.append(ident)
    for seg in split_ref_segments(row.ref or ""):
        if len(seg.encode("utf-8")) > PROSE_THRESHOLD_BYTES:
            prose.append(seg)
        else:
            references.append(seg)
    scope = (row.scope or "").strip()
    if scope:
        references.append(f"component: {scope}")
    return references, prose


def build_description(
    original_title: str,
    was_truncated: bool,
    prose_segments: list[str],
) -> str | None:
    """Compose `item.description` per the four-case table (178.2 §2).

    - gekappter Titel, keine Prosa        -> voller Originaltitel
    - keine Kappung, Prosa                -> Prosa-Segmente, durch Leerzeile
    - gekappter Titel und Prosa           -> Titel, Leerzeile, '---',
                                             Leerzeile, dann Prosa
    - sonst                               -> None (SQL NULL)
    """
    if was_truncated and not prose_segments:
        return original_title
    if not was_truncated and prose_segments:
        return "\n\n".join(prose_segments)
    if was_truncated and prose_segments:
        return (
            original_title
            + "\n\n---\n\n"
            + "\n\n".join(prose_segments)
        )
    return None


def emit_body(
    tenant_ref: str,
    scope_ref: str,
    milestones: list[dict],
    assignments: list[Assignment],
    pin_sha: str,
    digest: str,
) -> tuple[str, BodyStats]:
    """Emit the transactional body between BODY_BEGIN_MARKER and
    BODY_END_MARKER. Called twice: once with digest='<sha256_placeholder>'
    to compute the actual sha, once with the real digest.

    177.10: `tenant_ref` and `scope_ref` are the psql-var references
    (`:'tenant_id'` and `:'scope_id'`), not UUID literals. The emitted
    file is scope-independent; the operator binds both at psql call.

    178.1: the source's Nr is discarded. Assigned rows are sorted by
    their old Nr and dense-numbered 1..N; the old id (if any), the
    Ref segments and, if present, the component token land in
    `worklist.item_reference`. Descriptions are NULL unless the title
    was truncated, in which case the description carries the full
    original title.
    """
    lines: list[str] = []
    ap = lines.append

    tenant_lit = tenant_ref
    scope_lit = scope_ref

    ap(BODY_BEGIN_MARKER)
    ap("")
    ap("BEGIN;")
    ap("")
    ap("-- RLS binds the migrator too (V3 FORCE), so every DML in this file")
    ap("-- runs under app.tenant_id bound to the caller-supplied :'tenant_id'.")
    ap(f"SELECT set_config('app.tenant_id', {tenant_lit}, true);")
    ap("")
    ap("-- The scope id lives in its own transaction-local GUC so the")
    ap("-- idempotency DO-block below (where psql variables are NOT")
    ap("-- substituted) can read it via current_setting.")
    ap(f"SELECT set_config('kw_import.scope_id', {scope_lit}, true);")
    ap("")

    # -- Idempotency check ---------------------------------------------------
    ap("-- ----------------------------------------------------------------")
    ap("-- Idempotency: refuse a second application (typed error).")
    ap("-- ----------------------------------------------------------------")
    ap("DO $$")
    ap("DECLARE")
    ap("    v_scope UUID := current_setting('kw_import.scope_id')::uuid;")
    ap("    existing_name TEXT;")
    ap("BEGIN")
    ap("    SELECT name INTO existing_name")
    ap("      FROM worklist.attribute_definition")
    ap("     WHERE scope_id = v_scope")
    ap("       AND key      = 'corpus_import_marker';")
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
    # 177.10: bootstrap no longer seeds the three markers (REA-0007 §4);
    # milestone numbers start at 1, so M1..Mn in the source land at 1..n.
    milestone_shift = 0
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
    ap("-- Items (kumbuka assignments from the 177.8 dry-run).")
    ap("--")
    ap("-- 178.1: the source's Nr is DROPPED. Items get a dense new number")
    ap("-- 1..N in ascending order of the old Nr. The Nr lives only in an")
    ap("-- internal map (nr → new_number) used to rewrite edges; no field")
    ap("-- of the target carries it. The old id (CHORE-319 etc.) survives")
    ap("-- as the first entry in item_reference, the Ref segments follow,")
    ap("-- and if the source's Scope column is non-empty one further")
    ap("-- 'component: <raw>' entry is appended. Titles longer than 200")
    ap("-- chars (V7 char_length cap) are cut at the last word boundary")
    ap("-- and end with '…'; the description then carries the full")
    ap("-- original title. In every other case description is NULL.")
    ap("-- ----------------------------------------------------------------")

    item_selector_lookup = (
        f"(SELECT id FROM worklist.selector "
        f"WHERE tenant_id = {tenant_lit} AND scope_id = {scope_lit} "
        f"AND token = 'item')"
    )

    # Dense renumber: sort the assigned rows by the source Nr (numeric
    # ascending) and hand out 1..N. The Nr is a string in the source,
    # so the sort key is int(nr).
    kumbuka_items = [a for a in assignments if a.bucket == "assigned"]
    try:
        kumbuka_items.sort(key=lambda a: int(a.row.nr))
    except ValueError as e:
        raise SystemExit(
            f"assigned row carries a non-integer Nr and cannot be sorted "
            f"for dense renumbering: {e}"
        )
    nr_to_new_number: dict[str, int] = {}
    for new_number, a in enumerate(kumbuka_items, start=1):
        nr_to_new_number[a.row.nr] = new_number

    id_ref_count = 0
    truncated_title_count = 0
    prose_row_count = 0
    reference_entry_count = 0
    # Per-item reference lists, keyed by new_number, so the item_reference
    # INSERTs later can find each item by its new number.
    references_per_item: list[tuple[int, list[str]]] = []

    for a in kumbuka_items:
        row = a.row
        new_number = nr_to_new_number[row.nr]
        target_status = STATUS_MAPPING.get(row.status.strip())
        if target_status is None:
            raise SystemExit(
                f"row nr {row.nr!r} has unmapped status {row.status!r}. "
                f"The generator refuses to guess."
            )
        original_title = row.title.strip()
        kept_title, was_truncated = truncate_title_at_word_boundary(
            original_title
        )
        if was_truncated:
            truncated_title_count += 1

        references, prose_segments = build_reference_targets(row)
        if prose_segments:
            prose_row_count += 1
        reference_entry_count += len(references)

        description = build_description(
            original_title, was_truncated, prose_segments
        )

        if row.ident and row.ident.strip():
            id_ref_count += 1
        references_per_item.append((new_number, references))

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
        ap(f"       {new_number},")
        ap(f"       {sql_quote(kept_title)},")
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
        ap(f"                      AND number      = {new_number});")
    ap("")

    # -- Item references -----------------------------------------------------
    ap("-- ----------------------------------------------------------------")
    ap("-- item_reference: one row per external pointer, in the order the")
    ap("-- generator listed them. Empty target lists produce no INSERT.")
    ap("-- Ordinals start at 0 (V4 says >= 0) and are dense per item.")
    ap("-- ----------------------------------------------------------------")
    for new_number, targets in references_per_item:
        if not targets:
            continue
        item_lookup = (
            f"(SELECT id FROM worklist.item "
            f"WHERE tenant_id = {tenant_lit} AND scope_id = {scope_lit} "
            f"AND selector_id = {item_selector_lookup} "
            f"AND number = {new_number})"
        )
        for ordinal, target in enumerate(targets):
            ap("INSERT INTO worklist.item_reference")
            ap("    (id, tenant_id, scope_id, item_id, ordinal, target, "
               "status)")
            ap("SELECT gen_random_uuid(),")
            ap(f"       {tenant_lit}, {scope_lit},")
            ap(f"       {item_lookup},")
            ap(f"       {ordinal},")
            ap(f"       {sql_quote(target)},")
            ap("       'asserted';")
    ap("")

    # -- Edges (deps) --------------------------------------------------------
    ap("-- ----------------------------------------------------------------")
    ap("-- Edges: item_relation with type 'depends_on' (bootstrap-declared).")
    ap("-- Only edges where BOTH ends land in kumbuka are emitted; a dep on")
    ap("-- a deferred (jbaconsult) row is filtered rather than lost silently.")
    ap("-- Both ends are looked up by the NEW dense number, resolved from")
    ap("-- the source Nr via the internal map.")
    ap("-- ----------------------------------------------------------------")
    edges_emitted = 0
    edges_filtered = 0
    for a in kumbuka_items:
        deps = parse_deps(a.row.deps)
        from_new = nr_to_new_number[a.row.nr]
        for dep in deps:
            if dep not in nr_to_new_number:
                edges_filtered += 1
                continue
            to_new = nr_to_new_number[dep]
            from_lookup = (
                f"(SELECT id FROM worklist.item "
                f"WHERE tenant_id = {tenant_lit} AND scope_id = {scope_lit} "
                f"AND selector_id = {item_selector_lookup} "
                f"AND number = {from_new})"
            )
            to_lookup = (
                f"(SELECT id FROM worklist.item "
                f"WHERE tenant_id = {tenant_lit} AND scope_id = {scope_lit} "
                f"AND selector_id = {item_selector_lookup} "
                f"AND number = {to_new})"
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
    ap("-- INSERT ... ON CONFLICT because the bootstrap does NOT plant an")
    ap("-- item or iteration counter row (it says so in a comment): the")
    ap("-- service creates the row at its first allocation. Here we need")
    ap("-- it now, so we INSERT — and if the service or an earlier run")
    ap("-- already planted one, DO UPDATE lifts it.")
    ap("-- ----------------------------------------------------------------")
    for selector_token, source_table in [
        ("item", "item"),
        ("milestone", "milestone"),
    ]:
        ap("INSERT INTO worklist.number_space")
        ap("    (id, tenant_id, scope_id, selector_id, workstream_id, "
           "high_water_mark)")
        ap("SELECT gen_random_uuid(),")
        ap(f"       {tenant_lit}, {scope_lit},")
        ap(f"       (SELECT id FROM worklist.selector "
           f"WHERE tenant_id = {tenant_lit} AND scope_id = {scope_lit} "
           f"AND token = '{selector_token}'),")
        ap("       NULL,")
        ap(f"       COALESCE((SELECT MAX(number) FROM worklist.{source_table} "
           f"WHERE tenant_id = {tenant_lit} AND scope_id = {scope_lit}), 0)")
        ap("ON CONFLICT (tenant_id, scope_id, selector_id) "
           "WHERE workstream_id IS NULL")
        ap("DO UPDATE SET high_water_mark = GREATEST(")
        ap("    worklist.number_space.high_water_mark,")
        ap("    EXCLUDED.high_water_mark);")
        ap("")

    # -- Self-identification INSERT -----------------------------------------
    ap("-- ----------------------------------------------------------------")
    ap("-- Self-identification: the digest + pin + row count live in a")
    ap("-- withdrawn attribute_definition (see the file header for the")
    ap("-- reason no dedicated table exists).")
    ap("-- ----------------------------------------------------------------")
    marker_name = f"sha={digest}; pin={pin_sha}; rows={len(kumbuka_items)}"
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

    stats = BodyStats(
        row_count=len(kumbuka_items),
        id_ref_count=id_ref_count,
        truncated_title_count=truncated_title_count,
        edge_count=edges_emitted,
        prose_row_count=prose_row_count,
        reference_entry_count=reference_entry_count,
    )
    return "\n".join(lines) + "\n", stats


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


def _assert_reference_targets_fit(kumbuka_items: list[Assignment]) -> None:
    """Bricht laut ab, wenn ein TATSAECHLICH als Referenz emittiertes target
    die btree-v4-Zeilengrenze (2704 Byte) uebersteigt.

    178.2: nach der Prosa-Regel (Segmente > PROSE_THRESHOLD_BYTES gehen nach
    description) muss der Waechter still bleiben. Feuert er trotzdem, ist
    das ein Fehler in der Regel — laut zurueckgeben, nicht umgehen."""
    oversize: list[tuple[int, str, str, str]] = []  # bytes, nr, ident, head
    for a in kumbuka_items:
        references, _prose = build_reference_targets(a.row)
        for target in references:
            n = len(target.encode("utf-8"))
            if n > BTREE_V4_MAX_TARGET_BYTES:
                oversize.append((n, a.row.nr, a.row.ident, target[:120]))
    if not oversize:
        return
    oversize.sort(reverse=True)
    lines = [
        f"STOPP (Auftrag 178.2): {len(oversize)} als Referenz vorgesehene "
        f"item_reference.target-Werte uebersteigen den btree-v4-Zeilendeckel",
        f"({BTREE_V4_MAX_TARGET_BYTES} Byte) TROTZ der Prosa-Regel",
        f"(> {PROSE_THRESHOLD_BYTES} Byte -> description). Regel oder ihre",
        "Anwendung ist defekt. Emission haelt hier an.",
        "",
        f"{'bytes':>6}  {'nr':>4}  {'ident':16s} {'target head'}",
    ]
    for size, nr, ident, head in oversize[:20]:
        lines.append(f"{size:>6}  {nr:>4}  {ident:16s} {head}")
    if len(oversize) > 20:
        lines.append(f"  ... und {len(oversize) - 20} weitere.")
    raise ReferenceTargetTooLarge("\n".join(lines))


def generate(
    steering: Path,
    snapshot: Path,
    out: Path,
) -> tuple[str, BodyStats]:
    snap = load_snapshot_env(snapshot)
    pin_sha = verify_snapshot(steering, snap)

    worklist_bytes = git_show_bytes(steering, pin_sha, "WORKLIST.md")
    worklist_text = worklist_bytes.decode("utf-8")
    rows = parse_worklist_bytes(worklist_bytes)
    assignments = assign_all(rows)
    self_check(rows, assignments)
    check_no_open_row_in_catchall(assignments)

    milestones = parse_milestones(worklist_text)

    # 178.1 Stoppbedingung: bevor die Datei geschrieben wird, prueft der
    # Generator jedes vorgesehene item_reference.target gegen den btree-v4-
    # Zeilenlimit des Ziel-Index (idx_item_reference_target). Reisst der
    # Deckel bei mindestens einem Segment, halten wir laut an — der Auftrag
    # verbietet, Inhalte zu kuerzen oder das Schema anzupassen.
    _assert_reference_targets_fit(
        [a for a in assignments if a.bucket == "assigned"]
    )

    # 177.10: tenant and scope are psql variables; the emitted file is
    # UUID-independent. Pass sentinel strings that expand to `:'tenant_id'`
    # and `:'scope_id'` in every INSERT.
    tenant_ref = ":'tenant_id'"
    scope_ref = ":'scope_id'"

    # Pass 1: emit body with placeholder digest.
    body_pass1, stats = emit_body(
        tenant_ref, scope_ref, milestones, assignments,
        pin_sha, DIGEST_PLACEHOLDER,
    )
    real_digest = compute_digest(body_pass1)

    body_final = body_pass1.replace(DIGEST_PLACEHOLDER, real_digest)
    header = emit_header(
        real_digest, pin_sha,
        row_count=stats.row_count,
        id_ref_count=stats.id_ref_count,
        truncated_title_count=stats.truncated_title_count,
        edge_count=stats.edge_count,
        prose_row_count=stats.prose_row_count,
        reference_entry_count=stats.reference_entry_count,
    )
    full = header + body_final

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(full)
    return real_digest, stats


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--steering", required=True, type=Path)
    ap.add_argument("--snapshot", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()

    digest, stats = generate(args.steering, args.snapshot, args.out)
    print(f"wrote {args.out}", file=sys.stderr)
    print(f"  digest:              {digest}", file=sys.stderr)
    print(f"  rows:                {stats.row_count}", file=sys.stderr)
    print(f"  id_refs:             {stats.id_ref_count}", file=sys.stderr)
    print(f"  truncated_titles:    {stats.truncated_title_count}", file=sys.stderr)
    print(f"  edges:               {stats.edge_count}", file=sys.stderr)
    print(f"  prose_rows:          {stats.prose_row_count}", file=sys.stderr)
    print(f"  reference_entries:   {stats.reference_entry_count}", file=sys.stderr)
    print(f"  bytes:               {args.out.stat().st_size}", file=sys.stderr)


if __name__ == "__main__":
    main()
