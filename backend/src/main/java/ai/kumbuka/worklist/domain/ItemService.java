package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.repository.ItemRepository;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
 * {@code create} a call-in, {@code accept} it into the corpus,
 * {@code update} what is known about it, {@code withdraw} it, {@code read}
 * one, {@code query} a scope. Six, and every one of them is the platform's
 * own word for the act, spelled identically.
 *
 * <p><strong>Identity is deliberate, and a shared name is not a collision.</strong>
 * These names exist in sibling services too, and that is the mechanism rather
 * than an accident: with one vocabulary the caller-facing surface is the
 * UNION of the transitions instead of their sum, and it is the address that
 * says which service is meant, not the verb.
 *
 * <p>What the identity buys is guarded rather than asserted:
 * {@code VerbVocabularyGuardTest} holds the public methods below against a
 * literal transcription of the platform verb set, in both directions. A verb
 * too many and a verb too few are equally red, because drift back to a
 * service-private name is silent otherwise — it breaks nothing, compiles,
 * and is only noticed by the next reader who assumes the shared meaning.
 *
 * <p>There is no seventh that deletes. What the predecessor's {@code delete}
 * did — remove the row entirely — survives as a terminal status the scope
 * declared, and the consequence is that a number handed out is never handed
 * back. That makes the high-water mark a high-water mark BY CONSTRUCTION
 * rather than by a rule somebody has to keep.
 *
 * <h2>The status is a declared value and this class knows none of them</h2>
 *
 * {@link Field#STATUS} carries the IDENTITY of a status the scope declared,
 * and the four predicates hang off that declaration rather than off a list in
 * this file. That is the change this class exists on the far side of: the
 * shape it replaces carried five literals in a check constraint and a
 * matching list in Java, which is exactly the construction that makes a
 * second vocabulary impossible.
 *
 * <p><strong>The transition rules are not here.</strong> An item may not
 * become closed under a live claim; an item may not enter an iteration while
 * it is not actionable. Both are expressed over the PREDICATES and both need
 * the claim and the planning verbs to exist first. Writing them against the
 * estate's status names would be the predecessor's mistake in a new file.
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
public class ItemService {

    /**
     * An address, a scope id, a count, a transition. Never a title, never an
     * actor — the operator boundary of this service is a missing GRANT, and a
     * log line carrying content walks around it by a different road.
     */
    private static final Logger LOG = Logger.getLogger(ItemService.class);

    @Inject ItemRepository items;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;

    // ------------------------------------------------------------------
    // Reading.
    // ------------------------------------------------------------------

    /** One item, as the canonical field map. */
    @Transactional
    public Map<String, Object> read(UUID scopeId, UUID itemId) {
        return project(require(scopeId, itemId));
    }

    /**
     * Every item of a scope, oldest first.
     *
     * <p>Ordering by creation and not by the sort key of the contract. That
     * sort ranks by milestone and by a declared attribute, and ordering by a
     * declared attribute is a capability a scope declares rather than a
     * property every attribute has for free.
     */
    @Transactional
    public List<Map<String, Object>> query(UUID scopeId) {
        return items.inScope(scopeId)
            .stream()
            .map(this::project)
            .toList();
    }

    // ------------------------------------------------------------------
    // Writing.
    // ------------------------------------------------------------------

    /**
     * Create an item: record that something has been called in.
     *
     * <p>The tenant is not a parameter. It comes from the bound tenant
     * context, which is also what the policy checks the incoming row against
     * — an item whose tenant a caller could name would be an item a caller
     * could plant across the boundary.
     *
     * <p><strong>A title and a status are both required, and the second is
     * new.</strong> A status is a declared value now, so there is no default
     * to fall back on: a scope declares its vocabulary before it holds an
     * item, exactly as it declares a selector before an address can be
     * allocated. Inventing a status here would be this service deciding what
     * a scope's list means.
     *
     * <p>A created item carries no selector and no number. It gets both from
     * {@link #accept}, when a second party has decided what kind of thing it
     * is.
     */
    @Transactional
    public Map<String, Object> create(UUID scopeId, Map<String, ?> arguments) {
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
                    + "particular is allocated by acceptance, never supplied",
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

        UUID statusId = ItemFields.id(Field.STATUS, given.get(Field.STATUS));
        if (statusId == null) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "an item carries a status, and a status is a value the scope declared "
                    + "rather than one of a fixed set this service knows. Declare the "
                    + "vocabulary of scope " + scopeId + " and name the status to start "
                    + "an item in",
                List.of(Field.STATUS.canonicalName()));
        }

        Item item = new Item();
        item.scopeId = scopeId;
        item.title = title;
        item.statusId = vocabulary.requireStatus(scopeId, statusId).id;
        items.insert(item);

        // Everything else the caller supplied goes through the same path an
        // update takes, so that a value is validated the same way whether
        // it arrives at intake or later. A second validation path is a second
        // place for the two to disagree.
        Map<Field, Object> rest = new EnumMap<>(Field.class);
        rest.putAll(given);
        rest.remove(Field.TITLE);
        rest.remove(Field.STATUS);
        if (!rest.isEmpty() && applyEffectiveChanges(item, project(item), rest)) {
            stamp(item);
            items.flush();
        }

        LOG.infof("item created in scope %s", scopeId);
        return project(item);
    }

    /**
     * Accept an item into the corpus: allocate its number under a declared
     * selector.
     *
     * <p>The intake gate, and the one act of this store a second party
     * performs rather than the author. What it allocates is the pair
     * {@code (selector, number)} — {@code FEAT-51} — and the identity of an
     * item in the store is the TRIPLE scope, selector and number, never the
     * pair without the selector.
     *
     * <p>Once. An identifier that could be reallocated would make every
     * reference to the old one resolve to something else, so a second
     * acceptance is a typed refusal rather than a re-allocation.
     */
    @Transactional
    public Map<String, Object> accept(UUID scopeId, UUID itemId, String selectorToken,
            String conflictToken) {
        Item item = require(scopeId, itemId);
        requireCurrentToken(item, conflictToken);

        if (item.selectorId != null) {
            throw new WorklistException(
                WorklistException.Reason.ALREADY_ACCEPTED,
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
        items.flushAndRefresh(item);

        LOG.infof("item accepted as %s-%d in scope %s", selectorToken, number, scopeId);
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
    public Map<String, Object> update(UUID scopeId, UUID itemId, Map<String, ?> arguments) {
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
            LOG.debugf("update of an item in scope %s changed nothing and wrote nothing",
                scopeId);
            return current;
        }

        stamp(item);
        items.flushAndRefresh(item);
        LOG.infof("item updated in scope %s", scopeId);
        return project(item);
    }

    /**
     * Withdraw an item: it is taken back, and it keeps its number forever.
     *
     * <p>This is what the predecessor's {@code delete} becomes, and the status
     * it moves to is <strong>the scope's own</strong>. A terminal value named
     * in this file would be the literal vocabulary all over again, so the
     * caller names the status it means and this verb is what says the act is
     * a withdrawal rather than an ordinary change.
     *
     * <p>It goes through {@link #update} rather than beside it, so that the
     * conflict token, the no-op rule and the field validation are the same
     * code — a second write path is a second place for those three to drift.
     */
    @Transactional
    public Map<String, Object> withdraw(UUID scopeId, UUID itemId, UUID statusId,
            String conflictToken) {
        ItemStatus status = vocabulary.requireStatus(scopeId, statusId);
        if (!status.closed) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "withdrawing an item moves it to a status that is CLOSED, and " + statusId
                    + " is not. Which values a scope closes with is its own declaration; "
                    + "that a withdrawal is terminal is the platform's",
                List.of(String.valueOf(statusId)));
        }

        Map<String, Object> answer = update(scopeId, itemId, Map.of(
            Field.STATUS.canonicalName(), String.valueOf(status.id),
            Field.CONFLICT_TOKEN.canonicalName(), String.valueOf(conflictToken)));
        LOG.infof("item withdrawn in scope %s", scopeId);
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
            case DESCRIPTION -> {
                String description = ItemFields.text(field, value);
                if (Objects.equals(held, description)) {
                    return false;
                }
                item.description = description;
                return true;
            }
            case STATUS -> {
                return applyStatus(item, held, field, value);
            }
            case ATTRIBUTES -> {
                return applyAttributes(item, ItemFields.attributes(value));
            }
            case REFERENCES -> {
                return applyReferences(item, ItemFields.references(value));
            }
            case RELATIONS -> {
                return applyRelations(item, ItemFields.relations(value));
            }
            default -> throw new IllegalStateException(
                field.canonicalName() + " is settable and has no application");
        }
    }

    /** The status: resolved against the scope's own declared vocabulary. */
    private boolean applyStatus(Item item, Object held, Field field, Object value) {
        UUID statusId = ItemFields.id(field, value);
        if (statusId == null) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "an item carries a status on every path, so it cannot be cleared. What "
                    + "the predecessor's delete did is a terminal status here, not the "
                    + "absence of one",
                List.of(field.canonicalName()));
        }
        if (ItemFields.unchangedAsText(held, statusId)) {
            return false;
        }
        item.statusId = vocabulary.requireStatus(item.scopeId, statusId).id;
        return true;
    }

    /**
     * Set the declared attributes to exactly the given map.
     *
     * <p>Keyed by the declaration's KEY on the way in and by its IDENTITY in
     * the column, so a scope may rename a key and an item's stored value does
     * not move. Every key is resolved against the scope's declarations, and an
     * undeclared one is a typed refusal rather than a value nothing can read
     * back.
     *
     * <p>An enumerated attribute's value is checked against its declared
     * options; the other five types are stored as given. That asymmetry is the
     * concept's: the platform asks no question about what a {@code text} or a
     * {@code number} means, and it cannot render an option identity that was
     * never declared.
     */
    private boolean applyAttributes(Item item, Map<String, Object> wanted) {
        Map<String, Object> stored = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : wanted.entrySet()) {
            AttributeDefinition definition =
                vocabulary.requireAttribute(item.scopeId, entry.getKey());
            stored.put(String.valueOf(definition.id),
                storedValue(definition, entry.getValue()));
        }

        if (Objects.equals(item.attributes, stored)) {
            return false;
        }
        item.attributes = stored;
        return true;
    }

    /** One attribute value, in the form the column holds. */
    private Object storedValue(AttributeDefinition definition, Object given) {
        if (!AttributeDefinition.ENUMERATED.contains(definition.type)) {
            return given;
        }
        if (AttributeDefinition.CHOICE.equals(definition.type)) {
            return String.valueOf(
                vocabulary.requireOption(definition, ItemFields.id(Field.ATTRIBUTES, given)).id);
        }

        List<String> options = new ArrayList<>();
        for (String token : ItemFields.tokens(Field.ATTRIBUTES, given)) {
            String optionId = String.valueOf(
                vocabulary.requireOption(definition, ItemFields.id(Field.ATTRIBUTES, token)).id);
            if (!options.contains(optionId)) {
                options.add(optionId);
            }
        }
        return List.copyOf(options);
    }

    /**
     * Set the reference list to exactly the given entries, in the given order.
     *
     * <p><strong>The LIVING entries are what this walks, position by
     * position.</strong> Entry i of the wanted list is matched against the
     * i-th living row: its label, its target and its ordinal are moved to
     * where they should be. A wanted list longer than the living one gets
     * fresh rows for the tail; a shorter one withdraws the living rows beyond
     * its end.
     *
     * <p><strong>A withdrawn row is never touched again.</strong> Not its
     * content, not its ordinal, not its status. That is the difference from
     * walking by ordinal, which would find the tombstone sitting at the
     * position a growing list needs and either collide with it or overwrite
     * it — and an overwritten tombstone is a free slot that reads like
     * preservation.
     *
     * <p>The two loops below cannot both do work in one call: a wanted list is
     * either longer than the living one or shorter. So no ordinal is ever
     * withdrawn and re-issued within a single flush, and the partial unique
     * index never sees the two rows at once.
     */
    private boolean applyReferences(Item item, List<Map<String, Object>> wanted) {
        List<ItemReference> living = items.assertedReferences(item.id);
        boolean changed = false;

        for (int position = 0; position < wanted.size(); position++) {
            Map<String, Object> entry = wanted.get(position);
            String label = (String) entry.get(ItemFields.LABEL);
            String target = (String) entry.get(ItemFields.TARGET);

            if (position >= living.size()) {
                ItemReference row = new ItemReference();
                row.itemId = item.id;
                row.scopeId = item.scopeId;
                row.ordinal = position;
                row.label = label;
                row.target = target;
                items.insertReference(row);
                changed = true;
                continue;
            }

            ItemReference row = living.get(position);
            if (row.ordinal != position) {
                row.ordinal = position;
                changed = true;
            }
            if (!Objects.equals(row.label, label) || !Objects.equals(row.target, target)) {
                row.label = label;
                row.target = target;
                changed = true;
            }
        }

        for (int position = wanted.size(); position < living.size(); position++) {
            // The ordinal is left where it was. It is meaningless on a
            // withdrawn row — nothing reads it for order — and rewriting it
            // would be a change to a row that is supposed to stand as it was.
            living.get(position).status = ItemReference.WITHDRAWN;
            changed = true;
        }

        if (changed) {
            items.flush();
        }
        return changed;
    }

    /**
     * Set the relations to exactly the given set.
     *
     * <p>An edge that leaves the set is WITHDRAWN, never deleted — this
     * schema grants DELETE on nothing, and one exception would cost the whole
     * of that. An edge that re-enters a set it had left is asserted again on
     * the row that was already there.
     *
     * <p>A reference to an item that does not exist is refused by the foreign
     * key, and an undeclared type by {@link VocabularyRegistry}. A CYCLE over
     * blocking relations still can be written: no constraint expresses
     * acyclicity, the walk that finds one is a domain check with a red probe
     * of its own, and it is not built here.
     */
    private boolean applyRelations(Item item, List<Map<String, Object>> wanted) {
        for (Map<String, Object> entry : wanted) {
            if (item.id.equals(entry.get(ItemFields.ITEM))) {
                throw new WorklistException(
                    WorklistException.Reason.INVALID_VALUE,
                    "an item cannot relate to itself. That is the one cycle a single row "
                        + "can express, and the only one a constraint can see",
                    List.of(Field.RELATIONS.canonicalName()));
            }
            vocabulary.requireRelationType(item.scopeId, (UUID) entry.get(ItemFields.TYPE));
        }

        List<ItemRelation> edges = items.edgesOf(item.id);
        boolean changed = false;

        List<Map<String, Object>> present = new ArrayList<>();
        for (ItemRelation edge : edges) {
            Map<String, Object> key = Map.of(
                ItemFields.TYPE, edge.relationTypeId, ItemFields.ITEM, edge.toItemId);
            present.add(key);
            String target = wanted.contains(key)
                ? ItemRelation.ASSERTED : ItemRelation.WITHDRAWN;
            if (!target.equals(edge.status)) {
                edge.status = target;
                changed = true;
            }
        }

        for (Map<String, Object> entry : wanted) {
            if (present.contains(entry)) {
                continue;
            }
            ItemRelation edge = new ItemRelation();
            edge.fromItemId = item.id;
            edge.toItemId = (UUID) entry.get(ItemFields.ITEM);
            edge.relationTypeId = (UUID) entry.get(ItemFields.TYPE);
            edge.scopeId = item.scopeId;
            items.insertEdge(edge);
            changed = true;
        }

        if (changed) {
            items.flush();
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
        item.changedAt = Instant.now();
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
        Item item = items.byId(itemId);
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
     * <p>The declaration lookups are per item, through the persistence
     * context, so a query over a scope resolves each distinct declaration once
     * whatever the item count. A projection built from a join would be faster
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
        fields.put(Field.DESCRIPTION.canonicalName(), item.description);
        fields.put(Field.STATUS.canonicalName(), item.statusId);
        fields.put(Field.ATTRIBUTES.canonicalName(), declaredAttributes(item));
        fields.put(Field.REFERENCES.canonicalName(), assertedReferences(item));
        fields.put(Field.RELATIONS.canonicalName(), assertedRelations(item));
        fields.put(Field.MILESTONE.canonicalName(), item.milestoneId);
        fields.put(Field.CREATED_AT.canonicalName(), item.createdAt);
        fields.put(Field.CHANGED_AT.canonicalName(), item.changedAt);
        fields.put(Field.CONFLICT_TOKEN.canonicalName(), item.conflictToken);
        return fields;
    }

    private String tokenOfSelector(Item item) {
        if (item.selectorId == null) {
            return null;
        }
        Selector selector = items.selectorById(item.selectorId);
        return selector == null ? null : selector.token;
    }

    /**
     * The stored attributes, keyed back by the declaration's KEY.
     *
     * <p>The column keys by identity so that a key can be renamed; a caller
     * addresses the attribute by its key because that is the part it can hold
     * on to. The translation is here rather than at the boundary, so that a
     * read answer and the map a write compares against are the same shape.
     *
     * <p>A value under a declaration this scope no longer has is dropped from
     * the answer. It is not lost — the column still carries it — and showing
     * it would put a key in the answer that no declaration can name.
     */
    private Map<String, Object> declaredAttributes(Item item) {
        Map<String, Object> answer = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : item.attributes.entrySet()) {
            AttributeDefinition definition = definitionOf(entry.getKey());
            if (definition != null) {
                answer.put(definition.key, entry.getValue());
            }
        }
        return ItemFields.attributes(answer);
    }

    private AttributeDefinition definitionOf(String storedKey) {
        try {
            return vocabulary.attributeById(UUID.fromString(storedKey));
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    /** The asserted pointers, in the reader's order. */
    private List<Map<String, Object>> assertedReferences(Item item) {
        List<Map<String, Object>> answer = new ArrayList<>();
        for (ItemReference reference : items.assertedReferences(item.id)) {
            // Unmodifiable rather than Map.copyOf, because the label is
            // optional and Map.copyOf refuses a null value. The read answer
            // and the normalised write value have to be the same shape or the
            // comparison behind "a write that changes nothing writes nothing"
            // compares two different things.
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(ItemFields.LABEL, reference.label);
            entry.put(ItemFields.TARGET, reference.target);
            answer.add(Collections.unmodifiableMap(entry));
        }
        return List.copyOf(answer);
    }

    /**
     * The asserted edges only. A withdrawn edge is history, not a relation.
     *
     * <p>Sorted by the repository, in the same order the caller-facing
     * normalisation uses, so that a read answer sent straight back compares
     * equal rather than looking like a reordering.
     */
    private List<Map<String, Object>> assertedRelations(Item item) {
        List<Map<String, Object>> answer = new ArrayList<>();
        for (ItemRelation relation : items.assertedRelations(item.id)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(ItemFields.TYPE, relation.relationTypeId);
            entry.put(ItemFields.ITEM, relation.toItemId);
            answer.add(Collections.unmodifiableMap(entry));
        }
        return List.copyOf(answer);
    }
}
