/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.repository.ItemRepository;
import ai.kumbuka.worklist.repository.PlanningRepository;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Turns an address into the row it names.
 *
 * <p>Callers address by number — {@code worklist://kumbuka/item/562} — and this
 * store is keyed by surrogate ids everywhere, because a key that is also a
 * business value is a key that changes. Something has to be the bridge, and it
 * is this class rather than the surface: an address resolves against declared
 * vocabulary and against rows, both of which are the domain's, and a surface
 * that reached past the domain to a repository would be answering a question
 * about this schema in the one layer that is supposed to be able to change
 * protocol without touching it.
 *
 * <h2>It is a registry and not a verb</h2>
 *
 * Nothing here is a platform verb, and nothing here may become one. It sits
 * beside {@link SelectorRegistry} and {@link VocabularyRegistry} in exactly that
 * sense: mechanics the verbs are made of, named after what they hold rather than
 * after an act. The verb guard holds the five verb-carrying services against the
 * vocabulary; a public method here would be a sixth address for a caller, which
 * is why the names below are nouns-with-a-place and never words the catalogue
 * uses.
 *
 * <h2>The vocabulary stage runs here, and that is where the check order puts it</h2>
 *
 * Every resolution below asks {@link SelectorRegistry#require} first: is this
 * view declared in this scope at all. That is stage 3 of the ratified check
 * order and it must sit BEHIND scope visibility, which the surface has already
 * established by the time anything here is called. The refusal it produces says
 * a scope exists and something about its vocabulary — harmless to a caller who
 * may see the scope, and a scope enumerator if it could be reached by one who
 * may not.
 */
@ApplicationScoped
@TenantBound
public class AddressRegistry {

    @Inject ItemRepository items;
    @Inject PlanningRepository planning;
    @Inject SelectorRegistry selectors;
    @Inject ai.kumbuka.worklist.repository.WorkstreamRepository workstreams;

    /**
     * The vocabulary stage, without a row behind it.
     *
     * <p>For the two verbs that act on no existing object. {@code create} and
     * {@code query} take the view as an argument rather than as an address, so
     * they never reach a resolution — and without this they would never reach
     * the vocabulary stage either.
     *
     * <p><strong>Measured before this existed:</strong> a milestone or an
     * iteration could be created in a scope that had not declared the view,
     * because only the item allocator asks. The row was written, it was numbered,
     * and the address it answered with resolved to a refusal on the very next
     * call. An object that exists at an address nothing can address is worse than
     * a refused create, and it was reachable in one call.
     */
    @Transactional
    public void requireView(UUID scopeId, String view) {
        selectors.require(scopeId, view);
    }

    /**
     * The item at that number, or a typed refusal.
     *
     * <p>The view must be declared in the scope; the number then names one
     * item, because an item's number is unique within its scope (V18).
     */
    @Transactional
    public UUID itemAt(UUID scopeId, long number) {
        selectors.require(scopeId, Selector.ITEM);
        Item item = items.byAddress(scopeId, number).orElse(null);
        if (item == null) {
            throw absent(WorklistException.Reason.ITEM_UNKNOWN);
        }
        return item.id;
    }

    /** The iteration at that number, or a typed refusal. */
    @Transactional
    public UUID iterationAt(UUID scopeId, long number) {
        selectors.require(scopeId, Selector.ITERATION);
        Iteration iteration = planning.iterationByNumber(scopeId, number).orElse(null);
        if (iteration == null) {
            throw absent(WorklistException.Reason.ITERATION_UNKNOWN);
        }
        return iteration.id;
    }

    /** The milestone at that number, or a typed refusal. */
    @Transactional
    public UUID milestoneAt(UUID scopeId, long number) {
        selectors.require(scopeId, Selector.MILESTONE);
        Milestone milestone = planning.milestoneByNumber(scopeId, number).orElse(null);
        if (milestone == null) {
            throw absent(WorklistException.Reason.MILESTONE_UNKNOWN);
        }
        return milestone.id;
    }

    /**
     * The workstream at that number, or a typed refusal.
     *
     * <p>Ratified 2026-09-08 as the fourth view. Its counter runs
     * per-scope like those of {@code item}, {@code iteration} and
     * {@code milestone}; since V12 there is no counter that runs
     * per-workstream.
     */
    @Transactional
    public UUID workstreamAt(UUID scopeId, long number) {
        selectors.require(scopeId, Selector.WORKSTREAM);
        Workstream workstream = workstreams.findByNumber(scopeId, number).orElse(null);
        if (workstream == null) {
            throw absent(WorklistException.Reason.WORKSTREAM_UNKNOWN);
        }
        return workstream.id;
    }

    /**
     * The iteration the scope is working, or a typed refusal.
     *
     * <p>The pointer is on the settings row, so a scope with no settings row has
     * no current iteration — and that is answered as "there is none", not as
     * "there are no settings". The caller asked which iteration is current; the
     * shape of the record that would have held the answer is not their problem.
     *
     * <p>{@code ITERATION_ABSENT} rather than {@code ITERATION_UNKNOWN}: nothing
     * was addressed wrongly. The scope is simply not working an iteration right
     * now, which is a state it passes through every time one closes before the
     * next is advanced.
     */
    @Transactional
    public UUID currentIteration(UUID scopeId) {
        selectors.require(scopeId, Selector.ITERATION);
        ScopeSetting setting = planning.settingOf(scopeId).orElse(null);
        Long currentNumber = setting == null ? null : setting.currentIterationNumber;
        UUID current = currentNumber == null ? null
            : planning.iterationByNumber(scopeId, currentNumber).orElseThrow().id;

        if (current == null) {
            throw new WorklistException(
                WorklistException.Reason.ITERATION_ABSENT,
                List.of("address"));
        }
        return current;
    }

    private static WorklistException absent(WorklistException.Reason reason) {
        return new WorklistException(reason,
            List.of());
    }
}
