package ai.kumbuka.worklist.adapter.rest;

import ai.kumbuka.worklist.adapter.payload.Payloads;
import ai.kumbuka.worklist.domain.WorklistException;
import ai.kumbuka.worklist.surface.SurfaceException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Turns the two families of typed refusal into HTTP, and keeps them apart.
 *
 * <h2>The status is not a judgement made here</h2>
 *
 * For a surface refusal the status travels on the reason itself. For a domain
 * refusal it is decided by the table below — one entry per reason, no default
 * branch that swallows a new one. A {@code switch} over an enum with no default
 * is what makes an added reason a compile error rather than a silent 500, and
 * this is exactly the place where a silent 500 would be indefensible: the reasons
 * are the published contract of the surface.
 *
 * <h2>Three statuses that look wrong and are not</h2>
 *
 * {@code SCOPE_UNRESOLVED} answers <strong>404</strong> rather than 403. The
 * directory answers for the bound subject only and existence in its answer IS the
 * permission, so a 403 would confirm that a scope exists to a caller who may not
 * see it — turning the error path into a scope enumerator. This is the standing
 * rule "404 and never 403", applied where it was always meant to apply.
 *
 * <p>{@code CONFLICT} answers <strong>412</strong> and not 409. The token
 * travels as {@code If-Match}, and 412 is the answer HTTP defines for a
 * precondition that did not hold — a client library retries or refreshes on it
 * without being taught anything about this scheme. 409 is kept for the states
 * that are conflicts of the object rather than of the caller's copy of it.
 *
 * <p>{@code IDENTIFIER_UNDECIDED} answers <strong>501</strong>. The call is
 * well-formed, addressed at a real item, and correct; what is missing is on our
 * side. A 4xx would tell the caller to change something, and there is nothing
 * they could change.
 */
@Provider
public class RefusalMapper implements ExceptionMapper<SurfaceException> {

    /**
     * The one place every refusal that reaches a caller passes through.
     *
     * <p>Logging here rather than at the throw sites is not tidiness: every site
     * is a chance to do it differently, and the one that gets forgotten is the
     * one somebody needed.
     *
     * <p><strong>DEBUG and not WARN, deliberately.</strong> A malformed address
     * arrives on every client typo, and a refusal log at WARN would make this
     * service's operational log writable by whoever calls it — flood the surface
     * with broken addresses and you fill the log. What belongs at WARN is a
     * statement about the deployment, and those already live where they are
     * decided: the scope directory warns on an unresolved scope, and the selector
     * registry warns on nothing a caller can provoke.
     *
     * <p>The subject is absent, as everywhere in this service. Correlation runs
     * through a request id; a second aggregatable record of who was refused what
     * is how not-collecting-behavioural-data gets circumvented without anybody
     * deciding to.
     */
    private static final Logger LOG = Logger.getLogger(RefusalMapper.class);

    /**
     * Mapped per exception type rather than over {@code RuntimeException}.
     *
     * <p>A mapper registered for the supertype is chosen for every runtime
     * exception the framework raises too — the 405 a wrong method produces, the
     * 415 a missing content type produces — and re-throwing them from inside a
     * mapper turns each into a 500. The framework's own refusals are part of this
     * surface's contract, so they must reach the caller as themselves.
     */
    @Override
    public Response toResponse(SurfaceException e) {
        LOG.debugf("surface refusal: %s -> %d", e.reason().name(), e.reason().status());

        Response.ResponseBuilder response = Response.status(e.reason().status())
            .type(MediaType.APPLICATION_JSON)
            .entity(Payloads.Refusal.of(e.reason().name(), e.getMessage()));

        if (e.allow() != null) {
            // A 405 without Allow refuses without saying what would have worked,
            // which is the one thing the status is required to carry.
            response.header(HttpHeaders.ALLOW, e.allow());
        }
        return response.build();
    }

    /** The domain's refusals, on the same shape and the same discipline. */
    @Provider
    public static class Domain implements ExceptionMapper<WorklistException> {

        @Override
        public Response toResponse(WorklistException e) {
            int status = statusOf(e.reason());

            // A 5xx is ours, not the caller's, and no retry of theirs fixes it.
            // That is the one refusal class this surface raises to ERROR:
            // everything else is a caller being told no, which is the surface
            // working.
            if (status >= 500) {
                LOG.errorf("domain refusal answered %d: %s", status, e.reason().name());
            } else {
                LOG.debugf("domain refusal: %s -> %d", e.reason().name(), status);
            }

            return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(new Payloads.Refusal(e.reason().name(), e.getMessage(), e.offenders()))
                .build();
        }

        /**
         * One status per domain reason.
         *
         * <p>No {@code default}. A reason added to the domain must be given a
         * status here, and the compiler is what asks for it — the alternative is
         * a new refusal quietly becoming a 500 in a deployment nobody is
         * watching.
         */
        private static int statusOf(WorklistException.Reason reason) {
            return switch (reason) {
                // Malformed, and no scope had to be known to say so. A token that
                // names no view is decidable against the platform's own object
                // model, so it leaks nothing and sits in the grammar stage.
                case VIEW_UNKNOWN -> 400;

                // Nothing there — or nothing this subject may know is there. The
                // absent iteration pointer is in this group and not in the next:
                // 'current' addressed nothing, which is a fact about the address
                // and not about the caller's timing.
                case SCOPE_UNRESOLVED, ITEM_UNKNOWN, MILESTONE_UNKNOWN, ITERATION_UNKNOWN,
                     MEMBERSHIP_UNKNOWN, ITERATION_ABSENT -> 404;

                // The caller's copy is stale. It travels as If-Match, so this is
                // the precondition status and not the conflict one.
                case CONFLICT -> 412;

                // The object is real and its state says no.
                case MEMBERSHIP_PRESENT, ITERATION_CLOSED, ITERATION_INCOMPLETE,
                     ITEM_UNPLANNABLE, SETTING_ABSENT, SETTING_PRESENT,
                     CARDINALITY_EXCEEDED, MARK_REGRESSION -> 409;

                // Vocabulary: the call parses and names something that is not part
                // of what this scope offers, or carries a value a field cannot
                // take. A field this object does not have and a value nobody
                // declared are the same class of answer.
                case UNKNOWN_FIELD, FIELD_NOT_SETTABLE, INVALID_VALUE, VALUE_UNDECLARED,
                     SELECTOR_UNDECLARED, SELECTOR_WITHDRAWN -> 422;

                // Ours, not the caller's, and no retry of theirs will fix it.
                case SESSION_NOT_BOUND -> 500;

                // Also ours, and a different sentence: the call is correct and
                // the act has no carrier in this store yet. 501 says exactly
                // that, and it is the same status the surface gives an unbuilt
                // verb — because it is the same situation seen from the domain.
                case IDENTIFIER_UNDECIDED -> 501;
            };
        }
    }
}
