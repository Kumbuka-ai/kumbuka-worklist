/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.WorklistException;

/**
 * The catalogue of every reason this surface refuses with, and the HTTP status
 * each one answers (DEC-0043 R12).
 *
 * <p><strong>A code not in this enum cannot reach a caller.</strong> Each
 * constant has its message and its remedy in the message bundles
 * {@link RefusalMessages} and {@link RefusalRemedies}, under the constant's
 * name in lower camel case, and {@link RefusalCatalogue#requireDeclared} refuses
 * to let the service start when a code lacks either or a bundle names a code
 * this enum lacks. The texts themselves live in
 * {@code src/main/resources/messages/}, never here.
 *
 * <p>The domain's reasons are mapped onto these in {@link #of}, one switch
 * without a default: a reason added to the domain is a compile error here
 * rather than a refusal that escapes as an unexpected failure. The domain's
 * not-found class collapses into {@link #NOT_FOUND} there, once, for both
 * surfaces (DEC-0042).
 */
public enum RefusalCode {

    // --- the call, decided before any object is looked for -------------------

    /** The address violates the grammar. Decidable without a scope: 400. */
    ADDRESS_MALFORMED(400),
    /**
     * A method the route does not take: the framework's own 405, answered in
     * the envelope with {@code Allow} (C3.3, C8.3).
     */
    VERB_NOT_CARRIED(405),
    /**
     * {@code Kumbuka-Surface} with another value than {@code assistant}, or
     * sent twice: decided before anything else, with no data (C14.5).
     */
    HEADER_INVALID(400),
    /** The worklist scheme carries the verb elsewhere, or the address names a view the verb skips. */
    VERB_UNCARRIED(422),
    /** A verb of the scheme this service has yet to build: the fault is ours, 501. */
    VERB_UNBUILT(501),
    /** A write aimed at the workstream view, which is written by declaration. */
    SELECTOR_DECLARED_NOT_WRITTEN(422),
    /** A writing verb on a truncated address. 405 with {@code Allow}. */
    WRITE_ON_TRUNCATED_ADDRESS(405),
    /** A write through {@code iteration/current}. 405 with {@code Allow: GET}. */
    ADDRESS_READ_ONLY(405),
    /** A field write arrived without the conflict token it declares. */
    CONFLICT_TOKEN_MISSING(428),
    /** The body is no JSON object. */
    PAYLOAD_MALFORMED(400),
    /** A value an argument does not admit, decidable without a scope (DEC-0042). */
    ARGUMENT_INVALID(400),
    /** A mandatory argument left out or sent as null (DEC-0043 R17). */
    ARGUMENT_MISSING(400),
    /** A name the call does not declare, at any level (DEC-0043 R4). */
    ARGUMENT_UNKNOWN(400),
    /** A defect of the service (DEC-0043 R13). */
    UNEXPECTED_FAILURE(500),

    // --- the scope ----------------------------------------------------------

    /** Absent, invisible and unroutable alike, with no data (DEC-0042). */
    NOT_FOUND(404),
    /** The session the directory reads under was left unbound: ours, 500. */
    SESSION_NOT_BOUND(500),
    /** A visible scope of a kind this service keeps nothing in. */
    SCOPE_KIND_UNSUPPORTED(422),
    /** A write into a locked scope. */
    SCOPE_LOCKED(409),
    /** A write by a subject that may read the scope and may not write it. */
    SCOPE_READ_ONLY(403),

    // --- the vocabulary and the values of a call ------------------------------

    FIELD_RETRACTED(422),
    FIELD_NOT_SETTABLE(422),
    INVALID_VALUE(422),
    SELECTOR_UNDECLARED(422),
    SELECTOR_WITHDRAWN(422),
    VIEW_UNKNOWN(400),
    VALUE_UNDECLARED(422),
    /** A filter the scope's vocabulary does not declare for the view: 422 after visibility. */
    PREDICATE_UNKNOWN(422),

    // --- paging (ADR-0049) ----------------------------------------------------

    PAGE_SIZE_REJECTED(400),
    CURSOR_MALFORMED(400),
    CURSOR_FOREIGN(400),
    SORT_UNDECLARED(400),

    // --- the state of an object ------------------------------------------------

    /** The caller's copy is stale; it travels as If-Match, so 412. */
    CONFLICT(412),
    IDENTIFIER_UNDECIDED(501),
    MARK_REGRESSION(409),
    MEMBERSHIP_PRESENT(409),
    ITERATION_CLOSED(409),
    ITERATION_INCOMPLETE(409),
    ITERATION_ABSENT(404),
    ITEM_UNPLANNABLE(409),
    SETTING_ABSENT(409),
    SETTING_PRESENT(409),
    CARDINALITY_EXCEEDED(409),
    CLAIM_HELD(409),
    CLAIM_ABSENT(409),
    CLAIM_RECEIPT_UNKNOWN(422),
    DRAW_EMPTY(409),
    WORKSTREAM_WITHDRAWN(409),
    WORKSTREAM_HAS_REFERENCES(409),
    WORKSTREAM_DEFAULT_LOCKED(409),
    WORKSTREAM_MILESTONE_MISMATCH(409),
    ITEM_WORKSTREAM_MISSING(422),
    ITERATION_PRODUCED_MISSING(422);

    private final int status;

    RefusalCode(int status) {
        this.status = status;
    }

    /** The HTTP status this code answers with, fixed at the code. */
    public int status() {
        return status;
    }

    /**
     * The code a domain reason reaches the caller under.
     *
     * <p>No {@code default}: a reason added to the domain must be placed here,
     * and the compiler is what asks for it.
     */
    public static RefusalCode of(WorklistException.Reason reason) {
        return switch (reason) {
            // The not-found class, collapsed once for both surfaces (DEC-0042).
            case SCOPE_UNRESOLVED, ITEM_UNKNOWN, MILESTONE_UNKNOWN, ITERATION_UNKNOWN,
                 MEMBERSHIP_UNKNOWN, WORKSTREAM_UNKNOWN, RELATION_UNKNOWN -> NOT_FOUND;
            case SESSION_NOT_BOUND -> SESSION_NOT_BOUND;
            case SCOPE_KIND_UNSUPPORTED -> SCOPE_KIND_UNSUPPORTED;
            case SCOPE_LOCKED -> SCOPE_LOCKED;
            case SCOPE_READ_ONLY -> SCOPE_READ_ONLY;
            case FIELD_RETRACTED -> FIELD_RETRACTED;
            case FIELD_NOT_SETTABLE -> FIELD_NOT_SETTABLE;
            case INVALID_VALUE -> INVALID_VALUE;
            case CONFLICT -> CONFLICT;
            case SELECTOR_UNDECLARED -> SELECTOR_UNDECLARED;
            case SELECTOR_WITHDRAWN -> SELECTOR_WITHDRAWN;
            case VIEW_UNKNOWN -> VIEW_UNKNOWN;
            case VALUE_UNDECLARED -> VALUE_UNDECLARED;
            case PAGE_SIZE_REJECTED -> PAGE_SIZE_REJECTED;
            case CURSOR_MALFORMED -> CURSOR_MALFORMED;
            case CURSOR_FOREIGN -> CURSOR_FOREIGN;
            case SORT_UNDECLARED -> SORT_UNDECLARED;
            case ARGUMENT_UNKNOWN -> ARGUMENT_UNKNOWN;
            case ARGUMENT_INVALID -> ARGUMENT_INVALID;
            case PREDICATE_UNKNOWN -> PREDICATE_UNKNOWN;
            case IDENTIFIER_UNDECIDED -> IDENTIFIER_UNDECIDED;
            case MARK_REGRESSION -> MARK_REGRESSION;
            case MEMBERSHIP_PRESENT -> MEMBERSHIP_PRESENT;
            case ITERATION_CLOSED -> ITERATION_CLOSED;
            case ITERATION_INCOMPLETE -> ITERATION_INCOMPLETE;
            case ITERATION_ABSENT -> ITERATION_ABSENT;
            case ITEM_UNPLANNABLE -> ITEM_UNPLANNABLE;
            case SETTING_ABSENT -> SETTING_ABSENT;
            case SETTING_PRESENT -> SETTING_PRESENT;
            case CARDINALITY_EXCEEDED -> CARDINALITY_EXCEEDED;
            case CLAIM_HELD -> CLAIM_HELD;
            case CLAIM_ABSENT -> CLAIM_ABSENT;
            case CLAIM_RECEIPT_UNKNOWN -> CLAIM_RECEIPT_UNKNOWN;
            case DRAW_EMPTY -> DRAW_EMPTY;
            case WORKSTREAM_WITHDRAWN -> WORKSTREAM_WITHDRAWN;
            case WORKSTREAM_HAS_REFERENCES -> WORKSTREAM_HAS_REFERENCES;
            case WORKSTREAM_DEFAULT_LOCKED -> WORKSTREAM_DEFAULT_LOCKED;
            case WORKSTREAM_MILESTONE_MISMATCH -> WORKSTREAM_MILESTONE_MISMATCH;
            case ITEM_WORKSTREAM_MISSING -> ITEM_WORKSTREAM_MISSING;
            case ITERATION_PRODUCED_MISSING -> ITERATION_PRODUCED_MISSING;
        };
    }
}
