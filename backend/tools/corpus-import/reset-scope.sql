-- reset-scope.sql
--
-- Setzt einen kompletten Scope im worklist-Schema zurueck. Eine Transaktion,
-- alle scope-gebundenen Tabellen aus V4 bis V13; danach laufen Bootstrap
-- und Import erneut sauber.
--
-- Aufruf, gleiche Konvention wie Import und verify:
--
--   psql -v ON_ERROR_STOP=on \
--        -v tenant_id=<uuid> \
--        -v scope_id=<uuid> \
--        -f reset-scope.sql
--
-- Laeuft unter der Migrator-Rolle. RLS bindet den Migrator (V3 FORCE), also
-- wird app.tenant_id vor jedem DML gesetzt; ein DELETE ohne die Bindung ist
-- durch die Policy refused und die Transaktion bricht laut ab.
--
-- Grants: der Migrator ist Owner des Schemas und traegt daher implizit
-- DELETE auf jeder Tabelle. Sollte das durch ein spaeteres Privilegienmodell
-- (ADR-0005) anders werden — dann scheitert das erste DELETE unten mit
-- 'permission denied for table ...' und die Transaktion bricht ab. Der
-- Auftrag verbietet, das Loch mit einem GRANT zu schliessen; die Loch-
-- meldung ist die Rueckgabe.
--
-- Reihenfolge: Blaetter zuerst, dann Kern. Jede Referenz-Kante ist im Kopf
-- der Zeile genannt. Ein DELETE, das die Reihenfolge verletzt, scheitert
-- am Foreign Key statt still zu bleiben.
--
-- Was NICHT gelesen oder geloescht wird:
-- - worklist.flyway_schema_history (Migrator-eigen, keine Scope-Ebene).
-- - Rows anderer Scopes (WHERE scope_id = :'scope_id' auf jeder Zeile).

BEGIN;

SELECT set_config('app.tenant_id',      :'tenant_id', true);
-- Fuer den DO-Block am Ende (psql ersetzt Variablen NICHT innerhalb $$).
SELECT set_config('kw_reset.scope_id',  :'scope_id',  true);

-- claim -> item.
DELETE FROM worklist.claim
 WHERE tenant_id = :'tenant_id' AND scope_id = :'scope_id';

-- iteration_membership -> iteration + item.
DELETE FROM worklist.iteration_membership
 WHERE tenant_id = :'tenant_id' AND scope_id = :'scope_id';

-- item_relation -> item + relation_type.
DELETE FROM worklist.item_relation
 WHERE tenant_id = :'tenant_id' AND scope_id = :'scope_id';

-- item_reference -> item.
DELETE FROM worklist.item_reference
 WHERE tenant_id = :'tenant_id' AND scope_id = :'scope_id';

-- view_preference: kein FK, aber scope-gebunden.
DELETE FROM worklist.view_preference
 WHERE tenant_id = :'tenant_id' AND scope_id = :'scope_id';

-- attribute_option -> attribute_definition.
DELETE FROM worklist.attribute_option
 WHERE tenant_id = :'tenant_id' AND scope_id = :'scope_id';

-- scope_setting -> iteration (current_iteration_id). Vor iteration.
DELETE FROM worklist.scope_setting
 WHERE tenant_id = :'tenant_id' AND scope_id = :'scope_id';

-- item -> selector + item_status + milestone + workstream. Alle vier bleiben
-- noch stehen und werden anschliessend geraeumt.
DELETE FROM worklist.item
 WHERE tenant_id = :'tenant_id' AND scope_id = :'scope_id';

-- milestone -> workstream. Nach item, vor workstream.
DELETE FROM worklist.milestone
 WHERE tenant_id = :'tenant_id' AND scope_id = :'scope_id';

-- iteration: nach scope_setting.current_iteration_id (oben) und nach
-- iteration_membership (weiter oben) frei.
DELETE FROM worklist.iteration
 WHERE tenant_id = :'tenant_id' AND scope_id = :'scope_id';

-- number_space -> selector (+ workstream). Nach item/milestone frei.
DELETE FROM worklist.number_space
 WHERE tenant_id = :'tenant_id' AND scope_id = :'scope_id';

-- attribute_definition: nach attribute_option frei.
DELETE FROM worklist.attribute_definition
 WHERE tenant_id = :'tenant_id' AND scope_id = :'scope_id';

-- workstream: nach item/milestone/number_space frei.
DELETE FROM worklist.workstream
 WHERE tenant_id = :'tenant_id' AND scope_id = :'scope_id';

-- selector: nach item/number_space frei.
DELETE FROM worklist.selector
 WHERE tenant_id = :'tenant_id' AND scope_id = :'scope_id';

-- item_status: nach item frei.
DELETE FROM worklist.item_status
 WHERE tenant_id = :'tenant_id' AND scope_id = :'scope_id';

-- relation_type: nach item_relation frei.
DELETE FROM worklist.relation_type
 WHERE tenant_id = :'tenant_id' AND scope_id = :'scope_id';

-- Selbstbestaetigung: nach dem Reset ist im Scope nichts mehr zu finden.
DO $$
DECLARE
    v_scope UUID := current_setting('kw_reset.scope_id')::uuid;
    v_items BIGINT;
    v_ws    BIGINT;
    v_sel   BIGINT;
    v_setting BIGINT;
BEGIN
    SELECT count(*) INTO v_items   FROM worklist.item        WHERE scope_id = v_scope;
    SELECT count(*) INTO v_ws      FROM worklist.workstream  WHERE scope_id = v_scope;
    SELECT count(*) INTO v_sel     FROM worklist.selector    WHERE scope_id = v_scope;
    SELECT count(*) INTO v_setting FROM worklist.scope_setting WHERE scope_id = v_scope;
    IF v_items + v_ws + v_sel + v_setting <> 0 THEN
        RAISE EXCEPTION
            'reset-scope rot: Scope % traegt nach dem Reset noch Rows '
            '(items=%, workstreams=%, selectors=%, scope_settings=%). '
            'Vermutlich neue scope-gebundene Tabelle seit V13 — reset-scope.sql '
            'ist unvollstaendig.',
            v_scope, v_items, v_ws, v_sel, v_setting;
    END IF;
    RAISE NOTICE 'reset-scope gruen: Scope % ist leer.', v_scope;
END $$;

COMMIT;
