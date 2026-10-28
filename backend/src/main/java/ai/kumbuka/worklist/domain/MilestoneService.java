/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.repository.ScopeAccessRepository;
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
     * The write-path caps on the three text fields, matching the check
     * constraints V7 carries on the columns. A value over the cap is refused
     * at both layers — here as a typed refusal, at the flush as a constraint
     * violation — and the two must not disagree.
     */
    static final int TITLE_MAX = 200;

    /** Matching {@code ck_milestone_vision_length} from V7. */
    static final int VISION_MAX = 200;

    /** Matching {@code ck_milestone_mission_length} from V7. */
    static final int MISSION_MAX = 1500;

    /**
     * The number spaces the three axes allocate from live under their
     * selector, and the milestone selector is the one this allocator reads.
     */
    @Inject SelectorRegistry selectors;
    @Inject ScopeAccessRepository scopeAccess;

    // ------------------------------------------------------------------
    // Reading.
    // ------------------------------------------------------------------

    /** One milestone, as the canonical field map. */
    @Transactional
    public Map<String, Object> read(UUID scopeId, UUID milestoneId) {
        return project(require(scopeId, milestoneId), List.of());
    }

    /**
     * One page of a scope's milestones, narrowed by the filters the milestone
     * view declares, in the request's sort.
     */
    @Transactional
    public Page query(UUID scopeId, PageRequest request) {
        return Page.of(request, planning.milestonePage(scopeId, request),
            milestone -> PageCursor.Position.of(request.sort(), milestone.number,
                milestone.createdAt, milestone.rank),
            milestone -> project(milestone, List.of()));
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

        Milestone milestone = new Milestone();
        milestone.scopeId = scopeId;
        milestone.title = capped(Field.TITLE, required(Field.TITLE, given.get(Field.TITLE)),
            TITLE_MAX);
        milestone.number = allocateNumber(scopeId);

        Map<Field, Object> settable = settableOnly(Addressed.MILESTONE, given);
        settable.remove(Field.TITLE);
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
        Milestone milestone = requireLocked(scopeId, milestoneId);
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
        return selectors.allocate(scopeId, Selector.MILESTONE);
    }

    /**
     * Apply the settable fields that actually differ, and report whether any
     * did.
     *
     * <p>The milestone is stamped at the FIRST field that changes it, not
     * after the last. Activating reads the active milestone and flushes its
     * demotion, and a read or a flush after the title was set would meet the
     * changed milestone not yet stamped, which is refused there. So a caller
     * of this method does not stamp again.
     */
    private boolean applyEffectiveChanges(Milestone milestone, Map<String, Object> current,
            Map<Field, Object> settable) {
        boolean changed = false;
        for (Map.Entry<Field, Object> entry : settable.entrySet()) {
            if (applyOne(milestone, current, entry.getKey(), entry.getValue()) && !changed) {
                milestone.stamp(clock.instant());
                changed = true;
            }
        }
        return changed;
    }

    private boolean applyOne(Milestone milestone, Map<String, Object> current, Field field,
            Object value) {
        Object held = current.get(field.canonicalName());
        switch (field) {
            case TITLE -> {
                String title = capped(field, required(field, value),
                    TITLE_MAX);
                return moved(held, title, () -> milestone.title = title);
            }
            case KIND -> {
                String kind = oneOf(field, required(field, value), Milestone.KINDS);
                return moved(held, kind, () -> milestone.kind = kind);
            }
            case STATUS -> {
                return applyStatus(milestone, held, field, value);
            }
            case VISION -> {
                String vision = ItemFields.text(field, value)
                    .map(text -> capped(field, text, VISION_MAX)).orElse(null);
                return moved(held, vision, () -> milestone.vision = vision);
            }
            case MISSION -> {
                String mission = ItemFields.text(field, value)
                    .map(text -> capped(field, text, MISSION_MAX)).orElse(null);
                return moved(held, mission, () -> milestone.mission = mission);
            }
            case RANK -> {
                Optional<Integer> rank = whole(field, value);
                return rank.isPresent() && moved(held, rank.get(), () -> milestone.rank = rank.get());
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
    private boolean applyStatus(Milestone milestone, @Nullable Object held, Field field,
            @Nullable Object value) {
        String status = oneOf(field, required(field, value),
            Milestone.STATUSES);
        if (ItemFields.unchangedAsText(held, status)) {
            return false;
        }

        if (Milestone.ACTIVE.equals(status)) {
            Milestone current = planning.activeMilestone(milestone.scopeId).orElse(null);
            if (current != null && !current.id.equals(milestone.id)) {
                current.status = Milestone.PLANNED;
                current.stamp(clock.instant());
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
            List.of(Field.KIND.path()));
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
     * The milestone of that id in that scope, or a typed refusal.
     *
     * <p>No transaction of its own, and no annotation claiming one: it is
     * called from a verb that is already inside one, and a self-call reaches
     * no interceptor anyway. An annotation here would say something that is
     * not true of the call.
     */
    private Milestone require(UUID scopeId, UUID milestoneId) {
        return requireIn(scopeId, planning.milestoneById(milestoneId).orElse(null));
    }

    /**
     * The same, locked and read as it stands under the lock. Every write that
     * checks the milestone's token loads it here (C10.2, C11.1).
     */
    private Milestone requireLocked(UUID scopeId, UUID milestoneId) {
        return requireIn(scopeId, planning.lockMilestoneById(milestoneId).orElse(null));
    }

    /**
     * @param milestone the milestone the id named; absent where it names none,
     *                  which is refused like a milestone of another scope
     */
    private static Milestone requireIn(UUID scopeId, @Nullable Milestone milestone) {
        if (milestone == null || !milestone.scopeId.equals(scopeId)) {
            throw new WorklistException(
                WorklistException.Reason.MILESTONE_UNKNOWN,
                List.of());
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
    private Map<String, Object> project(Milestone milestone,
            List<Notice> warnings) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(Field.ID.canonicalName(), milestone.id);
        fields.put(Field.SCOPE.canonicalName(), slugOf(milestone.scopeId));
        fields.put(Field.NUMBER.canonicalName(), milestone.number);
        fields.put(Field.TITLE.canonicalName(), milestone.title);
        fields.put(Field.KIND.canonicalName(), milestone.kind);
        fields.put(Field.STATUS.canonicalName(), milestone.status);
        fields.put(Field.VISION.canonicalName(), milestone.vision);
        fields.put(Field.MISSION.canonicalName(), milestone.mission);
        fields.put(Field.RANK.canonicalName(), milestone.rank);
        // No `workstream`. Retracted from the milestone on 2026-09-28: the
        // edge itself went in V12, and the field stayed answerable for
        // nineteen days after it — which is how a caller came to file
        // milestones per workstream and build on the conclusion.
        // `Field.RETRACTED` carries the sentence one who still sends it gets.
        fields.put(Field.CREATED_AT.canonicalName(), milestone.createdAt);
        fields.put(Field.UPDATED_AT.canonicalName(), milestone.updatedAt);
        fields.put(Field.CONFLICT_TOKEN.canonicalName(), milestone.conflictToken().orElse(null));
        fields.put(Field.WARNINGS.canonicalName(), warnings);
        return fields;
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
