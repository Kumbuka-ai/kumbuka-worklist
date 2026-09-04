package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** One scope's settings, as the canonical field map. */
    @Transactional
    public Map<String, Object> read(UUID scopeId) {
        return project(requireSetting(scopeId), List.of());
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

        if (planning.settingOf(scopeId) != null) {
            throw new WorklistException(
                WorklistException.Reason.SETTING_PRESENT,
                "scope " + scopeId + " is already open and carries its settings row. "
                    + "There is one per scope; change what it holds through `update` so "
                    + "that the conflict token catches a second writer",
                List.of(String.valueOf(scopeId)));
        }

        ScopeSetting setting = new ScopeSetting();
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
        applyEffectiveChanges(setting, project(setting, List.of()), rest);

        planning.insert(setting);
        planning.flushAndRefresh(setting);
        LOG.infof("scope %s opened", scopeId);
        return project(setting, List.of());
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
        ScopeSetting setting = requireSetting(scopeId);
        setting.requireCurrentToken(given.get(Field.CONFLICT_TOKEN));

        Map<String, Object> current = project(setting, List.of());
        refuseUnsettableChanges(Addressed.SETTING, current, given);

        if (!applyEffectiveChanges(setting, current, settableOnly(Addressed.SETTING, given))) {
            LOG.debugf("update of the settings of scope %s changed nothing and wrote "
                + "nothing", scopeId);
            return current;
        }

        setting.stamp();
        planning.flushAndRefresh(setting);
        LOG.infof("settings of scope %s updated", scopeId);
        return project(setting, List.of());
    }

    // ------------------------------------------------------------------
    // The mechanisms the verbs above are made of.
    // ------------------------------------------------------------------

    /** One of the four numbers V4 leaves without a default, refused by name when absent. */
    private static int mandatory(Field field, Map<Field, Object> given) {
        Integer value = whole(field, given.get(field));
        if (value == null || value <= 0) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                field.canonicalName() + " is one of the four cardinality numbers a scope "
                    + "names when it is opened, and it is a positive whole number. They "
                    + "carry no defaults on purpose: a number this service chose would be "
                    + "a platform constant wearing a setting's clothes. A limit of zero "
                    + "forbids what the setting exists to bound",
                List.of(field.canonicalName()));
        }
        return value;
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
            case ALLOCATION_MODE -> {
                String mode = oneOf(field, required(field, value,
                    "a scope allocates in one of two modes, so the mode cannot be cleared"),
                    ScopeSetting.ALLOCATION_MODES);
                return moved(held, mode, () -> setting.allocationMode = mode);
            }
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
    private static int positive(Field field, Object value) {
        Integer number = whole(field, value);
        if (number == null || number <= 0) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                field.canonicalName() + " is a positive whole number. A limit of zero "
                    + "forbids what the setting exists to bound, and a negative one is "
                    + "not a limit at all",
                List.of(field.canonicalName()));
        }
        return number;
    }

    private static boolean moved(Object held, Object wanted, Runnable write) {
        if (Objects.equals(held == null ? null : String.valueOf(held),
                wanted == null ? null : String.valueOf(wanted))) {
            return false;
        }
        write.run();
        return true;
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
     */
    static Map<String, Object> project(ScopeSetting setting, List<String> warnings) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(Field.ID.canonicalName(), setting.id);
        fields.put(Field.SCOPE.canonicalName(), setting.scopeId);
        fields.put(Field.ALLOCATION_MODE.canonicalName(), setting.allocationMode);
        fields.put(Field.CURRENT_ITERATION.canonicalName(), setting.currentIterationId);
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
        fields.put(Field.CONFLICT_TOKEN.canonicalName(), setting.conflictToken);
        fields.put(Field.WARNINGS.canonicalName(), warnings);
        return fields;
    }
}
