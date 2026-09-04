package ai.kumbuka.worklist.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Turning a caller's values into the shape the store compares and stores, and
 * back.
 *
 * <p>Separate from {@link ItemService} because none of it needs a database, and
 * because the comparison below is the mechanism behind two guarantees at once
 * — that a write changing nothing writes nothing, and that a read answer sent
 * straight back is accepted rather than silently discarded. Both rest on
 * being able to say whether two values are the same value, so that question
 * gets one implementation and one place to be wrong in.
 *
 * <h2>Why the multi-valued fields are normalised</h2>
 *
 * {@code relations} is a SET: an edge is asserted or it is not, and listing
 * it twice or in another order says nothing different. {@code references} is
 * a LIST — its order is the reader's order and is part of the value — but two
 * entries that differ only in a trailing space are the same entry.
 * {@code attributes} is a MAP, and the order its keys arrive in says nothing
 * at all.
 *
 * <p>Without normalisation, re-sending a read answer whose list came back in
 * another order would look like a change, and the item would take a new
 * modification date and a rotated conflict token for a write that changed
 * nothing — which is the exact defect this domain was built against, arriving
 * by a different road.
 */
final class ItemFields {

    /** The key of the optional display text of a reference entry. */
    static final String LABEL = "label";
    /** The key of the pointer itself. Positioned, never followed. */
    static final String TARGET = "target";
    /** The key of a relation entry's declared type, as its identity. */
    static final String TYPE = "type";
    /** The key of a relation entry's other end, as its identity. */
    static final String ITEM = "item";

    private ItemFields() {
    }

    /**
     * A caller's value as text, or null.
     *
     * <p>An empty or blank string is null. A Markdown cell cannot tell the
     * two apart and so needed a filler token; here they are the same absence,
     * and accepting both spellings of it would mean two rows that differ only
     * in which spelling of "nothing" they carry.
     */
    static String text(Field field, Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof CharSequence)) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                field.canonicalName() + " is text, and " + raw.getClass().getSimpleName()
                    + " was given",
                List.of(field.canonicalName()));
        }
        String value = raw.toString().trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * A caller's value as a set of tokens: sorted, distinct, never null.
     *
     * <p>Accepts a collection or an array, because a caller that read the
     * answer back out of JSON has a list and one that read it out of the
     * entity has an array, and refusing either would make the round trip
     * depend on which route the value took.
     */
    static List<String> tokens(Field field, Object raw) {
        List<String> out = new ArrayList<>(rawTokens(field, raw));
        out.sort(Comparator.naturalOrder());
        return List.copyOf(out);
    }

    /**
     * A caller's value as a sequence of tokens: distinct, and in the order
     * they were given.
     *
     * <p>The counterpart of {@link #tokens(Field, Object)}, which sorts
     * because what it normalises is a SET. Here the order is the value — an
     * iteration's membership sequence is what it is because of the order —
     * so sorting it would destroy the thing being written.
     */
    static List<String> tokensInOrder(Field field, Object raw) {
        return List.copyOf(rawTokens(field, raw));
    }

    /** A caller's value as an identity, or a refusal naming what was given. */
    static UUID id(Field field, Object raw) {
        String token = text(field, raw == null ? null : String.valueOf(raw));
        if (token == null) {
            return null;
        }
        try {
            return UUID.fromString(token);
        } catch (IllegalArgumentException notAnId) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                field.canonicalName() + " carries the identity of a declared value, and "
                    + token + " is not one. A declared value has an identity separate "
                    + "from its name, and the identity is what an item stores — the "
                    + "name is what a reader sees and may be changed at will",
                List.of(field.canonicalName()));
        }
    }

    /**
     * The reference list as entries: label and target, in the caller's order.
     *
     * <p>The order is preserved and is NOT normalised away: it is the reader's
     * order and is part of what was written. What is normalised is each entry
     * — a blank label is the same absence as no label — so that a round trip
     * through JSON compares equal to what came out of the store.
     */
    static List<Map<String, Object>> references(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object element : elements(Field.REFERENCES, raw)) {
            Map<?, ?> entry = entry(Field.REFERENCES, element);
            String target = text(Field.REFERENCES, entry.get(TARGET));
            if (target == null) {
                throw new WorklistException(
                    WorklistException.Reason.INVALID_VALUE,
                    "a reference entry carries a target. The service validates its form "
                        + "and never resolves it — a pointer to a document, a URL and a "
                        + "citation are all the same kind of thing here — but an entry "
                        + "pointing at nothing is not an entry",
                    List.of(Field.REFERENCES.canonicalName()));
            }
            // An ordinary unmodifiable map rather than Map.copyOf: the label
            // is optional, and Map.copyOf refuses a null value outright. An
            // entry with no label would raise from inside the normalisation,
            // which reads as a broken conversion rather than as the absent
            // label it actually is.
            Map<String, Object> normalised = new LinkedHashMap<>();
            normalised.put(LABEL, text(Field.REFERENCES, entry.get(LABEL)));
            normalised.put(TARGET, target);
            out.add(Collections.unmodifiableMap(normalised));
        }
        return List.copyOf(out);
    }

    /**
     * The relation set as entries: the declared type and the other end, both
     * as identities, sorted and distinct.
     *
     * <p>Sorted because it is a set and its order carries nothing. Without
     * that, a caller re-sending a read answer would present the same edges in
     * another order and the comparison would report a change nobody made.
     */
    static List<Map<String, Object>> relations(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object element : elements(Field.RELATIONS, raw)) {
            Map<?, ?> entry = entry(Field.RELATIONS, element);
            UUID type = id(Field.RELATIONS, entry.get(TYPE));
            UUID item = id(Field.RELATIONS, entry.get(ITEM));
            if (type == null || item == null) {
                throw new WorklistException(
                    WorklistException.Reason.INVALID_VALUE,
                    "a relation entry carries a `" + TYPE + "` and an `" + ITEM + "`, "
                        + "both as identities. A relation without a type is the "
                        + "predecessor's untyped edge, and every machine reader of one "
                        + "has to guess whether it blocks",
                    List.of(Field.RELATIONS.canonicalName()));
            }
            Map<String, Object> normalised = new LinkedHashMap<>();
            normalised.put(TYPE, type);
            normalised.put(ITEM, item);
            Map<String, Object> frozen = Map.copyOf(normalised);
            if (!out.contains(frozen)) {
                out.add(frozen);
            }
        }
        out.sort(Comparator
            .comparing((Map<String, Object> e) -> String.valueOf(e.get(ITEM)))
            .thenComparing(e -> String.valueOf(e.get(TYPE))));
        return List.copyOf(out);
    }

    /**
     * The declared attributes as a map, with the keys in a stable order.
     *
     * <p>Sorted by key, because the order a map arrives in says nothing and
     * two answers differing only in it would compare unequal. The VALUES are
     * left as they came: what a value may be is the declared attribute's type,
     * and that question is answered where the declaration can be read.
     */
    static Map<String, Object> attributes(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> given)) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                Field.ATTRIBUTES.canonicalName() + " is a map from a declared "
                    + "attribute's key to its value, and "
                    + raw.getClass().getSimpleName() + " was given",
                List.of(Field.ATTRIBUTES.canonicalName()));
        }

        // A sorted map rather than a sort of the keys followed by a second
        // lookup: the keys of the incoming map are of unknown type, so a
        // rendered key cannot be used to read the value back out of it.
        Map<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : given.entrySet()) {
            // A key present with a null value is the absence of the
            // attribute, and it is dropped rather than stored. Otherwise
            // clearing an attribute and never having set it would be two
            // different states that read the same.
            if (entry.getValue() != null) {
                sorted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        // Copied into a map that KEEPS the order rather than returned through
        // Map.copyOf, which guarantees none. The sort is the whole point here:
        // an answer whose keys came back in another order would compare
        // unequal to itself.
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    /**
     * Whether a caller's value is the value already held.
     *
     * <p>Read-only fields are compared as text. A caller echoing a read
     * answer back may have carried a uuid through JSON as a string and an
     * instant as its ISO form, and treating those as different values would
     * turn every honest round trip into a refusal.
     */
    static boolean unchangedAsText(Object current, Object incoming) {
        return Objects.equals(
            current == null ? null : String.valueOf(current),
            incoming == null ? null : String.valueOf(incoming));
    }

    /** A collection or an array as elements, or a refusal naming the field. */
    private static Collection<?> elements(Field field, Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof Collection<?> collection) {
            return collection;
        }
        if (raw instanceof Object[] array) {
            return List.of(array);
        }
        throw new WorklistException(
            WorklistException.Reason.INVALID_VALUE,
            field.canonicalName() + " is a list of entries, and "
                + raw.getClass().getSimpleName() + " was given",
            List.of(field.canonicalName()));
    }

    /** One entry of a composite list, as a map. */
    private static Map<?, ?> entry(Field field, Object element) {
        if (element instanceof Map<?, ?> map) {
            return map;
        }
        throw new WorklistException(
            WorklistException.Reason.INVALID_VALUE,
            "an entry of " + field.canonicalName() + " is an object, and "
                + (element == null ? "null" : element.getClass().getSimpleName())
                + " was given",
            List.of(field.canonicalName()));
    }

    /** The distinct, trimmed, non-empty renderings of a caller's collection. */
    private static List<String> rawTokens(Field field, Object raw) {
        List<String> out = new ArrayList<>();
        for (Object element : elements(field, raw)) {
            // Rendered rather than type-checked, unlike a scalar field. A
            // caller echoing a read answer back may hold the elements as
            // uuids or as the strings they became in transit, and refusing
            // one of those would make the round trip depend on which route
            // the value took.
            String token = element == null ? null : String.valueOf(element).trim();
            if (token != null && !token.isEmpty() && !out.contains(token)) {
                out.add(token);
            }
        }
        return out;
    }
}
