package ai.kumbuka.worklist.surface;

/**
 * A typed refusal the surface itself produces, as distinct from one the domain
 * produces.
 *
 * <p>The two are kept apart deliberately. {@code WorklistException} says
 * something about an item, an iteration or a milestone — its state, its
 * vocabulary, its conflict token — and the domain is the only place that knows
 * those things. This one says something about the <em>call</em>: the address
 * does not parse, the scheme does not carry the verb, a writing verb arrived on
 * a collection. None of that needs an object to exist, and most of it is
 * decided before one is looked for.
 *
 * <p>Every reason below names a status class and keeps it. A caller that has to
 * tell "you addressed this wrongly" from "that verb is not part of this scheme"
 * from "there is nothing there" cannot do it from prose, and a surface that
 * answered all three the same way would be indistinguishable from one with
 * unbuilt routes.
 */
public class SurfaceException extends RuntimeException {

    /**
     * The category of a surface refusal, and the HTTP status it carries.
     *
     * <p>The status travels with the reason rather than being decided at the
     * mapper, because the choice is part of the published contract and a mapper
     * is exactly where a choice gets quietly changed.
     */
    public enum Reason {

        /**
         * The address violates the production. Stage 1, and decidable without
         * knowing any scope — which is why answering 400 leaks nothing and why
         * this check sits in front of everything else.
         *
         * <p>The view is checked here too, and that placement is a decision.
         * Which selectors a SCOPE has declared is vocabulary and sits behind
         * scope visibility; that there are three views at all is the platform's
         * object model and is decidable without knowing any scope. Putting it
         * in stage 1 therefore leaks nothing, and putting it in stage 3 would
         * make a misspelt view reveal that a scope exists.
         */
        ADDRESS_MALFORMED(400),

        /**
         * The worklist scheme does not carry this verb.
         *
         * <p>422 and never 404: a not-found says the object is missing, an
         * unimplemented path says nothing at all, and both invite the caller to
         * retry. A category error says the call will never work, and names why.
         */
        VERB_UNCARRIED(422),

        /**
         * The scheme carries this verb and this service has not built it.
         *
         * <p>A different sentence from the one above and it must not be spelled
         * the same way. The capability declaration says the worklist scheme
         * carries the claim family, the graph verbs and {@code validate}; this
         * service has none of them yet. Answering {@code VERB_UNCARRIED} would
         * tell a caller the act does not exist in this scheme, which is false
         * and would stop them waiting for something that is coming.
         *
         * <p>501, which is the one status in this enum that says the fault is
         * ours. It is also the status the catalogue refuses for an uncarried
         * verb, and that refusal is what makes it the right one here: the two
         * cases are answered differently because they ARE different.
         */
        VERB_UNBUILT(501),

        /**
         * A writing verb arrived on a truncated address that declares no set
         * semantics.
         *
         * <p>405, and the answer carries {@code Allow}. HTTP does not forbid a
         * write on a collection — it merely finds it unusual — so this is an
         * explicit check rather than something the framework does for us.
         */
        WRITE_ON_TRUNCATED_ADDRESS(405),

        /**
         * The address resolves and may not be written through.
         *
         * <p>{@code iteration/current} is the one such address: it is a pointer
         * the scope moves, so a write through it would land on whichever
         * iteration happened to be current when the call arrived. 405 with
         * {@code Allow: GET}, because the address is real and the method is
         * what does not apply to it.
         */
        ADDRESS_READ_ONLY(405),

        /** A field write arrived without the conflict token it declares. */
        CONFLICT_TOKEN_MISSING(428),

        /** The request body is absent or does not carry what the verb needs. */
        PAYLOAD_MALFORMED(400);

        private final int status;

        Reason(int status) {
            this.status = status;
        }

        /** The HTTP status this reason answers with, fixed at the reason. */
        public int status() {
            return status;
        }
    }

    private final transient Reason reason;
    private final transient String allow;

    public SurfaceException(Reason reason, String message) {
        this(reason, message, null);
    }

    /**
     * @param allow the {@code Allow} header value, where the status requires
     *              one. 405 without it is a refusal that does not say what
     *              would have worked.
     */
    public SurfaceException(Reason reason, String message, String allow) {
        super(message);
        this.reason = reason;
        this.allow = allow;
    }

    public Reason reason() {
        return reason;
    }

    public String allow() {
        return allow;
    }
}
