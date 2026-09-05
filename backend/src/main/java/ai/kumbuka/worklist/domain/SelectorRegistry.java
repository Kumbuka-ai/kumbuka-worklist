package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.repository.PlanningRepository;
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
     * Read for one column: the scope's allocation mode.
     *
     * <p>The mode lives on the settings row rather than here because it is a
     * scope's working style and not a property of an address space. Reading it
     * through the planning repository rather than adding a second reader keeps
     * the settings row with one owner.
     */
    @Inject PlanningRepository planning;

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

        // And the scope-wide counter beside it, if the scope has none yet.
        // Both counters exist at all times, which is what makes the
        // allocation mode a setting rather than a migration.
        if (selectors.scopeWideSpace(scopeId) == null) {
            NumberSpace wide = new NumberSpace();
            wide.selectorId = null;
            wide.scopeId = scopeId;
            wide.highWaterMark = 0L;
            selectors.insert(wide);
        }

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
     * <p><strong>BOTH counters are advanced and one is read.</strong> The
     * scope-wide mark moves with every allocation whatever position the scope
     * is in, so that switching the allocation mode is a read against a counter
     * that was maintained all along rather than a reconstruction from rows
     * that no longer say what was handed out. What is read here is the
     * per-selector position, which is the default; reading the other one is
     * the verb that switches the mode, and that verb does not exist yet.
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
            // A selector without its space is a row that predates the
            // declaration path above, or one written around it. Reported
            // rather than repaired: creating the missing space here would
            // silently accept the second case.
            throw new WorklistException(
                WorklistException.Reason.SELECTOR_UNDECLARED,
                "selector " + selector.token + " has no number space in scope " + scopeId
                    + ". A declared selector always has one; this selector was not "
                    + "declared through the declaring verb",
                List.of(selector.token));
        }

        space.highWaterMark = space.highWaterMark + 1;

        NumberSpace wide = selectors.lockScopeWideSpace(scopeId);
        if (wide == null) {
            // A scope with per-selector counters and no scope-wide one is a
            // scope whose selectors predate this arrangement. Reported rather
            // than repaired: a counter created here would start at zero and
            // hand out numbers this scope has already used.
            throw new WorklistException(
                WorklistException.Reason.SELECTOR_UNDECLARED,
                "scope " + scopeId + " has no scope-wide number space. Every scope that "
                    + "has a selector has one, maintained beside the per-selector "
                    + "counters so that the allocation mode is a setting rather than a "
                    + "migration; a scope missing it was not opened through the "
                    + "declaring verb",
                List.of(selector.token));
        }
        wide.highWaterMark = wide.highWaterMark + 1;

        selectors.flush();

        long allocated = scopeWide(scopeId) ? wide.highWaterMark : space.highWaterMark;
        LOG.debugf("number %d allocated under selector %s in scope %s",
            allocated, selector.token, scopeId);
        return allocated;
    }

    /**
     * Which counter the allocator reads, for one scope.
     *
     * <p>Both are advanced above whatever this answers; only the value handed
     * back differs. That is what makes the mode a setting rather than a
     * migration, and it is why this method is a read of one column rather
     * than a branch around the allocation.
     *
     * <p><strong>A scope with no settings row allocates scope-wide.</strong>
     * The settings row carries cardinality limits V4 deliberately left without
     * defaults, so a scope acquires one when somebody decides what those limits
     * are — which is later than its first item. Falling back to the per-selector
     * position instead would mean a scope numbered one way before that decision
     * and another way after it, with the switch happening as a side effect of an
     * unrelated act. The column's own default is {@code scope_wide} (V6), so the
     * fallback and the stored default say the same thing.
     */
    private boolean scopeWide(UUID scopeId) {
        ScopeSetting setting = planning.settingOf(scopeId);
        return setting == null || ScopeSetting.SCOPE_WIDE.equals(setting.allocationMode);
    }

    /**
     * Carry a high-water mark forward, for an import that arrives with
     * numbers already allocated elsewhere.
     *
     * <p>Forward only. Moving a mark back is not a smaller version of moving
     * it forward — it is the act of handing out numbers that are already in
     * use, which is the one thing the mark exists to prevent. The refusal
     * carries both values so the caller can see by how much it was wrong.
     */
    @Transactional
    public long carryMarkForward(UUID scopeId, String token, long mark) {
        Selector selector = require(scopeId, token);
        NumberSpace space = selectors.lockSpace(selector.id);
        NumberSpace wide = selectors.lockScopeWideSpace(scopeId);

        long standing = standingMark(scopeId, space, wide);
        if (space == null || wide == null || mark < standing) {
            throw new WorklistException(
                WorklistException.Reason.MARK_REGRESSION,
                "the high-water mark of selector " + token + " in scope " + scopeId
                    + " stands at " + standing + " and may not be set to " + mark
                    + ". A mark is carried forward and never back: every number up to "
                    + "the mark has been handed out, and setting it lower hands the same "
                    + "numbers out a second time",
                List.of(token));
        }

        // BOTH marks move, for the same reason the allocator advances both: a
        // mark left behind here is a mark that would be read after a mode
        // switch and would hand out numbers this scope has already used. An
        // import carries a corpus forward, and the corpus is the scope's,
        // whichever counter happens to be answering for it today.
        space.highWaterMark = Math.max(space.highWaterMark, mark);
        wide.highWaterMark = Math.max(wide.highWaterMark, mark);

        selectors.flush();
        LOG.infof("high-water mark of selector %s in scope %s carried to %d",
            token, scopeId, mark);
        return mark;
    }

    /**
     * The current mark: the one the scope's mode reads.
     *
     * <p>A caller asking where a space stands is asking what the next number
     * will be built on, so this answers with the counter the allocator would
     * read. The other one is still maintained and is still exact; it is simply
     * not the answer to this question.
     */
    @Transactional
    public long markOf(UUID scopeId, String token) {
        Selector selector = require(scopeId, token);
        return standingMark(scopeId, selectors.space(selector.id),
            selectors.scopeWideSpace(scopeId));
    }

    /** Whichever of the two counters the scope's allocation mode names. */
    private long standingMark(UUID scopeId, NumberSpace space, NumberSpace wide) {
        NumberSpace read = scopeWide(scopeId) ? wide : space;
        return read == null ? 0L : read.highWaterMark;
    }

    private Selector find(UUID scopeId, String token) {
        return selectors.find(scopeId, token);
    }
}
