package ai.kumbuka.worklist.surface;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

/**
 * Derives the calling subject from the token, and from nothing else.
 *
 * <p>The subject is the token's stable subject — {@code sub}, pinned in the
 * configuration rather than left to the default chain, because that chain falls
 * through to a display name and an identifier that can change is one that stops
 * matching the rows it wrote.
 *
 * <p>It is never read from a request body. Authorship is server-derived from the
 * write channel, so a surface that accepted a subject would let a caller sign
 * somebody else's name to a transition — and here it would do worse than that:
 * the scope directory answers for the bound subject only and existence in its
 * answer IS the permission, so a caller-supplied subject would be a
 * caller-supplied authorisation.
 *
 * <p><strong>There is no capacity beside the subject, and that is this service
 * rather than an omission.</strong> The sibling service splits its callers into
 * an executing apparatus and a console because two of its guarantees hang off
 * the distinction. Nothing here is projected differently for different callers:
 * an item reads the same to everyone who may see its scope. Inventing a capacity
 * that no verb consults would be a permission-shaped field that decides nothing,
 * and the first person to read it would assume it did.
 */
@RequestScoped
public class CallerActor {

    @Inject SecurityIdentity identity;

    /**
     * The subject this request acts as.
     *
     * <p>No refusal of its own. A request that got this far authenticated, and
     * what the subject may reach is answered by the scope directory — which
     * refuses by not resolving, in the one place that knows.
     */
    public String subject() {
        return identity.getPrincipal().getName();
    }
}
