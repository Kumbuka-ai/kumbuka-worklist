#!/usr/bin/env bash
#
# verify_import.sh — Mechanismus-Probe fuer den corpus-import bench,
# Sprint 178.1. Faehrt gegen einen ephemeren Postgres-16 unter der
# Migrator-Rolle (NOSUPERUSER, NOBYPASSRLS) durch:
#
#   PROBE V13   V13-Migration schaltet den Marker-INSERT frei (rot vor,
#               gruen nach).
#   PROBE 1     Import einspielen, verify.sql gruen.
#   PROBE 2     Zweites Einspielen: typisierter Abbruch (Idempotenz).
#   PROBE 3     Selbstidentifikation: veraenderte Datei hashiert anders.
#   PROBE 4     Transaktionsgrenze: Fehler in der Mitte -> Rollback.
#   PROBE 5     reset-scope.sql, danach Bootstrap+Import erneut, verify.sql
#               gruen (das Reset-Skript raeumt den Scope so weit, dass ein
#               Neuversuch sauber laeuft).
#   PROBE R1    Importdatei mit doppelter Nummer -> V2 (Dichte) rot.
#   PROBE R2    Importdatei ohne ID-Referenzen  -> V4 rot.
#   PROBE R3    Gekappte Titel ohne description  -> V5 rot.
#   PROBE R4    Falscher expected_digest        -> V0 rot vor jeder anderen
#               Pruefung.
#   PROBE R5    Reset laesst scope_setting stehen; zweiter Bootstrap rot mit
#               beobachtetem Wortlaut.
#
# Voraussetzungen: docker, python3.

set -euo pipefail

CONTAINER=kw-test-pg-178-1
POSTGRES_PW=super
MIGRATOR_ROLE=worklist_migrator
MIGRATOR_PW=migrpw
DB=kumbuka

cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "== starting postgres:16 =="
docker run -d --name "$CONTAINER" \
    -e POSTGRES_PASSWORD=$POSTGRES_PW \
    -e POSTGRES_DB=$DB \
    postgres:16 >/dev/null

for i in $(seq 1 30); do
    if docker exec "$CONTAINER" pg_isready -U postgres -d $DB >/dev/null 2>&1; then
        break
    fi
    sleep 1
done

SU="docker exec -e PGPASSWORD=$POSTGRES_PW -i $CONTAINER psql -U postgres -d $DB -v ON_ERROR_STOP=on"
MG="docker exec -e PGPASSWORD=$MIGRATOR_PW -i $CONTAINER psql -U $MIGRATOR_ROLE -d $DB -v ON_ERROR_STOP=on"

echo "== creating migrator role (NOSUPERUSER, NOBYPASSRLS, CREATEROLE) =="
$SU <<SQL >/dev/null
CREATE ROLE $MIGRATOR_ROLE LOGIN NOSUPERUSER NOBYPASSRLS CREATEROLE
    PASSWORD '$MIGRATOR_PW';
GRANT ALL PRIVILEGES ON DATABASE $DB TO $MIGRATOR_ROLE;
ALTER SCHEMA public OWNER TO $MIGRATOR_ROLE;
SQL

echo "== migrator role attributes (from pg_roles) =="
$SU -tA <<SQL
SELECT format('rolsuper=%s | rolbypassrls=%s | rolcreaterole=%s',
              rolsuper, rolbypassrls, rolcreaterole)
  FROM pg_roles WHERE rolname = '$MIGRATOR_ROLE';
SQL

echo ""
echo "== running Flyway migrations V1..V12 as $MIGRATOR_ROLE =="
MIGRATION_DIR=../../src/main/resources/db/migration
for f in $(ls "$MIGRATION_DIR"/V*.sql | sort -V | grep -v V13); do
    echo "  -- $(basename $f)"
    $MG < "$f" >/dev/null
done

# ---------------------------------------------------------------------------
# PROBE V13 — rot vor, gruen nach
# ---------------------------------------------------------------------------

echo ""
echo "=========================================================="
echo "PROBE V13 — rot vor der Migration, gruen danach"
echo "=========================================================="

TENANT_V13=$(python3 -c "import uuid; print(uuid.uuid4())")
SCOPE_V13=$(python3 -c "import uuid; print(uuid.uuid4())")

$MG <<SQL >/dev/null
BEGIN;
SELECT set_config('app.tenant_id', '$TENANT_V13', true);
INSERT INTO worklist.selector (tenant_id, scope_id, token) VALUES
    ('$TENANT_V13', '$SCOPE_V13', 'item'),
    ('$TENANT_V13', '$SCOPE_V13', 'iteration'),
    ('$TENANT_V13', '$SCOPE_V13', 'milestone'),
    ('$TENANT_V13', '$SCOPE_V13', 'workstream');
INSERT INTO worklist.workstream (tenant_id, scope_id, number, token, description, is_default)
VALUES ('$TENANT_V13', '$SCOPE_V13', 1, 'default',
        'red-probe default workstream', true);
COMMIT;
SQL

MARKER_SQL="
BEGIN;
SELECT set_config('app.tenant_id', '$TENANT_V13', true);
INSERT INTO worklist.milestone (tenant_id, scope_id, number, title, kind, status, rank)
VALUES ('$TENANT_V13', '$SCOPE_V13', 1, 'Noch nicht bewertet', 'not_assessed', 'planned', 10);
COMMIT;
"

echo "  -- vor V13: synthetischer Marker-INSERT ohne workstream_id --"
echo "$MARKER_SQL" | $MG > /tmp/pre-v13.out 2>&1 || true
if grep -q 'null value in column "workstream_id"' /tmp/pre-v13.out; then
    echo "  OK rot mit erwartetem Wortlaut:"
    grep "null value" /tmp/pre-v13.out | head -1
else
    echo "  FAIL — V13-Probe hat den Defekt nicht getroffen:"
    cat /tmp/pre-v13.out
    exit 1
fi

echo "  -- applying V13 --"
$MG < "$MIGRATION_DIR"/V13__milestone_workstream_id_nullable.sql >/dev/null

echo "  -- nach V13: derselbe Marker-INSERT --"
echo "$MARKER_SQL" | $MG > /tmp/post-v13.out 2>&1
if grep -q "ERROR" /tmp/post-v13.out; then
    echo "  FAIL — Marker-INSERT nach V13 noch immer rot:"
    cat /tmp/post-v13.out
    exit 1
fi
echo "  OK gruen — Marker-INSERT geht mit NULL workstream_id durch"

# Aufraeumen, damit die spaeteren Proben nicht damit kollidieren.
$MG <<SQL >/dev/null
DELETE FROM worklist.milestone    WHERE tenant_id = '$TENANT_V13';
DELETE FROM worklist.workstream   WHERE tenant_id = '$TENANT_V13';
DELETE FROM worklist.number_space WHERE tenant_id = '$TENANT_V13';
DELETE FROM worklist.selector     WHERE tenant_id = '$TENANT_V13';
SQL

# ---------------------------------------------------------------------------
# Bootstrap kumbuka (marker-free 177.10 version) fuer den Hauptscope.
# ---------------------------------------------------------------------------

TENANT_ID=$(python3 -c "import uuid; print(uuid.uuid4())")
SCOPE_ID=$(python3 -c "import uuid; print(uuid.uuid4())")
BOOTSTRAP=../../src/main/resources/db/bootstrap/bootstrap-scope.sql

echo ""
echo "== running bootstrap-scope.sql for kumbuka =="
{ echo "SELECT set_config('app.tenant_id', '$TENANT_ID', false);"; cat "$BOOTSTRAP"; } | \
    $MG -v tenant_id="$TENANT_ID" -v scope_id="$SCOPE_ID" >/dev/null

# ---------------------------------------------------------------------------
# Generate the file und Erwartungswerte extrahieren.
# ---------------------------------------------------------------------------

echo ""
echo "== generating import.sql (UUID-independent) =="
GEN_OUT=/tmp/gen-out-178-1.txt
python3 generate_import.py \
    --steering /Users/johannes/Work/kumbuka.ai/dev/steering \
    --snapshot ./snapshot.env \
    --out       ./out/import.sql 2> >(tee "$GEN_OUT" >&2)

DIGEST=$(awk '/digest:/ {print $2}' "$GEN_OUT")
EXPECTED_ROWS=$(awk '/^  rows:/ {print $2}' "$GEN_OUT")
EXPECTED_ID_REFS=$(awk '/^  id_refs:/ {print $2}' "$GEN_OUT")
EXPECTED_TRUNC=$(awk '/^  truncated_titles:/ {print $2}' "$GEN_OUT")
EXPECTED_EDGES=$(awk '/^  edges:/ {print $2}' "$GEN_OUT")
BYTES=$(wc -c < ./out/import.sql | tr -d ' ')

echo "  digest:            $DIGEST"
echo "  expected rows:     $EXPECTED_ROWS"
echo "  expected id_refs:  $EXPECTED_ID_REFS"
echo "  expected truncs:   $EXPECTED_TRUNC"
echo "  expected edges:    $EXPECTED_EDGES"
echo "  bytes:             $BYTES"

verify_sql() {
    # $1 = digest to verify against
    $MG \
        -v tenant_id="$TENANT_ID" \
        -v scope_id="$SCOPE_ID" \
        -v expected_digest="$1" \
        -v expected_rows="$EXPECTED_ROWS" \
        -v expected_id_refs="$EXPECTED_ID_REFS" \
        -v expected_truncated_titles="$EXPECTED_TRUNC" \
        -v expected_edges="$EXPECTED_EDGES" \
        -f verify.sql
}

# ---------------------------------------------------------------------------
# PROBE 1 — Kontrolllauf: Import + verify.sql gruen.
# ---------------------------------------------------------------------------

echo ""
echo "=========================================================="
echo "PROBE 1 — Import einspielen und verify.sql gruen"
echo "=========================================================="
$MG -v tenant_id="$TENANT_ID" -v scope_id="$SCOPE_ID" < ./out/import.sql >/dev/null
echo "  import.sql applied cleanly"

verify_sql "$DIGEST" > /tmp/verify-1.out 2>&1
if grep -qE "ERROR|EXCEPTION" /tmp/verify-1.out; then
    echo "  FAIL verify.sql rot:"
    cat /tmp/verify-1.out
    exit 1
fi
grep -E "NOTICE|V[0-9]" /tmp/verify-1.out | head -30
echo "  OK verify.sql gruen"

# ---------------------------------------------------------------------------
# PROBE 2 — Idempotenz: zweites Einspielen refused.
# ---------------------------------------------------------------------------

echo ""
echo "=========================================================="
echo "PROBE 2 — rot: zweites Einspielen"
echo "=========================================================="
$MG -v tenant_id="$TENANT_ID" -v scope_id="$SCOPE_ID" < ./out/import.sql > /tmp/second-run.out 2>&1 || true
if grep -q "already applied" /tmp/second-run.out; then
    echo "  OK typisierter Abbruch:"
    grep "already applied" /tmp/second-run.out | head -1
else
    echo "  FAIL"
    cat /tmp/second-run.out
    exit 1
fi

# ---------------------------------------------------------------------------
# PROBE 3 — Selbstidentifikation der Datei.
# ---------------------------------------------------------------------------

echo ""
echo "=========================================================="
echo "PROBE 3 — rot: veraenderte Datei hashiert anders"
echo "=========================================================="
cp out/import.sql out/import-tampered.sql
sed -i.bak 's/Konzept- und Architekturarbeit/TAMPERED description/' out/import-tampered.sql
TAMPERED_DIGEST=$(awk "/corpus-import-body-begin/,/corpus-import-body-end/" out/import-tampered.sql | \
    sed "s/$DIGEST/0000000000000000000000000000000000000000000000000000000000000000/g" | \
    shasum -a 256 | awk '{print $1}')
if [[ "$TAMPERED_DIGEST" == "$DIGEST" ]]; then
    echo "  FAIL tamper undetected"
    exit 1
fi
echo "  OK: header claims $DIGEST"
echo "                tampered body hashes to $TAMPERED_DIGEST"

# ---------------------------------------------------------------------------
# PROBE 4 — Transaktionsgrenze.
# ---------------------------------------------------------------------------

echo ""
echo "=========================================================="
echo "PROBE 4 — rot: Fehler in Mitte -> Rollback"
echo "=========================================================="
SCOPE_TXN=$(python3 -c "import uuid; print(uuid.uuid4())")
{ echo "SELECT set_config('app.tenant_id', '$TENANT_ID', false);"; cat "$BOOTSTRAP"; } | \
    $MG -v tenant_id="$TENANT_ID" -v scope_id="$SCOPE_TXN" >/dev/null

python3 <<PY
import re
text = open('out/import.sql').read()
matches = list(re.finditer(r'INSERT INTO worklist\.item$', text, flags=re.MULTILINE))
assert len(matches) >= 200, f'only {len(matches)} item inserts'
pos = matches[199].start()
text = text[:pos] + 'SELECT bogus_column_that_does_not_exist ' + text[pos + len('INSERT INTO worklist.item'):]
open('out/import-poisoned.sql', 'w').write(text)
PY

before=$($MG -tA <<SQL | tail -1
SELECT set_config('app.tenant_id', '$TENANT_ID', false);
SELECT count(*) FROM worklist.item WHERE tenant_id='$TENANT_ID' AND scope_id='$SCOPE_TXN';
SQL
)
echo "  items before: $before"
$MG -v tenant_id="$TENANT_ID" -v scope_id="$SCOPE_TXN" < out/import-poisoned.sql > /tmp/poisoned.out 2>&1 || true
if grep -qE "ERROR" /tmp/poisoned.out; then
    echo "  OK broke on injected error:"
    grep "ERROR" /tmp/poisoned.out | head -1
else
    echo "  FAIL"
    cat /tmp/poisoned.out
    exit 1
fi
after=$($MG -tA <<SQL | tail -1
SELECT set_config('app.tenant_id', '$TENANT_ID', false);
SELECT count(*) FROM worklist.item WHERE tenant_id='$TENANT_ID' AND scope_id='$SCOPE_TXN';
SQL
)
echo "  items after: $after"
[[ "$after" == "$before" ]] || { echo "FAIL: rollback leaked ($before -> $after)"; exit 1; }
echo "  OK: transaction rolled back"

# ---------------------------------------------------------------------------
# PROBE 5 — reset-scope.sql, dann Bootstrap+Import erneut, verify.sql gruen.
# ---------------------------------------------------------------------------

echo ""
echo "=========================================================="
echo "PROBE 5 — reset-scope + Bootstrap+Import erneut + verify"
echo "=========================================================="
$MG -v tenant_id="$TENANT_ID" -v scope_id="$SCOPE_ID" -f reset-scope.sql > /tmp/reset.out 2>&1
grep -E "NOTICE|reset-scope" /tmp/reset.out | tail -5

REMAINING=$($MG -tA <<SQL | tail -1
SELECT set_config('app.tenant_id', '$TENANT_ID', false);
SELECT count(*) FROM worklist.item WHERE scope_id='$SCOPE_ID';
SQL
)
[[ "$REMAINING" == "0" ]] || { echo "FAIL: Scope nach reset nicht leer ($REMAINING items)"; exit 1; }
echo "  OK: Scope nach reset leer"

{ echo "SELECT set_config('app.tenant_id', '$TENANT_ID', false);"; cat "$BOOTSTRAP"; } | \
    $MG -v tenant_id="$TENANT_ID" -v scope_id="$SCOPE_ID" >/dev/null
echo "  bootstrap rerun clean"

$MG -v tenant_id="$TENANT_ID" -v scope_id="$SCOPE_ID" < ./out/import.sql >/dev/null
echo "  import rerun clean"

verify_sql "$DIGEST" > /tmp/verify-2.out 2>&1
if grep -qE "ERROR|EXCEPTION" /tmp/verify-2.out; then
    echo "  FAIL verify.sql nach rerun rot:"
    cat /tmp/verify-2.out
    exit 1
fi
echo "  OK verify.sql gruen"

# ---------------------------------------------------------------------------
# Helper: separater Scope pro roter Probe (jede haengt an einem frischen
# Bootstrap, damit die vorherige Probe nichts vermischt).
# ---------------------------------------------------------------------------

fresh_scope() {
    local t=$1 s=$2
    { echo "SELECT set_config('app.tenant_id', '$t', false);"; cat "$BOOTSTRAP"; } | \
        $MG -v tenant_id="$t" -v scope_id="$s" >/dev/null
}

expect_verify_red() {
    # $1 = tenant, $2 = scope, $3 = grep pattern, $4 = expected label
    local out=/tmp/rp.out
    $MG \
        -v tenant_id="$1" \
        -v scope_id="$2" \
        -v expected_digest="$DIGEST" \
        -v expected_rows="$EXPECTED_ROWS" \
        -v expected_id_refs="$EXPECTED_ID_REFS" \
        -v expected_truncated_titles="$EXPECTED_TRUNC" \
        -v expected_edges="$EXPECTED_EDGES" \
        -f verify.sql > "$out" 2>&1 || true
    if grep -qE "$3" "$out"; then
        echo "  OK $4 rot mit Wortlaut:"
        grep -E "$3" "$out" | head -1
    else
        echo "  FAIL $4 nicht rot mit erwartetem Wortlaut. Volle Ausgabe:"
        cat "$out"
        exit 1
    fi
}

# ---------------------------------------------------------------------------
# PROBE R1 — Dichte-Nummernraum rot (Duplikat statt eindeutig).
# ---------------------------------------------------------------------------

echo ""
echo "=========================================================="
echo "PROBE R1 — rot: Dichte-Pruefung V2"
echo "=========================================================="

TENANT_R1=$(python3 -c "import uuid; print(uuid.uuid4())")
SCOPE_R1=$(python3 -c "import uuid; print(uuid.uuid4())")
fresh_scope "$TENANT_R1" "$SCOPE_R1"

# Kontrolllauf vorher: verify.sql wuerde ohne Marker rot werden (V0), also
# spielen wir den Import gruen ein und beobachten V2 dann rot nach dem Patch.
python3 <<PY
import re
text = open('out/import.sql').read()
# Verdopple die Nummer '2' auf '1' -> Nummer 2 fehlt.
inserts = list(re.finditer(r'INSERT INTO worklist\.item\n[^;]+;', text))
target = inserts[1]  # zweiter Item-INSERT (number = 2)
patched = target.group(0).replace('number      = 2', 'number      = 1', 1)
patched = re.sub(r'(       )2,(\n       \')', r'\g<1>1,\g<2>', patched, count=1)
text = text[:target.start()] + patched + text[target.end():]
open('out/import-r1.sql', 'w').write(text)
PY

$MG -v tenant_id="$TENANT_R1" -v scope_id="$SCOPE_R1" < out/import-r1.sql > /tmp/r1-import.out 2>&1 || true
# Der Import selbst laeuft wahrscheinlich schon rot (unique index), das ist
# auch eine Form von 'V2 rot'. Wir akzeptieren beide Wortlaute.
if grep -qE 'duplicate key|uq_item_address' /tmp/r1-import.out; then
    echo "  OK V2 rot bereits im Import (Duplikat verletzt uq_item_address):"
    grep -E "duplicate key|uq_item_address" /tmp/r1-import.out | head -1
else
    expect_verify_red "$TENANT_R1" "$SCOPE_R1" "V2 rot: Nummernraum nicht dicht|V2 rot: mindestens eine Nummer|V2 rot: Zeilenzahl weicht ab" "V2"
fi

# ---------------------------------------------------------------------------
# PROBE R2 — Import ohne ID-Referenzen -> V4 rot.
# ---------------------------------------------------------------------------

echo ""
echo "=========================================================="
echo "PROBE R2 — rot: ID-Referenz-Pruefung V4"
echo "=========================================================="

TENANT_R2=$(python3 -c "import uuid; print(uuid.uuid4())")
SCOPE_R2=$(python3 -c "import uuid; print(uuid.uuid4())")
fresh_scope "$TENANT_R2" "$SCOPE_R2"

# Streiche alle item_reference-INSERTs mit ordinal=0 UND ID-Form aus dem Body.
python3 <<PY
import re
text = open('out/import.sql').read()
# item_reference INSERT-Block ist mehrzeilig, endet mit 'asserted;\n'.
pattern = re.compile(
    r'INSERT INTO worklist\.item_reference\n[^;]*?ordinal      = 0[^;]*?asserted;\n',
    re.DOTALL,
)
# Etwas robuster: wir suchen INSERT-Bloecke wo target ein ID-Muster ist
# und ordinal 0 ist. Wir fassen jeden INSERT als Block bis zum naechsten Semikolon.
blocks = list(re.finditer(r'INSERT INTO worklist\.item_reference\n[^;]+;', text))
removed = 0
out = []
last = 0
id_re = re.compile(r"'([A-Z][A-Z0-9]*(-[A-Z0-9]+)*-\d+)'")
for b in blocks:
    body = b.group(0)
    if '\n       0,\n' in body and id_re.search(body):
        # Skip (i.e. drop) this insert.
        out.append(text[last:b.start()])
        last = b.end()
        removed += 1
out.append(text[last:])
open('out/import-r2.sql', 'w').write(''.join(out))
print(f'  removed {removed} id-reference inserts')
PY

$MG -v tenant_id="$TENANT_R2" -v scope_id="$SCOPE_R2" < out/import-r2.sql >/dev/null
expect_verify_red "$TENANT_R2" "$SCOPE_R2" "V4 rot: Items mit ID-Referenz weichen ab" "V4"

# ---------------------------------------------------------------------------
# PROBE R3 — Gekappte Titel ohne description -> V5 rot.
# ---------------------------------------------------------------------------

echo ""
echo "=========================================================="
echo "PROBE R3 — rot: Titel-Pruefung V5"
echo "=========================================================="

TENANT_R3=$(python3 -c "import uuid; print(uuid.uuid4())")
SCOPE_R3=$(python3 -c "import uuid; print(uuid.uuid4())")
fresh_scope "$TENANT_R3" "$SCOPE_R3"

# In jedem Item-INSERT mit einem Titel-Feld, das ein '…' traegt, wird die
# description auf NULL zurueckgesetzt.
python3 <<PY
import re
text = open('out/import.sql').read()
blocks = list(re.finditer(r'INSERT INTO worklist\.item\n[^;]+;', text))
patched = 0
new_text = []
last = 0
for b in blocks:
    body = b.group(0)
    # Suche ein Titel-Literal mit '…'; wenn vorhanden, ersetze die Zeile
    # danach (description) durch NULL.
    if "…'," in body:
        # Titel-Zeile: '<title>…',   Nachste Zeile: '<desc>',
        new_body = re.sub(
            r"(…',\n       )'[^']*(?:''[^']*)*',",
            r"\1NULL,",
            body,
            count=1,
        )
        if new_body != body:
            patched += 1
            new_text.append(text[last:b.start()])
            new_text.append(new_body)
            last = b.end()
new_text.append(text[last:])
open('out/import-r3.sql', 'w').write(''.join(new_text))
print(f'  patched {patched} truncated-title inserts')
PY

$MG -v tenant_id="$TENANT_R3" -v scope_id="$SCOPE_R3" < out/import-r3.sql >/dev/null
expect_verify_red "$TENANT_R3" "$SCOPE_R3" "V5 rot: gekappte Titel weichen ab|V5 rot: [0-9]+ gekappte Titel ohne description" "V5"

# ---------------------------------------------------------------------------
# PROBE R4 — Falscher expected_digest -> V0 rot vor allem anderen.
# ---------------------------------------------------------------------------

echo ""
echo "=========================================================="
echo "PROBE R4 — rot: V0 (Identitaet vor Inhalt)"
echo "=========================================================="

# Der Hauptscope traegt einen gruenen Import; wir rufen verify.sql mit einem
# falschen expected_digest auf.
WRONG_DIGEST=$(python3 -c "print('0'*64)")
$MG \
    -v tenant_id="$TENANT_ID" \
    -v scope_id="$SCOPE_ID" \
    -v expected_digest="$WRONG_DIGEST" \
    -v expected_rows="$EXPECTED_ROWS" \
    -v expected_id_refs="$EXPECTED_ID_REFS" \
    -v expected_truncated_titles="$EXPECTED_TRUNC" \
    -v expected_edges="$EXPECTED_EDGES" \
    -f verify.sql > /tmp/r4.out 2>&1 || true

if grep -q "V0 rot: Digest weicht ab" /tmp/r4.out; then
    echo "  OK V0 rot vor jeder inhaltlichen Pruefung:"
    grep "V0 rot" /tmp/r4.out | head -1
    # V1a sollte NICHT erscheinen, denn V0 hat abgebrochen.
    if grep -q "V1a\|V1b gruen\|V2 gruen" /tmp/r4.out; then
        echo "  FAIL: eine inhaltliche Pruefung lief trotzdem"
        exit 1
    fi
else
    echo "  FAIL"
    cat /tmp/r4.out
    exit 1
fi

# ---------------------------------------------------------------------------
# PROBE R5 — Reset laesst scope_setting stehen -> zweiter Bootstrap rot.
# ---------------------------------------------------------------------------

echo ""
echo "=========================================================="
echo "PROBE R5 — rot: unvollstaendiger Reset (scope_setting bleibt)"
echo "=========================================================="

TENANT_R5=$(python3 -c "import uuid; print(uuid.uuid4())")
SCOPE_R5=$(python3 -c "import uuid; print(uuid.uuid4())")
fresh_scope "$TENANT_R5" "$SCOPE_R5"
$MG -v tenant_id="$TENANT_R5" -v scope_id="$SCOPE_R5" < ./out/import.sql >/dev/null

# reset-scope.sql ohne die scope_setting-Zeile: temporaere Kopie erzeugen.
grep -v 'DELETE FROM worklist.scope_setting' reset-scope.sql > /tmp/reset-broken.sql

# Der Reset selbst laeuft in dieser Variante durch (item etc. sind weg,
# scope_setting bleibt). Ein zweiter Bootstrap trifft dann auf die schon
# vorhandene scope_setting-Zeile.
$MG -v tenant_id="$TENANT_R5" -v scope_id="$SCOPE_R5" -f /tmp/reset-broken.sql >/dev/null 2>&1 || true

{ echo "SELECT set_config('app.tenant_id', '$TENANT_R5', false);"; cat "$BOOTSTRAP"; } | \
    $MG -v tenant_id="$TENANT_R5" -v scope_id="$SCOPE_R5" > /tmp/r5-bootstrap.out 2>&1 || true

if grep -qE 'duplicate key|pk_scope_setting|scope_setting.*already exists' /tmp/r5-bootstrap.out; then
    echo "  OK zweiter Bootstrap rot mit Wortlaut:"
    grep -E "duplicate key|pk_scope_setting|already exists" /tmp/r5-bootstrap.out | head -1
else
    # Wenn Bootstrap trotzdem durchgeht, waere R5 vom Dispatch als 'nicht
    # brechbar' zu melden; dann nennen wir es explizit statt zu erfinden.
    echo "  HINWEIS: Bootstrap laeuft trotz stehender scope_setting durch."
    echo "  Der Dispatch verlangt dann die explizite Nennung statt einer"
    echo "  erfundenen Probe:"
    cat /tmp/r5-bootstrap.out | head -20
    exit 1
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

echo ""
echo "=========================================================="
echo "ALL PROBES PASSED under role $MIGRATOR_ROLE"
echo "  (NOSUPERUSER, NOBYPASSRLS, CREATEROLE)"
echo "=========================================================="
echo "  file:               backend/tools/corpus-import/out/import.sql"
echo "  digest:             $DIGEST"
echo "  bytes:              $BYTES"
echo "  expected_rows:      $EXPECTED_ROWS"
echo "  expected_id_refs:   $EXPECTED_ID_REFS"
echo "  expected_truncs:    $EXPECTED_TRUNC"
echo "  expected_edges:     $EXPECTED_EDGES"
