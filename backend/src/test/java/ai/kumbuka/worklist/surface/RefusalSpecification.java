package ai.kumbuka.worklist.surface;

import java.util.List;

/**
 * The refusal envelope as DEC-0042 writes it, transcribed by hand.
 *
 * <p>Every literal here is read from
 * {@code spec/docs/architecture/decisions/DEC-0042-every-refusal-carries-one-envelope-whichever-hop-produced-it.md}
 * and typed out. Nothing is imported from
 * {@code ai.kumbuka.worklist.adapter.payload}: an expectation taken from the
 * artefact it checks expects whatever the artefact does, which is the one
 * thing a conformance test must not do.
 *
 * <h2>What the node says</h2>
 *
 * <p>"Every refusal a caller receives on the verb surface carries
 * {@code { reason, message }}, with machine-readable detail, where a refusal
 * has any, under {@code data}. This holds on the protocol tool error and on
 * the REST error body, on a service's own adapter and on the router, whether
 * the service or the router produced the refusal."
 *
 * <p>"An address of an object that does not exist, an address in a scope the
 * caller cannot see, and an address whose scheme the router cannot route are
 * refused with the single code {@code NOT_FOUND}, in the same envelope, with
 * no {@code data} and no message that distinguishes them, whether a service or
 * the router produced the answer. No code distinguishes these cases, on any
 * service, at any time."
 *
 * <h2>The one literal that is not from the node</h2>
 *
 * {@link #NOT_FOUND_MESSAGE}. The node fixes that the message must not
 * distinguish the cases; it does not write the message. The router does, in
 * {@code RouterException.NOT_FOUND_MESSAGE}, and "whether a service or the
 * router produced the answer" is only true if this service spells it the same
 * way. So it is transcribed from the router's source — measured on
 * {@code Kumbuka-ai/platform}, {@code origin/main} at {@code ced8459},
 * 2026-09-21 — and named here as a transcription rather than smuggled in as
 * though the node had said it.
 *
 * <p>The cases inside this service are compared with EACH OTHER regardless,
 * which is the node's own clause and holds whatever the message is. The
 * literal is what additionally binds them to the other hop.
 */
public final class RefusalSpecification {

    private RefusalSpecification() {
    }

    /** The two members every refusal carries. */
    public static final String REASON = "reason";
    public static final String MESSAGE = "message";

    /** Machine-readable detail, present only where a refusal has any. */
    public static final String DATA = "data";

    /** The one code of the not-found class, from the one-not-found clause. */
    public static final String NOT_FOUND = "NOT_FOUND";

    /** The message of that class, transcribed from the router (see above). */
    public static final String NOT_FOUND_MESSAGE =
        "nothing is addressed here. Check the address, and that you are a member of "
            + "the scope it names.";

    /**
     * The members a refusal body may carry, and nothing else.
     *
     * <p>{@code data} is optional by the node's own wording ("where a refusal
     * has any"); {@code reason} and {@code message} are not.
     */
    public static final List<String> PERMITTED_MEMBERS = List.of(REASON, MESSAGE, DATA);

    /**
     * The member name this service carried its detail under before this pass,
     * and the one thing the envelope must never carry at the top level.
     *
     * <p>Not from DEC-0042 — the node never mentions it. It is named here
     * because obligation 6 of dispatch 187.4 is written against it:
     * "{@code offenders} stand under {@code data}, never at the top level of
     * the refusal envelope".
     */
    public static final String FORBIDDEN_TOP_LEVEL_MEMBER = "offenders";

    /**
     * How a refusal travels on the protocol surface: as the tool error of a
     * successful JSON-RPC response, not as a JSON-RPC error.
     *
     * <p>DEC-0042 binds "the protocol tool error", which in MCP is
     * {@code isError} on a tool result.
     */
    public static final String MCP_STRUCTURED_CONTENT = "structuredContent";
    public static final String MCP_IS_ERROR = "isError";

    /**
     * The three codes dispatch 187.4 declares for the scope conditions, with
     * the spelling it fixes.
     *
     * <p>Measured before declaring them, on 2026-09-21: neither DEC-0042 nor
     * the router's verb catalogue
     * ({@code Kumbuka-ai/platform}, {@code router/…/verb/VerbCatalog.java} at
     * {@code ced8459}) carries a code for any of the three conditions. The
     * dispatch fixes the names so that this service and the dispatch service
     * arrive at one spelling rather than two.
     */
    public static final String SCOPE_KIND_UNSUPPORTED = "SCOPE_KIND_UNSUPPORTED";
    public static final String SCOPE_READ_ONLY = "SCOPE_READ_ONLY";
    public static final String SCOPE_LOCKED = "SCOPE_LOCKED";
}
