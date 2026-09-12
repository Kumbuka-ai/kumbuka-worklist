package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.repository.SelectorRepository;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * The address spaces of a scope: which selectors exist, and what number comes
 * next in each.
 *
 * <p>Two things live here because they are the same thing seen twice. A
 * selector is the head of an address; a number space is the tail of the same
 * address; and the rule that makes both work — that an address is issued once
 * and never again — is one rule, kept in one place.
 *
 * <p>{@code @TenantBound} at class level, so the database session setting is
 * bound inside every transaction. Both layers of the enforcement model move
 * together or the ORM filter and the policy disagree about who is asking, and
 * the result — an empty set — looks exactly like correct isolation.
 */
@ApplicationScoped
@TenantBound
public class SelectorRegistry {

    /** An address, a scope id, a number. Never content, never an actor. */
    private static final Logger LOG = Logger.getLogger(SelectorRegistry.class);

    @Inject SelectorRepository selectors;

    /**
     * Declare a selector. This is the ONLY way one comes into existence.
     *
     * <p>No other method here or anywhere else inserts into this table, and
     * that is the whole design rather than an implementation choice: a
     * service that creates a selector on first use answers {@code FAET-1}
     * by opening a second address space, and afterwards nothing distinguishes
     * the typo from the intention — both exist, both have items under them.
     *
     * <p>Declaring one that already exists returns the existing one rather
     * than refusing. Declaration is a statement that the space should exist,
     * and it is either true or it was already true; a caller retrying after a
     * timeout should not have to tell those two apart.
     */
    @Transactional
    public Selector declare(UUID scopeId, String token) {
        if (token == null || !Selector.TOKEN_PATTERN.matcher(token).matches()) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "a selector token is a leading lower-case letter followed by lower-case "
                    + "alphanumerics and interior hyphens. Upper case is refused rather "
                    + "than folded, because folding would make two strings resolve to one "
                    + "selector. Refused: " + token,
                List.of(String.valueOf(token)));
        }

        // The token is well formed; whether it names a VIEW is the next
        // question and a different one. Form is decidable without knowing
        // anything about this deployment, and the admissible set is not —
        // which is why the two are two checks and not one pattern.
        if (!Selector.VIEWS.contains(token)) {
            throw new WorklistException(
                WorklistException.Reason.VIEW_UNKNOWN,
                "the selector is the view, and there are three: " + Selector.VIEWS
                    + ". '" + token + "' is none of them. The families an item may belong "
                    + "to are a scope's own declared vocabulary and are no longer address "
                    + "spaces, so declaring one here would open a fourth view — and every "
                    + "address issued under it would name a kind of thing this service "
                    + "does not hold",
                List.of(token));
        }

        Selector existing = find(scopeId, token);
        if (existing != null) {
            return existing;
        }

        Selector selector = new Selector();
        selector.scopeId = scopeId;
        selector.token = token;
        selectors.insert(selector);

        // The address space opens with the selector, at zero. Created here
        // rather than lazily on the first allocation, because a lazily
        // created mark is a mark that two concurrent first allocations both
        // try to create, and the loser sees a constraint violation rather
        // than a number.
        NumberSpace space = new NumberSpace();
        space.selectorId = selector.id;
        space.scopeId = scopeId;
        space.highWaterMark = 0L;
        selectors.insert(space);

        LOG.infof("selector %s declared in scope %s", token, scopeId);
        return selector;
    }

    /**
     * Withdraw a selector: nothing new is accepted under it, and everything
     * already accepted keeps resolving.
     *
     * <p>The token stays occupied. That is the point — a released token could
     * be declared again to mean something else, and every address ever issued
     * under the first meaning would quietly resolve to the second.
     */
    @Transactional
    public Selector withdraw(UUID scopeId, String token) {
        Selector selector = require(scopeId, token);
        if (Selector.WITHDRAWN.equals(selector.status)) {
            return selector;
        }
        selector.status = Selector.WITHDRAWN;
        selectors.flush();
        LOG.infof("selector %s withdrawn in scope %s", token, scopeId);
        return selector;
    }

    /** Every selector of a scope, declared and withdrawn alike, by token. */
    @Transactional
    public List<Selector> inScope(UUID scopeId) {
        return selectors.inScope(scopeId);
    }

    /**
     * The selector of that token, or a typed refusal naming it.
     *
     * <p>The refusal names the token AND says what to do about it, because
     * "no such selector" and "you have to declare it first" are the same fact
     * and only the second one is actionable.
     */
    @Transactional
    public Selector require(UUID scopeId, String token) {
        Selector selector = find(scopeId, token);
        if (selector == null) {
            throw new WorklistException(
                WorklistException.Reason.SELECTOR_UNDECLARED,
                "selector " + token + " is not declared in scope " + scopeId
                    + ". It is not created by using it: an address space that appeared "
                    + "on first use would make a misspelt selector indistinguishable "
                    + "from an intended one. Declare it first",
                List.of(String.valueOf(token)));
        }
        return selector;
    }

    /**
     * The next number in a selector's space, and the mark moved to match.
     *
     * <p>The row is locked for the length of the transaction, so two callers
     * allocating at once are serialised and get different numbers rather than
     * the same one twice. {@code PESSIMISTIC_WRITE} rather than a retry loop
     * because the contended case here is two agents working the same scope,
     * which is normal rather than exceptional.
     *
     * <p>A number allocated by a transaction that then rolls back is BURNT:
     * the mark rolls back with it, so it is handed out again. That is the one
     * place this differs from a sequence, and it is the safe direction —
     * a number is reused only when nothing ever saw it.
     *
     * <p><strong>Each selector reads its own counter.</strong> The three
     * views — item, iteration, milestone — each carry a {@link NumberSpace}
     * row of their own, and the address form carries the view, so
     * {@code .../item/1}, {@code .../iteration/1} and {@code .../milestone/1}
     * are three different addresses. There is no scope-wide counter beside
     * them.
     */
    @Transactional
    public long allocate(UUID scopeId, Selector selector) {
        if (Selector.WITHDRAWN.equals(selector.status)) {
            throw new WorklistException(
                WorklistException.Reason.SELECTOR_WITHDRAWN,
                "selector " + selector.token + " is withdrawn in scope " + scopeId
                    + ", so no further address is issued under it. What was already "
                    + "issued keeps resolving",
                List.of(selector.token));
        }

        NumberSpace space = selectors.lockSpace(selector.id);
        if (space == null) {
            // Lazy init, ratified 2026-09-12 (SPRINT_180.5). The number
            // space is a bookkeeping row keyed by the SELECTOR, and the
            // Vokabularpakt binds only the selector's declaration — not
            // this row. A missing scope-wide space for a resolved selector
            // is a catch-up write, not the second address space the
            // earlier refusal existed against: the selector was resolved
            // by {@code require(scope, token)} on the way in, so we are
            // not admitting a mis-spelt token, we are catching up with a
            // scope opened before the row was seeded (the shape Kumbuka's
            // bootstrap left behind — selectors declared through raw SQL,
            // no scope-wide number_space row for iteration and, for
            // milestone, a per-workstream row that lockSpace ignores).
            //
            // TWO SHAPES ARE HANDLED, one per branch below.
            //
            //   1. A per-workstream row exists (V12 part-2 backfill did
            //      not reach this scope). V12's uq_number_space_selector
            //      is (tenant, scope, selector_id) unconditionally, so a
            //      second row cannot be inserted alongside it. We READ
            //      the stray row and rewrite its workstream_id to null —
            //      the same act V12's UPDATE performed on scopes bootstrapped
            //      before this migration. The high-water mark carries
            //      forward.
            //
            //   2. No row of any shape exists. Insert one at zero.
            //
            // The write is inside this @Transactional, so a rollback
            // takes it with it, and the same PESSIMISTIC_WRITE reasoning
            // holds for the increment below — this catch-up is not the
            // counter's first hand-out, it is the row that lets a first
            // hand-out happen at all.
            NumberSpace stray = selectors.lockAnySpace(selector.id);
            if (stray != null) {
                stray.workstreamId = null;
                selectors.flush();
                space = stray;
                LOG.infof("number space for selector %s in scope %s carried forward "
                    + "from per-workstream to scope-wide (mark %d)",
                    selector.token, scopeId, space.highWaterMark);
            } else {
                space = new NumberSpace();
                space.selectorId = selector.id;
                space.scopeId    = scopeId;
                space.highWaterMark = 0L;
                selectors.insert(space);
                LOG.infof("number space for selector %s opened lazily in scope %s",
                    selector.token, scopeId);
            }
        }

        space.highWaterMark = space.highWaterMark + 1;
        selectors.flush();

        long allocated = space.highWaterMark;
        LOG.debugf("number %d allocated under selector %s in scope %s",
            allocated, selector.token, scopeId);
        return allocated;
    }

    /**
     * The next number in a selector's per-workstream space, and the mark
     * moved to match.
     *
     * <p>Same mechanism as {@link #allocate(UUID, Selector)}, but for a
     * counter that lives per (selector, workstream) — today only the
     * milestone selector does. The lock is on the workstream-specific row,
     * so two workstreams' milestone counters advance independently.
     */
    @Transactional
    public long allocateInWorkstream(UUID scopeId, Selector selector, UUID workstreamId) {
        if (Selector.WITHDRAWN.equals(selector.status)) {
            throw new WorklistException(
                WorklistException.Reason.SELECTOR_WITHDRAWN,
                "selector " + selector.token + " is withdrawn in scope " + scopeId
                    + ", so no further address is issued under it",
                List.of(selector.token));
        }

        NumberSpace space = selectors.lockSpaceInWorkstream(selector.id, workstreamId);
        if (space == null) {
            // A workstream without its milestone counter is a workstream
            // that was not declared through the declaring verb, or one
            // whose scope predates the split. Reported rather than
            // repaired: creating one here would silently accept both.
            throw new WorklistException(
                WorklistException.Reason.SELECTOR_UNDECLARED,
                "selector " + selector.token + " has no number space in scope " + scopeId
                    + " for workstream " + workstreamId + ". A declared workstream carries "
                    + "one; this workstream was not declared through the declaring verb",
                List.of(selector.token, String.valueOf(workstreamId)));
        }

        space.highWaterMark = space.highWaterMark + 1;
        selectors.flush();

        long allocated = space.highWaterMark;
        LOG.debugf("number %d allocated under selector %s in scope %s for workstream %s",
            allocated, selector.token, scopeId, workstreamId);
        return allocated;
    }

    /**
     * Open a workstream-scoped number space for a selector.
     *
     * <p>Called by {@code WorkstreamService.declare} for the milestone
     * selector, so a new workstream opens with a milestone counter of its
     * own at zero.
     */
    @Transactional
    public NumberSpace openSpaceInWorkstream(UUID scopeId, Selector selector, UUID workstreamId) {
        NumberSpace existing = selectors.spaceInWorkstream(selector.id, workstreamId);
        if (existing != null) {
            return existing;
        }
        NumberSpace space = new NumberSpace();
        space.selectorId    = selector.id;
        space.scopeId       = scopeId;
        space.workstreamId  = workstreamId;
        space.highWaterMark = 0L;
        selectors.insert(space);
        return space;
    }

    /**
     * Carry a high-water mark forward, for an import that arrives with
     * numbers already allocated elsewhere.
     *
     * <p>Forward only. Moving a mark back is not a smaller version of moving
     * it forward — it is the act of handing out numbers that are already in
     * use, which is the one thing the mark exists to prevent. The refusal
     * carries both values so the caller can see by how much it was wrong.
     *
     * <p>{@code require} above guarantees the selector was declared, and
     * {@link #declare} opens the {@link NumberSpace} row for it in the same
     * transaction — so the space is present here by invariant. The
     * corresponding null check lives one method up, in {@link #allocate},
     * where every acceptance passes; a broken invariant surfaces there as a
     * typed refusal rather than as a rare-path NPE.
     */
    @Transactional
    public long carryMarkForward(UUID scopeId, String token, long mark) {
        Selector selector = require(scopeId, token);
        NumberSpace space = selectors.lockSpace(selector.id);

        long standing = space.highWaterMark;
        if (mark < standing) {
            throw new WorklistException(
                WorklistException.Reason.MARK_REGRESSION,
                "the high-water mark of selector " + token + " in scope " + scopeId
                    + " stands at " + standing + " and may not be set to " + mark
                    + ". A mark is carried forward and never back: every number up to "
                    + "the mark has been handed out, and setting it lower hands the same "
                    + "numbers out a second time",
                List.of(token));
        }

        space.highWaterMark = mark;

        selectors.flush();
        LOG.infof("high-water mark of selector %s in scope %s carried to %d",
            token, scopeId, mark);
        return mark;
    }

    /**
     * The current mark of a selector: the highest number ever handed out
     * under it.
     *
     * <p>A caller asking where a space stands is asking what the next number
     * will be built on. Each selector has one counter; this reads it.
     *
     * <p>The space is present by the same invariant {@link #carryMarkForward}
     * relies on: a declared selector always has one.
     */
    @Transactional
    public long markOf(UUID scopeId, String token) {
        Selector selector = require(scopeId, token);
        return selectors.space(selector.id).highWaterMark;
    }

    private Selector find(UUID scopeId, String token) {
        return selectors.find(scopeId, token);
    }
}
