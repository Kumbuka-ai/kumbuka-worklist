package ai.kumbuka.worklist.repository;

import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The uuids of support rows, read for as long as the store still carries them.
 *
 * <p>ADR-0042 stage R2 moves every reference onto the surrogate {@code pk},
 * and stage R3 drops the uuid column {@code id} of the support tables. Three
 * things still need that uuid while stage R2 runs: the stored form of
 * {@code item.attributes} (keyed by definition uuid, option uuid as value —
 * the form the image before this one reads), an option given by its uuid on
 * the way in, and the settings' {@code fields.id} on the way out.
 *
 * <p><strong>Why native, and why {@code to_jsonb(row) ->> 'id'}.</strong>
 * The entities no longer map {@code id}: an image that mapped it could not
 * start against the stage-R3 schema, which DEC-0018 requires it to do. Reading
 * the column through the row's own JSON form returns NULL once the column is
 * gone instead of raising, so this image keeps working after the drop and
 * falls back to the surrogate wherever a uuid is no longer there. Every query
 * here reads one scope's vocabulary rows, which are few, under the session's
 * tenant binding, so none of them can cross a tenant boundary.
 *
 * <p>This class goes with stage R3.
 */
@ApplicationScoped
@TenantBound
public class RetiringUuidRepository {

    @Inject EntityManager em;

    /** The settings row's uuid, or null once the column is gone. */
    @Transactional
    public UUID settingId(UUID scopeId) {
        // Native for the reason the class states: the column is not mapped.
        List<?> rows = em.createNativeQuery(
                "SELECT to_jsonb(s) ->> 'id' FROM worklist.scope_setting s WHERE s.scope_id = ?1")
            .setParameter(1, scopeId)
            .getResultList();
        return rows.isEmpty() ? null : uuidOrNull(rows.get(0));
    }

    /** Definition uuid to surrogate, for one scope; empty once the column is gone. */
    @Transactional
    public Map<UUID, Long> definitionPksByUuid(UUID scopeId) {
        // Native for the reason the class states: the column is not mapped.
        return pksByUuid(em.createNativeQuery(
                "SELECT v.pk, to_jsonb(v) ->> 'id' FROM worklist.attribute_definition v "
                    + "WHERE v.scope_id = ?1")
            .setParameter(1, scopeId)
            .getResultList());
    }

    /** Option uuid to surrogate, for one scope; empty once the column is gone. */
    @Transactional
    public Map<UUID, Long> optionPksByUuid(UUID scopeId) {
        // Native for the reason the class states: the column is not mapped.
        return pksByUuid(em.createNativeQuery(
                "SELECT v.pk, to_jsonb(v) ->> 'id' FROM worklist.attribute_option v "
                    + "WHERE v.scope_id = ?1")
            .setParameter(1, scopeId)
            .getResultList());
    }

    /** Rows of (surrogate, uuid text) as a map from uuid to surrogate, skipping absent uuids. */
    private static Map<UUID, Long> pksByUuid(List<?> rows) {
        Map<UUID, Long> answer = new HashMap<>();
        for (Object row : rows) {
            Object[] columns = (Object[]) row;
            UUID uuid = uuidOrNull(columns[1]);
            if (uuid != null) {
                answer.put(uuid, ((Number) columns[0]).longValue());
            }
        }
        return answer;
    }

    private static UUID uuidOrNull(Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }
}
