package ai.kumbuka.worklist.surface;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the verb surface accepts, in a form that names no protocol.
 *
 * <p>These shapes exist because the surface must not import the adapter's wire
 * types. They are deliberately NOT the wire shapes renamed: a wire shape answers
 * to a published contract and changes when that contract does; these answer to
 * the verbs. Today most fields coincide, and that is fine — what matters is that
 * a change to one is not automatically a change to the other.
 *
 * <h2>Why a write arrives as a map and not as a record per object</h2>
 *
 * The field catalogue is one enum over five addressed things, and it is the
 * domain that resolves a name against what is being addressed and refuses what
 * it does not recognise, by name. A record per object here would be a second
 * catalogue: it would decide the field set at the surface, and a field added to
 * the domain would then be silently unreachable rather than newly available.
 *
 * <h2>Why the null check is not here</h2>
 *
 * A missing body is refused by the surface, after the scope has been resolved,
 * because the ratified check order answers "not found" for a scope the caller
 * may not see before it says anything about the body. So an adapter hands over
 * {@code null} rather than refusing early, and every record here is nullable at
 * the call site by design.
 */
public final class VerbInput {

    private VerbInput() {
    }

    /**
     * The canonical field map of a write, exactly as the caller wrote it.
     *
     * <p>Carried through untouched. The one thing the surface adds is the
     * conflict token, and it adds it because the token arrives in the transport
     * ({@code If-Match}) while the domain reads it as a field — see
     * {@link #withToken}.
     */
    public record Fields(Map<String, Object> values) {

        public Fields {
            values = values == null ? Map.of() : Map.copyOf(values);
        }

        /**
         * The same fields with the conflict token written in under its canonical
         * name.
         *
         * <p>The token travels in the transport and the domain reads it as one
         * of the fields, so somebody has to move it across. Here rather than in
         * each adapter: two adapters moving it are two chances to move it
         * differently, and the one that forgot would produce a write refused for
         * a missing token that the caller demonstrably sent.
         *
         * <p><strong>The transport wins over a token in the body.</strong> A
         * caller doing the obvious thing — read an object, change one value,
         * send the whole answer back — sends the token twice, and the two agree.
         * Where they disagree, the one in the request line is the one the caller
         * meant this time; the one in the body is a leftover of what they read.
         * Refusing the disagreement instead was considered and dropped: it would
         * fail the round trip that the canonical naming exists to make safe.
         */
        public Fields withToken(String canonicalName, String token) {
            Map<String, Object> merged = new LinkedHashMap<>(values);
            merged.put(canonicalName, token);
            return new Fields(merged);
        }
    }

    /**
     * The status an item is withdrawn into.
     *
     * <p>The value is the scope's own: which statuses are terminal is a
     * declaration, and a terminal value named at the surface would be the
     * literal vocabulary the whole store was rebuilt to get rid of. What the
     * verb contributes is that the act is a withdrawal; which closed status it
     * lands in is the caller's to say and the domain's to check.
     */
    public record Withdrawal(String status) {
    }
}
