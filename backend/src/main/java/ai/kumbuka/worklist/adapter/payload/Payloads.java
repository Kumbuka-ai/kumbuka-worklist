package ai.kumbuka.worklist.adapter.payload;

import ai.kumbuka.worklist.surface.AddressParser;
import ai.kumbuka.worklist.surface.VerbInput;
import ai.kumbuka.worklist.surface.VerbSurface;

import java.util.List;
import java.util.Map;

/**
 * The wire shapes of the verb surface.
 *
 * <p>They are records in one file rather than a package of classes because they
 * are one contract: the payload contracts behind the verbs are the named gap in
 * the specification, and scattering a provisional shape across a dozen files
 * makes it look more settled than it is. When the shapes are specified, this file
 * is what gets replaced.
 *
 * <p><strong>No shape here carries a subject.</strong> Authorship is derived from
 * the token and never accepted from a caller, so a field for it would be a field
 * the server has to ignore — and a field the server ignores is one a client will
 * eventually rely on.
 *
 * <h2>Why an answer is an address beside a field map, and not one flat object</h2>
 *
 * The field map is the domain's projection under its own canonical names, and the
 * address is the surface's. Merging them would put an adapter's key into the
 * namespace a caller writes back on the next update — where the domain would
 * either refuse it as unknown or, worse, silently accept a key that means
 * something to nobody. Keeping them apart is what makes read-modify-write safe:
 * everything under {@code fields} may be sent back untouched.
 */
public final class Payloads {

    private Payloads() {
    }

    // ----------------------------------------------------------------------
    // Wire shape to verb input
    // ----------------------------------------------------------------------
    //
    // The translation runs in this direction and only in this direction. The
    // adapter knows the surface; the surface does not know the adapter, which is
    // what keeps the two out of an import cycle. Every one of these passes null
    // through untouched: a missing body is refused by the surface, after the
    // scope has been resolved, and refusing it here would move that answer in
    // front of a check the check order puts first.

    /** The fields behind a create or an update, or null when no body arrived. */
    public static VerbInput.Fields fields(Map<String, Object> body) {
        return body == null ? null : new VerbInput.Fields(body);
    }

    /** The status behind a withdrawal, or null when no body arrived. */
    public static VerbInput.Withdrawal withdrawal(WithdrawRequest request) {
        return request == null ? null : new VerbInput.Withdrawal(request.status());
    }

    /** The lease duration behind a claim or a draw, or null when no body arrived. */
    public static VerbInput.Lease lease(LeaseRequest request) {
        return request == null ? null : new VerbInput.Lease(request.durationSeconds());
    }

    /** The receipt behind a release, or null when no body arrived. */
    public static VerbInput.Release releaseOf(ReleaseRequest request) {
        return request == null ? null : new VerbInput.Release(request.receipt());
    }

    /** The edge behind a relate or an unrelate, or null when no body arrived. */
    public static VerbInput.Edge edge(EdgeRequest request) {
        return request == null ? null : new VerbInput.Edge(request.toItem(), request.type());
    }

    // ----------------------------------------------------------------------
    // Verb answer to wire shape
    // ----------------------------------------------------------------------

    /** One object, at its address. */
    public static ObjectResponse of(String scope, VerbSurface.Result result) {
        return new ObjectResponse(
            AddressParser.render(scope, result.address()), result.fields());
    }

    /** A set of them. */
    public static Listing of(String scope, VerbSurface.Listing listing) {
        return new Listing(listing.objects().stream()
            .map(result -> of(scope, result))
            .toList(), listing.truncated());
    }

    /**
     * What a caller supplies to withdraw an item.
     *
     * <p>The status is the scope's declared value, by identity. A display name
     * would be a value the scope may change under the caller, and a withdrawal
     * that named one would land somewhere else the day somebody renamed it.
     */
    public record WithdrawRequest(String status) {
    }

    /**
     * What a caller supplies to claim a lease or to draw one.
     *
     * <p>Seconds because integers travel legibly and a duration string is a
     * form a caller has to guess at. The domain refuses a non-positive value —
     * a zero-duration lease is inert the moment it is granted, which is the
     * predecessor's exact defect.
     */
    public record LeaseRequest(long durationSeconds) {
    }

    /**
     * What a caller supplies to release a lease.
     *
     * <p>The receipt the row was minted with. Opaque here, compared as a
     * string in the domain — a caller that parses it is a caller that breaks
     * when the generator changes.
     */
    public record ReleaseRequest(String receipt) {
    }

    /**
     * What a caller supplies to assert or withdraw one edge.
     *
     * <p>The source is the address the verb runs against. The target and the
     * type both travel as ids, because a declared value has an identity
     * separate from its name and the display name is what a scope is free to
     * change.
     */
    public record EdgeRequest(String toItem, String type) {
    }

    /**
     * One object as it goes out: its address, and what the domain projected.
     *
     * <p>The field map is passed through unaltered. An adapter that reshaped it
     * would be a second naming of the same values — which is the defect the
     * canonical field catalogue was built to remove, reintroduced one layer out.
     */
    public record ObjectResponse(String address, Map<String, Object> fields) {
    }

    /**
     * A listing.
     *
     * <p>An object around the list rather than the bare array, so that anything a
     * listing later needs to say about itself is an added key rather than a
     * changed shape.
     *
     * <p>{@code truncated} is a fact about the write. A caller who did not
     * name a limit sees {@code false}; a caller whose limit was reached sees
     * {@code true} — the same silent-ceiling defect the sprint-169 read had
     * one layer down, refused at the surface.
     */
    public record Listing(List<ObjectResponse> objects, boolean truncated) {
    }

    /**
     * A refusal, in the one shape every refusal takes.
     *
     * <p>The reason is for a caller and is stable; the message is for a human and
     * names the specifics. A caller matching on prose is a caller that breaks
     * when somebody improves the wording.
     */
    public record Refusal(String reason, String message, List<String> offenders) {

        public static Refusal of(String reason, String message) {
            return new Refusal(reason, message, List.of());
        }
    }
}
