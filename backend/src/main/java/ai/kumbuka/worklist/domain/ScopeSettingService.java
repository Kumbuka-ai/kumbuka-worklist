/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The scope's own settings: one row, and everything the scope decides.
 *
 * <h2>The verbs</h2>
 *
 * {@code create} the row — opening the scope — {@code read} it and
 * {@code update} it. There is no {@code query}: one row per scope is not
 * something a scope-addressed call enumerates.
 *
 * <h2>Opening a scope asks for nothing</h2>
 *
 * The settings exist from the moment the scope exists in this service
 * (TAR-0002 B11): {@code create} is the act that opens the scope, and it
 * writes the row with an empty pointer and an empty default column set
 * unless the caller names one; no number is asked for. The settings carry no
 * cardinality numbers; planning is bounded by the platform constants of
 * REA-0011 D9. A write that
 * names one of the four retired numbers is refused like a write naming any
 * other field the settings do not carry.
 *
 * <h2>The current iteration pointer is read here and written by {@code advance}</h2>
 *
 * {@link Field#CURRENT_ITERATION} is not settable. A caller that could set it
 * would be able to make an iteration current without promoting it, and
 * "promoted" and "pointed at" would become two states that read the same.
 * {@link IterationService#advance} is the verb that says what the act is.
 */
@ApplicationScoped
@TenantBound
public class ScopeSettingService extends PlanningService {

    /*
     * Nothing here logs (DEC-0051, C4). The opening and the settings are
     * written by no verb; the administration surface calls them and writes
     * their lines.
     */

    /**
     * The scope-opening act seeds the four views through this registry.
     *
     * <p>Without the seeding the four views would have to be declared
     * separately by a caller who knew — a fixture, because nothing outside
     * this service reaches the declaration path. That would not scale: a
     * scope opened through the surface would either carry four follow-up
     * declarations or would refuse every item, iteration, milestone and
     * workstream created under it. Seeding on open is what makes {@code create} a call
     * that opens a scope end to end.
     */
    @Inject SelectorRegistry selectors;

    /** One scope's settings, as the canonical field map. */
    @Transactional
    public Map<String, Object> read(UUID scopeId) {
        return projectOf(requireSetting(scopeId), List.of());
    }

    /**
     * Open a scope: write the settings row it plans against.
     *
     * <p>Once. A second call is a typed refusal rather than an overwrite:
     * there is one row per scope, and an overwrite would silently replace the
     * pointer and the column set a scope is already working with.
     *
     * <p>Nothing is required. The row is written with an empty pointer and
     * an empty default column set, unless the caller names a column set.
     */
    @Transactional
    public Map<String, Object> create(UUID scopeId, Map<String, ?> arguments) {
        Map<Field, Object> given = Field.resolve(Addressed.SETTING, arguments);
        refuseUnsettableChanges(Addressed.SETTING, Map.of(), given);

        if (planning.settingOf(scopeId).isPresent()) {
            throw new WorklistException(
                WorklistException.Reason.SETTING_PRESENT,
                List.of());
        }

        ScopeSetting setting = new ScopeSetting();
        setting.scopeKey = scopeId;
        setting.scopeId = scopeId;
        applyEffectiveChanges(setting, projectOf(setting, List.of()),
            settableOnly(Addressed.SETTING, given));

        planning.insert(setting);
        planning.flushAndRefresh(setting);

        // Seed the four views the platform's object model fixes. Each
        // declaration is idempotent, so a fixture that already declared one
        // of them (the older test setup, the imports of the predecessor's
        // corpus) collides with nothing here. What is new is that a scope
        // opened THROUGH this verb no longer needs its four declarations to
        // be arranged from outside — the surface projection depends on that,
        // because a caller opening a scope has no verb to declare a view
        // with, and no reason to know that one exists.
        for (String view : Selector.VIEWS) {
            selectors.open(scopeId, view);
        }

        return projectOf(setting, List.of());
    }

    /**
     * Change what a scope decides.
     *
     * <p><strong>A write that changes nothing writes nothing</strong> — the
     * same rule as everywhere else in this domain, and the reason a caller
     * can read the settings, change the column set and send the whole answer
     * back.
     */
    @Transactional
    public Map<String, Object> update(UUID scopeId, Map<String, ?> arguments) {
        Map<Field, Object> given = Field.resolve(Addressed.SETTING, arguments);
        ScopeSetting setting = requireLockedSetting(scopeId);
        setting.requireCurrentToken(given.get(Field.CONFLICT_TOKEN));

        Map<String, Object> current = projectOf(setting, List.of());
        refuseUnsettableChanges(Addressed.SETTING, current, given);

        if (!applyEffectiveChanges(setting, current, settableOnly(Addressed.SETTING, given))) {
            return current;
        }

        setting.stamp(clock.instant());
        planning.flushAndRefresh(setting);
        return projectOf(setting, List.of());
    }

    // ------------------------------------------------------------------
    // The mechanisms the verbs above are made of.
    // ------------------------------------------------------------------

    private boolean applyEffectiveChanges(ScopeSetting setting, Map<String, Object> current,
            Map<Field, Object> settable) {
        boolean changed = false;
        for (Map.Entry<Field, Object> entry : settable.entrySet()) {
            changed |= applyOne(setting, current, entry.getKey(), entry.getValue());
        }
        return changed;
    }

    private boolean applyOne(ScopeSetting setting, Map<String, Object> current, Field field,
            Object value) {
        Object held = current.get(field.canonicalName());
        switch (field) {
            case DEFAULT_COLUMNS -> {
                List<String> columns = ItemFields.tokensInOrder(field, value);
                String[] wanted = columns.toArray(new String[0]);
                return moved(held, columns, () -> setting.defaultColumns = wanted);
            }
            default -> throw new IllegalStateException(
                field.canonicalName() + " is settable on a scope setting and has no "
                    + "application");
        }
    }

    private static boolean moved(@Nullable Object held, @Nullable Object wanted, Runnable write) {
        if (Objects.equals(held == null ? null : String.valueOf(held),
                wanted == null ? null : String.valueOf(wanted))) {
            return false;
        }
        write.run();
        return true;
    }

    /**
     * The projection, with the current iteration's identity resolved from the
     * number the settings row holds (ADR-0042). The answer still names the
     * iteration by its identity; only the stored reference changed form.
     */
    private Map<String, Object> projectOf(ScopeSetting setting, List<Notice> warnings) {
        Iteration current = setting.currentIterationNumber == null ? null
            : planning.iterationByNumber(setting.scopeId, setting.currentIterationNumber).orElse(null);
        return project(setting, current == null ? null : current.id, warnings);
    }

    /**
     * The settings as the canonical field map — the one answer shape, used by
     * the read AND by the comparison the writes make.
     *
     * <p>The two high-water marks are deliberately absent from it. They
     * record what the allocators have handed out, which is not something a
     * caller sets, reads back or round-trips; putting them in the answer
     * would invite a write that carried them, and a mark a caller can carry
     * is a mark that can be carried backwards.
     *
     * @param currentIterationId the current iteration's identity; absent where
     *                           the scope has no current iteration
     */
    static Map<String, Object> project(ScopeSetting setting, @Nullable UUID currentIterationId,
            List<Notice> warnings) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(Field.SCOPE.canonicalName(), setting.scopeId);
        fields.put(Field.CURRENT_ITERATION.canonicalName(), currentIterationId);
        fields.put(Field.DEFAULT_COLUMNS.canonicalName(),
            List.of(setting.defaultColumns));
        fields.put(Field.CREATED_AT.canonicalName(), setting.createdAt);
        fields.put(Field.UPDATED_AT.canonicalName(), setting.updatedAt);
        fields.put(Field.CONFLICT_TOKEN.canonicalName(), setting.conflictToken().orElse(null));
        fields.put(Field.WARNINGS.canonicalName(), warnings);
        return fields;
    }
}
