package ai.kumbuka.worklist.adapter.rest;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The verbs the REST surface expresses in colon notation, the depth each one
 * acts at, and which of the three classes it is in.
 *
 * <h2>Why colon notation is forced</h2>
 *
 * The id part of an address may be multi-segment — a membership is written
 * {@code iteration/27/562} — so a verb written as a trailing path segment could
 * not be told apart from a further id segment. The colon appears in no address
 * part, which is what makes it decidable. This is a consequence of the address
 * space, not a style choice, and it is why the table below exists at all.
 *
 * <h2>Why it is a table rather than seventeen annotations</h2>
 *
 * Measured against Quarkus REST 3.33.2 on 2026-09-01 in the sibling service: a
 * path template takes the whole segment it appears in. {@code @Path("{id}:close")}
 * registers as {@code {id}} and matches {@code 27:close} with the verb inside
 * the variable, and a narrowing regex on the template is ignored. So a route per
 * verb is not available from the framework, and the seventeen would silently
 * collapse into one — which is the failure mode where every transition answers
 * whichever handler happened to sort first.
 *
 * <p>What is registered instead is one binding per address depth, and the verb is
 * split off here. The <strong>outward form is unchanged</strong> — a caller still
 * writes {@code POST …/27:close} — and it is the outward form the conformance
 * probe measures, end to end, against the specification. What changed is where
 * the split happens, not what a client sees.
 *
 * <h2>The three classes, and why an unbuilt verb is not an uncarried one</h2>
 *
 * {@link Kind#CARRIED} acts. {@link Kind#UNCARRIED} is a category error: the
 * capability declaration does not give the verb to this scheme, and a caller
 * that hears it can stop looking. {@link Kind#UNBUILT} is neither: the
 * declaration gives the worklist scheme the claim family, the graph verbs and
 * {@code validate}, and this service has built none of them. Answering those as
 * uncarried would tell a caller the act does not exist here, which is false and
 * is exactly the kind of false that gets built around.
 */
public enum CustomMethod {

    // ---- Carried, at item depth -----------------------------------------

    /**
     * The intake gate. Reachable, and it refuses — what it used to allocate was
     * the address, which is now allocated with the object. The reasoning is on
     * {@code ItemService.accept} and the decision it waits for is not a build's.
     */
    ACCEPT("accept", Depth.ITEM, Kind.CARRIED),

    /** Takes an item back into a status its scope declared as closed. */
    WITHDRAW("withdraw", Depth.ITEM, Kind.CARRIED),

    /** Terminal on an iteration or a milestone, and never on an item. */
    CLOSE("close", Depth.ITEM, Kind.CARRIED),

    // ---- Carried, at collection depth -----------------------------------

    /**
     * The one carried transition on a truncated address.
     *
     * <p>Admissible there because its verb contract declares set semantics, and
     * the only declarable set semantics is exactly one: it promotes the first
     * open iteration in the scope's own order. Undeclared stays fail-closed,
     * which is why every other carried transition sits at item depth and answers
     * 405 when addressed at a collection.
     */
    ADVANCE("advance", Depth.COLLECTION, Kind.CARRIED),

    // ---- Carried by the scheme, not built by this service ---------------
    //
    // Each answers 501 naming itself. The form below is the one they will take
    // when they are built, as far as it is decided: the claim family follows the
    // sibling service, and the graph verbs are written at item depth here
    // although their ratified form is a sub-collection — which is recorded in
    // the specification as provisional rather than settled by this table.

    CLAIM("claim", Depth.ITEM, Kind.UNBUILT),
    RELEASE("release", Depth.ITEM, Kind.UNBUILT),
    RELATE("relate", Depth.ITEM, Kind.UNBUILT),
    UNRELATE("unrelate", Depth.ITEM, Kind.UNBUILT),
    VALIDATE("validate", Depth.ITEM, Kind.UNBUILT),
    CLAIM_NEXT("claim_next", Depth.COLLECTION, Kind.UNBUILT),

    // ---- Not carried, and answered by name rather than left absent ------

    SEND("send", Depth.ITEM, Kind.UNCARRIED),
    APPEND("append", Depth.ITEM, Kind.UNCARRIED),
    ABANDON("abandon", Depth.ITEM, Kind.UNCARRIED),
    BLOCK("block", Depth.ITEM, Kind.UNCARRIED),
    RESUME("resume", Depth.ITEM, Kind.UNCARRIED),
    CONSUME("consume", Depth.ITEM, Kind.UNCARRIED),

    /**
     * At collection depth because a digest is a summary of a set.
     *
     * <p>Its ratified target is the scope rather than one view, and this surface
     * binds no scope-depth route — so the nearest truncation is where it is
     * refused. That it is refused at all is what the placement has to get right;
     * where it would sit if the scheme carried it is a question for the run that
     * carries it.
     */
    DIGEST("digest", Depth.COLLECTION, Kind.UNCARRIED);

    /** The address depth a verb acts at. Undeclared would mean item only. */
    public enum Depth {
        /** A complete address. */
        ITEM,
        /** A truncated address, which only a declared set semantics admits. */
        COLLECTION
    }

    /**
     * What kind of answer a verb gets.
     *
     * <p>Named {@code Kind} and not {@code Class}: the specification calls these
     * the verb classes, and a nested type by that name would shadow
     * {@code java.lang.Class} for every reader of this file.
     */
    public enum Kind {
        /** This service performs it. */
        CARRIED,
        /** The scheme does not have it: a typed category error. */
        UNCARRIED,
        /** The scheme has it and this service has not built it. */
        UNBUILT
    }

    /** The separator. It appears in no address part, which is the whole point. */
    public static final char SEPARATOR = ':';

    private final String verb;
    private final Depth depth;
    private final Kind kind;

    CustomMethod(String verb, Depth depth, Kind kind) {
        this.verb = verb;
        this.depth = depth;
        this.kind = kind;
    }

    public String verb() {
        return verb;
    }

    public Depth depth() {
        return depth;
    }

    public Kind kind() {
        return kind;
    }

    /** The verbs of one depth, for the conformance probe and for routing. */
    public static List<CustomMethod> at(Depth depth) {
        return Arrays.stream(values()).filter(m -> m.depth == depth).toList();
    }

    /**
     * Splits a path segment into the address part and the verb, where there is
     * one.
     *
     * <p>Split at the <em>last</em> colon rather than the first, because the
     * address part is the thing that may grow segments and the verb is the thing
     * that may not. Splitting at the first colon would make a future
     * multi-segment id shadow the verb.
     *
     * @return the split, or empty when the segment carries no colon at all —
     *         which is a plain address and not a malformed verb
     */
    public static Optional<Split> split(String segment, Depth depth) {
        int at = segment.lastIndexOf(SEPARATOR);
        if (at < 0) {
            return Optional.empty();
        }

        String address = segment.substring(0, at);
        String verb = segment.substring(at + 1);

        return Optional.of(new Split(address, verb,
            at(depth).stream().filter(m -> m.verb.equals(verb)).findFirst().orElse(null)));
    }

    /**
     * A segment taken apart: what addresses, what acts, and which verb it is if
     * this depth carries one by that name.
     *
     * @param method null when no verb of this depth is spelled that way, which is
     *               a different thing from the segment carrying no verb
     */
    public record Split(String address, String verb, CustomMethod method) {

        public boolean isKnown() {
            return method != null;
        }
    }
}
