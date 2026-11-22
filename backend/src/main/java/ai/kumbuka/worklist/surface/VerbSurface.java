/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.AddendumService;
import ai.kumbuka.worklist.domain.AddressRegistry;
import ai.kumbuka.worklist.domain.Field;
import ai.kumbuka.worklist.domain.FulfilmentService;
import ai.kumbuka.worklist.domain.IdempotencyService;
import ai.kumbuka.worklist.domain.ItemService;
import ai.kumbuka.worklist.domain.IterationService;
import ai.kumbuka.worklist.domain.MembershipService;
import ai.kumbuka.worklist.domain.Milestone;
import ai.kumbuka.worklist.domain.MilestoneService;
import ai.kumbuka.worklist.domain.MilestoneWrite;
import ai.kumbuka.worklist.domain.OpenCallService;
import ai.kumbuka.worklist.domain.Page;
import ai.kumbuka.worklist.domain.PageRequest;
import ai.kumbuka.worklist.domain.QuerySpec;
import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.TagService;
import ai.kumbuka.worklist.domain.WorkstreamService;
import ai.kumbuka.worklist.domain.Written;
import ai.kumbuka.worklist.platform.ScopeDirectory;
import ai.kumbuka.worklist.platform.ScopeDirectory.Access;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.regex.Pattern;

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
 * <p><strong>Carried</strong> — the verbs of the target cut this scheme has and
 * this service has built. They act. The authoritative list is transcribed in
 * the specification {@code verb-surface.tsv} the conformance probe reads.
 *
 * <h2>Every answer names the calls open to its caller</h2>
 *
 * <p>An answer about an object carries {@code next}: the calls open on it in
 * its state, as {@link OpenCallService} decides them from the same predicates
 * the verbs refuse on, and of those the writing ones only where the platform's
 * read contract lets the caller write into the scope -- the same answer the
 * write path is refused on. So a named call is never refused for the object's
 * state, and a caller who may only read is named the reading calls alone.
 *
 * <p><strong>Uncarried</strong> — {@code send}, {@code append}, {@code digest},
 * {@code abandon}, {@code block}, {@code resume}, {@code consume}. The capability
 * declaration does not give them to this scheme; they answer a typed category
 * error naming the reason, never a not-found and never a silent absence.
 *
 * <p><strong>Unbuilt</strong> — none today. The graph verbs and
 * {@code validate} sat here in an earlier revision; they were built in
 * SPRINT_171.2 and moved up into the carried class. The section is kept in the
 * register so that a verb the scheme carries and this service has not built
 * again gets its own sentence — {@code VERB_UNBUILT} rather than the two
 * refusals a caller could act on incorrectly.
 *
 * <h2>A fourth class the surface answers, keyed on the view rather than on the
 * verb</h2>
 *
 * <p>The workstream view is declared, not written through this surface. A
 * caller aiming {@code create} or {@code update} at it hears a third sentence
 * ({@link RefusalCode#SELECTOR_DECLARED_NOT_WRITTEN}) that says the
 * act exists and runs on the selector-declaration surface, not here. {@code read}
 * and {@code query} on the same view do act — a workstream is addressable like
 * any other declared view — and every other verb refuses on the existing view
 * checks (item-only for the graph and tag verbs and for {@code submit},
 * {@code discard}, {@code start}, {@code return}, {@code book} and
 * {@code addendum}, iteration-only for
 * {@code advance}, iteration, milestone and item for {@code close},
 * iteration-only for the membership addresses).
 */
@ApplicationScoped
@TenantBound
public class VerbSurface {

    /*
     * Every line a verb leaves is written here, through PlatformLog, and only
     * once the domain's Written says the write stored something: a technical
     * address and a scope id, never a title, a motto, a description, a token
     * or the subject. The operator boundary of this service is a missing GRANT,
     * and a log line carrying content walks around it by a different road. The
     * domain logs nothing (DEC-0051, C4).
     */

    /**
     * A string that looks like a uuid.
     *
     * <p>Sprint 180.4 fixes the wire form of the four caller-facing declared
     * values — status, milestone, workstream and relation-type — to names,
     * numbers, tokens and names respectively. A uuid on any of those fields
     * is a caller writing the platform's own identity and is a form refusal;
     * the check runs at the surface for the arguments this class parses
     * itself, before the domain sees them.
     */
    private static final Pattern UUID_SHAPE = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Inject ItemService items;
    @Inject IterationService iterations;
    @Inject MilestoneService milestones;
    @Inject WorkstreamService workstreams;
    @Inject MembershipService memberships;
    @Inject OpenCallService openCalls;
    @Inject TagService tags;
    @Inject FulfilmentService fulfilment;
    @Inject AddendumService addenda;
    @Inject IdempotencyService idempotency;
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
     *
     * <p>Under an idempotency key (C10.4) a repeat with the same view and the
     * same fields answers the object the first call made, read as it stands
     * now, and creates nothing; the key's other repeats are refused in
     * {@link IdempotencyService}. The fields are compared as the domain reads
     * them, with {@code null} absent at every depth (C7.4), so that a repeat
     * that spells an absent field as {@code null} is the same call.
     *
     * @param body           the body the transport carried; absent where it carried
     *                       none, which this surface refuses where the verb needs one
     * @param idempotencyKey the key the transport carried; absent where it carried
     *                       none, and then every call creates
     */
    @Transactional
    public Result create(String subject, String rawScope, String rawView,
                         VerbInput.@Nullable Fields body, @Nullable String idempotencyKey) {
        return create(subject, rawScope, rawView, body, idempotencyKey, false);
    }

    /**
     * {@link #create(String, String, String, VerbInput.Fields, String)}, and
     * on the item view a draft that is submitted in the same act (REA-0011
     * D12): the REST surface's alone, which the MCP adapter never passes. On
     * another view it is a value the call does not take.
     *
     * <p>A key repeated with the same fields and another {@code submit} is
     * another call, and is refused as a spent key.
     *
     * @param submit whether the created draft is submitted in the same act
     */
    @Transactional
    public Result create(String subject, String rawScope, String rawView,
                         VerbInput.@Nullable Fields body, @Nullable String idempotencyKey,
                         boolean submit) {
        Optional<String> key = idempotencyKey(idempotencyKey);
        Entry in = entry(subject, rawScope, rawView, Access.WRITE);
        refuseWriteOnDeclaredView(in.view(), CREATE);
        if (submit && !Selector.ITEM.equals(in.view())) {
            throw SurfaceException.invalid(SurfaceDeclaration.SUBMIT_ARGUMENT);
        }
        addresses.requireView(in.scopeId(), in.view());
        Map<String, Object> fields = required(body, FIELDS).values();
        String digest = CanonicalJson.version(submit
            ? Map.of(SELECTOR, in.view(), FIELDS, fields, SurfaceDeclaration.SUBMIT_ARGUMENT, true)
            : Map.of(SELECTOR, in.view(), FIELDS, fields));

        Optional<Long> first = key.flatMap(given ->
            idempotency.firstAnswer(in.scopeId(), given, CREATE, digest));
        if (first.isPresent()) {
            PlatformLog.createRepeated(in.scope());
            return opened(in, at(in.view(), produced(in, first.get())));
        }

        Map<String, Object> created = switch (in.view()) {
            case Selector.ITEM -> logged(items.create(in.scopeId(), subject, fields, submit),
                PlatformLog.TechnicalAddress::of, address -> {
                    PlatformLog.itemCreated(address, in.scope());
                    PlatformLog.created(address, in.scope());
                    if (submit) {
                        PlatformLog.submitted(address, in.scope());
                    }
                });
            case Selector.ITERATION -> logged(iterations.create(in.scopeId(), fields),
                PlatformLog.TechnicalAddress::of, address -> {
                    PlatformLog.iterationCreated(address, in.scope());
                    PlatformLog.created(address, in.scope());
                });
            case Selector.MILESTONE -> loggedAs(milestones.create(in.scopeId(), fields),
                write -> {
                    PlatformLog.TechnicalAddress address =
                        PlatformLog.TechnicalAddress.of(write.stored());
                    demotionLogged(write, in);
                    PlatformLog.milestoneCreated(address, in.scope());
                    PlatformLog.created(address, in.scope());
                }, () -> { });
            default -> throw unreachableView(in.view());
        };
        key.ifPresent(given -> idempotency.remember(in.scopeId(), given,
            new IdempotencyService.Spent(CREATE, digest, in.view(), numberOf(created))));

        return opened(in, at(in.view(), created));
    }

    /** The number of what a create produced, read off its projection: its address in the view. */
    private static long numberOf(Map<String, Object> created) {
        if (!(created.get(Field.NUMBER.canonicalName()) instanceof Number number)) {
            throw new IllegalStateException(
                "the answer of a create carries no number, so its key has nothing to name");
        }
        return number.longValue();
    }

    /**
     * What a create produced, as it stands now: the answer to its repeat. The
     * ledger has found it standing under the key's lock, so it resolves.
     */
    private Map<String, Object> produced(Entry in, long number) {
        return switch (in.view()) {
            case Selector.ITEM -> items.read(in.scopeId(), addresses.itemAt(in.scopeId(), number));
            case Selector.ITERATION ->
                iterations.read(in.scopeId(), addresses.iterationAt(in.scopeId(), number));
            case Selector.MILESTONE ->
                milestones.read(in.scopeId(), addresses.milestoneAt(in.scopeId(), number));
            default -> throw unreachableView(in.view());
        };
    }

    /**
     * The idempotency key, checked for form before any scope is resolved
     * (C1.3): text of 1 to {@value #KEY_LENGTH_MAX} characters.
     */
    private static Optional<String> idempotencyKey(@Nullable String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        if (raw.isBlank() || raw.length() > KEY_LENGTH_MAX) {
            throw SurfaceException.invalid(IDEMPOTENCY_KEY);
        }
        return Optional.of(raw);
    }

    // ======================================================================
    // query — the other verb on a truncated address
    // ======================================================================

    /**
     * One page of the objects of one view, narrowed by the filters the view
     * declares, in the sort the caller names.
     *
     * <p>The check order continues the one in {@link #entry}: grammar, scope
     * visibility and the view's vocabulary first, then the paging against
     * {@link ai.kumbuka.worklist.domain.QueryFilter} and the view's sorts — a
     * filter name the view does not declare, a value in the wrong form, a page
     * size out of range, a sort the view does not declare, or a cursor this
     * query did not hand out — and only then the store, where the view's service resolves
     * the values the scope must have declared. It runs here and not in either
     * adapter, so both transports refuse the same call the same way and an
     * invisible scope still answers 404 however wrong the narrowing is.
     *
     * <p>Every refusal names the argument; nothing that fails a check is
     * answered with the collection (REQ-0156).
     */
    @Transactional
    public Listing query(String subject, String rawScope, String rawView, QuerySpec spec) {
        Entry in = entry(subject, rawScope, rawView, Access.READ);
        addresses.requireView(in.scopeId(), in.view());
        PageRequest request = spec.pageFor(in.scopeId(), in.view());

        Page answered = switch (in.view()) {
            case Selector.ITEM -> items.query(in.scopeId(), request);
            case Selector.ITERATION -> iterations.query(in.scopeId(), request);
            case Selector.MILESTONE -> milestones.query(in.scopeId(), request);
            case Selector.WORKSTREAM -> workstreams.query(in.scopeId(), request);
            default -> throw unreachableView(in.view());
        };
        PlatformLog.queried(in.scope(), PlatformLog.Count.of(answered.objects()));
        // The open calls of an item depend on the scope's open iterations,
        // which are read once for the page rather than once per entry.
        LongFunction<OpenCallService.Open> itemCalls = Selector.ITEM.equals(in.view())
            ? openCalls.itemsOf(in.scopeId()) : null;
        return new Listing(answered.objects().stream()
            .map(row -> opened(in, at(in.view(), row), itemCalls)).toList(),
            answered.next());
    }

    // ======================================================================
    // read and update — the item form
    // ======================================================================

    @Transactional
    public Result read(String subject, String rawScope, String rawView, String rawId) {
        return read(subject, rawScope, rawView, rawId, null);
    }

    /**
     * Reads one object, an item with as much of its fulfilment ledger as the
     * option asks.
     *
     * <p>The option is checked for form before the scope is resolved (C1.3):
     * one of its two declared values. {@code all} answers the whole ledger
     * of an item in booking order; on another view, which carries no ledger,
     * it is refused as a value outside the form of the call.
     *
     * @param rawFulfilment the option as it arrived; absent where the call
     *                      named none, and then the latest entry per
     *                      criterion and instance is answered
     */
    @Transactional
    public Result read(String subject, String rawScope, String rawView, String rawId,
                       @Nullable String rawFulfilment) {
        ItemService.Ledger ledger = ledger(rawFulfilment);
        Entry in = entry(subject, rawScope, rawView, Access.READ);
        AddressParser.Target target = AddressParser.target(rawView, rawId);
        if (ledger == ItemService.Ledger.ALL && !Selector.ITEM.equals(in.view())) {
            throw SurfaceException.invalid(SurfaceDeclaration.FULFILMENT_ARGUMENT);
        }
        UUID id = resolve(in, target);

        return opened(in, at(target, switch (in.view()) {
            case Selector.ITEM -> items.read(in.scopeId(), id, ledger);
            case Selector.ITERATION -> iterations.read(in.scopeId(), id);
            case Selector.MILESTONE -> milestones.read(in.scopeId(), id);
            case Selector.WORKSTREAM -> workstreams.read(in.scopeId(), id);
            default -> throw unreachableView(in.view());
        }));
    }

    /**
     * The option of {@code read} as the domain takes it, or the refusal of a
     * value outside its declared two. A form fault, decided before the scope.
     *
     * @param raw the option as it arrived; absent where the call named none
     */
    private static ItemService.Ledger ledger(@Nullable String raw) {
        if (raw == null || SurfaceDeclaration.FULFILMENT_LATEST.equals(raw)) {
            return ItemService.Ledger.LATEST;
        }
        if (SurfaceDeclaration.FULFILMENT_ALL.equals(raw)) {
            return ItemService.Ledger.ALL;
        }
        throw SurfaceException.invalid(SurfaceDeclaration.FULFILMENT_ARGUMENT);
    }

    /**
     * Changes what is known about an object.
     *
     * <p>The conflict token arrives in the transport and the domain reads it as
     * a field, so it is written into the field map here — see
     * {@link VerbInput.Fields#withToken}. A write with no token is refused
     * before the domain is entered, because the domain's refusal would be about
     * a stale token and this one is about an absent one.
     *
     * @param conflictToken the token the transport carried; absent where it carried
     *                      none, which this surface refuses as CONFLICT_TOKEN_MISSING
     * @param body          the body the transport carried; absent where it carried
     *                      none, which this surface refuses where the verb needs one
     */
    @Transactional
    public Result update(String subject, String rawScope, String rawView, String rawId,
                         @Nullable String conflictToken, VerbInput.@Nullable Fields body) {
        Entry in = entry(subject, rawScope, rawView, Access.WRITE);
        refuseWriteOnDeclaredView(in.view(), "update");
        AddressParser.Target target = AddressParser.target(rawView, rawId);
        requireWritable(target);
        UUID id = resolve(in, target);

        Map<String, Object> fields = required(body, FIELDS)
            .withToken(Field.CONFLICT_TOKEN.canonicalName(), requireToken(conflictToken))
            .values();

        return opened(in, at(target, switch (in.view()) {
            case Selector.ITEM -> logged(items.update(in.scopeId(), id, fields),
                PlatformLog.TechnicalAddress::of, address -> {
                    PlatformLog.itemUpdated(in.scope());
                    PlatformLog.updated(address, in.scope());
                }, () -> PlatformLog.itemUnchanged(in.scope()));
            case Selector.ITERATION -> logged(iterations.update(in.scopeId(), id, fields),
                PlatformLog.TechnicalAddress::of, address -> {
                    PlatformLog.iterationUpdated(address, in.scope());
                    PlatformLog.updated(address, in.scope());
                }, () -> PlatformLog.iterationUnchanged(in.scope()));
            case Selector.MILESTONE -> loggedAs(milestones.update(in.scopeId(), id, fields),
                write -> {
                    PlatformLog.TechnicalAddress address =
                        PlatformLog.TechnicalAddress.of(write.stored());
                    demotionLogged(write, in);
                    PlatformLog.milestoneUpdated(address, in.scope());
                    PlatformLog.updated(address, in.scope());
                }, () -> PlatformLog.milestoneUnchanged(in.scope()));
            default -> throw unreachableView(in.view());
        }));
    }

    // ======================================================================
    // The transitions this scheme carries
    // ======================================================================

    /**
     * Closes an iteration, a milestone or an item.
     *
     * <p>One verb over three views, chosen by the address. On an item it is
     * the transition into the phase closed (REA-0011 D1), from the phase open
     * or in progress; a workstream is declared and never closed here.
     *
     * @param conflictToken the token the transport carried; absent where it carried
     *                      none, which this surface refuses as CONFLICT_TOKEN_MISSING
     */
    @Transactional
    public Result close(String subject, String rawScope, String rawView, String rawId,
                        @Nullable String conflictToken) {
        return close(subject, rawScope, rawView, rawId, conflictToken, VerbInput.Closing.none());
    }

    /**
     * Same close, with a produced-name for the iteration path.
     *
     * @param produced      the produced-name; absent where the caller sent none
     * @param conflictToken the token the transport carried; absent where it carried
     *                      none, which this surface refuses as CONFLICT_TOKEN_MISSING
     */
    @Transactional
    public Result close(String subject, String rawScope, String rawView, String rawId,
                        @Nullable String conflictToken, @Nullable String produced) {
        return close(subject, rawScope, rawView, rawId, conflictToken,
            new VerbInput.Closing(produced, null, null));
    }

    /**
     * Same close, with every argument a close takes.
     *
     * <p>The naming of what was produced is mandatory on an iteration
     * (ratified 2026-09-08) and is taken on the iteration path alone; the
     * reason and the status are taken on the item path alone, where the
     * reason is mandatory for an item leaving the phase in progress. Each
     * of them on a path that has no place for it is a value the call does
     * not take there, and is refused naming it.
     *
     * @param conflictToken the token the transport carried; absent where it carried
     *                      none, which this surface refuses as CONFLICT_TOKEN_MISSING
     * @param closing       the produced-name, the reason and the status, each
     *                      absent where the caller sent none
     */
    @Transactional
    public Result close(String subject, String rawScope, String rawView, String rawId,
                        @Nullable String conflictToken, VerbInput.Closing closing) {
        Entry in = entry(subject, rawScope, rawView, Access.WRITE);
        AddressParser.Target target = AddressParser.target(rawView, rawId);
        requireWritable(target);
        refuseOutOfPlace(Selector.ITEM.equals(in.view()), closing);
        UUID id = resolve(in, target);
        String token = requireToken(conflictToken);
        String produced = closing.produced();
        String reason = closing.reason();
        String status = closing.status();

        return opened(in, at(target, switch (in.view()) {
            case Selector.ITERATION -> logged(iterations.close(in.scopeId(), id, produced, token),
                PlatformLog.TechnicalAddress::of, address -> {
                    PlatformLog.iterationClosed(address, in.scope());
                    PlatformLog.closed(address, in.scope());
                });
            case Selector.MILESTONE -> loggedAs(milestones.close(in.scopeId(), id, token),
                write -> {
                    PlatformLog.TechnicalAddress address =
                        PlatformLog.TechnicalAddress.of(write.stored());
                    PlatformLog.milestoneUpdated(address, in.scope());
                    PlatformLog.milestoneClosed(in.scope());
                    PlatformLog.closed(address, in.scope());
                }, () -> PlatformLog.milestoneUnchanged(in.scope()));
            case Selector.ITEM -> logged(items.close(in.scopeId(), id, token, reason,
                    optionalLabel(SurfaceDeclaration.STATUS_ARGUMENT, status)),
                PlatformLog.TechnicalAddress::of, address -> PlatformLog.closed(address, in.scope()));
            case Selector.WORKSTREAM -> throw SurfaceException.of(
                RefusalCode.VERB_UNCARRIED, CLOSE);
            default -> throw unreachableView(in.view());
        }));
    }

    // ======================================================================
    // The transitions of an item (REA-0011 D1)
    // ======================================================================

    /**
     * Submits a draft: it moves to the phase open.
     *
     * @param conflictToken the token the transport carried; absent where it carried
     *                      none, which this surface refuses as CONFLICT_TOKEN_MISSING
     */
    @Transactional
    public Result submit(String subject, String rawScope, String rawView, String rawId,
                         @Nullable String conflictToken) {
        Entry in = entry(subject, rawScope, rawView, Access.WRITE);
        AddressParser.Target target = requireView(rawView, rawId, Selector.ITEM, SUBMIT);
        UUID id = resolve(in, target);

        return opened(in, at(target, logged(items.submit(in.scopeId(), id,
                requireToken(conflictToken)),
            PlatformLog.TechnicalAddress::of, address -> PlatformLog.submitted(address, in.scope()))));
    }

    /**
     * Discards a draft: it is deleted. The answer is the draft as it stood,
     * at the address that now names nothing, and names no call: there is no
     * object left to call on.
     *
     * @param conflictToken the token the transport carried; absent where it carried
     *                      none, which this surface refuses as CONFLICT_TOKEN_MISSING
     */
    @Transactional
    public Result discard(String subject, String rawScope, String rawView, String rawId,
                          @Nullable String conflictToken) {
        Entry in = entry(subject, rawScope, rawView, Access.WRITE);
        AddressParser.Target target = requireView(rawView, rawId, Selector.ITEM, DISCARD);
        UUID id = resolve(in, target);

        return at(target, logged(items.discard(in.scopeId(), id, requireToken(conflictToken)),
            PlatformLog.TechnicalAddress::of, address -> PlatformLog.discarded(address, in.scope())));
    }

    /**
     * Starts an open item: it moves to the phase in progress.
     *
     * @param conflictToken the token the transport carried; absent where it carried
     *                      none, which this surface refuses as CONFLICT_TOKEN_MISSING
     * @param status        the status the item enters; absent where the caller
     *                      named none
     */
    @Transactional
    public Result start(String subject, String rawScope, String rawView, String rawId,
                        @Nullable String conflictToken, @Nullable String status) {
        Entry in = entry(subject, rawScope, rawView, Access.WRITE);
        AddressParser.Target target = requireView(rawView, rawId, Selector.ITEM, START);
        UUID id = resolve(in, target);

        return opened(in, at(target, logged(items.start(in.scopeId(), id,
                requireToken(conflictToken),
                optionalLabel(SurfaceDeclaration.STATUS_ARGUMENT, status)),
            PlatformLog.TechnicalAddress::of, address -> PlatformLog.started(address, in.scope()))));
    }

    /**
     * Returns an item in progress to the phase open, with its mandatory
     * reason. Spelled {@code return_}: the verb's name is a keyword of Java.
     *
     * @param conflictToken the token the transport carried; absent where it carried
     *                      none, which this surface refuses as CONFLICT_TOKEN_MISSING
     * @param reason        the reason the transport carried; absent where it
     *                      carried none, which this surface refuses as
     *                      ARGUMENT_MISSING
     * @param status        the status the item enters; absent where the caller
     *                      named none
     */
    @Transactional
    public Result return_(String subject, String rawScope, String rawView, String rawId,
                          @Nullable String conflictToken, @Nullable String reason,
                          @Nullable String status) {
        Entry in = entry(subject, rawScope, rawView, Access.WRITE);
        AddressParser.Target target = requireView(rawView, rawId, Selector.ITEM, RETURN);
        UUID id = resolve(in, target);
        String token = requireToken(conflictToken);
        String why = requireText(SurfaceDeclaration.REASON_ARGUMENT, reason);

        return opened(in, at(target, logged(items.return_(in.scopeId(), id, token, why,
                optionalLabel(SurfaceDeclaration.STATUS_ARGUMENT, status)),
            PlatformLog.TechnicalAddress::of, address -> PlatformLog.returned(address, in.scope()))));
    }

    /**
     * Refuses an argument of {@code close} on a path that has no place for
     * it: what was produced on an item, a reason or a status on an iteration
     * or a milestone. A value the call does not take there, decided before
     * the address is resolved.
     *
     * @param item whether the close is addressed at an item
     */
    private static void refuseOutOfPlace(boolean item, VerbInput.Closing closing) {
        if (item && closing.produced() != null) {
            throw SurfaceException.invalid(PRODUCED);
        }
        if (!item && closing.reason() != null) {
            throw SurfaceException.invalid(SurfaceDeclaration.REASON_ARGUMENT);
        }
        if (!item && closing.status() != null) {
            throw SurfaceException.invalid(SurfaceDeclaration.STATUS_ARGUMENT);
        }
    }

    /**
     * An optional text argument that names a declared value, refused by its
     * declared name where it is empty or carries the platform's identity;
     * absent where the call left it out.
     */
    private static @Nullable String optionalLabel(String name, @Nullable String raw) {
        return raw == null ? null : requireLabel(name, raw);
    }

    /**
     * Promotes the first planned iteration to current.
     *
     * <p>A write on a truncated address, admissible because the verb contract
     * declares set semantics and the only declarable one is exactly one. It
     * promotes the first open iteration in the
     * scope's own order — not one the caller names, which is why there is no
     * address for it to take.
     *
     * <p>The token it presents is the SETTINGS row's, not an iteration's. The
     * pointer being moved lives there, and a token of the iteration would guard
     * the wrong row: two callers advancing at once would both hold a valid
     * iteration token and both move the pointer.
     *
     * @param conflictToken the token the transport carried; absent where it carried
     *                      none, which this surface refuses as CONFLICT_TOKEN_MISSING
     */
    @Transactional
    public Result advance(String subject, String rawScope, String rawView,
                          @Nullable String conflictToken) {
        Entry in = entry(subject, rawScope, rawView, Access.WRITE);
        if (!Selector.ITERATION.equals(in.view())) {
            throw SurfaceException.of(RefusalCode.VERB_UNCARRIED, "advance");
        }

        Map<String, Object> setting = logged(iterations.advance(in.scopeId(),
                requireToken(conflictToken)),
            PlatformLog.TechnicalAddress::of, address -> {
                PlatformLog.iterationPromoted(address, in.scope());
                PlatformLog.advanced(address, in.scope());
            });

        // The answer is the SETTINGS row, because that is what the verb wrote:
        // it moved a pointer. Dressing it as the promoted iteration would be
        // this class inventing an answer the domain did not give, and the two
        // would disagree the first time the domain returned a warning. Its
        // address is the pointer, and the one call it names is the read of
        // the iteration the pointer now names; the writes go to that
        // iteration's number, which the read answers.
        AddressParser.Target pointer = new AddressParser.Target(Selector.ITERATION, null, null);
        return new Result(pointer, setting,
            List.of(new NextStep(declaredVerb(READ), Selector.ITERATION, pointer,
                NextStepTexts::read)), null);
    }

    // ======================================================================
    // The membership: addressed under its iteration, never as a view
    // ======================================================================

    @Transactional
    public Result readMembership(String subject, String rawScope, String rawView,
                                 String rawIteration, String rawItem) {
        Entry in = entry(subject, rawScope, rawView, Access.READ);
        AddressParser.Target target = AddressParser.membership(rawView, rawIteration, rawItem);
        Membership at = membership(in, target);

        return opened(in, at(target, memberships.read(in.scopeId(), at.iterationId(),
            at.itemId())));
    }

    /**
     * Adds an item to an iteration, at the end of its order.
     *
     * @param rawItem       the item segment of the membership address; absent where
     *                      the address carried none, which this surface refuses
     * @param conflictToken the token the transport carried; absent where it carried
     *                      none, which this surface refuses as CONFLICT_TOKEN_MISSING
     */
    @Transactional
    public Result plan(String subject, String rawScope, String rawView, String rawIteration,
                       @Nullable String rawItem, @Nullable String conflictToken) {
        Entry in = entry(subject, rawScope, rawView, Access.WRITE);
        AddressParser.Target target = AddressParser.membership(rawView, rawIteration, rawItem);
        Membership at = membership(in, target);

        return opened(in, at(target, loggedAs(memberships.plan(in.scopeId(), at.iterationId(),
                at.itemId(), requireToken(conflictToken)),
            membership -> {
                PlatformLog.itemPlanned(PlatformLog.TechnicalAddress.iterationOf(membership),
                    in.scope());
                PlatformLog.planned(PlatformLog.TechnicalAddress.of(membership), in.scope());
            }, () -> { })));
    }

    /**
     * Removes it again.
     *
     * @param rawItem       the item segment of the membership address; absent where
     *                      the address carried none, which this surface refuses
     * @param conflictToken the token the transport carried; absent where it carried
     *                      none, which this surface refuses as CONFLICT_TOKEN_MISSING
     */
    @Transactional
    public Result unplan(String subject, String rawScope, String rawView, String rawIteration,
                         @Nullable String rawItem, @Nullable String conflictToken) {
        Entry in = entry(subject, rawScope, rawView, Access.WRITE);
        AddressParser.Target target = AddressParser.membership(rawView, rawIteration, rawItem);
        Membership at = membership(in, target);

        return opened(in, at(target, loggedAs(memberships.unplan(in.scopeId(),
                at.iterationId(), at.itemId(), requireToken(conflictToken)),
            membership -> {
                PlatformLog.itemUnplanned(PlatformLog.TechnicalAddress.iterationOf(membership),
                    in.scope());
                PlatformLog.unplanned(PlatformLog.TechnicalAddress.of(membership), in.scope());
            }, () -> { })));
    }

    /**
     * Changes a membership — its status in this iteration, above all.
     *
     * @param conflictToken the token the transport carried; absent where it carried
     *                      none, which this surface refuses as CONFLICT_TOKEN_MISSING
     * @param body          the body the transport carried; absent where it carried
     *                      none, which this surface refuses where the verb needs one
     */
    @Transactional
    public Result updateMembership(String subject, String rawScope, String rawView,
                                   String rawIteration, String rawItem, @Nullable String conflictToken,
                                   VerbInput.@Nullable Fields body) {
        Entry in = entry(subject, rawScope, rawView, Access.WRITE);
        AddressParser.Target target = AddressParser.membership(rawView, rawIteration, rawItem);
        Membership at = membership(in, target);

        Map<String, Object> fields = required(body, FIELDS)
            .withToken(Field.CONFLICT_TOKEN.canonicalName(), requireToken(conflictToken))
            .values();

        return opened(in, at(target, loggedAs(memberships.update(in.scopeId(),
                at.iterationId(), at.itemId(), fields),
            membership -> {
                PlatformLog.membershipMoved(PlatformLog.TechnicalAddress.iterationOf(membership),
                    in.scope());
                PlatformLog.updated(PlatformLog.TechnicalAddress.of(membership), in.scope());
            }, () -> PlatformLog.membershipUnchanged(in.scope()))));
    }

    // ======================================================================
    // The graph verbs
    // ======================================================================

    /**
     * Assert one directed, typed edge from this item to another.
     *
     * <p>The source is the addressed item; the target and the type arrive in
     * the body. Item depth is provisional — the ratified form is a
     * sub-collection under the item — and this surface has no sub-collection
     * yet.
     *
     * <p>Idempotent under the triple: reasserting an edge that already exists
     * writes nothing, exactly as the whole-set rewrite in {@code update}
     * treats an unchanged entry.
     *
     * @param conflictToken the token the transport carried; absent where it carried
     *                      none, which this surface refuses as CONFLICT_TOKEN_MISSING
     * @param body          the body the transport carried; absent where it carried
     *                      none, which this surface refuses where the verb needs one
     */
    @Transactional
    public Result relate(String subject, String rawScope, String rawView, String rawId,
                         @Nullable String conflictToken, VerbInput.@Nullable Edge body) {
        Entry in = entry(subject, rawScope, rawView, Access.WRITE);
        AddressParser.Target target = requireView(rawView, rawId, Selector.ITEM, "relate");
        UUID from = resolve(in, target);

        VerbInput.Edge edge = required(body, TO_ITEM, TYPE);
        String toAddress = requireLabel(TO_ITEM, edge.toItem());
        String typeName = requireLabel(TYPE, edge.type());

        return opened(in, at(target, loggedAs(items.relate(in.scopeId(), from, toAddress,
                typeName, requireToken(conflictToken)),
            write -> {
                PlatformLog.TechnicalAddress source = PlatformLog.TechnicalAddress.of(write.stored());
                PlatformLog.TechnicalAddress to = PlatformLog.TechnicalAddress.of(write.target());
                PlatformLog.relationAsserted(source, to, in.scope());
                PlatformLog.related(source, to, in.scope());
            }, () -> { })));
    }

    /**
     * Withdraw one asserted edge from this item to another.
     *
     * @param conflictToken the token the transport carried; absent where it carried
     *                      none, which this surface refuses as CONFLICT_TOKEN_MISSING
     * @param body          the body the transport carried; absent where it carried
     *                      none, which this surface refuses where the verb needs one
     */
    @Transactional
    public Result unrelate(String subject, String rawScope, String rawView, String rawId,
                           @Nullable String conflictToken, VerbInput.@Nullable Edge body) {
        Entry in = entry(subject, rawScope, rawView, Access.WRITE);
        AddressParser.Target target = requireView(rawView, rawId, Selector.ITEM, "unrelate");
        UUID from = resolve(in, target);

        VerbInput.Edge edge = required(body, TO_ITEM, TYPE);
        String toAddress = requireLabel(TO_ITEM, edge.toItem());
        String typeName = requireLabel(TYPE, edge.type());

        return opened(in, at(target, loggedAs(items.unrelate(in.scopeId(), from, toAddress,
                typeName, requireToken(conflictToken)),
            write -> {
                PlatformLog.TechnicalAddress source = PlatformLog.TechnicalAddress.of(write.stored());
                PlatformLog.TechnicalAddress to = PlatformLog.TechnicalAddress.of(write.target());
                PlatformLog.relationWithdrawn(source, to, in.scope());
                PlatformLog.unrelated(source, to, in.scope());
            }, () -> { })));
    }

    // ======================================================================
    // The tags
    // ======================================================================

    /**
     * Attach one tag of the scope's vocabulary to an item.
     *
     * <p>A write, so the scope is resolved for writing and a subject without
     * the write right meets {@code SCOPE_READ_ONLY}. No conflict token: the
     * assignment is a relation, and the item's token and timestamp stay as
     * they are. Only an item carries tags; an iteration, a milestone or a
     * workstream address is refused as {@code TAG_ITEM_ONLY} before anything
     * is looked up.
     *
     * @param rawTag the token the transport carried; absent where it carried
     *               none, which this surface refuses as ARGUMENT_MISSING
     */
    @Transactional
    public Result tagAttach(String subject, String rawScope, String rawView, String rawId,
                            @Nullable String rawTag) {
        Entry in = entry(subject, rawScope, rawView, Access.WRITE);
        AddressParser.Target target = requireItem(rawView, rawId);
        UUID id = resolve(in, target);

        return opened(in, at(target, logged(tags.tagAttach(in.scopeId(), id,
                requireText(TAG, rawTag), subject),
            PlatformLog.TechnicalAddress::of, address -> {
                PlatformLog.tagAttachedTo(address, in.scope());
                PlatformLog.tagAttached(address, in.scope());
            }, () -> PlatformLog.tagAlreadyOn(in.scope()))));
    }

    /**
     * Detach one tag from an item; a withdrawn tag detaches like any other.
     *
     * @param rawTag the token the transport carried; absent where it carried
     *               none, which this surface refuses as ARGUMENT_MISSING
     */
    @Transactional
    public Result tagDetach(String subject, String rawScope, String rawView, String rawId,
                            @Nullable String rawTag) {
        Entry in = entry(subject, rawScope, rawView, Access.WRITE);
        AddressParser.Target target = requireItem(rawView, rawId);
        UUID id = resolve(in, target);

        return opened(in, at(target, logged(tags.tagDetach(in.scopeId(), id,
                requireText(TAG, rawTag)),
            PlatformLog.TechnicalAddress::of, address -> {
                PlatformLog.tagDetachedFrom(address, in.scope());
                PlatformLog.tagDetached(address, in.scope());
            }, () -> PlatformLog.tagAbsent(in.scope()))));
    }

    /**
     * The address of a tag verb, which only an item answers to. The other
     * three views are refused with the sentence that says so, rather than as
     * a verb this address skips: the verb is carried, and the object is one
     * that carries no tags.
     */
    private static AddressParser.Target requireItem(String rawView, String rawId) {
        AddressParser.Target target = AddressParser.target(rawView, rawId);
        if (!Selector.ITEM.equals(target.view())) {
            throw new SurfaceException(RefusalCode.TAG_ITEM_ONLY);
        }
        return target;
    }

    // ======================================================================
    // The fulfilment ledger
    // ======================================================================

    /**
     * Book what one instance reported against the acceptance criteria of an
     * item.
     *
     * <p>A write, so the scope is resolved for writing and a subject without
     * the write right meets {@code SCOPE_READ_ONLY}; every subject with it
     * may book, on an item that is open, in progress or closed, and a draft
     * is refused by state. No conflict
     * token: the ledger stands beside the item, and the item's token and
     * timestamp stay as they are. The actor of every entry is the subject
     * this surface derived from the token. Only an item carries a ledger; an
     * address of another view is refused as a verb that view does not carry.
     *
     * <p>The instance and the entries are handed to the domain as they
     * arrived; what they say is checked there, in one act, before anything is
     * written.
     *
     * @param body the arguments the transport carried; absent where it
     *             carried none, which this surface refuses as ARGUMENT_MISSING
     */
    @Transactional
    public Result book(String subject, String rawScope, String rawView, String rawId,
                       VerbInput.@Nullable Booking body) {
        Entry in = entry(subject, rawScope, rawView, Access.WRITE);
        AddressParser.Target target = requireView(rawView, rawId, Selector.ITEM, BOOK);
        UUID id = resolve(in, target);

        VerbInput.Booking booking = required(body, INSTANCE, ENTRIES);
        if (booking.instance() == null && booking.entries() == null) {
            throw SurfaceException.missing(INSTANCE, ENTRIES);
        }
        if (booking.instance() == null) {
            throw SurfaceException.missing(INSTANCE);
        }
        if (booking.entries() == null) {
            throw SurfaceException.missing(ENTRIES);
        }

        return opened(in, at(target, loggedAs(fulfilment.book(in.scopeId(), id,
                booking.instance(), booking.entries(), subject),
            entries -> {
                PlatformLog.TechnicalAddress address =
                    PlatformLog.TechnicalAddress.of(entries.get(0));
                PlatformLog.fulfilmentBooked(address, PlatformLog.Count.of(entries), in.scope());
                PlatformLog.booked(address, in.scope());
            }, () -> { })));
    }

    // ======================================================================
    // The addenda
    // ======================================================================

    /**
     * Write one addendum on an item: an append-only text beside its frozen
     * content (REA-0011 D1, D13).
     *
     * <p>A write, so the scope is resolved for writing and a subject without
     * the write right meets {@code SCOPE_READ_ONLY}; until permissions per
     * transition arrive the write right of the scope is what guards it
     * (REA-0011 section 4). An item in the phase open or in progress takes
     * one, and a draft or a closed item is refused by state. No conflict
     * token, as the tag calls: the addendum stands beside the item, and the
     * item's token and change time stay as they are. The author is the
     * subject this surface derived from the token. Only an item takes an
     * addendum; an address of another view is refused as a verb that view
     * does not carry.
     *
     * @param rawText the text the transport carried; absent where it carried
     *                none, which this surface refuses as ARGUMENT_MISSING
     */
    @Transactional
    public Result addendum(String subject, String rawScope, String rawView, String rawId,
                           @Nullable String rawText) {
        Entry in = entry(subject, rawScope, rawView, Access.WRITE);
        AddressParser.Target target = requireView(rawView, rawId, Selector.ITEM, ADDENDUM);
        UUID id = resolve(in, target);
        String text = requireText(SurfaceDeclaration.ADDENDUM_TEXT, rawText);

        return opened(in, at(target, logged(addenda.addendum(in.scopeId(), id, text, subject),
            PlatformLog.TechnicalAddress::of, address -> {
                PlatformLog.addendumWritten(address, in.scope());
                PlatformLog.addended(address, in.scope());
            })));
    }

    // ======================================================================
    // The validation check
    // ======================================================================

    /**
     * Walk the scope and report every consistency it currently holds and every
     * one it does not.
     *
     * <p>Mutates nothing — the concept fixes that in one line — and reports
     * findings in the answer rather than as a refusal. A caller reading the
     * report is reading a snapshot of the store's own graph; a caller finding
     * an empty findings list is reading a scope the platform's checks accept.
     *
     * <p>Addressed at the ITEM collection because the concept fixes the target
     * as the scope, and the item view is the nearest truncation this surface
     * offers. The other two views are refused as category errors: iterations
     * and milestones do not carry the platform's cross-item consistencies that
     * validate is here to walk.
     */
    @Transactional
    public Result validate(String subject, String rawScope, String rawView) {
        Entry in = entry(subject, rawScope, rawView, Access.READ);
        if (!Selector.ITEM.equals(in.view())) {
            throw SurfaceException.of(RefusalCode.VERB_UNCARRIED, "validate");
        }
        addresses.requireView(in.scopeId(), in.view());

        Map<String, Object> answered = items.validate(in.scopeId());
        if (answered.get(FINDINGS) instanceof Collection<?> findings) {
            PlatformLog.validationReported(PlatformLog.Count.of(findings), in.scope());
        }
        PlatformLog.validated(in.scope());
        // A report about the scope and no object of it: no call stands open
        // on it, and it waits for nobody.
        return new Result(new AddressParser.Target(Selector.ITEM, null, null), answered);
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
     * @param at the id segment of the address, absent where the address names a
     *           collection; checked first, so that a caller who got the address
     *           wrong is told about the address first
     */
    @Transactional
    public void uncarried(String subject, String rawScope, String rawView, @Nullable String at,
                          String verb) {
        entry(subject, rawScope, rawView, Access.READ);
        if (at != null) {
            AddressParser.target(rawView, at);
        }
        throw SurfaceException.of(RefusalCode.VERB_UNCARRIED, verb);
    }

    /**
     * A verb this scheme carries and this service has not built.
     *
     * <p>Answered apart from the one above and it matters which a caller gets.
     * "Not carried" says the act does not exist here and never will, and a caller
     * that believes it stops asking. This one says the act belongs here and is
     * not ready, which is a different thing to plan around.
     *
     * @param at            the id segment of the address; absent where the address
     *                      names a collection
     */
    @Transactional
    public void unbuilt(String subject, String rawScope, String rawView, @Nullable String at,
                        String verb) {
        entry(subject, rawScope, rawView, Access.READ);
        if (at != null) {
            AddressParser.target(rawView, at);
        }
        throw SurfaceException.of(RefusalCode.VERB_UNBUILT, verb);
    }

    /**
     * Stage 1 and stage 2 alone: the scope parses and this subject sees it.
     *
     * <p>Called by an adapter before it refuses an argument the call does not
     * declare. That refusal is decidable from the verb alone, and DEC-0042
     * still answers {@code NOT_FOUND} for a scope the caller cannot see
     * "whatever else is wrong with the call": an unknown argument must not
     * tell a stranger that the scope exists.
     */
    @Transactional
    public void requireVisible(String subject, String rawScope) {
        scopes.resolve(subject, AddressParser.scope(rawScope), Access.READ);
    }

    // ======================================================================
    // Stage 1 and stage 2
    // ======================================================================

    /**
     * Grammar, then scope visibility, then what the scope admits.
     *
     * <p>The grammar runs against the raw strings before anything is resolved.
     * Stage 1 is decidable without knowing a scope, so its refusal leaks nothing;
     * every later refusal necessarily reveals that a lookup happened.
     *
     * <p>Nothing here touches the store. That is what makes the order observable:
     * a probe can call this against a scope it may not see and get 404 with
     * whatever else is wrong with the call, because nothing that would answer
     * differently has run yet.
     *
     * <p><strong>Every verb passes through here, and that is why the access mode
     * is an argument.</strong> The scope's kind, its lock and the subject's
     * write right are the read contract's answers and the directory judges
     * them; what the directory cannot know is whether the act about to run is a
     * read or a write. Declaring it at each call site — rather than deriving it
     * from a list of verb names kept somewhere — is what keeps a verb added
     * later from defaulting silently into the permissive case: the parameter
     * has no default, so a new verb does not compile until somebody says which
     * it is.
     */
    private Entry entry(String subject, String rawScope, String rawView, Access access) {
        String slug = AddressParser.scope(rawScope);
        String view = AddressParser.view(rawView);
        ScopeDirectory.ScopeAccess resolved = scopes.resolve(subject, slug, access);
        Entry in = new Entry(resolved.scopeId(), view, resolved.writable());
        PlatformLog.scopeResolved(in.scope());
        return in;
    }

    // ======================================================================
    // The calls an answer names as open
    // ======================================================================

    /**
     * The answer with the calls open on its object, and the object's state.
     *
     * <p>The open calls are {@link OpenCallService}'s, decided by the same
     * predicates the verbs refuse on; the writing ones stand only where this
     * caller may write into the scope, by the read contract's answer that
     * {@link #entry} holds -- the one a write is refused on. Every call is
     * named at the object's numbered address, never through the pointer
     * {@code current}, which takes no write.
     */
    private Result opened(Entry in, Result answered) {
        return opened(in, answered, null);
    }

    /**
     * @param items the open calls of the items of this scope, read once for a
     *              page; absent where the answer is not about items, and then
     *              asked of the service for the one object
     */
    private Result opened(Entry in, Result answered,
                          @Nullable LongFunction<OpenCallService.Open> items) {
        AddressParser.Target target = answered.address();
        OpenCallService.Open open;
        if (target.isMembership()) {
            open = openCalls.forMembership(in.scopeId(), Objects.requireNonNull(target.number()),
                Objects.requireNonNull(target.member()));
        } else {
            long number = numberOf(answered.fields());
            open = switch (target.view()) {
                case Selector.ITEM -> items != null ? items.apply(number)
                    : openCalls.forItem(in.scopeId(), number);
                case Selector.ITERATION -> openCalls.forIteration(in.scopeId(), number);
                case Selector.MILESTONE -> openCalls.forMilestone(in.scopeId(), number);
                case Selector.WORKSTREAM -> openCalls.forWorkstream(in.scopeId(), number);
                default -> throw unreachableView(target.view());
            };
        }
        List<NextStep> next = open.calls().stream()
            .filter(call -> !call.verb().writes() || in.writable())
            .map(VerbSurface::step)
            .toList();
        return new Result(target, answered.fields(), next, open.state());
    }

    /** One open call as the surface names it: its declared verb, its address, its text. */
    private static NextStep step(OpenCallService.Call call) {
        VerbName verb = declaredVerb(call.verb().name().toLowerCase(Locale.ROOT));
        AddressParser.Target at = call.number() == null ? null
            : new AddressParser.Target(call.view(), call.number(), call.member());
        Function<NextStepTexts, String> does = switch (call.verb()) {
            case READ -> NextStepTexts::read;
            case UPDATE -> NextStepTexts::update;
            case CLOSE -> Selector.ITEM.equals(call.view()) ? NextStepTexts::closeItem
                : NextStepTexts::close;
            case ADVANCE -> NextStepTexts::advance;
            case PLAN -> call.member() == null ? NextStepTexts::planIteration
                : NextStepTexts::planItem;
            case UNPLAN -> NextStepTexts::unplan;
            case RELATE -> NextStepTexts::relate;
            case UNRELATE -> NextStepTexts::unrelate;
            case TAG_ATTACH -> NextStepTexts::tagAttach;
            case TAG_DETACH -> NextStepTexts::tagDetach;
            case BOOK -> NextStepTexts::book;
            case ADDENDUM -> NextStepTexts::addendum;
            case SUBMIT -> NextStepTexts::submit;
            case DISCARD -> NextStepTexts::discard;
            case START -> NextStepTexts::start;
            case RETURN -> NextStepTexts::returnItem;
        };
        return new NextStep(verb, call.view(), at, does);
    }

    // ======================================================================
    // The lines a write leaves
    // ======================================================================

    /**
     * The answer of a write, after its lines: written exactly when the write
     * stored something (C13.3), by the technical address of what it stored.
     * Written here and not in the domain, which logs nothing (DEC-0051, C4).
     */
    private static <T, E> T logged(Written<T, E> written,
                                   Function<E, PlatformLog.TechnicalAddress> address,
                                   Consumer<PlatformLog.TechnicalAddress> lines) {
        return logged(written, address, lines, () -> { });
    }

    /**
     * {@link #logged(Written, Function, Consumer)}, and the line a call that
     * wrote nothing leaves instead.
     */
    private static <T, E> T logged(Written<T, E> written,
                                   Function<E, PlatformLog.TechnicalAddress> address,
                                   Consumer<PlatformLog.TechnicalAddress> lines,
                                   Runnable unchanged) {
        return loggedAs(written, stored -> lines.accept(address.apply(stored)), unchanged);
    }

    /**
     * The answer of a write, with its lines handed what it stored: for a
     * write whose lines name more than one address of it, as a membership's
     * name its iteration and itself.
     */
    private static <T, E> T loggedAs(Written<T, E> written, Consumer<E> lines,
                                     Runnable unchanged) {
        E stored = written.stored();
        if (stored == null) {
            unchanged.run();
        } else {
            lines.accept(stored);
        }
        return written.answer();
    }

    /**
     * The line of the demotion a milestone write stored, exactly where it
     * demoted the active milestone: by the demoted milestone's number.
     */
    private static void demotionLogged(MilestoneWrite write, Entry in) {
        Milestone demoted = write.demoted();
        if (demoted != null) {
            PlatformLog.milestoneDemoted(PlatformLog.TechnicalAddress.of(demoted), in.scope());
        }
    }

    /**
     * The verb the declaration holds under this name: the declared instance,
     * never one made from the text, as {@code valueOf} answers a constant of
     * an enum.
     *
     * @throws IllegalArgumentException for a name the declaration does not hold
     */
    public static VerbName declaredVerb(String name) {
        return SurfaceDeclaration.verbName(name)
            .orElseThrow(() -> new IllegalArgumentException("an undeclared verb name"));
    }

    // ======================================================================
    // Stage 3 and stage 4, through the domain
    // ======================================================================

    /** The row one address names, with the vocabulary checked on the way. */
    private UUID resolve(Entry in, AddressParser.Target target) {
        Long number = target.number();
        if (number == null) {
            // The moving pointer: what Target.isCurrent() says, read here off
            // the component itself so the compiler sees the number is present.
            return addresses.currentIteration(in.scopeId());
        }
        return switch (target.view()) {
            case Selector.ITEM -> addresses.itemAt(in.scopeId(), number);
            case Selector.ITERATION -> addresses.iterationAt(in.scopeId(), number);
            case Selector.MILESTONE -> addresses.milestoneAt(in.scopeId(), number);
            case Selector.WORKSTREAM -> addresses.workstreamAt(in.scopeId(), number);
            default -> throw unreachableView(target.view());
        };
    }

    /**
     * Both rows a membership address names, resolved in the order it is written.
     * {@link AddressParser#membership} parses both numbers or refuses, so a
     * membership target carries both.
     */
    private Membership membership(Entry in, AddressParser.Target target) {
        return new Membership(
            addresses.iterationAt(in.scopeId(), Objects.requireNonNull(target.number())),
            addresses.itemAt(in.scopeId(), Objects.requireNonNull(target.member())));
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
            throw new SurfaceException(RefusalCode.ADDRESS_READ_ONLY,
                "GET");
        }
    }

    /**
     * A writing verb of the verb surface arrived on a view that is declared,
     * not written through this surface.
     *
     * <p>The one such view today is {@code workstream}. Refused here rather
     * than in the domain and BEFORE any resolution or vocabulary check, so
     * that the answer says which surface the act belongs on rather than
     * cascading into a not-found or a permission error from the store.
     */
    private static void refuseWriteOnDeclaredView(String view, String verb) {
        if (!Selector.WORKSTREAM.equals(view)) {
            return;
        }
        throw SurfaceException.of(RefusalCode.SELECTOR_DECLARED_NOT_WRITTEN, verb);
    }

    /** A verb that only one view carries, addressed at another. */
    private static AddressParser.Target requireView(String rawView, String rawId,
                                                    String expected, String verb) {
        AddressParser.Target target = AddressParser.target(rawView, rawId);
        if (!expected.equals(target.view())) {
            throw SurfaceException.of(RefusalCode.VERB_UNCARRIED, verb);
        }
        requireWritable(target);
        return target;
    }

    private static String requireToken(@Nullable String presented) {
        if (presented == null || presented.isBlank()) {
            throw new SurfaceException(RefusalCode.CONFLICT_TOKEN_MISSING);
        }
        return presented;
    }


    /**
     * The body a verb needs, refused by the names of its mandatory arguments
     * when nothing arrived (DEC-0043 R17: an absent body and a body of nulls
     * are the same call).
     */
    private static <T> T required(@Nullable T body, String... arguments) {
        if (body == null) {
            throw SurfaceException.missing(arguments);
        }
        return body;
    }

    /**
     * A mandatory text argument that names a declared value or an address,
     * refused by its declared name and never by what arrived.
     */
    private static String requireLabel(String name, @Nullable String raw) {
        String candidate = requireText(name, raw);
        if (UUID_SHAPE.matcher(candidate).matches()) {
            throw SurfaceException.invalid(name);
        }
        return candidate;
    }

    /** A mandatory text argument, refused by its declared name when absent or empty. */
    private static String requireText(String name, @Nullable String raw) {
        if (raw == null) {
            throw SurfaceException.missing(name);
        }
        String candidate = raw.strip();
        if (candidate.isEmpty()) {
            throw SurfaceException.invalid(name);
        }
        return candidate;
    }

    /** The argument names this class refuses by. */
    private static final String FIELDS = "fields";
    private static final String TO_ITEM = "to_item";
    private static final String TYPE = "type";
    private static final String TAG = "tag";
    private static final String BOOK = "book";
    private static final String ADDENDUM = "addendum";
    private static final String INSTANCE = SurfaceDeclaration.BOOK_INSTANCE;
    private static final String ENTRIES = SurfaceDeclaration.BOOK_ENTRIES;
    private static final String CLOSE = "close";
    private static final String SUBMIT = "submit";
    private static final String DISCARD = "discard";
    private static final String START = "start";
    private static final String RETURN = "return";
    private static final String PRODUCED = "produced";
    private static final String READ = "read";
    private static final String CREATE = "create";
    private static final String SELECTOR = "selector";
    private static final String IDEMPOTENCY_KEY = "idempotency_key";

    /** The member of a validation's answer that lists its findings. */
    private static final String FINDINGS = "findings";

    /** The longest idempotency key taken, in characters. */
    private static final int KEY_LENGTH_MAX = 255;

    /**
     * The switch arm that cannot be reached.
     *
     * <p>Every switch over the view above has one, because the grammar admits
     * a closed set of view tokens and a Java switch over a String does not know
     * that. It throws rather than returning something, so that a view added to
     * the grammar without an arm here is a loud failure and not a silent
     * branch. The count is not stated here: it lives on {@link Selector#VIEWS}
     * and only there, so that adding one does not silently invalidate a comment
     * this far from it.
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

    /**
     * The answer to any verb: what the domain projected, at the address it
     * is, with the calls open to the caller on it (C6.1).
     *
     * @param next  the calls open to this caller on the object in its state;
     *              empty for an answer about no single object
     * @param state the object's state as its read answers it, which a refusal
     *              by state names (C8.7); absent for an answer about no
     *              single object
     */
    public record Result(AddressParser.Target address, Map<String, Object> fields,
                         List<NextStep> next, @Nullable String state) {

        /** An answer before its open calls are known, or about no single object. */
        Result(AddressParser.Target address, Map<String, Object> fields) {
            this(address, fields, List.of(), null);
        }

        /**
         * The conflict token the answer carries, where it carries one.
         *
         * <p>Read off the projection here so that an adapter dressing this in a
         * transport header does not have to know the domain's field names. The
         * token is a field because the round trip needs it there — the map a
         * caller read is the map they send back — and it is also a header
         * because that is where HTTP looks for it. One value, two places it is
         * legible from, and this method is the single place that knows both.
         */
        public Optional<String> conflictToken() {
            return Optional.ofNullable(fields.get(Field.CONFLICT_TOKEN.canonicalName()))
                .map(String::valueOf);
        }
    }

    /**
     * What a listing answers with.
     *
     * <p>An object around the list rather than the bare array, so that anything
     * a listing later needs to say about itself is an added key rather than a
     * changed shape. A bare array cannot grow a sibling field, and this
     * surface is a published contract from the day it answers.
     *
     * <p>{@code next} is the cursor to the rest, absent exactly when nothing
     * follows. A silent ceiling is the sprint-169 defect one layer up — an
     * answer that reads complete and is not.
     *
     * @param next the cursor to the rest; absent exactly when nothing follows
     */
    public record Listing(List<Result> objects, @Nullable String next) {
    }

    /**
     * One call open to the caller, as an answer names it under {@code next}:
     * the declared verb, the address it is made at, and the text of what it
     * does. The adapter words it in the names of the caller's surface (C6.2).
     *
     * @param target the address the call is made at; absent where it is made
     *               at the view's collection, which only {@code advance} is
     */
    public record NextStep(VerbName verb, String view, AddressParser.@Nullable Target target,
                           Function<NextStepTexts, String> does) {

        /** The complete address the call is made at, or the view's collection. */
        public String address(String scope) {
            return target == null ? AddressParser.renderCollection(scope, view)
                : AddressParser.render(scope, target);
        }

        /**
         * The call as the caller's surface names it: the verb, a space, the
         * address.
         *
         * @param toolNames whether the caller came through the assistant
         *                  surface, which names the tool ({@code worklist_read});
         *                  REST names the verb ({@code read})
         */
        public String call(String scope, boolean toolNames) {
            return (toolNames ? verb.toolName() : verb.name()) + " " + address(scope);
        }
    }

    /**
     * Everything one call needs once the first two stages have held.
     *
     * @param writable whether the caller may write into the scope, by the
     *                 read contract's answer the write path is refused on
     */
    private record Entry(UUID scopeId, String view, boolean writable) {

        /** The scope as a log line takes it. */
        PlatformLog.ScopeId scope() {
            return PlatformLog.ScopeId.of(scopeId);
        }
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
