#!/usr/bin/env bash
#
# verify_import.sh — mechanism probe for the corpus-import bench,
# under the migrator role (Sprint 177.10, sharpened from 177.9).
#
# Sprint 177.9 ran this probe as superuser. That put the file's very
# first trap — RLS binds the migrator too — under the one condition
# under which RLS does not fire. Sprint 177.10 fixes that: every SQL
# action from here on runs under a `worklist_migrator` role created
# with NOSUPERUSER and NOBYPASSRLS, the same shape the migrator has on
# the host.
#
# The probe also fires the sharper V13-red-probe: an isolated attempt
# to seed a milestone marker WITHOUT `workstream_id` — the very thing
# the earlier bootstrap did — is fired against a fresh scope BEFORE
# V13 (expected rot, `null value in column "workstream_id"`) and
# AFTER V13 (expected gruen). That is the mechanism V13 was written
# to unblock.
#
# Prerequisites: docker, python3. No local psql needed — the container's
# psql is used via `docker exec`.

set -euo pipefail

CONTAINER=kw-test-pg-177-10
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
# PROBE V13 — rot before, gruen after
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

# Clean up so it does not pollute later probes.
$MG <<SQL >/dev/null
DELETE FROM worklist.milestone   WHERE tenant_id = '$TENANT_V13';
DELETE FROM worklist.workstream  WHERE tenant_id = '$TENANT_V13';
DELETE FROM worklist.number_space WHERE tenant_id = '$TENANT_V13';
DELETE FROM worklist.selector    WHERE tenant_id = '$TENANT_V13';
SQL

# ---------------------------------------------------------------------------
# Bootstrap kumbuka (marker-free 177.10 version)
# ---------------------------------------------------------------------------

TENANT_ID=$(python3 -c "import uuid; print(uuid.uuid4())")
SCOPE_ID=$(python3 -c "import uuid; print(uuid.uuid4())")

echo ""
echo "== running bootstrap-scope.sql for kumbuka (marker-free) =="
BOOTSTRAP=../../src/main/resources/db/bootstrap/bootstrap-scope.sql
{ echo "SELECT set_config('app.tenant_id', '$TENANT_ID', false);"; cat "$BOOTSTRAP"; } | \
    $MG -v tenant_id="$TENANT_ID" -v scope_id="$SCOPE_ID" >/dev/null

# ---------------------------------------------------------------------------
# Generate the file (UUID-independent since 177.10)
# ---------------------------------------------------------------------------

echo ""
echo "== generating import.sql (UUID-independent) =="
python3 generate_import.py \
    --steering /Users/johannes/Work/kumbuka.ai/dev/steering \
    --snapshot ./snapshot.env \
    --out       ./out/import.sql
DIGEST=$(head -20 ./out/import.sql | grep -oE '[a-f0-9]{64}' | head -1)
BYTES=$(wc -c < ./out/import.sql | tr -d ' ')
echo "  digest: $DIGEST"
echo "  bytes:  $BYTES"

# ---------------------------------------------------------------------------
# PROBE 1 — Kontrolllauf
# ---------------------------------------------------------------------------

echo ""
echo "=========================================================="
echo "PROBE 1 — Kontrolllauf: einspielen als $MIGRATOR_ROLE"
echo "=========================================================="
$MG -v tenant_id="$TENANT_ID" -v scope_id="$SCOPE_ID" < ./out/import.sql >/dev/null
echo "  import.sql applied cleanly"

echo ""
echo "-- Item-Zahl je Strang --"
$MG <<SQL
SELECT set_config('app.tenant_id', '$TENANT_ID', false);
SELECT ws.token, count(i.*) AS items
  FROM worklist.workstream ws
  LEFT JOIN worklist.item i ON i.workstream_id = ws.id
 WHERE ws.tenant_id = '$TENANT_ID' AND ws.scope_id = '$SCOPE_ID'
 GROUP BY ws.token ORDER BY items DESC;
SQL

TOTAL=$($MG -tA <<SQL | tail -1
SELECT set_config('app.tenant_id', '$TENANT_ID', false);
SELECT count(*) FROM worklist.item WHERE tenant_id='$TENANT_ID' AND scope_id='$SCOPE_ID';
SQL
)
echo "-- Total Items: $TOTAL (erwartet 488) --"
[[ "$TOTAL" == "488" ]] || { echo "FAIL"; exit 1; }
echo "  OK"

EDGES=$($MG -tA <<SQL | tail -1
SELECT set_config('app.tenant_id', '$TENANT_ID', false);
SELECT count(*) FROM worklist.item_relation WHERE tenant_id='$TENANT_ID' AND scope_id='$SCOPE_ID';
SQL
)
echo "-- Kanten: $EDGES --"

echo "-- Meilenstein-Nummern (erwartet 1..7 seit 177.10) --"
$MG <<SQL
SELECT set_config('app.tenant_id', '$TENANT_ID', false);
SELECT number, title FROM worklist.milestone
 WHERE tenant_id = '$TENANT_ID' AND scope_id = '$SCOPE_ID' AND kind = 'milestone'
 ORDER BY number;
SQL

NEXT_NUM=$($MG -tA <<SQL | tail -1
SELECT set_config('app.tenant_id', '$TENANT_ID', false);
SELECT high_water_mark + 1
  FROM worklist.number_space ns
  JOIN worklist.selector s ON s.id = ns.selector_id
 WHERE ns.tenant_id = '$TENANT_ID' AND ns.scope_id = '$SCOPE_ID'
   AND s.token = 'item' AND ns.workstream_id IS NULL;
SQL
)
MAX_NUM=$($MG -tA <<SQL | tail -1
SELECT set_config('app.tenant_id', '$TENANT_ID', false);
SELECT MAX(number) FROM worklist.item WHERE tenant_id='$TENANT_ID' AND scope_id='$SCOPE_ID';
SQL
)
echo "-- Zaehlerprobe: max=$MAX_NUM, next=$NEXT_NUM --"
[[ "$NEXT_NUM" == "$((MAX_NUM + 1))" ]] || { echo "FAIL"; exit 1; }
echo "  OK (N+1)"

MARKER=$($MG -tA <<SQL | tail -1
SELECT set_config('app.tenant_id', '$TENANT_ID', false);
SELECT name FROM worklist.attribute_definition
 WHERE tenant_id='$TENANT_ID' AND scope_id='$SCOPE_ID' AND key='corpus_import_marker';
SQL
)
echo "-- Selbstidentifikation --"
[[ "$MARKER" == *"$DIGEST"* ]] || { echo "FAIL marker missing digest"; exit 1; }
echo "  OK: $MARKER"

# ---------------------------------------------------------------------------
# PROBE 2 — Idempotenz
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
# PROBE 3 — Selbstidentifikation
# ---------------------------------------------------------------------------

echo ""
echo "=========================================================="
echo "PROBE 3 — rot: veraenderte Datei"
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
# PROBE 4 — Transaktionsgrenze
# ---------------------------------------------------------------------------

echo ""
echo "=========================================================="
echo "PROBE 4 — rot: Fehler in Mitte → Rollback"
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
# Summary
# ---------------------------------------------------------------------------

echo ""
echo "=========================================================="
echo "ALL PROBES PASSED under role $MIGRATOR_ROLE"
echo "  (NOSUPERUSER, NOBYPASSRLS, CREATEROLE)"
echo "=========================================================="
echo "  file:    backend/tools/corpus-import/out/import.sql"
echo "  digest:  $DIGEST"
echo "  bytes:   $BYTES"
echo "  rows:    $TOTAL"
echo "  edges:   $EDGES"
