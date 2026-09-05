package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.AddressRegistry;
import ai.kumbuka.worklist.domain.Field;
import ai.kumbuka.worklist.domain.ItemService;
import ai.kumbuka.worklist.domain.IterationService;
import ai.kumbuka.worklist.domain.MembershipService;
import ai.kumbuka.worklist.domain.MilestoneService;
import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.platform.ScopeDirectory;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The verbs, once.
 *
 * <p>Both expositions call this and neither reimplements it. That is what makes
 * "REST is the complete surface and MCP only omits" a property of the
 * construction rather than a claim about it: an omission is a tool the MCP
 * adapter does not declare, and an addition is impossible because there is
 * nothing here for one adapter to reach that the other cannot.
 *
 * <p>Two things deliberately do <strong>not</strong> live here, because they are
 * expression rather than offering. The HTTP form of a verb — colon notation, 201
 * with {@code Location}, 405 with {@code Allow} — is the REST adapter's. The
 * shape of a JSON-RPC tool call is the MCP adapter's. What is shared is the act,
 * its checks and their order.
 *
 * <h2>The order of the checks, and why it is here</h2>
 *
 * Grammar (stage 1) then scope visibility (stage 2) then vocabulary (stage 3)
 * then resolution (stage 4). It is in this class rather than in each adapter
 * because a check order is exactly the thing that drifts between two copies, and
 * the one that would drift first is the one whose whole purpose is that a scope
 * the caller cannot see answers 404 however broken the rest of the call is.
 *
 * <p>Stages 1 and 2 run in {@link #entry}; stages 3 and 4 run inside
 * {@link AddressRegistry}, which the entry hands its resolved scope to. The
 * split is not cosmetic: everything before the scope is resolved is decidable
 * without knowing any scope and therefore leaks nothing, and everything after it
 * necessarily reveals that a lookup happened.
 *
 * <h2>Three classes of verb, answered three ways</h2>
 *
 * <p><strong>Carried</strong> — the ten this scheme has and this service has
 * built. They act.
 *
 * <p><strong>Uncarried</strong> — {@code send}, {@code append}, {@code digest},
 * {@code abandon}, {@code block}, {@code resume}, {@code consume}. The capability
 * declaration does not give them to this scheme; they answer a typed category
 * error naming the reason, never a not-found and never a silent absence.
 *
 * <p><strong>Unbuilt</strong> — the claim family, the graph verbs and
 * {@code validate}. The declaration DOES give them to this scheme and this
 * service has not built them. Answering them as uncarried would be a lie a
 * caller acts on: it says the act will never exist here.
 */
@ApplicationScoped
@TenantBound
public class VerbSurface {

    /**
     * The convention this service's loggers keep: an address, a scope id, a
     * count, a transition — never a title, a motto, a description, a token or
     * the subject. The operator boundary of this service is a missing GRANT, and
     * a log line carrying content walks around it by a different road.
     */
    private static final Logger LOG = Logger.getLogger(VerbSurface.class);

    @Inject ItemService items;
    @Inject IterationService iterations;
    @Inject MilestoneService milestones;
    @Inject MembershipService memberships;
    @Inject AddressRegistry addresses;
    @Inject ScopeDirectory scopes;

    // ======================================================================
    // create — the collection form
    // ======================================================================

    /**
     * Brings an object into being in one view.
     *
     * <p>The view arrives as an argument and not as an address, and that is the
     * ratified rule rather than a convenience: an address without an id part is
     * reserved, so a three-part string is not available to mean "the objects of
     * this scope", and giving it that meaning by accident is exactly what the
     * reservation forbids.
     *
     * <p>Every number this store hands out is allocated inside the transaction
     * that inserts the row and none is ever accepted from a caller. What comes
     * back therefore carries an address the caller could not have predicted,
     * which is the point.
     *
     * <p>The vocabulary stage runs even though nothing is resolved: a view this
     * scope has not declared has no address space, so an object created under it
     * would answer with an address that resolves to a refusal on the next call.
     * The item allocator asks anyway; the two axes allocate from their own marks
     * and would not.
     */
    @Transactional
    public Result create(String subject, String rawScope, String rawView,
                         VerbInput.Fields body) {
        Entry in = entry(subject, rawScope, rawView);
        addresses.requireView(in.scopeId(), in.view());
        Map<String, Object> fields = required(body).values();

        Map<String, Object> created = switch (in.view()) {
            case Selector.ITEM -> items.create(in.scopeId(), fields);
            case Selector.ITERATION -> iterations.create(in.scopeId(), fields);
            case Selector.MILESTONE -> milestones.create(in.scopeId(), fields);
            default -> throw unreachableView(in.view());
        };

        Result result = at(in.view(), created);
        LOG.infof("create %s in scope %s", result.address().id(), in.scopeId());
        return result;
    }

    // ======================================================================
    // query — the other verb on a truncated address
    // ======================================================================

    /**
     * The objects of one view.
     *
     * <p>No filters. The three domain queries take a scope and nothing else, and
     * a filter argument accepted here and dropped on the way down would answer
     * the full set while looking like a correct narrow one — which is the exact
     * defect the canonical naming was built against, moved one layer out. When
     * the domain grows a filter, this passes it through raw and lets the domain
     * refuse what it does not carry.
     */
    @Transactional
    public Listing query(String subject, String rawScope, String rawView) {
        Entry in = entry(subject, rawScope, rawView);
        addresses.requireView(in.scopeId(), in.view());

        List<Map<String, Object>> found = switch (in.view()) {
            case Selector.ITEM -> items.query(in.scopeId());
            case Selector.ITERATION -> iterations.query(in.scopeId());
            case Selector.MILESTONE -> milestones.query(in.scopeId());
            default -> throw unreachableView(in.view());
        };

        LOG.debugf("query %s in scope %s: %d hit(s)", in.view(), in.scopeId(), found.size());
        return new Listing(found.stream().map(row -> at(in.view(), row)).toList());
    }

    // ======================================================================
    // read and update — the item form
    // ======================================================================

    @Transactional
    public Result read(String subject, String rawScope, String rawView, String rawId) {
        Entry in = entry(subject, rawScope, rawView);
        AddressParser.Target target = AddressParser.target(rawView, rawId);
        UUID id = resolve(in, target);

        return at(target, switch (in.view()) {
            case Selector.ITEM -> items.read(in.scopeId(), id);
            case Selector.ITERATION -> iterations.read(in.scopeId(), id);
            case Selector.MILESTONE -> milestones.read(in.scopeId(), id);
            default -> throw unreachableView(in.view());
        });
    }

    /**
     * Changes what is known about an object.
     *
     * <p>The conflict token arrives in the transport and the domain reads it as
     * a field, so it is written into the field map here — see
     * {@link VerbInput.Fields#withToken}. A write with no token is refused
     * before the domain is entered, because the domain's refusal would be about
     * a stale token and this one is about an absent one.
     */
    @Transactional
    public Result update(String subject, String rawScope, String rawView, String rawId,
                         String conflictToken, VerbInput.Fields body) {
        Entry in = entry(subject, rawScope, rawView);
        AddressParser.Target target = AddressParser.target(rawView, rawId);
        requireWritable(target);
        UUID id = resolve(in, target);

        Map<String, Object> fields = required(body)
            .withToken(Field.CONFLICT_TOKEN.canonicalName(), requireToken(conflictToken))
            .values();

        Result result = at(target, switch (in.view()) {
            case Selector.ITEM -> items.update(in.scopeId(), id, fields);
            case Selector.ITERATION -> iterations.update(in.scopeId(), id, fields);
            case Selector.MILESTONE -> milestones.update(in.scopeId(), id, fields);
            default -> throw unreachableView(in.view());
        });
        LOG.infof("update %s in scope %s", target.id(), in.scopeId());
        return result;
    }

    // ======================================================================
    // The transitions this scheme carries
    // ======================================================================

    /**
     * The intake gate.
     *
     * <p>It refuses, and the refusal is the domain's rather than this class's.
     * That placement is the whole of it: what {@code accept} would allocate is a
     * question about the store, and a surface that answered it — by executing
     * something, or by refusing on its own authority — would be deciding a
     * specification gap in an adapter. See {@link ItemService#accept}.
     */
    @Transactional
    public Result accept(String subject, String rawScope, String rawView, String rawId,
                         String conflictToken) {
        Entry in = entry(subject, rawScope, rawView);
        AddressParser.Target target = requireView(rawView, rawId, Selector.ITEM, "accept");
        UUID id = resolve(in, target);

        return at(target, items.accept(in.scopeId(), id, requireToken(conflictToken)));
    }

    /**
     * Takes an item back. It keeps its address forever.
     *
     * <p><strong>This is the declared semantic deviation of the mapping table,
     * and it is named here rather than smoothed over.</strong> The catalogue
     * maps {@code withdraw} onto the predecessor's {@code delete}, and the two
     * do different things: {@code delete} removes the row entirely, and this
     * tombstones. The reconciliation is open — it is a decision about the
     * predecessor's corpus and its migration, not about this surface — and what
     * this build owes it is that neither side is picked silently.
     *
     * <p>Which side this service is on is not in doubt and never was: there is
     * no hard delete anywhere in this store, and the reason is specific rather
     * than doctrinal. An address that was issued must not later resolve to
     * nothing. The predecessor did the opposite and the consequence was
     * measured: a removed item's number became allocatable again, so two items
     * in one corpus's history could carry one number and every external
     * reference to the first kept resolving, at the wrong item.
     *
     * <p>What the deviation costs, stated so that the migration can price it: a
     * corpus imported from the predecessor carries rows that were deleted and
     * are simply absent, and nothing here can tell those apart from rows that
     * were never created. That is a gap in the imported history and not
     * something this verb can repair.
     *
     * <p>The status it moves into is the scope's own and travels in the body:
     * which values a scope closes with is its declaration, and that a withdrawal
     * is terminal is the platform's. A terminal status named here would be the
     * literal vocabulary the store was rebuilt to remove.
     */
    @Transactional
    public Result withdraw(String subject, String rawScope, String rawView, String rawId,
                           String conflictToken, VerbInput.Withdrawal body) {
        Entry in = entry(subject, rawScope, rawView);
        AddressParser.Target target = requireView(rawView, rawId, Selector.ITEM, "withdraw");
        UUID id = resolve(in, target);

        Result result = at(target, items.withdraw(in.scopeId(), id,
            statusOf(required(body).status()), requireToken(conflictToken)));
        LOG.infof("withdraw %s in scope %s", target.id(), in.scopeId());
        return result;
    }

    /**
     * Closes an iteration or a milestone.
     *
     * <p>One verb over two views, chosen by the address. It is never addressed at
     * an item: an item's terminality is a declared status reached through
     * {@code update}, and the asymmetry against the sibling scheme is a decision
     * — an item is mutable for its whole life, an exchange freezes.
     */
    @Transactional
    public Result close(String subject, String rawScope, String rawView, String rawId,
                        String conflictToken) {
        Entry in = entry(subject, rawScope, rawView);
        AddressParser.Target target = AddressParser.target(rawView, rawId);
        requireWritable(target);
        UUID id = resolve(in, target);
        String token = requireToken(conflictToken);

        Result result = at(target, switch (in.view()) {
            case Selector.ITERATION -> iterations.close(in.scopeId(), id, token);
            case Selector.MILESTONE -> milestones.close(in.scopeId(), id, token);
            case Selector.ITEM -> throw new SurfaceException(
                SurfaceException.Reason.VERB_UNCARRIED,
                "'close' is not addressed at an item. An item's terminality is a status "
                    + "its scope declared as closed, reached through 'update' — so closing "
                    + "one is an ordinary change of a declared value and not a transition "
                    + "of its own. On this scheme 'close' addresses the iteration or the "
                    + "milestone, both of which have a terminal transition at form level.");
            default -> throw unreachableView(in.view());
        });
        LOG.infof("close %s in scope %s", target.id(), in.scopeId());
        return result;
    }

    /**
     * Promotes the first planned iteration to current.
     *
     * <p>A write on a truncated address, admissible for the same reason the
     * claiming draw is: the verb contract declares set semantics and the only
     * declarable one is exactly one. It promotes the first open iteration in the
     * scope's own order — not one the caller names, which is why there is no
     * address for it to take.
     *
     * <p>The token it presents is the SETTINGS row's, not an iteration's. The
     * pointer being moved lives there, and a token of the iteration would guard
     * the wrong row: two callers advancing at once would both hold a valid
     * iteration token and both move the pointer.
     */
    @Transactional
    public Result advance(String subject, String rawScope, String rawView,
                          String conflictToken) {
        Entry in = entry(subject, rawScope, rawView);
        if (!Selector.ITERATION.equals(in.view())) {
            throw new SurfaceException(SurfaceException.Reason.VERB_UNCARRIED,
                "'advance' promotes an iteration and is addressed at the iteration view. "
                    + "The goal axis has no order to advance through: a milestone becomes "
                    + "active by being set active, which is a declared status and an "
                    + "ordinary update.");
        }

        Map<String, Object> setting = iterations.advance(in.scopeId(),
            requireToken(conflictToken));
        LOG.infof("advance in scope %s", in.scopeId());

        // The answer is the SETTINGS row, because that is what the verb wrote:
        // it moved a pointer. Dressing it as the promoted iteration would be
        // this class inventing an answer the domain did not give, and the two
        // would disagree the first time the domain returned a warning.
        return new Result(new AddressParser.Target(Selector.ITERATION, null, null), setting);
    }

    // ======================================================================
    // The membership: addressed under its iteration, never as a view
    // ======================================================================

    @Transactional
    public Result readMembership(String subject, String rawScope, String rawView,
                                 String rawIteration, String rawItem) {
        Entry in = entry(subject, rawScope, rawView);
        AddressParser.Target target = AddressParser.membership(rawView, rawIteration, rawItem);
        Membership at = membership(in, target);

        return at(target, memberships.read(in.scopeId(), at.iterationId(), at.itemId()));
    }

    /** Adds an item to an iteration, at the end of its order. */
    @Transactional
    public Result plan(String subject, String rawScope, String rawView, String rawIteration,
                       String rawItem, String conflictToken) {
        Entry in = entry(subject, rawScope, rawView);
        AddressParser.Target target = AddressParser.membership(rawView, rawIteration, rawItem);
        Membership at = membership(in, target);

        Result result = at(target, memberships.plan(in.scopeId(), at.iterationId(),
            at.itemId(), requireToken(conflictToken)));
        LOG.infof("plan %s in scope %s", target.id(), in.scopeId());
        return result;
    }

    /** Removes it again. */
    @Transactional
    public Result unplan(String subject, String rawScope, String rawView, String rawIteration,
                         String rawItem, String conflictToken) {
        Entry in = entry(subject, rawScope, rawView);
        AddressParser.Target target = AddressParser.membership(rawView, rawIteration, rawItem);
        Membership at = membership(in, target);

        Result result = at(target, memberships.unplan(in.scopeId(), at.iterationId(),
            at.itemId(), requireToken(conflictToken)));
        LOG.infof("unplan %s in scope %s", target.id(), in.scopeId());
        return result;
    }

    /** Changes a membership — its status in this iteration, above all. */
    @Transactional
    public Result updateMembership(String subject, String rawScope, String rawView,
                                   String rawIteration, String rawItem, String conflictToken,
                                   VerbInput.Fields body) {
        Entry in = entry(subject, rawScope, rawView);
        AddressParser.Target target = AddressParser.membership(rawView, rawIteration, rawItem);
        Membership at = membership(in, target);

        Map<String, Object> fields = required(body)
            .withToken(Field.CONFLICT_TOKEN.canonicalName(), requireToken(conflictToken))
            .values();

        Result result = at(target, memberships.update(in.scopeId(), at.iterationId(),
            at.itemId(), fields));
        LOG.infof("update %s in scope %s", target.id(), in.scopeId());
        return result;
    }

    // ======================================================================
    // The verbs that do not act, and the two different reasons for it
    // ======================================================================

    /**
     * A verb the capability declaration does not give this scheme.
     *
     * <p>Behind scope visibility, deliberately. Capability is declared per scope,
     * so a category error is in principle a statement about a scope; today the
     * declaration is identical for every scope and nothing leaks either way, but
     * the order is the ratified one and a per-scope declaration is the direction
     * of travel.
     *
     * @param at the id part where the call carried one, so that a caller who got
     *           the address wrong is told about the address first
     */
    @Transactional
    public void uncarried(String subject, String rawScope, String rawView, String at,
                          String verb, String why) {
        entry(subject, rawScope, rawView);
        if (at != null) {
            AddressParser.target(rawView, at);
        }
        throw new SurfaceException(SurfaceException.Reason.VERB_UNCARRIED,
            "'" + verb + "' is not part of the worklist scheme. " + why);
    }

    /**
     * A verb this scheme carries and this service has not built.
     *
     * <p>Answered apart from the one above and it matters which a caller gets.
     * "Not carried" says the act does not exist here and never will, and a caller
     * that believes it stops asking. This one says the act belongs here and is
     * not ready, which is a different thing to plan around.
     */
    @Transactional
    public void unbuilt(String subject, String rawScope, String rawView, String at,
                        String verb, String why) {
        entry(subject, rawScope, rawView);
        if (at != null) {
            AddressParser.target(rawView, at);
        }
        throw new SurfaceException(SurfaceException.Reason.VERB_UNBUILT,
            "'" + verb + "' is a verb of the worklist scheme and this service has not "
                + "built it. " + why);
    }

    // ======================================================================
    // Stage 1 and stage 2
    // ======================================================================

    /**
     * Grammar, then scope visibility.
     *
     * <p>The grammar runs against the raw strings before anything is resolved.
     * Stage 1 is decidable without knowing a scope, so its refusal leaks nothing;
     * every later refusal necessarily reveals that a lookup happened.
     *
     * <p>Nothing here touches the store. That is what makes the order observable:
     * a probe can call this against a scope it may not see and get 404 with
     * whatever else is wrong with the call, because nothing that would answer
     * differently has run yet.
     */
    private Entry entry(String subject, String rawScope, String rawView) {
        String slug = AddressParser.scope(rawScope);
        String view = AddressParser.view(rawView);
        return new Entry(scopes.resolve(subject, slug).scopeId(), view);
    }

    // ======================================================================
    // Stage 3 and stage 4, through the domain
    // ======================================================================

    /** The row one address names, with the vocabulary checked on the way. */
    private UUID resolve(Entry in, AddressParser.Target target) {
        if (target.isCurrent()) {
            return addresses.currentIteration(in.scopeId());
        }
        return switch (target.view()) {
            case Selector.ITEM -> addresses.itemAt(in.scopeId(), target.number());
            case Selector.ITERATION -> addresses.iterationAt(in.scopeId(), target.number());
            case Selector.MILESTONE -> addresses.milestoneAt(in.scopeId(), target.number());
            default -> throw unreachableView(target.view());
        };
    }

    /** Both rows a membership address names, resolved in the order it is written. */
    private Membership membership(Entry in, AddressParser.Target target) {
        return new Membership(
            addresses.iterationAt(in.scopeId(), target.number()),
            addresses.itemAt(in.scopeId(), target.member()));
    }

    // ======================================================================
    // The refusals this class owns
    // ======================================================================

    /**
     * A write through the moving pointer.
     *
     * <p>Refused here rather than in the domain, and it has to be: the domain
     * receives a resolved iteration id, and by then there is nothing left to say
     * that the caller addressed the pointer rather than the iteration.
     */
    private static void requireWritable(AddressParser.Target target) {
        if (target.isCurrent()) {
            throw new SurfaceException(SurfaceException.Reason.ADDRESS_READ_ONLY,
                "'" + AddressParser.CURRENT + "' is a pointer the scope moves, so a write "
                    + "through it would land on whichever iteration happened to be current "
                    + "when the call arrived. Read it to find out which one that is, then "
                    + "write at its number.",
                "GET");
        }
    }

    /** A verb that only one view carries, addressed at another. */
    private static AddressParser.Target requireView(String rawView, String rawId,
                                                    String expected, String verb) {
        AddressParser.Target target = AddressParser.target(rawView, rawId);
        if (!expected.equals(target.view())) {
            throw new SurfaceException(SurfaceException.Reason.VERB_UNCARRIED,
                "'" + verb + "' is addressed at the " + expected + " view and this address "
                    + "names the " + target.view() + " view. The verb is one word across "
                    + "the platform and the ADDRESS says what it acts on, so aiming it at "
                    + "the wrong kind of thing is a category error rather than a typo.");
        }
        requireWritable(target);
        return target;
    }

    private static String requireToken(String presented) {
        if (presented == null || presented.isBlank()) {
            throw new SurfaceException(SurfaceException.Reason.CONFLICT_TOKEN_MISSING,
                "this write declares conflict-token repetition, so it carries one. The "
                    + "token is the one handed out with the last read. Without it a retry "
                    + "across a network cannot be told from a second, different write.");
        }
        return presented;
    }

    private static UUID statusOf(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new SurfaceException(SurfaceException.Reason.PAYLOAD_MALFORMED,
                "a withdrawal names the status it moves the item into, by identity and not "
                    + "by display name: '" + raw + "' is not one. A declared value's name "
                    + "is a display property and may be changed at will, so a caller "
                    + "writing one would be writing something that is allowed to move "
                    + "under it.");
        }
    }

    private static <T> T required(T body) {
        if (body == null) {
            throw new SurfaceException(SurfaceException.Reason.PAYLOAD_MALFORMED,
                "this verb takes arguments and none arrived.");
        }
        return body;
    }

    /**
     * The switch arm that cannot be reached.
     *
     * <p>Every switch over the view above has one, because the grammar admits
     * exactly three values and a Java switch over a String does not know that. It
     * throws rather than returning something, so that a fourth view added to the
     * grammar without an arm here is a loud failure and not a silent branch.
     */
    private static IllegalStateException unreachableView(String view) {
        return new IllegalStateException(
            "'" + view + "' passed the grammar and names no view. The grammar admits "
                + Selector.VIEWS + " and nothing else, so this is unreachable unless the "
                + "two have been changed apart.");
    }

    // ======================================================================
    // Answers
    // ======================================================================

    /** The answer to any verb: what the domain projected, at the address it is. */
    public record Result(AddressParser.Target address, Map<String, Object> fields) {

        /**
         * The conflict token the answer carries, or null.
         *
         * <p>Read off the projection here so that an adapter dressing this in a
         * transport header does not have to know the domain's field names. The
         * token is a field because the round trip needs it there — the map a
         * caller read is the map they send back — and it is also a header
         * because that is where HTTP looks for it. One value, two places it is
         * legible from, and this method is the single place that knows both.
         */
        public String conflictToken() {
            Object token = fields.get(Field.CONFLICT_TOKEN.canonicalName());
            return token == null ? null : String.valueOf(token);
        }
    }

    /**
     * What a listing answers with.
     *
     * <p>An object around the list rather than the bare array, so that anything a
     * listing later needs to say about itself — a continuation token above all —
     * is an added key rather than a changed shape. A bare array cannot grow a
     * sibling field, and this surface is a published contract from the day it
     * answers.
     *
     * <p><strong>There is no paging today and none is implied.</strong> The whole
     * matching set comes back. That is bounded for a scope of the size this
     * scheme is built for and unbounded in general, and it is reported rather
     * than quietly deferred: introducing paging is a decision about the published
     * contract, which is not this run's to make.
     */
    public record Listing(List<Result> objects) {
    }

    /** Everything one call needs once the first two stages have held. */
    private record Entry(UUID scopeId, String view) {
    }

    /** The two rows a membership address names. */
    private record Membership(UUID iterationId, UUID itemId) {
    }

    /**
     * The address of what a verb answered with, read back from the answer.
     *
     * <p>Read from the projection rather than remembered from the call, because
     * {@code create} has no address to remember: the number is allocated inside
     * the transaction and the answer is the first place it exists.
     */
    private static Result at(String view, Map<String, Object> fields) {
        Object number = fields.get(Field.NUMBER.canonicalName());
        if (!(number instanceof Number allocated)) {
            throw new IllegalStateException(
                "the answer carries no number, so it has no address. Every object of every "
                    + "view is numbered at creation; a projection without one means the "
                    + "allocation and the answer have been changed apart.");
        }
        return new Result(
            new AddressParser.Target(view, allocated.longValue(), null), fields);
    }

    private static Result at(AddressParser.Target target, Map<String, Object> fields) {
        return new Result(target, fields);
    }
}
