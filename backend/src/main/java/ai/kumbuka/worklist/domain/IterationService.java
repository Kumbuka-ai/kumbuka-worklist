package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The time axis: when an item is being worked.
 *
 * <h2>The verbs</h2>
 *
 * {@code create} an iteration, {@code read} one, {@code update} it — which
 * carries the membership ORDER, addressed here and not at the memberships —
 * {@code query} the axis, {@code close} it, and {@code advance} to the next.
 *
 * <h2>The order is addressed at the iteration, and that is the aggregate rule</h2>
 *
 * A reorder writes twelve membership rows and presents ONE conflict token:
 * the iteration's. The rows have no token of their own precisely so that this
 * is expressible — a membership is addressed at its own address for its
 * status, and belongs to the iteration's aggregate for everything about the
 * sequence.
 *
 * <p>Position is derived from the order and never given. A caller that could
 * write a position could write two rows to the same one, and the density the
 * sequence depends on would become something somebody has to maintain.
 *
 * <h2>The iteration has no status column, and the close is a fact</h2>
 *
 * Complete is derived from the memberships and current is a pointer on the
 * scope's settings. {@link #close} writes a timestamp, and the refusal in
 * front of it names every membership that is neither done nor dropped —
 * because closing over them would decide for the operator what happened to
 * each one.
 */
@ApplicationScoped
@TenantBound
public class IterationService extends PlanningService {

    /** An address, a scope id, a count, a transition. Never a motto, never an actor. */
    private static final Logger LOG = Logger.getLogger(IterationService.class);

    /** What the cardinality refusal and its warning call the thing being counted. */
    private static final String OPEN_ITERATIONS = "the number of open iterations in this scope";

    // ------------------------------------------------------------------
    // Reading.
    // ------------------------------------------------------------------

    /** One iteration, as the canonical field map. */
    @Transactional
    public Map<String, Object> read(UUID scopeId, UUID iterationId) {
        return project(require(scopeId, iterationId), List.of());
    }

    /** The scope's whole axis, in the order the iterations are to be worked. */
    @Transactional
    public List<Map<String, Object>> query(UUID scopeId) {
        return planning.iterationsInScope(scopeId).stream()
            .map(iteration -> project(iteration, List.of()))
            .toList();
    }

    // ------------------------------------------------------------------
    // Writing.
    // ------------------------------------------------------------------

    /**
     * Create an iteration.
     *
     * <p>The motto and the description are both required, and that is not
     * ceremony: they are the only machine-readable criterion by which an
     * agent can refuse an item as out of scope for the current iteration. A
     * default here would be this service deciding what a scope's iteration
     * means.
     *
     * <p>The number comes from the scope's own mark and is never given. The
     * cardinality of OPEN iterations is checked before the write: the hard
     * limit refuses, and the advisory threshold beside it warns in the answer
     * and admits. Both numbers are the scope's own.
     */
    @Transactional
    public Map<String, Object> create(UUID scopeId, Map<String, ?> arguments) {
        Map<Field, Object> given = Field.resolve(Addressed.ITERATION, arguments);
        refuseUnsettableChanges(Addressed.ITERATION, Map.of(), given);

        ScopeSetting setting = requireSetting(scopeId);
        int openAfterwards = planning.openIterations(scopeId).size() + 1;
        refuseBeyond(openAfterwards, setting.maxPlannedIterations, OPEN_ITERATIONS);

        Iteration iteration = new Iteration();
        iteration.scopeId = scopeId;
        iteration.motto = required(Field.MOTTO, given.get(Field.MOTTO),
            "an iteration carries a motto. With the description beside it, it is the "
                + "only machine-readable criterion by which an agent can refuse an item "
                + "as out of scope for this iteration");
        iteration.description = required(Field.DESCRIPTION, given.get(Field.DESCRIPTION),
            "an iteration carries a description: what it contains and what it does not. "
                + "Mandatory for the same reason the motto is, and not decoration");
        iteration.number = allocateNumber(scopeId);

        Integer rank = whole(Field.RANK, given.get(Field.RANK));
        if (rank != null) {
            iteration.rank = rank;
        }

        planning.insert(iteration);
        planning.flushAndRefresh(iteration);
        LOG.infof("iteration %d created in scope %s", iteration.number, scopeId);

        return project(iteration, warnings(warningAt(openAfterwards,
            setting.warnPlannedIterations, setting.maxPlannedIterations, OPEN_ITERATIONS)));
    }

    /**
     * Change what is known about an iteration, including its membership
     * order.
     *
     * <p><strong>A write that changes nothing writes nothing.</strong> That
     * covers the order too: sending back the sequence a read answered with
     * moves no row, no timestamp and no token.
     */
    @Transactional
    public Map<String, Object> update(UUID scopeId, UUID iterationId,
            Map<String, ?> arguments) {
        Map<Field, Object> given = Field.resolve(Addressed.ITERATION, arguments);
        Iteration iteration = require(scopeId, iterationId);
        refuseClosed(iteration);
        iteration.requireCurrentToken(given.get(Field.CONFLICT_TOKEN));

        Map<String, Object> current = project(iteration, List.of());
        refuseUnsettableChanges(Addressed.ITERATION, current, given);

        if (!applyEffectiveChanges(iteration, current,
                settableOnly(Addressed.ITERATION, given))) {
            LOG.debugf("update of an iteration in scope %s changed nothing and wrote "
                + "nothing", scopeId);
            return current;
        }

        iteration.stamp();
        planning.flushAndRefresh(iteration);
        LOG.infof("iteration %d updated in scope %s", iteration.number, scopeId);
        return project(iteration, List.of());
    }

    /**
     * Close an iteration: it is done with, and it stays readable.
     *
     * <p>The refusal in front of it NAMES the memberships that are neither
     * done nor dropped. Closing over them would carry an outcome onto each
     * item that nobody chose, and the concept is explicit that doing so is a
     * deliberate, separately expressed act — which this service does not
     * provide and does not fake.
     *
     * <p><strong>What this close does NOT do</strong> is carry each
     * membership's outcome onto its item. That would mean writing an item's
     * status from the planning layer, and on this scheme {@code close}
     * addresses the iteration or the milestone and never an item. The
     * mapping — completed becomes finished, abandoned becomes abandoned —
     * also has nowhere to land: an item's status is a value the SCOPE
     * declared, and no declaration says which of its statuses means finished.
     */
    @Transactional
    public Map<String, Object> close(UUID scopeId, UUID iterationId, String conflictToken) {
        Iteration iteration = require(scopeId, iterationId);
        refuseClosed(iteration);
        iteration.requireCurrentToken(conflictToken);
        refuseLiveMemberships(iteration);

        iteration.closedAt = Instant.now();
        iteration.stamp();

        ScopeSetting setting = requireSetting(scopeId);
        if (iteration.id.equals(setting.currentIterationId)) {
            // The pointer cannot outlive what it points at: a closed current
            // iteration is a state where the draw has somewhere to look and
            // nothing to find, which is the empty answer the concept insists
            // must mean "plan" rather than "close".
            setting.currentIterationId = null;
            setting.stamp();
        }

        planning.flushAndRefresh(iteration);
        LOG.infof("iteration %d closed in scope %s", iteration.number, scopeId);
        return project(iteration, List.of());
    }

    /**
     * Promote the first planned iteration to current.
     *
     * <p>First in the axis's own order — the rank, and the number only as a
     * tiebreak — rather than the lowest number: a scope may decide to work
     * its fifth iteration before its fourth, and the number is an identity
     * rather than a sequence.
     *
     * <p>The pointer lives on the scope's settings and the write presents
     * THEIR token, because that is the aggregate being written. An iteration
     * is not modified by being pointed at.
     */
    @Transactional
    public Map<String, Object> advance(UUID scopeId, String conflictToken) {
        ScopeSetting setting = requireSetting(scopeId);
        setting.requireCurrentToken(conflictToken);

        Iteration next = planning.openIterations(scopeId).stream()
            .filter(iteration -> !iteration.id.equals(setting.currentIterationId))
            .findFirst()
            .orElseThrow(() -> new WorklistException(
                WorklistException.Reason.ITERATION_ABSENT,
                "scope " + scopeId + " has no further open iteration to promote. That is "
                    + "a call to plan one, and it is deliberately a different answer from "
                    + "an iteration that is running with nothing left to do — which is a "
                    + "call to close it",
                List.of(String.valueOf(scopeId))));

        setting.currentIterationId = next.id;
        setting.stamp();
        planning.flushAndRefresh(setting);
        LOG.infof("iteration %d promoted to current in scope %s", next.number, scopeId);
        return ScopeSettingService.project(setting, List.of());
    }

    // ------------------------------------------------------------------
    // The mechanisms the verbs above are made of.
    // ------------------------------------------------------------------

    /**
     * The next number on the time axis, from the scope's persisted mark.
     *
     * <p>Under a write lock, so two concurrent creations cannot read the same
     * value. Advancing the mark does not rotate the settings' conflict token:
     * this is an allocator side effect of a write on the ITERATION aggregate,
     * and rotating there would move a token no caller of this verb holds.
     */
    private long allocateNumber(UUID scopeId) {
        ScopeSetting locked = planning.lockSettingOf(scopeId);
        locked.iterationHighWaterMark = locked.iterationHighWaterMark + 1;
        planning.flush();
        return locked.iterationHighWaterMark;
    }

    private boolean applyEffectiveChanges(Iteration iteration, Map<String, Object> current,
            Map<Field, Object> settable) {
        boolean changed = false;
        for (Map.Entry<Field, Object> entry : settable.entrySet()) {
            changed |= applyOne(iteration, current, entry.getKey(), entry.getValue());
        }
        return changed;
    }

    private boolean applyOne(Iteration iteration, Map<String, Object> current, Field field,
            Object value) {
        Object held = current.get(field.canonicalName());
        switch (field) {
            case MOTTO -> {
                String motto = required(field, value,
                    "an iteration carries a motto on every path, so it cannot be cleared");
                return moved(held, motto, () -> iteration.motto = motto);
            }
            case DESCRIPTION -> {
                String description = required(field, value,
                    "an iteration carries a description on every path, so it cannot be "
                        + "cleared — it is the criterion an agent refuses against");
                return moved(held, description, () -> iteration.description = description);
            }
            case RANK -> {
                Integer rank = whole(field, value);
                return rank != null && moved(held, rank, () -> iteration.rank = rank);
            }
            case ORDER -> {
                return applyOrder(iteration, value);
            }
            default -> throw new IllegalStateException(
                field.canonicalName() + " is settable on an iteration and has no application");
        }
    }

    /**
     * Rewrite the membership sequence to exactly the order given.
     *
     * <p>Every item of the sequence must already be a member: reordering is
     * not a way to plan. An item that is a member and is left out of the
     * sequence is a refusal too, because dropping it silently would remove a
     * membership through a verb that says it is reordering.
     *
     * <p>Positions are dense and rewritten as a whole. The rows carry no
     * token of their own, so this whole rewrite presents the iteration's one
     * token — which is the aggregate rule doing its work.
     */
    private boolean applyOrder(Iteration iteration, Object value) {
        List<IterationMembership> living = planning.membershipsOf(iteration.id);
        List<String> wanted = ItemFields.tokensInOrder(Field.ORDER, value);

        List<String> held = living.stream().map(m -> String.valueOf(m.itemId)).toList();
        if (!wanted.containsAll(held) || !held.containsAll(wanted)) {
            List<String> difference = new ArrayList<>(wanted);
            difference.removeAll(held);
            difference.addAll(held.stream().filter(id -> !wanted.contains(id)).toList());
            throw new WorklistException(
                WorklistException.Reason.MEMBERSHIP_UNKNOWN,
                "an order names exactly the items that ARE members of this iteration, "
                    + "each once. These are named on one side only: " + difference
                    + ". Reordering does not plan an item in and does not unplan one "
                    + "out; those are `plan` and `unplan`, and they say what they do",
                difference);
        }

        boolean changed = false;
        for (int position = 0; position < wanted.size(); position++) {
            changed |= moveTo(living, wanted.get(position), position);
        }
        if (changed) {
            planning.flush();
        }
        return changed;
    }

    private static boolean moveTo(List<IterationMembership> living, String itemId,
            int position) {
        for (IterationMembership membership : living) {
            if (String.valueOf(membership.itemId).equals(itemId)
                    && membership.position != position) {
                membership.position = position;
                return true;
            }
        }
        return false;
    }

    /**
     * A closed iteration takes no further writes.
     *
     * <p>Its memberships stay readable — that is what makes a closed
     * iteration a record rather than a gap — but nothing moves in it again.
     * Without this the close would be a timestamp anybody could write around.
     */
    private static void refuseClosed(Iteration iteration) {
        if (iteration.open()) {
            return;
        }
        throw new WorklistException(
            WorklistException.Reason.ITERATION_CLOSED,
            "iteration " + iteration.number + " was closed at " + iteration.closedAt
                + " and takes no further writes. What it held stays readable; a closed "
                + "iteration is a record of what was worked and not a gap",
            List.of(String.valueOf(iteration.id)));
    }

    /**
     * The close refuses while live memberships stand, and it names them.
     *
     * <p>Naming them is the point. A refusal that only states the rule sends
     * the reader back to the store to work out which rows it meant, and the
     * whole value of checking where the check runs is that the answer is
     * right there.
     */
    private void refuseLiveMemberships(Iteration iteration) {
        List<String> live = planning.membershipsOf(iteration.id).stream()
            .filter(IterationMembership::live)
            .map(membership -> String.valueOf(membership.itemId))
            .toList();
        if (live.isEmpty()) {
            return;
        }
        throw new WorklistException(
            WorklistException.Reason.ITERATION_INCOMPLETE,
            "iteration " + iteration.number + " still holds memberships that are neither "
                + "done nor dropped: " + live + ". Closing over them would decide for you "
                + "what happened to each one. Mark each membership's outcome first",
            live);
    }

    private static boolean moved(Object held, Object wanted, Runnable write) {
        if (Objects.equals(held == null ? null : String.valueOf(held),
                wanted == null ? null : String.valueOf(wanted))) {
            return false;
        }
        write.run();
        return true;
    }

    /** The iteration of that id in that scope, or a typed refusal. */
    private Iteration require(UUID scopeId, UUID iterationId) {
        Iteration iteration = planning.iterationById(iterationId);
        if (iteration == null || !iteration.scopeId.equals(scopeId)) {
            throw new WorklistException(
                WorklistException.Reason.ITERATION_UNKNOWN,
                "no iteration " + iterationId + " in scope " + scopeId,
                List.of(String.valueOf(iterationId)));
        }
        return iteration;
    }

    /**
     * The iteration as the canonical field map — the one answer shape, used
     * by the reads AND by the comparison the writes make.
     */
    private Map<String, Object> project(Iteration iteration, List<String> warnings) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(Field.ID.canonicalName(), iteration.id);
        fields.put(Field.SCOPE.canonicalName(), iteration.scopeId);
        fields.put(Field.NUMBER.canonicalName(), iteration.number);
        fields.put(Field.MOTTO.canonicalName(), iteration.motto);
        fields.put(Field.DESCRIPTION.canonicalName(), iteration.description);
        fields.put(Field.RANK.canonicalName(), iteration.rank);
        fields.put(Field.ORDER.canonicalName(), order(iteration));
        fields.put(Field.CLOSED_AT.canonicalName(), iteration.closedAt);
        fields.put(Field.CREATED_AT.canonicalName(), iteration.createdAt);
        fields.put(Field.UPDATED_AT.canonicalName(), iteration.updatedAt);
        fields.put(Field.CONFLICT_TOKEN.canonicalName(), iteration.conflictToken);
        fields.put(Field.WARNINGS.canonicalName(), warnings);
        return fields;
    }

    /** The membership sequence, as the item identities in their order. */
    private List<UUID> order(Iteration iteration) {
        return planning.membershipsOf(iteration.id).stream()
            .map(membership -> membership.itemId)
            .toList();
    }
}
