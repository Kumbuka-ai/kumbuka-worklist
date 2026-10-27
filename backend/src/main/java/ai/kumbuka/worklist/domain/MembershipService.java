/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.repository.ItemRepository;
import ai.kumbuka.worklist.repository.ScopeAccessRepository;
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

    /** The argument a refusal about the addressed membership names (DEC-0043 R18). */
    private static final String ADDRESS = "address";


    /** An address, a scope id, a count, a transition. Never a title, never an actor. */
    private static final Logger LOG = Logger.getLogger(MembershipService.class);


    @Inject ItemRepository items;
    @Inject VocabularyRegistry vocabulary;
    @Inject ScopeAccessRepository scopeAccess;

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
        return planning.plannedItemNumbers(scopeId).stream()
            .map(number -> itemIdOf(scopeId, number))
            .toList();
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
        Iteration iteration = requireLockedIteration(scopeId, iterationId);
        iteration.requireCurrentToken(conflictToken);
        Item item = refuseUnplannable(scopeId, itemId);

        if (planning.membership(scopeId, iteration.number, item.number) != null) {
            throw new WorklistException(
                WorklistException.Reason.MEMBERSHIP_PRESENT,
                List.of(ADDRESS));
        }

        ScopeSetting setting = requireSetting(scopeId);
        List<IterationMembership> living = planning.membershipsOf(scopeId, iteration.number);
        int afterwards = living.size() + 1;
        refuseBeyond(afterwards, setting.maxMembershipsPerIteration);

        IterationMembership membership = new IterationMembership();
        membership.scopeId = scopeId;
        membership.iterationNumber = iteration.number;
        membership.itemNumber = item.number;
        membership.position = living.size();
        planning.insert(membership);

        iteration.stamp(clock.instant());
        planning.flushAndRefresh(membership);
        LOG.infof("item planned into iteration %d in scope %s", iteration.number, scopeId);

        return project(iteration, membership, warnings(warningAt(afterwards,
            setting.warnMembershipsPerIteration, Notice.MEMBERSHIPS_AT_THRESHOLD)));
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
        Iteration iteration = requireLockedIteration(scopeId, iterationId);
        iteration.requireCurrentToken(conflictToken);
        IterationMembership membership = require(iteration, itemId);

        if (IterationMembership.DROPPED.equals(membership.status)) {
            return project(iteration, membership, List.of());
        }

        membership.status = IterationMembership.DROPPED;
        closeUpBehind(iteration, membership.position);

        iteration.stamp(clock.instant());
        planning.flushAndRefresh(membership);
        LOG.infof("item unplanned from iteration %d in scope %s", iteration.number, scopeId);
        return project(iteration, membership, List.of());
    }

    /**
     * Move a membership's own status to one of the four states
     * {@link IterationMembership} declares.
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
        Iteration iteration = requireLockedIteration(scopeId, iterationId);
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
            required(Field.MEMBERSHIP_STATUS, wanted),
            IterationMembership.STATUSES);
        if (status.equals(membership.status)) {
            LOG.debugf("update of a membership in scope %s changed nothing and wrote "
                + "nothing", scopeId);
            return current;
        }

        if (IterationMembership.ACTIVE.equals(status)) {
            demoteTheActiveMembership(iteration, membership.itemNumber);
        }
        membership.status = status;

        iteration.stamp(clock.instant());
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
     * <p>The milestone is assigned through {@code item.update} against
     * {@link Field#MILESTONE_ID}, and the item verb runs the existence check
     * — same scope, milestone not closed.
     */
    private Item refuseUnplannable(UUID scopeId, UUID itemId) {
        Item item = items.byId(itemId);
        if (item == null || !item.scopeId.equals(scopeId)) {
            throw new WorklistException(
                WorklistException.Reason.ITEM_UNKNOWN,
                List.of(ADDRESS));
        }

        ItemStatus status = vocabulary.requireStatus(scopeId, item.statusPk);
        if (!status.actionable) {
            throw new WorklistException(
                WorklistException.Reason.ITEM_UNPLANNABLE,
                List.of(ADDRESS));
        }

        if (item.milestoneNumber == null) {
            throw new WorklistException(
                WorklistException.Reason.ITEM_UNPLANNABLE,
                List.of(ADDRESS));
        }

        Milestone milestone = planning.milestoneByNumber(scopeId, item.milestoneNumber);
        if (milestone == null || !milestone.onTheProductPath()) {
            throw new WorklistException(
                WorklistException.Reason.ITEM_UNPLANNABLE,
                List.of(ADDRESS));
        }
        return item;
    }

    /**
     * Demote whichever membership of this iteration is active, and flush.
     *
     * <p>Flushed before the caller sets its own row, because the partial
     * unique index does not care which statement in a batch came first: two
     * rows carrying {@code active} at flush time is what it refuses, and it
     * would refuse the write that was removing the condition.
     */
    private void demoteTheActiveMembership(Iteration iteration, Long exceptItemNumber) {
        for (IterationMembership other
                : planning.membershipsOf(iteration.scopeId, iteration.number)) {
            if (IterationMembership.ACTIVE.equals(other.status)
                    && !other.itemNumber.equals(exceptItemNumber)) {
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
    private void closeUpBehind(Iteration iteration, int vacated) {
        for (IterationMembership membership
                : planning.membershipsOf(iteration.scopeId, iteration.number)) {
            if (membership.live() && membership.position > vacated) {
                membership.position = membership.position - 1;
            }
        }
    }

    private Iteration requireIteration(UUID scopeId, UUID iterationId) {
        return requireOpen(scopeId, planning.iterationById(iterationId));
    }

    /**
     * The iteration, locked and read as it stands under the lock, or the same
     * refusals. A membership write presents the iteration's token and moves
     * it, so the iteration is the row the comparison and the write are atomic
     * on (C10.2, C11.1).
     */
    private Iteration requireLockedIteration(UUID scopeId, UUID iterationId) {
        return requireOpen(scopeId, planning.lockIterationById(iterationId));
    }

    private static Iteration requireOpen(UUID scopeId, Iteration iteration) {
        if (iteration == null || !iteration.scopeId.equals(scopeId)) {
            throw new WorklistException(
                WorklistException.Reason.ITERATION_UNKNOWN,
                List.of(ADDRESS));
        }
        if (!iteration.open()) {
            throw new WorklistException(
                WorklistException.Reason.ITERATION_CLOSED,
                List.of(ADDRESS));
        }
        return iteration;
    }

    private IterationMembership require(Iteration iteration, UUID itemId) {
        Item item = items.byId(itemId);
        IterationMembership membership = item == null || !item.scopeId.equals(iteration.scopeId)
            ? null
            : planning.membership(iteration.scopeId, iteration.number, item.number);
        if (membership == null) {
            throw new WorklistException(
                WorklistException.Reason.MEMBERSHIP_UNKNOWN,
                List.of(ADDRESS));
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
    private Map<String, Object> project(Iteration iteration,
            IterationMembership membership, List<Notice> warnings) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(Field.SCOPE.canonicalName(), slugOf(membership.scopeId));
        fields.put(Field.ITERATION_ID.canonicalName(), iteration.number);
        fields.put(Field.ITEM_ID.canonicalName(),
            itemIdOf(membership.scopeId, membership.itemNumber));
        fields.put(Field.POSITION.canonicalName(), membership.position);
        fields.put(Field.MEMBERSHIP_STATUS.canonicalName(), membership.status);
        fields.put(Field.CREATED_AT.canonicalName(), membership.createdAt);
        fields.put(Field.UPDATED_AT.canonicalName(), membership.updatedAt);
        fields.put(Field.CONFLICT_TOKEN.canonicalName(), iteration.conflictToken());
        fields.put(Field.WARNINGS.canonicalName(), warnings);
        return fields;
    }

    /**
     * The identity of the item a membership holds by number. The answer still
     * names the item by its identity; only the stored reference changed form.
     */
    private UUID itemIdOf(UUID scopeId, Long itemNumber) {
        Item item = items.byAddress(scopeId, itemNumber);
        return item == null ? null : item.id;
    }

    /** The slug of a scope, or the scope's id when the access row is absent. */
    private String slugOf(UUID scopeId) {
        try {
            return scopeAccess.findByScopeId(scopeId)
                .map(ScopeAccessRepository.ScopeAccessRow::slug)
                .orElse(String.valueOf(scopeId));
        } catch (RuntimeException notReadable) {
            return String.valueOf(scopeId);
        }
    }
}
