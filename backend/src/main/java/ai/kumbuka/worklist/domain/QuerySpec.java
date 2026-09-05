package ai.kumbuka.worklist.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The narrowing of one {@code query}: which enumerated columns to filter on,
 * how many rows the caller wants, and where to continue.
 *
 * <h2>Why the filter is a field map rather than a builder</h2>
 *
 * The same reason the write catalogue is a field map: one shape for every
 * addressed view, refused by name against what the address's field set carries.
 * A per-view builder would put the domain's field set at the surface, and a
 * field added to a domain view would then be silently unreachable from a
 * caller — the exact defect the field enum exists against.
 *
 * <h2>Every filter value is a raw string on the way in</h2>
 *
 * Strings, because the transport carries them legibly and each domain field
 * knows how to parse its own. A UUID field takes a uuid string, a boolean
 * takes {@code "true"} or {@code "false"}, a status id takes a uuid string.
 * That is the same convention {@link Field#resolve} runs on the write path
 * — one canonical name per field, one parse per type, and a caller sending
 * something the field cannot read is refused by name rather than at the
 * database.
 *
 * <p><strong>Only enumerated columns are addressable.</strong> A filter over
 * a free-text field would be a substring match with rules the surface would
 * have to publish, and it is not the shape this iteration of the surface
 * takes. Names, mottos and descriptions are readable through {@code read}
 * once a caller has an address, and searchable through a verb this scheme
 * does not carry today.
 *
 * <h2>Paging is limit only, cursor is not built here</h2>
 *
 * A cursor-based continuation is what a real paginated read wants, and it
 * needs a stable order and an encoding a caller can round-trip. Neither is
 * hard, and both belong in the same iteration as the shape of the answer —
 * a listing that grows a {@code next_cursor} key is a shape decision, and
 * this build fits into the existing {@link ai.kumbuka.worklist.surface.VerbSurface.Listing}
 * without one.
 *
 * <p>So the limit is the whole of paging today. A caller who wants the tail
 * of a listing longer than the limit is answered {@code truncated} in the
 * listing, and the run that builds the cursor lands the round trip.
 */
public record QuerySpec(Map<String, Object> filter, int limit) {

    /** The default upper bound on one answer, when a caller does not name one. */
    public static final int DEFAULT_LIMIT = 100;

    /**
     * The absolute upper bound. A caller asking for more is refused rather
     * than served the ceiling silently: the whole point of the parameter is
     * that the caller says how much they want, and truncating without saying
     * is what the sprint-169 defect looks like one layer out.
     */
    public static final int MAX_LIMIT = 1000;

    public QuerySpec {
        filter = filter == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(filter));
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        if (limit > MAX_LIMIT) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "the limit on one answer is at most " + MAX_LIMIT + ", and was " + limit
                    + ". A caller wanting more sees the second batch with a second call — "
                    + "answering the ceiling silently would give an answer that looks "
                    + "complete and is not",
                java.util.List.of("limit"));
        }
    }

    /** The all-of-it spec: no filter, default limit. */
    public static QuerySpec all() {
        return new QuerySpec(Map.of(), DEFAULT_LIMIT);
    }
}
