package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The item, and everything that may be done to it.
 *
 * <h2>The verbs</h2>
 *
 * {@code state} a call-in, {@code admit} it into an address space,
 * {@code amend} what is known about it, {@code retract} it, {@code inspect}
 * one, {@code survey} a scope. Six, and none of these names is a verb of
 * another service on this platform — an accidental homonym across two
 * services is a reader believing they already know what a call does.
 *
 * <p>There is no seventh that deletes. What the predecessor's {@code delete}
 * did — remove the row entirely — survives as the status {@code withdrawn},
 * and the consequence is that a number handed out is never handed back. That
 * makes the high-water mark a high-water mark BY CONSTRUCTION rather than by
 * a rule somebody has to keep, and it is kept without exception: a raw item
 * carrying no number is not carved out, because one rule beats two.
 *
 * <h2>One naming, in both directions</h2>
 *
 * Reads answer with {@link Field}'s canonical names and writes take the same
 * names. A caller may therefore read an item, change one value in the answer
 * and send the whole thing back — which is the obvious thing to do and is a
 * trap in the predecessor, where reads answer in capitalised column names and
 * writes take lower-case parameters, and the unmatched names are DISCARDED
 * SILENTLY. The row then carries a fresh modification date and a rotated
 * conflict token with not one field changed.
 *
 * <p>Both halves of that are answered here. An unknown argument is a typed
 * refusal that names it ({@link WorklistException.Reason#UNKNOWN_FIELD}), and
 * a write that changes no value writes NOTHING — no timestamp, no token
 * rotation, no statement at all.
 *
 * <p>{@code @TenantBound} at class level, so the database session setting is
 * bound inside every transaction and both enforcement layers agree about who
 * is asking.
 */
@ApplicationScoped
@TenantBound
public class ItemStore {

    /**
     * An address, a scope id, a count, a transition. Never a title, never an
     * actor — the operator boundary of this service is a missing GRANT, and a
     * log line carrying content walks around it by a different road.
     */
    private static final Logger LOG = Logger.getLogger(ItemStore.class);

    @Inject EntityManager em;
    @Inject SelectorRegistry selectors;
    @Inject TermRegistry terms;

    // ------------------------------------------------------------------
    // Reading.
    // ------------------------------------------------------------------

    /** One item, as the canonical field map. */
    @Transactional
    public Map<String, Object> inspect(UUID scopeId, UUID itemId) {
        return project(require(scopeId, itemId));
    }

    /**
     * Every item of a scope, oldest first.
     *
     * <p>Ordering by creation and not by the sort key of the contract: the
     * sort key ranks by milestone and cluster, the milestone is the planning
     * layer's, and a partial implementation of a documented order is worse
     * than an obviously different one.
     */
    @Transactional
    public List<Map<String, Object>> survey(UUID scopeId) {
        return em.createQuery(
                "SELECT i FROM Item i WHERE i.scopeId = :scope ORDER BY i.createdAt, i.id",
                Item.class)
            .setParameter("scope", scopeId)
            .getResultList()
            .stream()
            .map(this::project)
            .toList();
    }

    // ------------------------------------------------------------------
    // Writing.
    // ------------------------------------------------------------------

    /**
     * State an item: record that something has been called in.
     *
     * <p>The tenant is not a parameter. It comes from the bound tenant
     * context, which is also what the policy checks the incoming row against
     * — an item whose tenant a caller could name would be an item a caller
     * could plant across the boundary.
     *
     * <p>A stated item has no address. It gets one from {@link #admit}, when
     * somebody has decided what kind of thing it is.
     */
    @Transactional
    public Map<String, Object> state(UUID scopeId, Map<String, ?> arguments) {
        Map<Field, Object> given = Field.resolve(arguments);

        List<String> notSettable = given.keySet().stream()
            .filter(field -> !field.settable())
            .map(Field::canonicalName)
            .toList();
        if (!notSettable.isEmpty()) {
            throw new WorklistException(
                WorklistException.Reason.FIELD_NOT_SETTABLE,
                "a new item has no " + notSettable + " to carry: those fields are the "
                    + "service's and are derived rather than given. An address in "
                    + "particular is allocated by admission, never supplied",
                notSettable);
        }

        String title = ItemFields.text(Field.TITLE, given.get(Field.TITLE));
        if (title == null) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "an item carries a title. It is the one thing a call-in always has, and "
                    + "the one field that is required regardless of status",
                List.of(Field.TITLE.canonicalName()));
        }

        Item item = new Item();
        item.scopeId = scopeId;
        item.title = title;
        em.persist(item);
        em.flush();

        // Everything else the caller supplied goes through the same path an
        // amendment takes, so that a value is validated the same way whether
        // it arrives at intake or later. A second validation path is a second
        // place for the two to disagree.
        Map<Field, Object> rest = new EnumMap<>(Field.class);
        rest.putAll(given);
        rest.remove(Field.TITLE);
        if (!rest.isEmpty() && applyEffectiveChanges(item, project(item), rest)) {
            stamp(item);
            em.flush();
        }

        LOG.infof("item stated in scope %s", scopeId);
        return project(item);
    }

    /**
     * Admit an item into an address space: allocate its number under a
     * declared selector.
     *
     * <p>Once. An address that could be reallocated would make every
     * reference to the old one resolve to something else, so a second
     * admission is a typed refusal rather than a re-allocation.
     */
    @Transactional
    public Map<String, Object> admit(UUID scopeId, UUID itemId, String selectorToken,
            String conflictToken) {
        Item item = require(scopeId, itemId);
        requireCurrentToken(item, conflictToken);

        if (item.selectorId != null) {
            throw new WorklistException(
                WorklistException.Reason.ALREADY_ADMITTED,
                "item " + itemId + " already carries an address and an address is "
                    + "allocated once. Every reference ever written to it resolves "
                    + "through that address",
                List.of(String.valueOf(itemId)));
        }

        Selector selector = selectors.require(scopeId, selectorToken);
        long number = selectors.allocate(scopeId, selector);

        item.selectorId = selector.id;
        item.number = number;
        stamp(item);
        em.flush();
        em.refresh(item);

        LOG.infof("item admitted as %s-%d in scope %s", selectorToken, number, scopeId);
        return project(item);
    }

    /**
     * Change what is known about an item.
     *
     * <p><strong>A write that changes nothing writes nothing.</strong> Not a
     * timestamp, not a rotated token, not a statement. The comparison below
     * is the mechanism, and it is the answer to a measured defect: a write
     * assembled out of a read answer discarded every field silently, and the
     * row afterwards carried a fresh modification date and a new conflict
     * token with no value different. A false change trail and a false
     * conflict signal, from a call that did nothing and reported success.
     *
     * <p>The conflict token travels IN the field map, under
     * {@link Field#CONFLICT_TOKEN}, which is what makes the round trip work:
     * the answer a caller read already carries it.
     */
    @Transactional
    public Map<String, Object> amend(UUID scopeId, UUID itemId, Map<String, ?> arguments) {
        Map<Field, Object> given = Field.resolve(arguments);
        Item item = require(scopeId, itemId);

        requireCurrentToken(item, given.get(Field.CONFLICT_TOKEN));

        Map<String, Object> current = project(item);
        refuseUnsettableChanges(current, given);

        Map<Field, Object> settable = new EnumMap<>(Field.class);
        given.forEach((field, value) -> {
            if (field.settable()) {
                settable.put(field, value);
            }
        });

        boolean changed = applyEffectiveChanges(item, current, settable);
        if (!changed) {
            // The whole point. No statement is issued, so the modification
            // date and the token stay where they are, and the change trail
            // keeps meaning what it says.
            LOG.debugf("amendment of an item in scope %s changed nothing and wrote nothing",
                scopeId);
            return current;
        }

        stamp(item);
        em.flush();
        em.refresh(item);
        LOG.infof("item amended in scope %s", scopeId);
        return project(item);
    }

    /**
     * Retract an item: it is taken back, and it keeps its number forever.
     *
     * <p>This is what the predecessor's {@code delete} becomes. It goes
     * through {@link #amend} rather than beside it, so that the conflict
     * token, the no-op rule and the field validation are the same code —
     * a second write path is a second place for those three to drift.
     */
    @Transactional
    public Map<String, Object> retract(UUID scopeId, UUID itemId, String conflictToken) {
        Map<String, Object> answer = amend(scopeId, itemId, Map.of(
            Field.STATUS.canonicalName(), Item.WITHDRAWN,
            Field.CONFLICT_TOKEN.canonicalName(), String.valueOf(conflictToken)));
        LOG.infof("item retracted in scope %s", scopeId);
        return answer;
    }

    // ------------------------------------------------------------------
    // The mechanisms the verbs above are made of.
    // ------------------------------------------------------------------

    /**
     * Refuse a read-only field that carries a DIFFERENT value, and accept one
     * that carries the value it already has.
     *
     * <p>The second half is what makes the round trip usable. A caller
     * sending a read answer back is sending {@code id}, {@code created_at}
     * and the rest along with it, and refusing all of those would mean the
     * canonical naming had bought a loud trap instead of a silent one.
     *
     * <p>{@link Field#CONFLICT_TOKEN} is exempt because it was already
     * checked, by {@link #requireCurrentToken}, which is a stricter test than
     * this one.
     */
    private static void refuseUnsettableChanges(Map<String, Object> current,
            Map<Field, Object> given) {
        List<String> refused = new ArrayList<>();
        given.forEach((field, value) -> {
            if (field.settable() || field == Field.CONFLICT_TOKEN) {
                return;
            }
            if (!ItemFields.unchangedAsText(current.get(field.canonicalName()), value)) {
                refused.add(field.canonicalName());
            }
        });

        if (!refused.isEmpty()) {
            throw new WorklistException(
                WorklistException.Reason.FIELD_NOT_SETTABLE,
                "these fields are the service's and may not be changed: " + refused
                    + ". Sending them back unaltered is fine — that is what a read "
                    + "answer carries — but the values given differ from the ones held. "
                    + "Settable here is " + Field.settableNames(),
                refused);
        }
    }

    /**
     * Apply the settable fields that actually differ, and report whether any
     * did.
     *
     * <p>Field by field, comparing against the projection rather than against
     * the entity: the projection is what a caller READ, so comparing against
     * it is comparing like with like. Comparing against the entity would mean
     * comparing a token a caller sent with a uuid the row holds, and every
     * such comparison would report a change.
     */
    private boolean applyEffectiveChanges(Item item, Map<String, Object> current,
            Map<Field, Object> settable) {
        boolean changed = false;
        for (Map.Entry<Field, Object> entry : settable.entrySet()) {
            changed |= applyOne(item, current, entry.getKey(), entry.getValue());
        }
        return changed;
    }

    private boolean applyOne(Item item, Map<String, Object> current, Field field,
            Object value) {
        Object held = current.get(field.canonicalName());
        switch (field) {
            case TITLE -> {
                String title = ItemFields.text(field, value);
                if (title == null) {
                    throw new WorklistException(
                        WorklistException.Reason.INVALID_VALUE,
                        "an item carries a title on every status, so it cannot be cleared",
                        List.of(field.canonicalName()));
                }
                if (Objects.equals(held, title)) {
                    return false;
                }
                item.title = title;
                return true;
            }
            case STATUS -> {
                String status = ItemFields.text(field, value);
                if (!Item.STATUSES.contains(status)) {
                    throw new WorklistException(
                        WorklistException.Reason.INVALID_VALUE,
                        "there is no status " + status + ". The vocabulary is "
                            + Item.STATUSES + ". `planned` is deliberately absent: it "
                            + "means membership of an iteration, the membership table "
                            + "is the planning layer's, and a value nothing maintains "
                            + "is worse than an absent one",
                        List.of(field.canonicalName()));
                }
                if (Objects.equals(held, status)) {
                    return false;
                }
                item.status = status;
                return true;
            }
            case REFERENCE -> {
                String reference = ItemFields.text(field, value);
                if (Objects.equals(held, reference)) {
                    return false;
                }
                item.reference = reference;
                return true;
            }
            case COMPONENT -> {
                List<String> tags = ItemFields.componentTokens(value);
                if (Objects.equals(held, tags)) {
                    return false;
                }
                item.component = tags.toArray(new String[0]);
                return true;
            }
            case DEPENDS_ON -> {
                return applyDependencies(item, ItemFields.ids(field, value));
            }
            case CLUSTER, TYPE, PRIORITY, SIZE -> {
                return applyTerm(item, held, field, value);
            }
            default -> throw new IllegalStateException(
                field.canonicalName() + " is settable and has no application");
        }
    }

    /** A vocabulary field: resolved against the scope's own declared terms. */
    private boolean applyTerm(Item item, Object held, Field field, Object value) {
        String token = ItemFields.text(field, value);
        if (Objects.equals(held, token)) {
            return false;
        }
        UUID termId = token == null
            ? null
            : terms.require(item.scopeId, TermRegistry.axisOf(field), token).id;

        switch (field) {
            case CLUSTER -> item.clusterTermId = termId;
            case TYPE -> item.typeTermId = termId;
            case PRIORITY -> item.priorityTermId = termId;
            case SIZE -> item.sizeTermId = termId;
            default -> throw new IllegalStateException(field + " is not a vocabulary field");
        }
        return true;
    }

    /**
     * Set the dependency edges to exactly the given set.
     *
     * <p>An edge that leaves the set is WITHDRAWN, never deleted — this
     * schema grants DELETE on nothing, and one exception would cost the whole
     * of that. An edge that re-enters a set it had left is asserted again on
     * the row that was already there.
     *
     * <p>A reference to an item that does not exist is refused by the foreign
     * key rather than found later by an inventory walk. A CYCLE still can be
     * written: no constraint expresses acyclicity, and the walk that would
     * find one is a whole-inventory question that does not belong on a write
     * path.
     */
    private boolean applyDependencies(Item item, List<UUID> wanted) {
        if (wanted.contains(item.id)) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "an item cannot depend on itself. That is the one cycle a single row "
                    + "can express, and the only one a constraint can see",
                List.of(Field.DEPENDS_ON.canonicalName()));
        }

        List<ItemDependency> edges = em.createQuery(
                "SELECT d FROM ItemDependency d WHERE d.itemId = :item", ItemDependency.class)
            .setParameter("item", item.id)
            .getResultList();

        boolean changed = false;
        List<UUID> present = new ArrayList<>();
        for (ItemDependency edge : edges) {
            present.add(edge.dependsOnId);
            String target = wanted.contains(edge.dependsOnId)
                ? ItemDependency.ASSERTED : ItemDependency.WITHDRAWN;
            if (!target.equals(edge.status)) {
                edge.status = target;
                changed = true;
            }
        }

        for (UUID target : wanted) {
            if (present.contains(target)) {
                continue;
            }
            ItemDependency edge = new ItemDependency();
            edge.itemId = item.id;
            edge.dependsOnId = target;
            em.persist(edge);
            changed = true;
        }

        if (changed) {
            em.flush();
        }
        return changed;
    }

    /**
     * The modification date and the conflict token, moved together.
     *
     * <p>One place, called only where an effective change was established, so
     * that "the token rotated" and "something changed" cannot come apart.
     */
    private static void stamp(Item item) {
        item.updatedAt = Instant.now();
        item.conflictToken = UUID.randomUUID().toString();
    }

    /**
     * The conflict token must be the one the row currently carries.
     *
     * <p>A stale one and an absent one are the same refusal, and it carries
     * the CURRENT token: a caller that has to make a second call just to
     * learn what it should have sent will end up reading, and a caller that
     * reads in order to overwrite has stopped detecting conflicts.
     */
    private static void requireCurrentToken(Item item, Object presented) {
        if (presented != null && item.conflictToken.equals(String.valueOf(presented))) {
            return;
        }
        throw new WorklistException(
            WorklistException.Reason.CONFLICT,
            (presented == null
                ? "a write carries the conflict token it read; none was given. "
                : "the conflict token given is not the one this item carries. ")
                + "The item has changed since it was read, or was never read. Its "
                + "current token is " + item.conflictToken
                + " — re-read, re-apply the change and retry",
            List.of(item.conflictToken));
    }

    private Item require(UUID scopeId, UUID itemId) {
        Item item = itemId == null ? null : em.find(Item.class, itemId);
        if (item == null || !item.scopeId.equals(scopeId)) {
            throw new WorklistException(
                WorklistException.Reason.ITEM_UNKNOWN,
                "no item " + itemId + " in scope " + scopeId,
                List.of(String.valueOf(itemId)));
        }
        return item;
    }

    /**
     * The item as the canonical field map — the one answer shape, used by the
     * reads AND by the comparison the writes make.
     *
     * <p>That reuse is the point rather than an economy. If the read answer
     * and the value a write compares against were built separately, the two
     * could disagree, and a write would report a change where a reader saw
     * none — which is the class of defect this domain exists against.
     *
     * <p>The term and selector lookups are per item. Through the persistence
     * context, so a survey of a scope resolves each distinct term once
     * whatever the item count; a projection built from a join would be faster
     * and is not worth a second query path while there is no reading surface
     * to make it matter.
     */
    private Map<String, Object> project(Item item) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(Field.ID.canonicalName(), item.id);
        fields.put(Field.SCOPE.canonicalName(), item.scopeId);
        fields.put(Field.SELECTOR.canonicalName(), tokenOfSelector(item));
        fields.put(Field.NUMBER.canonicalName(), item.number);
        fields.put(Field.TITLE.canonicalName(), item.title);
        fields.put(Field.STATUS.canonicalName(), item.status);
        fields.put(Field.CLUSTER.canonicalName(), tokenOfTerm(item.clusterTermId));
        fields.put(Field.TYPE.canonicalName(), tokenOfTerm(item.typeTermId));
        fields.put(Field.PRIORITY.canonicalName(), tokenOfTerm(item.priorityTermId));
        fields.put(Field.SIZE.canonicalName(), tokenOfTerm(item.sizeTermId));
        fields.put(Field.COMPONENT.canonicalName(),
            ItemFields.tokens(Field.COMPONENT, item.component));
        fields.put(Field.REFERENCE.canonicalName(), item.reference);
        fields.put(Field.DEPENDS_ON.canonicalName(), assertedDependencies(item));
        fields.put(Field.CREATED_AT.canonicalName(), item.createdAt);
        fields.put(Field.UPDATED_AT.canonicalName(), item.updatedAt);
        fields.put(Field.CONFLICT_TOKEN.canonicalName(), item.conflictToken);
        return fields;
    }

    private String tokenOfSelector(Item item) {
        if (item.selectorId == null) {
            return null;
        }
        Selector selector = em.find(Selector.class, item.selectorId);
        return selector == null ? null : selector.token;
    }

    private String tokenOfTerm(UUID termId) {
        if (termId == null) {
            return null;
        }
        Term term = em.find(Term.class, termId);
        return term == null ? null : term.token;
    }

    /**
     * The asserted edges only. A withdrawn edge is history, not a dependency.
     *
     * <p>Sorted, so that the answer is stable across reads. Without that, a
     * caller who re-sent a read answer would present the same set in another
     * order, and a comparison would report a change the caller never made —
     * the item would take a fresh modification date and a rotated token for a
     * write that changed nothing.
     */
    private List<UUID> assertedDependencies(Item item) {
        return em.createQuery(
                "SELECT d.dependsOnId FROM ItemDependency d "
                    + "WHERE d.itemId = :item AND d.status = :status "
                    + "ORDER BY d.dependsOnId", UUID.class)
            .setParameter("item", item.id)
            .setParameter("status", ItemDependency.ASSERTED)
            .getResultList()
            .stream()
            .sorted()
            .toList();
    }
}
