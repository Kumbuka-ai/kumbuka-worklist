package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The goal axis: which goal an item serves.
 *
 * <h2>The verbs</h2>
 *
 * {@code create} a milestone, {@code read} one, {@code update} it,
 * {@code query} the scope's axis, {@code close} it. Five, and every one is
 * the platform's own word for the act, spelled identically — it is the
 * ADDRESS that says a milestone is meant and not the verb.
 *
 * <p>{@code close} addresses the iteration or the milestone on this scheme
 * and never an item. An item becomes terminal through {@code update} against
 * the status its scope declared, which is a different mechanism for a
 * different kind of object.
 *
 * <h2>Assignment is an item update, not a verb of this service</h2>
 *
 * The milestone an item serves is written through {@code item.update} against
 * {@link Field#MILESTONE_ID}. A verb here would be the surface's, and the
 * verb catalogue's mapping table for this scheme names none — {@code update}
 * carries iteration order and membership status on the planning side, and
 * nothing else. The precondition in {@link MembershipService#plan} — that an
 * item carries a milestone on the product path — is met by the item verb.
 *
 * <h2>At most one active, and setting one demotes the other</h2>
 *
 * The invariant is a partial unique index and not a rule this class keeps.
 * What this class does is express the intention in ONE write: activating a
 * milestone demotes the current active in the same transaction, because a
 * refusal would make the operator perform two writes to say one thing.
 */
@ApplicationScoped
@TenantBound
public class MilestoneService extends PlanningService {

    /** An address, a scope id, a count, a transition. Never a title, never an actor. */
    private static final Logger LOG = Logger.getLogger(MilestoneService.class);

    /**
     * The number spaces the three axes allocate from live under their
     * selector, and the milestone selector is the one this allocator reads.
     */
    @Inject SelectorRegistry selectors;
    @Inject WorkstreamService workstreams;

    // ------------------------------------------------------------------
    // Reading.
    // ------------------------------------------------------------------

    /** One milestone, as the canonical field map. */
    @Transactional
    public Map<String, Object> read(UUID scopeId, UUID milestoneId) {
        return project(require(scopeId, milestoneId), List.of());
    }

    /** The scope's whole axis, in the axis's own order. */
    @Transactional
    public List<Map<String, Object>> query(UUID scopeId) {
        return planning.milestonesInScope(scopeId).stream()
            .map(milestone -> project(milestone, List.of()))
            .toList();
    }

    // ------------------------------------------------------------------
    // Writing.
    // ------------------------------------------------------------------

    /**
     * Create a milestone, or one of the three markers.
     *
     * <p>The number is allocated from the scope's own mark and is never
     * given: a number a caller could choose is a number that can be handed
     * out twice. A closed milestone stays in the table, so the mark counts
     * past it.
     *
     * <p>A created milestone is {@code planned}. Making it active is an
     * {@link #update}, because activating is the act that demotes the current
     * active and that act should be visible as one.
     */
    @Transactional
    public Map<String, Object> create(UUID scopeId, Map<String, ?> arguments) {
        Map<Field, Object> given = Field.resolve(Addressed.MILESTONE, arguments);
        refuseUnsettableChanges(Addressed.MILESTONE, Map.of(), given);

        // V12 (2026-09-09): the milestone-workstream edge is retracted
        // (TAR-0002 section 4, REQ-0148 obsolete). The `workstream_id`
        // column on `milestone` stays for one image cycle and is
        // populated from the caller's argument or from the scope's
        // default for symmetry with the old shape, but no invariant
        // reads it. The number is allocated from the scope-wide
        // milestone counter.
        Workstream workstream = resolveWorkstream(scopeId, given.get(Field.WORKSTREAM_ID));

        Milestone milestone = new Milestone();
        milestone.scopeId = scopeId;
        milestone.workstreamId = workstream.id;
        milestone.title = required(Field.TITLE, given.get(Field.TITLE),
            "a milestone carries a title. It is the axis position's handle in every "
                + "listing, and the one field a marker needs as much as a goal does");
        milestone.number = allocateNumber(scopeId);

        Map<Field, Object> settable = settableOnly(Addressed.MILESTONE, given);
        settable.remove(Field.TITLE);
        // Workstream was resolved above and is set on the row already; leaving
        // it in the settable map would send it through `applyOne`, whose
        // switch has no case for it (the frame ratifies the workstream on a
        // milestone as write-once from create, and moving it later would
        // change which counter numbered the milestone).
        settable.remove(Field.WORKSTREAM_ID);
        applyEffectiveChanges(milestone, project(milestone, List.of()), settable);
        refuseGoalOnAMarker(milestone);

        planning.insert(milestone);
        planning.flushAndRefresh(milestone);
        LOG.infof("milestone %d created in scope %s", milestone.number, scopeId);
        return project(milestone, List.of());
    }

    /**
     * Change what is known about a milestone.
     *
     * <p><strong>A write that changes nothing writes nothing.</strong> Not a
     * timestamp, not a rotated token, not a statement — the same rule the
     * item domain measured, and for the same reason: a write assembled out of
     * a read answer would otherwise leave a false change trail and a false
     * conflict signal behind it.
     *
     * <p>Activating demotes the current active in the same transaction. The
     * demotion is flushed BEFORE the activation, because the partial unique
     * index would otherwise see two active rows in one statement batch and
     * refuse the write that was expressing one intention.
     */
    @Transactional
    public Map<String, Object> update(UUID scopeId, UUID milestoneId,
            Map<String, ?> arguments) {
        Map<Field, Object> given = Field.resolve(Addressed.MILESTONE, arguments);
        Milestone milestone = require(scopeId, milestoneId);
        milestone.requireCurrentToken(given.get(Field.CONFLICT_TOKEN));

        Map<String, Object> current = project(milestone, List.of());
        refuseUnsettableChanges(Addressed.MILESTONE, current, given);

        if (!applyEffectiveChanges(milestone, current,
                settableOnly(Addressed.MILESTONE, given))) {
            LOG.debugf("update of a milestone in scope %s changed nothing and wrote "
                + "nothing", scopeId);
            return current;
        }
        refuseGoalOnAMarker(milestone);

        milestone.stamp();
        planning.flushAndRefresh(milestone);
        LOG.infof("milestone %d updated in scope %s", milestone.number, scopeId);
        return project(milestone, List.of());
    }

    /**
     * Close a milestone: the goal is reached or abandoned, and it stays an
     * object.
     *
     * <p>Closing touches no item. Items keep pointing at it and every
     * reference still resolves, which is the whole reason a milestone is a
     * row rather than a label — and the reason the number is not handed back.
     *
     * <p>It goes through {@link #update} rather than beside it, so that the
     * conflict token, the no-op rule and the field validation are the same
     * code. A second write path is a second place for those three to drift.
     */
    @Transactional
    public Map<String, Object> close(UUID scopeId, UUID milestoneId, String conflictToken) {
        Map<String, Object> answer = update(scopeId, milestoneId, Map.of(
            Field.STATUS.canonicalName(), Milestone.CLOSED,
            Field.CONFLICT_TOKEN.canonicalName(), String.valueOf(conflictToken)));
        LOG.infof("milestone closed in scope %s", scopeId);
        return answer;
    }

    // ------------------------------------------------------------------
    // The mechanisms the verbs above are made of.
    // ------------------------------------------------------------------

    /**
     * The next number on the goal axis, from the scope's persisted mark for
     * the milestone selector.
     *
     * <p>The mark is the {@link NumberSpace} row of the {@code milestone}
     * selector; the allocator takes it under a write lock and advances it in
     * the same transaction as the milestone row it numbers, so two concurrent
     * creations cannot read the same value. The rotation is on the milestone
     * aggregate the caller wrote; the settings' own token is untouched.
     *
     * <p>Advancing this mark is what {@link SelectorRegistry#allocate} does
     * on the item and iteration selectors too — one mechanism, one counter
     * per view. The goal axis is a class of its own, so the selector-keyed
     * counter is where its next number belongs.
     */
    private long allocateNumber(UUID scopeId) {
        requireSetting(scopeId);
        Selector milestoneSelector = selectors.require(scopeId, Selector.MILESTONE);
        return selectors.allocate(scopeId, milestoneSelector);
    }

    /**
     * Resolve a workstream at milestone-create time.
     *
     * <p>Named → require + refuse-withdrawn. Absent → the scope's default.
     * The check runs before number allocation because the milestone
     * counter is per-workstream — the allocator needs to know which
     * counter to advance.
     */
    private Workstream resolveWorkstream(UUID scopeId, Object value) {
        if (value == null) {
            return workstreams.requireDefault(scopeId);
        }
        UUID workstreamId;
        try {
            workstreamId = UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException notAnId) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "the workstream is named by its identity, not by its token. Refused: "
                    + value,
                List.of(Field.WORKSTREAM_ID.canonicalName()));
        }
        Workstream workstream = workstreams.require(scopeId, workstreamId);
        workstreams.refuseWithdrawn(workstream);
        return workstream;
    }

    private boolean applyEffectiveChanges(Milestone milestone, Map<String, Object> current,
            Map<Field, Object> settable) {
        boolean changed = false;
        for (Map.Entry<Field, Object> entry : settable.entrySet()) {
            changed |= applyOne(milestone, current, entry.getKey(), entry.getValue());
        }
        return changed;
    }

    private boolean applyOne(Milestone milestone, Map<String, Object> current, Field field,
            Object value) {
        Object held = current.get(field.canonicalName());
        switch (field) {
            case TITLE -> {
                String title = required(field, value,
                    "a milestone carries a title on every status, so it cannot be cleared");
                return moved(held, title, () -> milestone.title = title);
            }
            case KIND -> {
                String kind = oneOf(field, required(field, value,
                    "a milestone is a goal or one of the three positions on the axis; "
                        + "which it is cannot be cleared"), Milestone.KINDS);
                return moved(held, kind, () -> milestone.kind = kind);
            }
            case STATUS -> {
                return applyStatus(milestone, held, field, value);
            }
            case VISION -> {
                String vision = ItemFields.text(field, value);
                return moved(held, vision, () -> milestone.vision = vision);
            }
            case MISSION -> {
                String mission = ItemFields.text(field, value);
                return moved(held, mission, () -> milestone.mission = mission);
            }
            case RANK -> {
                Integer rank = whole(field, value);
                return rank != null && moved(held, rank, () -> milestone.rank = rank);
            }
            case WORKSTREAM_ID -> {
                // V12 (2026-09-09): the milestone-workstream edge is
                // retracted (TAR-0002 section 4, REQ-0148 obsolete), and
                // the milestone's number space returned to scope-wide.
                // The column `milestone.workstream_id` stays for one image
                // cycle but carries no invariant; an update is accepted
                // (or echoed) and no cross-workstream refuse-* fires.
                UUID given = ItemFields.id(field, value);
                if (ItemFields.unchangedAsText(held, given)) {
                    return false;
                }
                return moved(held, given, () -> milestone.workstreamId = given);
            }
            default -> throw new IllegalStateException(
                field.canonicalName() + " is settable on a milestone and has no application");
        }
    }

    /**
     * The status, and the demotion that makes "at most one active" expressible
     * in one write.
     *
     * <p>The demotion is flushed before the row being activated is touched.
     * Both rows carrying {@code active} at flush time is exactly what the
     * partial unique index refuses, and it would refuse the write that was
     * removing the condition.
     */
    private boolean applyStatus(Milestone milestone, Object held, Field field, Object value) {
        String status = oneOf(field, required(field, value,
            "a milestone carries a status on every path, so it cannot be cleared"),
            Milestone.STATUSES);
        if (ItemFields.unchangedAsText(held, status)) {
            return false;
        }

        if (Milestone.ACTIVE.equals(status)) {
            Milestone current = planning.activeMilestone(milestone.scopeId);
            if (current != null && !current.id.equals(milestone.id)) {
                current.status = Milestone.PLANNED;
                current.stamp();
                planning.flush();
                LOG.infof("milestone %d demoted in scope %s", current.number,
                    milestone.scopeId);
            }
        }
        milestone.status = status;
        return true;
    }

    /**
     * A marker is a position and not a goal, so it carries neither a vision
     * nor a mission.
     *
     * <p>Checked here as a typed refusal because V4 checks it as a
     * constraint, and a constraint violation arrives at flush, under JTA,
     * outside the typed refusal model — which is how a refusal becomes a 500.
     * The constraint stays: it is the mechanism, and this is the message.
     */
    private static void refuseGoalOnAMarker(Milestone milestone) {
        if (Milestone.GOAL.equals(milestone.kind)
                || (milestone.vision == null && milestone.mission == null)) {
            return;
        }
        throw new WorklistException(
            WorklistException.Reason.INVALID_VALUE,
            "a milestone of kind " + milestone.kind + " is a position on the axis and "
                + "never a goal, so it carries neither a vision nor a mission. Those two "
                + "belong to a milestone that something is being worked towards",
            List.of(Field.KIND.canonicalName()));
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
     * The milestone of that id in that scope, or a typed refusal.
     *
     * <p>No transaction of its own, and no annotation claiming one: it is
     * called from a verb that is already inside one, and a self-call reaches
     * no interceptor anyway. An annotation here would say something that is
     * not true of the call.
     */
    private Milestone require(UUID scopeId, UUID milestoneId) {
        Milestone milestone = planning.milestoneById(milestoneId);
        if (milestone == null || !milestone.scopeId.equals(scopeId)) {
            throw new WorklistException(
                WorklistException.Reason.MILESTONE_UNKNOWN,
                "no milestone " + milestoneId + " in scope " + scopeId,
                List.of(String.valueOf(milestoneId)));
        }
        return milestone;
    }

    /**
     * The milestone as the canonical field map — the one answer shape, used
     * by the reads AND by the comparison the writes make.
     *
     * <p>That reuse is the point rather than an economy. If the read answer
     * and the value a write compares against were built separately, the two
     * could disagree, and a write would report a change where a reader saw
     * none.
     */
    private static Map<String, Object> project(Milestone milestone,
            List<String> warnings) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(Field.ID.canonicalName(), milestone.id);
        fields.put(Field.SCOPE.canonicalName(), milestone.scopeId);
        fields.put(Field.NUMBER.canonicalName(), milestone.number);
        fields.put(Field.TITLE.canonicalName(), milestone.title);
        fields.put(Field.KIND.canonicalName(), milestone.kind);
        fields.put(Field.STATUS.canonicalName(), milestone.status);
        fields.put(Field.VISION.canonicalName(), milestone.vision);
        fields.put(Field.MISSION.canonicalName(), milestone.mission);
        fields.put(Field.RANK.canonicalName(), milestone.rank);
        fields.put(Field.WORKSTREAM_ID.canonicalName(), milestone.workstreamId);
        fields.put(Field.CREATED_AT.canonicalName(), milestone.createdAt);
        fields.put(Field.UPDATED_AT.canonicalName(), milestone.updatedAt);
        fields.put(Field.CONFLICT_TOKEN.canonicalName(), milestone.conflictToken);
        fields.put(Field.WARNINGS.canonicalName(), warnings);
        return fields;
    }
}
