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

    /**
     * The item at that number, or a typed refusal.
     *
     * <p>The view is resolved to its selector row and passed into the lookup, so
     * that identity is read as the triple scope, selector and number under both
     * allocation modes. Under the scope-wide position the number alone would do;
     * relying on that would make the code correct only for the mode it happens
     * to run in.
     */
    @Transactional
    public UUID itemAt(UUID scopeId, long number) {
        Selector view = selectors.require(scopeId, Selector.ITEM);
        Item item = items.byAddress(scopeId, view.id, number);
        if (item == null) {
            throw absent(WorklistException.Reason.ITEM_UNKNOWN, Selector.ITEM, number, scopeId);
        }
        return item.id;
    }

    /** The iteration at that number, or a typed refusal. */
    @Transactional
    public UUID iterationAt(UUID scopeId, long number) {
        selectors.require(scopeId, Selector.ITERATION);
        Iteration iteration = planning.iterationByNumber(scopeId, number);
        if (iteration == null) {
            throw absent(WorklistException.Reason.ITERATION_UNKNOWN,
                Selector.ITERATION, number, scopeId);
        }
        return iteration.id;
    }

    /** The milestone at that number, or a typed refusal. */
    @Transactional
    public UUID milestoneAt(UUID scopeId, long number) {
        selectors.require(scopeId, Selector.MILESTONE);
        Milestone milestone = planning.milestoneByNumber(scopeId, number);
        if (milestone == null) {
            throw absent(WorklistException.Reason.MILESTONE_UNKNOWN,
                Selector.MILESTONE, number, scopeId);
        }
        return milestone.id;
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
        ScopeSetting setting = planning.settingOf(scopeId);
        UUID current = setting == null ? null : setting.currentIterationId;

        if (current == null) {
            throw new WorklistException(
                WorklistException.Reason.ITERATION_ABSENT,
                "scope " + scopeId + " is working no iteration, so 'current' addresses "
                    + "nothing. The pointer moves when an iteration is advanced and is "
                    + "empty between a close and the next advance",
                List.of(Selector.ITERATION));
        }
        return current;
    }

    private static WorklistException absent(WorklistException.Reason reason, String view,
            long number, UUID scopeId) {
        return new WorklistException(reason,
            "no " + view + " numbered " + number + " in scope " + scopeId + ". Numbers are "
                + "never reused here, so this one was either never handed out or names "
                + "something in another scope",
            List.of(view + "/" + number));
    }
}
