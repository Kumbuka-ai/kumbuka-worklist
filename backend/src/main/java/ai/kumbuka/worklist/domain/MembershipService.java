package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.repository.ItemRepository;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One item's membership of one iteration: entering, leaving, and how far this
 * iteration has got with it.
 *
 * <h2>The verbs</h2>
 *
 * {@code plan} puts an item into an iteration at the end of its sequence,
 * {@code unplan} takes it out, {@code update} moves the membership's own
 * status, and {@code query} answers which items of a scope are PLANNED.
 *
 * <p>The reorder is not here. It is addressed at the iteration, because a
 * reorder writes the iteration's whole sequence under the iteration's one
 * conflict token — see {@link IterationService#update}.
 *
 * <h2>Every write here presents the ITERATION's token</h2>
 *
 * A membership is addressed at its own address and owns no token. That is not
 * an omission: a token of its own would make the row the aggregate again, and
 * a reorder of twelve memberships would have to present twelve tokens instead
 * of one. Addressing and token ownership are two different things and this is
 * the class where they come apart.
 *
 * <h2>{@code planned} is derived here and stored nowhere</h2>
 *
 * {@link #query} is the one place that answers it, and what it runs is a
 * query over memberships and iterations. There is no column and no status
 * value anywhere that means planned, which is what makes the orphan class —
 * an item reading planned with no membership — inexpressible rather than
 * merely forbidden. It was observed twice in the predecessor.
 */
@ApplicationScoped
@TenantBound
public class MembershipService extends PlanningService {

    /** An address, a scope id, a count, a transition. Never a title, never an actor. */
    private static final Logger LOG = Logger.getLogger(MembershipService.class);

    /** What the cardinality refusal and its warning call the thing being counted. */
    private static final String MEMBERSHIPS = "the number of items in this iteration";

    @Inject ItemRepository items;
    @Inject VocabularyRegistry vocabulary;

    // ------------------------------------------------------------------
    // Reading.
    // ------------------------------------------------------------------

    /**
     * <strong>Which items of this scope are planned.</strong>
     *
     * <p>The one place that derives it. An item is planned when it has a LIVE
     * membership — neither done nor dropped — of an OPEN iteration, and both
     * halves were defects in the predecessor: membership alone reports an item
     * planned after the iteration closed over it, and an open iteration alone
     * reports one planned after this iteration had finished with it.
     *
     * <p>It answers identities rather than projections. What "planned" is, is
     * a property of the item as the PLANNING layer sees it; assembling the
     * item's own answer here would mean a second projection of an item beside
     * the one the item verbs give, and the two would drift.
     */
    @Transactional
    public List<UUID> query(UUID scopeId) {
        return planning.plannedItemIds(scopeId);
    }

    /** One membership, as the canonical field map. */
    @Transactional
    public Map<String, Object> read(UUID scopeId, UUID iterationId, UUID itemId) {
        Iteration iteration = requireIteration(scopeId, iterationId);
        return project(iteration, require(iteration, itemId), List.of());
    }

    // ------------------------------------------------------------------
    // Writing.
    // ------------------------------------------------------------------

    /**
     * Put an item into an iteration, at the end of its sequence.
     *
     * <p>The preconditions are the concept's and both refusals NAME the value
     * they found: an item enters an iteration only when it is
     * <strong>actionable</strong> and carries a milestone that is
     * <strong>on the product path</strong>. An item off the path is not
     * plannable and an item without a milestone is not worked out.
     *
     * <p>The cardinality of the iteration is checked before the write: the
     * hard limit refuses, the advisory threshold warns in the answer and
     * admits. Both numbers come from the scope's own row.
     *
     * <p>The position is the end of the sequence and is not given. Where in
     * the sequence it should sit is the reorder's question, addressed at the
     * iteration, and answering it here would be a second way to write a
     * position.
     */
    @Transactional
    public Map<String, Object> plan(UUID scopeId, UUID iterationId, UUID itemId,
            String conflictToken) {
        Iteration iteration = requireIteration(scopeId, iterationId);
        iteration.requireCurrentToken(conflictToken);
        refuseUnplannable(scopeId, itemId);

        if (planning.membership(iterationId, itemId) != null) {
            throw new WorklistException(
                WorklistException.Reason.MEMBERSHIP_PRESENT,
                "item " + itemId + " is already in iteration " + iteration.number
                    + ". Planning it again would have to displace the membership that is "
                    + "there, along with its status and its place in the sequence",
                List.of(String.valueOf(itemId)));
        }

        ScopeSetting setting = requireSetting(scopeId);
        List<IterationMembership> living = planning.membershipsOf(iterationId);
        int afterwards = living.size() + 1;
        refuseBeyond(afterwards, setting.maxMembershipsPerIteration, MEMBERSHIPS);

        IterationMembership membership = new IterationMembership();
        membership.scopeId = scopeId;
        membership.iterationId = iterationId;
        membership.itemId = itemId;
        membership.position = living.size();
        planning.insert(membership);

        iteration.stamp();
        planning.flushAndRefresh(membership);
        LOG.infof("item planned into iteration %d in scope %s", iteration.number, scopeId);

        return project(iteration, membership, warnings(warningAt(afterwards,
            setting.warnMembershipsPerIteration, setting.maxMembershipsPerIteration,
            MEMBERSHIPS)));
    }

    /**
     * Take an item out of an iteration.
     *
     * <p>Symmetrical to {@link #plan} and carrying no status precondition on
     * either axis: a membership in any state can be removed, and the item
     * returns to the pool it came from.
     *
     * <p><strong>The row is not deleted, because this schema grants DELETE
     * nowhere.</strong> The membership moves to {@code dropped}, which is
     * terminal, so the derivation stops counting it as planned — the visible
     * effect a caller asked for — and the record of the item having been in
     * this iteration survives.
     *
     * <p>The positions behind it are closed up, so the sequence stays dense.
     * A gap would be a position nothing occupies and the next reorder would
     * silently rewrite it anyway.
     */
    @Transactional
    public Map<String, Object> unplan(UUID scopeId, UUID iterationId, UUID itemId,
            String conflictToken) {
        Iteration iteration = requireIteration(scopeId, iterationId);
        iteration.requireCurrentToken(conflictToken);
        IterationMembership membership = require(iteration, itemId);

        if (IterationMembership.DROPPED.equals(membership.status)) {
            return project(iteration, membership, List.of());
        }

        membership.status = IterationMembership.DROPPED;
        closeUpBehind(iterationId, membership.position);

        iteration.stamp();
        planning.flushAndRefresh(membership);
        LOG.infof("item unplanned from iteration %d in scope %s", iteration.number, scopeId);
        return project(iteration, membership, List.of());
    }

    /**
     * Move a membership's own status: todo, active, done or dropped.
     *
     * <p><strong>At most one membership of an iteration is active</strong>,
     * and that is held by a partial unique index rather than by this method.
     * What this method does is express the intention in one write —
     * activating demotes any other active membership of the same iteration,
     * flushed first, because the index would otherwise see two active rows in
     * one batch and refuse the write that was removing the condition.
     *
     * <p>The predecessor had two verbs disagreeing about who may set this
     * field, one refusing a fresh activation and one setting it without
     * comment. Two verbs disagreeing about who writes a field is a defect
     * regardless of which of them is right, which is why the invariant sits
     * in the store.
     */
    @Transactional
    public Map<String, Object> update(UUID scopeId, UUID iterationId, UUID itemId,
            Map<String, ?> arguments) {
        Map<Field, Object> given = Field.resolve(Addressed.MEMBERSHIP, arguments);
        Iteration iteration = requireIteration(scopeId, iterationId);
        iteration.requireCurrentToken(given.get(Field.CONFLICT_TOKEN));
        IterationMembership membership = require(iteration, itemId);

        Map<String, Object> current = project(iteration, membership, List.of());
        refuseUnsettableChanges(Addressed.MEMBERSHIP, current, given);

        Object wanted = settableOnly(Addressed.MEMBERSHIP, given)
            .get(Field.MEMBERSHIP_STATUS);
        if (wanted == null) {
            LOG.debugf("update of a membership in scope %s named nothing settable and "
                + "wrote nothing", scopeId);
            return current;
        }

        String status = oneOf(Field.MEMBERSHIP_STATUS,
            required(Field.MEMBERSHIP_STATUS, wanted,
                "a membership carries a status on every path, so it cannot be cleared"),
            IterationMembership.STATUSES);
        if (status.equals(membership.status)) {
            LOG.debugf("update of a membership in scope %s changed nothing and wrote "
                + "nothing", scopeId);
            return current;
        }

        if (IterationMembership.ACTIVE.equals(status)) {
            demoteTheActiveMembership(iterationId, itemId);
        }
        membership.status = status;

        iteration.stamp();
        planning.flushAndRefresh(membership);
        LOG.infof("membership status moved in iteration %d, scope %s", iteration.number,
            scopeId);
        return project(iteration, membership, List.of());
    }

    // ------------------------------------------------------------------
    // The mechanisms the verbs above are made of.
    // ------------------------------------------------------------------

    /**
     * An item enters an iteration only when it is actionable and carries a
     * milestone on the product path.
     *
     * <p>Three distinct causes and one reason code, with the offender naming
     * which. They read the same from outside and the remedy for each is
     * different: an item that is not actionable needs its status moved, an
     * item without a milestone needs the axis assigned, and one off the path
     * needs a decision that it belongs on it.
     *
     * <p><strong>The second of those has no verb behind it today.</strong>
     * Nothing in this service assigns {@code item.milestone_id} — see
     * {@link MilestoneService} for why that gap is reported rather than
     * closed here.
     */
    private void refuseUnplannable(UUID scopeId, UUID itemId) {
        Item item = items.byId(itemId);
        if (item == null || !item.scopeId.equals(scopeId)) {
            throw new WorklistException(
                WorklistException.Reason.ITEM_UNKNOWN,
                "no item " + itemId + " in scope " + scopeId,
                List.of(String.valueOf(itemId)));
        }

        ItemStatus status = vocabulary.requireStatus(scopeId, item.statusId);
        if (!status.actionable) {
            throw new WorklistException(
                WorklistException.Reason.ITEM_UNPLANNABLE,
                "item " + itemId + " carries the status " + status.name + ", which this "
                    + "scope declared as not actionable. An iteration is what is being "
                    + "worked, and something that cannot be worked on has no place in "
                    + "one",
                List.of(status.name));
        }

        if (item.milestoneId == null) {
            throw new WorklistException(
                WorklistException.Reason.ITEM_UNPLANNABLE,
                "item " + itemId + " carries no milestone, so nothing says which goal "
                    + "working it would serve. An item without one is not worked out "
                    + "yet — including the three markers, which ARE milestones and say "
                    + "so explicitly",
                List.of(String.valueOf(itemId)));
        }

        Milestone milestone = planning.milestoneById(item.milestoneId);
        if (milestone == null || !milestone.onTheProductPath()) {
            String found = milestone == null ? "a milestone that does not resolve"
                : milestone.kind;
            throw new WorklistException(
                WorklistException.Reason.ITEM_UNPLANNABLE,
                "item " + itemId + " carries " + found + ", which is not on the product "
                    + "path. Only " + Milestone.ON_THE_PRODUCT_PATH + " are; the other "
                    + "two positions on the axis say the item is off the path or has not "
                    + "been assessed, and neither is something to spend an iteration on",
                List.of(found));
        }
    }

    /**
     * Demote whichever membership of this iteration is active, and flush.
     *
     * <p>Flushed before the caller sets its own row, because the partial
     * unique index does not care which statement in a batch came first: two
     * rows carrying {@code active} at flush time is what it refuses, and it
     * would refuse the write that was removing the condition.
     */
    private void demoteTheActiveMembership(UUID iterationId, UUID exceptItemId) {
        for (IterationMembership other : planning.membershipsOf(iterationId)) {
            if (IterationMembership.ACTIVE.equals(other.status)
                    && !other.itemId.equals(exceptItemId)) {
                other.status = IterationMembership.TODO;
                planning.flush();
            }
        }
    }

    /**
     * Close the sequence up behind a membership that has left it.
     *
     * <p>Dense, because position is the sequence and a gap in it is a
     * position nothing occupies. The dropped row keeps the position it had:
     * nothing reads the position of a terminal membership for order, and
     * rewriting it would be a change to a row that is supposed to stand as it
     * was — the same reasoning the item domain applies to a withdrawn
     * reference's ordinal.
     */
    private void closeUpBehind(UUID iterationId, int vacated) {
        for (IterationMembership membership : planning.membershipsOf(iterationId)) {
            if (membership.live() && membership.position > vacated) {
                membership.position = membership.position - 1;
            }
        }
    }

    private Iteration requireIteration(UUID scopeId, UUID iterationId) {
        Iteration iteration = planning.iterationById(iterationId);
        if (iteration == null || !iteration.scopeId.equals(scopeId)) {
            throw new WorklistException(
                WorklistException.Reason.ITERATION_UNKNOWN,
                "no iteration " + iterationId + " in scope " + scopeId,
                List.of(String.valueOf(iterationId)));
        }
        if (!iteration.open()) {
            throw new WorklistException(
                WorklistException.Reason.ITERATION_CLOSED,
                "iteration " + iteration.number + " was closed at " + iteration.closedAt
                    + " and its memberships take no further writes. They stay readable, "
                    + "which is what makes a closed iteration a record rather than a gap",
                List.of(String.valueOf(iterationId)));
        }
        return iteration;
    }

    private IterationMembership require(Iteration iteration, UUID itemId) {
        IterationMembership membership = planning.membership(iteration.id, itemId);
        if (membership == null) {
            throw new WorklistException(
                WorklistException.Reason.MEMBERSHIP_UNKNOWN,
                "item " + itemId + " is not in iteration " + iteration.number
                    + " of scope " + iteration.scopeId,
                List.of(String.valueOf(itemId)));
        }
        return membership;
    }

    /**
     * The membership as the canonical field map, carrying the ITERATION's
     * conflict token.
     *
     * <p>That is the aggregate rule made visible in the answer: a caller
     * reads a membership, changes its status and sends the map back, and what
     * travels with it is the token of the thing that owns the write.
     */
    private static Map<String, Object> project(Iteration iteration,
            IterationMembership membership, List<String> warnings) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(Field.ID.canonicalName(), membership.itemId);
        fields.put(Field.SCOPE.canonicalName(), membership.scopeId);
        fields.put(Field.ITERATION_ID.canonicalName(), membership.iterationId);
        fields.put(Field.ITEM_ID.canonicalName(), membership.itemId);
        fields.put(Field.POSITION.canonicalName(), membership.position);
        fields.put(Field.MEMBERSHIP_STATUS.canonicalName(), membership.status);
        fields.put(Field.CREATED_AT.canonicalName(), membership.createdAt);
        fields.put(Field.UPDATED_AT.canonicalName(), membership.updatedAt);
        fields.put(Field.CONFLICT_TOKEN.canonicalName(), iteration.conflictToken);
        fields.put(Field.WARNINGS.canonicalName(), warnings);
        return fields;
    }
}
