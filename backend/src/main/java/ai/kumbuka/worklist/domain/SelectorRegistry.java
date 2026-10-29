/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
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
 * The address spaces of a scope: which views are open in it, and what number
 * comes next in each.
 *
 * <p>Two things live here because they are the same thing seen twice. A view
 * is the head of an address; a number space is the tail of the same address;
 * and the rule that makes both work — that an address is issued once and
 * never again — is one rule, kept in one place.
 *
 * <h2>The views are constants, the counters are the scope's</h2>
 *
 * Which views exist is {@link Selector#VIEWS} and nothing else. What a scope
 * holds is one {@link NumberSpace} per view it has opened, keyed by the view
 * (V24), and that counter is the whole answer to both questions asked here:
 * a view is open in a scope when its counter is there, and its next number is
 * the counter plus one. No answer is read off a selector row.
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

    /** The argument a refusal about the addressed view names. */
    private static final String SELECTOR_ARGUMENT = "selector";

    @Inject SelectorRepository selectors;

    /**
     * Open a view in a scope. This is the ONLY way a view comes to resolve
     * there.
     *
     * <p>No other method here or anywhere else opens a counter, and that is
     * the whole design rather than an implementation choice: a service that
     * opens an address space on first use answers a misspelt view by opening
     * a second one, and afterwards nothing distinguishes the typo from the
     * intention.
     *
     * <p>Opening one that is already open changes nothing. Opening is a
     * statement that the space should exist, and it is either true or it was
     * already true; a caller retrying after a timeout should not have to tell
     * those two apart. The mark of an open view is never reset by it.
     *
     * <p>The counter opens at zero, here rather than lazily on the first
     * allocation, because a lazily created mark is a mark that two concurrent
     * first allocations both try to create.
     */
    @Transactional
    public void open(UUID scopeId, String token) {
        if (token == null || !Selector.TOKEN_PATTERN.matcher(token).matches()) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                List.of(SELECTOR_ARGUMENT));
        }

        // The token is well formed; whether it names a VIEW is the next
        // question and a different one. Form is decidable without knowing
        // anything about this deployment, and the admissible set is not —
        // which is why the two are two checks and not one pattern.
        if (!Selector.VIEWS.contains(token)) {
            throw new WorklistException(
                WorklistException.Reason.VIEW_UNKNOWN,
                List.of(SELECTOR_ARGUMENT));
        }

        selectors.open(scopeId, token);
        LOG.debugf("view %s open in scope %s", token, scopeId);
    }

    /** The views open in a scope, by token. */
    @Transactional
    public List<String> inScope(UUID scopeId) {
        return selectors.views(scopeId);
    }

    /**
     * Nothing, when the view is open in the scope; a typed refusal naming the
     * argument otherwise.
     *
     * <p>A token that is none of the four views is refused the same way, and
     * without asking the store: it cannot be open anywhere.
     */
    @Transactional
    public void require(UUID scopeId, String view) {
        if (!Selector.VIEWS.contains(view) || selectors.space(scopeId, view).isEmpty()) {
            throw undeclared();
        }
    }

    /**
     * The next number in a view's space, and the mark moved to match.
     *
     * <p>The row is locked for the length of the transaction, so two callers
     * allocating at once are serialised and get different numbers rather than
     * the same one twice. {@code PESSIMISTIC_WRITE} rather than a retry loop
     * because the contended case here is two agents working the same scope,
     * which is normal rather than exceptional.
     *
     * <p>A number allocated by a transaction that then rolls back is NOT
     * burnt: the mark rolls back with it, so it is handed out again. That is
     * the one place this differs from a sequence, and it is the safe direction
     * — a number is reused only when nothing ever saw it.
     *
     * <p><strong>Each view reads its own counter.</strong> The address form
     * carries the view, so {@code .../item/1}, {@code .../iteration/1} and
     * {@code .../milestone/1} are three different addresses. There is no
     * scope-wide counter beside them.
     */
    @Transactional
    public long allocate(UUID scopeId, String view) {
        NumberSpace space = lock(scopeId, view);

        space.highWaterMark = space.highWaterMark + 1;
        selectors.flush();

        long allocated = space.highWaterMark;
        LOG.debugf("number %d allocated under view %s in scope %s",
            allocated, view, scopeId);
        return allocated;
    }

    /**
     * Carry a high-water mark forward, for an import that arrives with
     * numbers already allocated elsewhere.
     *
     * <p>Forward only. Moving a mark back is not a smaller version of moving
     * it forward — it is the act of handing out numbers that are already in
     * use, which is the one thing the mark exists to prevent.
     */
    @Transactional
    public long carryMarkForward(UUID scopeId, String view, long mark) {
        NumberSpace space = lock(scopeId, view);

        long standing = space.highWaterMark;
        if (mark < standing) {
            throw new WorklistException(
                WorklistException.Reason.MARK_REGRESSION,
                List.of());
        }

        space.highWaterMark = mark;

        selectors.flush();
        LOG.infof("high-water mark of view %s in scope %s carried to %d",
            view, scopeId, mark);
        return mark;
    }

    /**
     * The current mark of a view: the highest number ever handed out under
     * it.
     *
     * <p>A caller asking where a space stands is asking what the next number
     * will be built on. Each view has one counter; this reads it.
     */
    @Transactional
    public long markOf(UUID scopeId, String view) {
        if (!Selector.VIEWS.contains(view)) {
            throw undeclared();
        }
        return selectors.space(scopeId, view)
            .orElseThrow(SelectorRegistry::undeclared).highWaterMark;
    }

    /** The view's counter under lock, or the refusal for a view not open in the scope. */
    private NumberSpace lock(UUID scopeId, String view) {
        if (!Selector.VIEWS.contains(view)) {
            throw undeclared();
        }
        return selectors.lockSpace(scopeId, view).orElseThrow(SelectorRegistry::undeclared);
    }

    /**
     * The refusal for a view that is not open in the scope.
     *
     * <p>Named SELECTOR_UNDECLARED, and it says what to do about it, because
     * "no such view here" and "the scope has to be opened first" are the same
     * fact and only the second one is actionable.
     */
    private static WorklistException undeclared() {
        return new WorklistException(
            WorklistException.Reason.SELECTOR_UNDECLARED,
            List.of(SELECTOR_ARGUMENT));
    }
}
