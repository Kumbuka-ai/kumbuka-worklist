/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import java.util.List;

/**
 * The head of an address — the {@code item} of
 * {@code worklist://kumbuka/item/562} — and the four values it can take.
 *
 * <h2>The selector is the view, and the views are constants</h2>
 *
 * A token names one of the {@linkplain #VIEWS platform views} onto what a
 * scope holds: its items, its iterations, its milestones, its workstreams. It
 * used to name the family of an item — {@code FEAT}, {@code CHORE},
 * {@code BUG} — and each family was an address space of its own. That is what
 * changed: the families are no longer a number space, and what stands at the
 * head of an address now says which KIND of thing is at the other end of it.
 *
 * <p>The views are platform vocabulary and not a scope's declaration. A scope
 * declares which statuses and attributes its items carry; it does not declare
 * that iterations exist. So the four live here, as constants of the service,
 * and nowhere as a row a scope could hold or lack. What a scope does hold is
 * one {@link NumberSpace} per view, keyed by the view's token: that counter is
 * what an address under the view resolves through, and opening it is what
 * opens the view in a scope ({@link SelectorRegistry#open}).
 *
 * <p>Until the contraction release, the store still carries a row per view
 * and scope in {@code worklist.selector}, written beside every counter so the
 * previous image runs after a rollback. Nothing in this image reads it.
 *
 * <h2>A view is never opened implicitly</h2>
 *
 * Not by the first item that mentions it, not by any verb. That the set is
 * closed makes the rule easier to keep and does not replace it: a scope still
 * has to have its counters before an address under them resolves.
 */
public final class Selector {

    /** The view onto a scope's items. */
    public static final String ITEM = "item";

    /** The view onto its time axis. */
    public static final String ITERATION = "iteration";

    /** The view onto its goal axis. */
    public static final String MILESTONE = "milestone";

    /**
     * The view onto its workstream axis.
     *
     * <p>The fourth view, ratified 2026-09-08. An item carries a workstream
     * as an obligation and a milestone as an option; a milestone carries a
     * workstream too, and the invariant that binds them is enforced in the
     * core — an item with a milestone lies in the same workstream the
     * milestone does.
     */
    public static final String WORKSTREAM = "workstream";

    /**
     * The four views, in the order the address space reads them.
     *
     * <p>A {@code List} and not a {@code Set}, because a refusal that names
     * the admissible values reads better in a fixed order than in whatever
     * order a hash produced — and this list appears in refusal messages a
     * caller has to act on.
     */
    public static final List<String> VIEWS = List.of(ITEM, ITERATION, MILESTONE, WORKSTREAM);

    /**
     * The shape of a token: a leading letter, then alphanumerics and interior
     * hyphens, <strong>lower case throughout</strong>.
     *
     * <p><strong>The case is a decision made here and not a discovery.</strong>
     * ADR-0009 fixes the four address parts and says nothing about the case of
     * the selector; the constraint this pattern mirrors admitted upper case,
     * because the families it was written for were spelled {@code FEAT} and
     * {@code CHORE}. With the selector reduced to a closed set of platform
     * views the question stops being open and is settled the way the rest of
     * the address settles it: the scope is a DNS label and is lower case,
     * upper case is rejected rather than folded, and the selector now reads
     * the same way. Folding would make {@code Item} and {@code item} resolve
     * to one selector, which is an identity statement arrived at by leniency.
     *
     * <p>Here rather than in the registry that uses it, because
     * {@code ck_selector_token} in V6 is the same expression and the two must
     * not drift: a Java check that accepted what the database rejects would
     * turn a refusal into a constraint violation, and the other way round
     * would let a token through that nothing can store.
     *
     * <p>The pattern stays a FORM check even though the admissible set is now
     * a fixed handful of literals. Form and vocabulary are two stages of the
     * ratified check order — a malformed token is decidable without knowing
     * any scope, and which tokens a deployment admits is not — and collapsing
     * them here would put the vocabulary stage in front of the visibility
     * stage for this one value.
     *
     * <p><strong>The quantifiers are possessive, and that is load-bearing.</strong>
     * Written as {@code [a-z0-9]*(-[a-z0-9]+)*} — the obvious form, and the one
     * the constraint carries because PostgreSQL's engine does not backtrack
     * this way — the two nested stars give Java's engine an exponential number
     * of ways to split a long non-matching input, and a token of a few dozen
     * characters is enough to hang the thread. Possessive quantifiers commit to
     * what they consume and never give it back, so a failure is decided in one
     * pass. The accepted language is identical.
     */
    public static final java.util.regex.Pattern TOKEN_PATTERN =
        java.util.regex.Pattern.compile("^[a-z][a-z0-9]*+(?:-[a-z0-9]++)*+$");

    private Selector() {
    }
}
