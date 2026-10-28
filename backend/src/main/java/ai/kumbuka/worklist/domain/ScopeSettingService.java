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
import org.jboss.logging.Logger;
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
 * <h2>Why the four cardinality numbers are mandatory at creation</h2>
 *
 * V4 gives them no defaults, deliberately: "a number written at this line
 * would be a platform constant wearing a setting's clothes". So this verb
 * cannot fall back on one either, and a scope names its four numbers when it
 * is opened. The alternative — inventing four here — would move the platform
 * constant one layer up and hide it better.
 *
 * <p>The relation between a warning and its limit is NOT constrained, and
 * that is V4's decision restated rather than a gap: "well below" is a
 * judgement, and a scope that wants its warning at the limit is making a
 * choice rather than a mistake.
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

    /** A scope id and a transition. Never a limit's value, never an actor. */
    private static final Logger LOG = Logger.getLogger(ScopeSettingService.class);

    /**
     * The scope-opening act seeds the three views through this registry.
     *
     * <p>Without the seeding the three views would have to be declared
     * separately by a caller who knew — a fixture, because nothing outside
     * this service reaches the declaration path. That would not scale: a
     * scope opened through the surface would either carry three follow-up
     * declarations or would refuse every item, iteration and milestone
     * created under it. Seeding on open is what makes {@code create} a call
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
     * limits a scope is already working under.
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
        setting.maxPlannedIterations = mandatory(Field.MAX_PLANNED_ITERATIONS, given);
        setting.warnPlannedIterations = mandatory(Field.WARN_PLANNED_ITERATIONS, given);
        setting.maxMembershipsPerIteration =
            mandatory(Field.MAX_MEMBERSHIPS_PER_ITERATION, given);
        setting.warnMembershipsPerIteration =
            mandatory(Field.WARN_MEMBERSHIPS_PER_ITERATION, given);

        Map<Field, Object> rest = settableOnly(Addressed.SETTING, given);
        rest.remove(Field.MAX_PLANNED_ITERATIONS);
        rest.remove(Field.WARN_PLANNED_ITERATIONS);
        rest.remove(Field.MAX_MEMBERSHIPS_PER_ITERATION);
        rest.remove(Field.WARN_MEMBERSHIPS_PER_ITERATION);
        applyEffectiveChanges(setting, projectOf(setting, List.of()), rest);

        planning.insert(setting);
        planning.flushAndRefresh(setting);

        // Seed the three views the platform's object model fixes. Each
        // declaration is idempotent, so a fixture that already declared one
        // of them (the older test setup, the imports of the predecessor's
        // corpus) collides with nothing here. What is new is that a scope
        // opened THROUGH this verb no longer needs its three declarations to
        // be arranged from outside — the surface projection depends on that,
        // because a caller opening a scope has no verb to declare a view
        // with, and no reason to know that one exists.
        for (String view : Selector.VIEWS) {
            selectors.open(scopeId, view);
        }

        LOG.infof("scope %s opened, and the three views seeded", scopeId);
        return projectOf(setting, List.of());
    }

    /**
     * Change what a scope decides.
     *
     * <p><strong>A write that changes nothing writes nothing</strong> — the
     * same rule as everywhere else in this domain, and the reason a caller
     * can read the settings, change one number and send the whole answer
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
            LOG.debugf("update of the settings of scope %s changed nothing and wrote "
                + "nothing", scopeId);
            return current;
        }

        setting.stamp(clock.instant());
        planning.flushAndRefresh(setting);
        LOG.infof("settings of scope %s updated", scopeId);
        return projectOf(setting, List.of());
    }

    // ------------------------------------------------------------------
    // The mechanisms the verbs above are made of.
    // ------------------------------------------------------------------

    /** One of the four numbers V4 leaves without a default, refused by name when absent. */
    private static int mandatory(Field field, Map<Field, Object> given) {
        return whole(field, given.get(field))
            .filter(value -> value > 0)
            .orElseThrow(() -> new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                List.of(field.path())));
    }

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
            case MAX_PLANNED_ITERATIONS -> {
                int limit = positive(field, value);
                return moved(held, limit, () -> setting.maxPlannedIterations = limit);
            }
            case WARN_PLANNED_ITERATIONS -> {
                int threshold = positive(field, value);
                return moved(held, threshold, () -> setting.warnPlannedIterations = threshold);
            }
            case MAX_MEMBERSHIPS_PER_ITERATION -> {
                int limit = positive(field, value);
                return moved(held, limit, () -> setting.maxMembershipsPerIteration = limit);
            }
            case WARN_MEMBERSHIPS_PER_ITERATION -> {
                int threshold = positive(field, value);
                return moved(held, threshold,
                    () -> setting.warnMembershipsPerIteration = threshold);
            }
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

    /**
     * A cardinality number, refused where V4's check constraint would refuse
     * it.
     *
     * <p>Here rather than only there, because a constraint violation arrives
     * at flush, under JTA, outside the typed refusal model. The constraint
     * stays — it is the mechanism — and this is the message.
     */
    private static int positive(Field field, @Nullable Object value) {
        return whole(field, value)
            .filter(number -> number > 0)
            .orElseThrow(() -> new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                List.of(field.path())));
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
        fields.put(Field.MAX_PLANNED_ITERATIONS.canonicalName(),
            setting.maxPlannedIterations);
        fields.put(Field.WARN_PLANNED_ITERATIONS.canonicalName(),
            setting.warnPlannedIterations);
        fields.put(Field.MAX_MEMBERSHIPS_PER_ITERATION.canonicalName(),
            setting.maxMembershipsPerIteration);
        fields.put(Field.WARN_MEMBERSHIPS_PER_ITERATION.canonicalName(),
            setting.warnMembershipsPerIteration);
        fields.put(Field.DEFAULT_COLUMNS.canonicalName(),
            List.of(setting.defaultColumns));
        fields.put(Field.CREATED_AT.canonicalName(), setting.createdAt);
        fields.put(Field.UPDATED_AT.canonicalName(), setting.updatedAt);
        fields.put(Field.CONFLICT_TOKEN.canonicalName(), setting.conflictToken().orElse(null));
        fields.put(Field.WARNINGS.canonicalName(), warnings);
        return fields;
    }
}
