/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.repository.ItemRepository;
import ai.kumbuka.worklist.repository.WorkstreamRepository;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongFunction;

/**
 * The calls open on an object in its state, and the state itself: what an
 * answer names under {@code next} and a refusal by state under {@code state}
 * and {@code next} (service contract C6.1, C8.7; DEC-0043).
 *
 * <h2>The same decisions that admit and refuse the calls</h2>
 *
 * Every condition here is the one the verb itself refuses on, read through the
 * same predicate rather than a table beside it: {@link Iteration#open},
 * {@link Milestone#open}, {@link MembershipService#plannable},
 * {@link IterationService#promotable}, {@link PlanningService#within}, and the
 * membership row {@code plan} refuses as present. So a call named here is
 * never refused for the object's state: a second table would be one the
 * verbs drift away from.
 *
 * <p>Per object and state:
 * <ul>
 *   <li>an item, in every state: {@code read}, {@code update}, {@code relate},
 *       {@code unrelate}, {@code tag_attach}, {@code tag_detach}, {@code book},
 *       and {@code plan} into each open iteration it is no member of, where
 *       the item may enter an iteration at all and the iteration has room. A
 *       closed item stays writable, and takes a booking from every caller
 *       who may write; narrowing {@code book} on a closed item to the
 *       identity that concludes an instance comes with a later step;</li>
 *   <li>an open iteration: {@code read}, {@code update}, {@code close},
 *       {@code plan} at its own address while it has room, and
 *       {@code advance} at the iteration collection while an open iteration
 *       other than the current one stands; a closed one: {@code read};</li>
 *   <li>a milestone planned or active: {@code read}, {@code update},
 *       {@code close}; closed: {@code read};</li>
 *   <li>a membership of an open iteration: {@code read}, {@code update},
 *       {@code unplan}; of a closed one: {@code read};</li>
 *   <li>a workstream: {@code read}.</li>
 * </ul>
 *
 * <p>Whether the caller may write is not decided here: that is the platform's
 * read contract, which the surface has already asked. The surface drops the
 * calls that {@link Verb#writes()} where the caller may only read, so a
 * reader is named the reading calls alone.
 *
 * <p>No object of this service waits for another party, so nothing here
 * produces a {@code waiting_for}.
 */
@ApplicationScoped
@TenantBound
public class OpenCallService extends PlanningService {

    /** The word an iteration's state is read as: an open one takes writes. */
    public static final String OPEN = "open";

    /** The word a closed iteration, and a membership of one, is read as. */
    public static final String CLOSED = "closed";

    @Inject ItemRepository items;
    @Inject VocabularyRegistry vocabulary;
    @Inject WorkstreamRepository workstreams;

    /** A call a caller may make on an object, by the verb of the surface. */
    public enum Verb {
        READ(false),
        UPDATE(true),
        CLOSE(true),
        ADVANCE(true),
        PLAN(true),
        UNPLAN(true),
        RELATE(true),
        UNRELATE(true),
        TAG_ATTACH(true),
        TAG_DETACH(true),
        BOOK(true);

        private final boolean writes;

        Verb(boolean writes) {
            this.writes = writes;
        }

        /** Whether the call writes, and so is open only to a caller who may write. */
        public boolean writes() {
            return writes;
        }
    }

    /**
     * One open call, at the address it is made at.
     *
     * @param number the object's number in its view; absent where the call is
     *               made at the view's collection ({@code advance})
     * @param member the item number of a membership; absent where the address
     *               names an object rather than a membership
     */
    public record Call(Verb verb, String view, @Nullable Long number, @Nullable Long member) {
    }

    /**
     * An object's state and the calls open on it in that state.
     *
     * @param state the state as the object's read answers it: an item's
     *              status, a milestone's status, {@link #OPEN} or
     *              {@link #CLOSED} for an iteration and a membership of it, a
     *              workstream's status
     */
    public record Open(String state, List<Call> calls) {
    }

    // ------------------------------------------------------------------
    // Per object
    // ------------------------------------------------------------------

    /** The calls open on one item. */
    @Transactional
    public Open forItem(UUID scopeId, long number) {
        return itemsOf(scopeId).apply(number);
    }

    /**
     * The calls open on items of one scope, with the scope's open iterations
     * read once: a listing asks it for every item it answers.
     */
    @Transactional
    public LongFunction<Open> itemsOf(UUID scopeId) {
        List<Iteration> open = planning.openIterations(scopeId);
        Optional<ScopeSetting> setting = planning.settingOf(scopeId);
        List<Room> rooms = new ArrayList<>();
        for (Iteration iteration : open) {
            List<IterationMembership> held = planning.membershipsOf(scopeId, iteration.number);
            Set<Long> members = new HashSet<>();
            held.forEach(membership -> members.add(membership.itemNumber));
            rooms.add(new Room(iteration.number, members,
                setting.map(s -> within(held.size() + 1, s.maxMembershipsPerIteration))
                    .orElse(false)));
        }
        return new Items(scopeId, List.copyOf(rooms))::open;
    }

    /** The calls open on one iteration. */
    @Transactional
    public Open forIteration(UUID scopeId, long number) {
        Iteration iteration = planning.iterationByNumber(scopeId, number)
            .orElseThrow(() -> new WorklistException(
                WorklistException.Reason.ITERATION_UNKNOWN, List.of()));
        if (!iteration.open()) {
            return new Open(CLOSED, List.of(at(Verb.READ, Selector.ITERATION, number)));
        }
        Optional<ScopeSetting> setting = planning.settingOf(scopeId);
        List<Call> calls = new ArrayList<>(List.of(
            at(Verb.READ, Selector.ITERATION, number), at(Verb.UPDATE, Selector.ITERATION, number),
            at(Verb.CLOSE, Selector.ITERATION, number)));
        int held = planning.membershipsOf(scopeId, number).size();
        if (setting.map(s -> within(held + 1, s.maxMembershipsPerIteration)).orElse(false)) {
            calls.add(at(Verb.PLAN, Selector.ITERATION, number));
        }
        if (setting.flatMap(s -> IterationService.promotable(planning.openIterations(scopeId),
                s.currentIterationNumber)).isPresent()) {
            calls.add(new Call(Verb.ADVANCE, Selector.ITERATION, null, null));
        }
        return new Open(OPEN, List.copyOf(calls));
    }

    /** The calls open on one milestone. */
    @Transactional
    public Open forMilestone(UUID scopeId, long number) {
        Milestone milestone = planning.milestoneByNumber(scopeId, number)
            .orElseThrow(() -> new WorklistException(
                WorklistException.Reason.MILESTONE_UNKNOWN, List.of()));
        if (!milestone.open()) {
            return new Open(milestone.status, List.of(at(Verb.READ, Selector.MILESTONE, number)));
        }
        return new Open(milestone.status, List.of(at(Verb.READ, Selector.MILESTONE, number),
            at(Verb.UPDATE, Selector.MILESTONE, number),
            at(Verb.CLOSE, Selector.MILESTONE, number)));
    }

    /** The calls open on one membership, which are those of its iteration's state. */
    @Transactional
    public Open forMembership(UUID scopeId, long iteration, long item) {
        Iteration held = planning.iterationByNumber(scopeId, iteration)
            .orElseThrow(() -> new WorklistException(
                WorklistException.Reason.ITERATION_UNKNOWN, List.of()));
        if (!held.open()) {
            return new Open(CLOSED, List.of(new Call(Verb.READ, Selector.ITERATION, iteration,
                item)));
        }
        return new Open(OPEN, List.of(
            new Call(Verb.READ, Selector.ITERATION, iteration, item),
            new Call(Verb.UPDATE, Selector.ITERATION, iteration, item),
            new Call(Verb.UNPLAN, Selector.ITERATION, iteration, item)));
    }

    /** The calls open on one workstream: it is read here and declared elsewhere. */
    @Transactional
    public Open forWorkstream(UUID scopeId, long number) {
        Workstream workstream = workstreams.findByNumber(scopeId, number)
            .orElseThrow(() -> new WorklistException(
                WorklistException.Reason.WORKSTREAM_UNKNOWN, List.of()));
        return new Open(workstream.status,
            List.of(at(Verb.READ, Selector.WORKSTREAM, number)));
    }

    // ------------------------------------------------------------------

    private static Call at(Verb verb, String view, long number) {
        return new Call(verb, view, number, null);
    }

    /**
     * One open iteration as an item's plan sees it: who is a member, and
     * whether one more fits.
     */
    private record Room(long iteration, Set<Long> members, boolean admits) {
    }

    /**
     * The items of one scope against its open iterations, with each status and
     * milestone read once however many items name it.
     */
    private final class Items {

        private final UUID scopeId;
        private final List<Room> rooms;
        private final Map<Long, ItemStatus> statuses = new HashMap<>();
        private final Map<Long, Optional<Milestone>> milestones = new HashMap<>();

        Items(UUID scopeId, List<Room> rooms) {
            this.scopeId = scopeId;
            this.rooms = rooms;
        }

        /** The calls open on the item of this number. */
        Open open(long number) {
            Item item = items.byAddress(scopeId, number).orElseThrow(() -> new WorklistException(
                WorklistException.Reason.ITEM_UNKNOWN, List.of()));
            ItemStatus status = statuses.computeIfAbsent(item.statusPk,
                pk -> vocabulary.requireStatus(scopeId, pk));
            List<Call> calls = new ArrayList<>(List.of(
                at(Verb.READ, Selector.ITEM, number), at(Verb.UPDATE, Selector.ITEM, number),
                at(Verb.RELATE, Selector.ITEM, number), at(Verb.UNRELATE, Selector.ITEM, number),
                at(Verb.TAG_ATTACH, Selector.ITEM, number),
                at(Verb.TAG_DETACH, Selector.ITEM, number),
                at(Verb.BOOK, Selector.ITEM, number)));
            if (MembershipService.plannable(status, milestoneOf(item))) {
                rooms.stream()
                    .filter(room -> room.admits() && !room.members().contains(number))
                    .forEach(room -> calls.add(new Call(Verb.PLAN, Selector.ITERATION,
                        room.iteration(), number)));
            }
            return new Open(status.name, List.copyOf(calls));
        }

        /** The item's milestone; absent where it carries none or names none of the scope. */
        private @Nullable Milestone milestoneOf(Item item) {
            Long number = item.milestoneNumber;
            if (number == null) {
                return null;
            }
            return milestones.computeIfAbsent(number,
                n -> planning.milestoneByNumber(scopeId, n)).orElse(null);
        }
    }
}
