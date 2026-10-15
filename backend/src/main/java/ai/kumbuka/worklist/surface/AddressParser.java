/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.Selector;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns the path parts of {@code {scope}/{view}/{id}} into an address, and
 * refuses everything that is not one.
 *
 * <h2>Validation precedes parsing</h2>
 *
 * The production is applied to the raw string before anything is taken apart.
 * That order is the whole design: a parser that splits first and validates the
 * pieces afterwards has already decided what the pieces are, and its refusal
 * then describes the split rather than the input. Here a form error is a typed
 * rejection with nothing resolved, and a well-formed address of a thing that is
 * not there is a not-found from the store. <strong>The two classes never
 * merge.</strong> They are produced in different places by different code, and
 * this class is structurally unable to produce the second one — it never looks
 * anything up.
 *
 * <h2>What is tolerated and what is not</h2>
 *
 * What does not change identity is tolerated; what changes it is rejected. A
 * trailing slash changes nothing about which object is addressed, because the
 * occupied parts decide that, so it is tolerated on the way in and never
 * generated on the way out. Upper case is rejected and <strong>never
 * folded</strong>: folding would make two distinct strings resolve to one
 * identity, and an identity statement must not arise from leniency. That rule
 * is why the selector is lower case at all — the decision is recorded on
 * {@link Selector#TOKEN_PATTERN}, where the pattern the database mirrors lives.
 *
 * <h2>Why the view is checked here and the declaration is not</h2>
 *
 * The platform's views are a closed set — see {@link Selector#VIEWS} — and
 * they are the platform's object model, so "is this a view" is decidable
 * without knowing any scope and belongs in stage 1 with the rest of the
 * grammar. Whether a given scope has DECLARED that view is a different
 * question with a different answer path: it needs the scope, so it sits behind
 * scope visibility, and it is the domain that answers it. Moving the first
 * check back would cost nothing; moving the second one forward would turn the
 * error path into a scope enumerator.
 */
public final class AddressParser {

    /**
     * The scheme this service answers for, in leading position of an address.
     *
     * <p>Present in the MCP form and absent from the REST path, and that is not
     * an inconsistency: the scheme is the routing decision. Inbound over MCP
     * there is no request line for an address to travel in, so it arrives
     * complete in the body and the scheme comes with it; inbound over REST the
     * path is what routed, and a scheme repeated there would be a second place
     * for the routing to be decided.
     */
    public static final String SCHEME = "worklist";

    /**
     * The iteration the scope is working, addressed as a word rather than a
     * number.
     *
     * <p>It resolves and it is <strong>read-only</strong>. The pointer moves
     * when the scope advances, so an address that carried it into a stored
     * reference would name one iteration today and another one next week —
     * which is precisely what an address must not do. Writes through it are
     * refused at the surface rather than left to the domain, because the domain
     * receives a resolved iteration id and by then the pointer is gone.
     */
    public static final String CURRENT = "current";

    /**
     * The scope, as a DNS label: lower case, digits and inner hyphens, at most
     * sixty-three characters.
     *
     * <p>The address is a URI with the scope in the authority position, so its
     * grammar is the authority's and not a free slug. Checking it here rather
     * than at the directory keeps a malformed scope in stage 1, where the answer
     * leaks nothing — the directory's refusal necessarily reveals that a lookup
     * happened.
     */
    private static final Pattern SCOPE = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    /**
     * A number in an address: at least one, no leading zeroes.
     *
     * <p>Leading zeroes are form rather than taste: {@code 07} and {@code 7}
     * would be two strings for one object, which is the same
     * identity-by-leniency the case rule refuses. Zero itself is excluded
     * because every counter in this store hands out its first number as one,
     * and an address that can never resolve is better refused than looked up.
     */
    private static final Pattern NUMBER = Pattern.compile("[1-9]\\d{0,18}");


    private AddressParser() {
    }

    /**
     * What an address points at, once the grammar has held.
     *
     * @param view   one of the three, always present
     * @param number the object's number, or null for {@link #CURRENT}
     * @param member the item number of a membership, or null when the address
     *               names the iteration itself
     */
    public record Target(String view, Long number, Long member) {

        /** Whether the address is the moving pointer rather than a number. */
        public boolean isCurrent() {
            return number == null;
        }

        /** Whether the address names a membership under an iteration. */
        public boolean isMembership() {
            return member != null;
        }

        /** The id part, in the canonical form the surface generates. */
        public String id() {
            String head = isCurrent() ? CURRENT : String.valueOf(number);
            return isMembership() ? head + "/" + member : head;
        }
    }

    /** The three occupied parts of a complete address, as they arrive over MCP. */
    public record Parts(String scope, String view, String id) {
    }

    /**
     * The complete address of what a verb answered with.
     *
     * <p>Generated here and never echoed from the request, so a tolerated
     * trailing slash does not survive into a {@code Location} header that other
     * clients will treat as an identity.
     */
    public static String render(String scope, Target target) {
        return SCHEME + "://" + scope + "/" + target.view() + "/" + target.id();
    }

    /**
     * The scope name, checked against the authority production.
     *
     * @return the same string, unchanged. Nothing is normalised here: a value
     *         this method altered would be a second identity for the one that
     *         arrived.
     */
    public static String scope(String raw) {
        String candidate = requirePresent(raw);
        if (!SCOPE.matcher(candidate).matches()) {
            throw malformed();
        }
        return candidate;
    }

    /**
     * The view, checked against the platform's set.
     *
     * <p>The form check runs first and the membership check second, so that a
     * token which is not even a token is told apart from one that is a
     * perfectly good word for something this service does not hold.
     */
    public static String view(String raw) {
        String candidate = requirePresent(raw);
        if (!Selector.TOKEN_PATTERN.matcher(candidate).matches()) {
            throw malformed();
        }
        if (!Selector.VIEWS.contains(candidate)) {
            throw malformed();
        }
        return candidate;
    }

    /**
     * A complete address of one object: view plus a single id segment.
     *
     * <p>Takes the view as well because an address is four parts and a
     * three-part one has no meaning here — the reservation on a truncated
     * address is what makes {@code scope/view} a collection rather than "all the
     * objects of this scope".
     */
    public static Target target(String rawView, String rawId) {
        String view = view(rawView);
        String candidate = requirePresent(rawId);

        if (CURRENT.equals(candidate)) {
            if (!Selector.ITERATION.equals(view)) {
                throw malformed();
            }
            return new Target(view, null, null);
        }

        return new Target(view, number(candidate, "id"), null);
    }

    /**
     * The address of a membership: an item's place in one iteration.
     *
     * <p>A membership is addressed <strong>under</strong> its iteration, as a
     * second id segment, because that is what it is: it has no life of its own
     * and it belongs to the iteration's aggregate. It is not a fourth view. A
     * view for it would say it is a kind of thing a scope holds, and it would
     * need a number space of its own to be addressed in — which is the point at
     * which a relation starts pretending to be an object.
     *
     * <p><strong>Not addressable under {@link #CURRENT}.</strong> The pointer is
     * read-only, and a membership address is the one place where the read and
     * the write forms are the same string: {@code plan} writes at exactly the
     * address {@code read} reads. Admitting the pointer for the read half would
     * mean two spellings of one membership, one of which cannot be written
     * through — so it is refused for both, which is fail-closed and one rule
     * instead of two.
     */
    public static Target membership(String rawView, String rawIteration, String rawItem) {
        String view = view(rawView);
        if (!Selector.ITERATION.equals(view)) {
            throw malformed();
        }

        String iteration = requirePresent(rawIteration);
        if (CURRENT.equals(iteration)) {
            throw malformed();
        }

        return new Target(view, number(iteration, "id"), number(rawItem, "item number"));
    }

    /**
     * Splits {@code worklist://scope/view/id} into its parts, and refuses
     * everything that is not one.
     *
     * <p>An address is a URI with the scheme leading and the scope as a DNS
     * label in the authority position. It is split here by the production rather
     * than by a URI library, for the same reason validation precedes parsing
     * everywhere else: a library normalises, and a normalisation is an identity
     * statement made by somebody who was not asked.
     *
     * <p>A trailing slash is tolerated because it changes nothing about which
     * object is addressed — the occupied parts decide that. Upper case is not
     * folded. The id part may occupy two segments, which is what makes the count
     * below a range rather than an equality: truncation is recognised by which
     * PARTS are occupied and never by counting separators.
     */
    public static Parts uri(String raw) {
        String candidate = requirePresent(raw);
        String prefix = SCHEME + "://";

        if (!candidate.startsWith(prefix)) {
            throw malformed();
        }

        String rest = candidate.substring(prefix.length());
        if (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }

        List<String> parts = List.of(rest.split("/", -1));
        if (parts.size() < 3 || parts.size() > 4) {
            throw malformed();
        }

        // Each part is validated by its own production, and the whole address is
        // rejected if any of them fails. Validating here rather than at the call
        // site is what keeps the MCP and the REST entrance on one grammar.
        scope(parts.get(0));
        if (parts.size() == 3) {
            target(parts.get(1), parts.get(2));
            return new Parts(parts.get(0), parts.get(1), parts.get(2));
        }
        membership(parts.get(1), parts.get(2), parts.get(3));
        return new Parts(parts.get(0), parts.get(1), parts.get(2) + "/" + parts.get(3));
    }

    /**
     * A number in an address.
     *
     * <p>Bounded at nineteen digits by the pattern rather than by catching the
     * overflow: a value that does not fit is a form error, and finding that out
     * from an exception thrown by the parser would put the refusal in the
     * catch block of a method that is supposed to decide it.
     */
    private static long number(String raw, String part) {
        String candidate = requirePresent(raw);
        if (!NUMBER.matcher(candidate).matches()) {
            throw malformed();
        }
        return Long.parseLong(candidate);
    }

    private static String requirePresent(String raw) {
        if (raw == null || raw.isBlank()) {
            throw malformed();
        }
        return raw;
    }

    private static SurfaceException malformed() {
        return new SurfaceException(RefusalCode.ADDRESS_MALFORMED);
    }
}
