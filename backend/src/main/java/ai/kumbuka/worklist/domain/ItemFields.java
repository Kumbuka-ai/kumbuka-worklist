package ai.kumbuka.worklist.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Turning a caller's values into the shape the store compares and stores, and
 * back.
 *
 * <p>Separate from {@link ItemStore} because none of it needs a database, and
 * because the comparison below is the mechanism behind two guarantees at once
 * — that a write changing nothing writes nothing, and that a read answer sent
 * straight back is accepted rather than silently discarded. Both rest on
 * being able to say whether two values are the same value, so that question
 * gets one implementation and one place to be wrong in.
 *
 * <h2>Why the multi-valued fields are normalised to a sorted, distinct list</h2>
 *
 * {@code component} and {@code depends_on} are SETS: a tag is on the item or
 * it is not, and listing it twice or in another order says nothing different.
 * Without normalisation, re-sending a read answer whose list came back in
 * another order would look like a change, and the item would take a new
 * modification date and a rotated conflict token for a write that changed
 * nothing — which is the exact defect this domain was built against, arriving
 * by a different road.
 */
final class ItemFields {

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
        if (raw == null) {
            return List.of();
        }
        Collection<?> elements;
        if (raw instanceof Collection<?> collection) {
            elements = collection;
        } else if (raw instanceof Object[] array) {
            elements = List.of(array);
        } else {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                field.canonicalName() + " is a list of tokens, and "
                    + raw.getClass().getSimpleName() + " was given",
                List.of(field.canonicalName()));
        }

        List<String> out = new ArrayList<>();
        for (Object element : elements) {
            // Rendered rather than type-checked, unlike a scalar field. A
            // caller echoing a read answer back may hold the elements as
            // uuids or as the strings they became in transit, and refusing
            // one of those would make the round trip depend on which route
            // the value took. A scalar field stays strict, where a wrong type
            // is a real mistake rather than a rendering.
            String token = element == null ? null : String.valueOf(element).trim();
            if (token != null && !token.isEmpty() && !out.contains(token)) {
                out.add(token);
            }
        }
        out.sort(Comparator.naturalOrder());
        return List.copyOf(out);
    }

    /** A caller's value as a set of ids: sorted, distinct, never null. */
    static List<UUID> ids(Field field, Object raw) {
        List<UUID> out = new ArrayList<>();
        for (String token : tokens(field, raw)) {
            try {
                UUID id = UUID.fromString(token);
                if (!out.contains(id)) {
                    out.add(id);
                }
            } catch (IllegalArgumentException notAnId) {
                throw new WorklistException(
                    WorklistException.Reason.INVALID_VALUE,
                    field.canonicalName() + " holds item ids, and " + token
                        + " is not one. An item is referenced by its id — the "
                        + "predecessor's running number does not exist here, because a "
                        + "database has an identity from the insert onward",
                    List.of(field.canonicalName()));
            }
        }
        out.sort(Comparator.naturalOrder());
        return List.copyOf(out);
    }

    /** Component tags, checked against the shape the corpus actually uses. */
    static List<String> componentTokens(Object raw) {
        List<String> tokens = tokens(Field.COMPONENT, raw);
        List<String> malformed = tokens.stream()
            .filter(token -> !token.matches("^[a-z][a-z0-9-]*$"))
            .toList();
        if (!malformed.isEmpty()) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "a component tag is a lower-case token — e2e, ee-srv, none. Refused: "
                    + malformed,
                malformed);
        }
        return tokens;
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
}
