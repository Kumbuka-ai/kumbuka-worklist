/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.Claim;
import ai.kumbuka.worklist.domain.Item;
import ai.kumbuka.worklist.domain.ItemTag;
import ai.kumbuka.worklist.domain.Iteration;
import ai.kumbuka.worklist.domain.IterationMembership;
import ai.kumbuka.worklist.domain.Milestone;
import ai.kumbuka.worklist.domain.WorklistException;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.UUID;

/**
 * Every log line of the packages that serve a caller, and the only logger
 * they hold (DEC-0051, C4; C13.1, C13.2).
 *
 * <p>A line carries the platform's vocabulary and nothing else: the verb, the
 * technical address, the scope id, the reason code, the status, a count, the
 * reference and the class names of an unforeseen failure. Each line is one
 * method here, with a constant message, and each parameter is a type the
 * service builds from what it holds itself, never from a {@code String}, a
 * number or a uuid a caller could have chosen. So a value a caller wrote --
 * a title, a scope name, a selector, a workstream token, a receipt -- cannot
 * reach a line: there is no parameter that would take it.
 *
 * <p>The domain, the platform and the repository log nothing. A write's lines
 * are written by {@link VerbSurface}, which calls the write and is told by
 * its {@code Written} whether anything was stored; a refusal's line is
 * written where the refusal arrives at the surface.
 *
 * <p>The request id travels through the MDC into the format, and the service
 * through the logger's category; neither is a parameter.
 *
 * <p>{@code LogFacadeAccessTest} holds that no other class of the service
 * holds or calls a logger, {@code LogFacadeTypeTest} that every parameter is
 * one of the platform's types and that none of them can be built from a text.
 */
public final class PlatformLog {

    /*
     * Each message is a literal inside its logger call: constant, written
     * once, and where MessageBundleTest reads a sentence as a note for the
     * log rather than a text for a caller.
     */
    private static final Logger LOG = Logger.getLogger(PlatformLog.class);

    private PlatformLog() {
    }

    // ======================================================================
    // Writes: one INFO line per completed write, by the technical address
    // and the scope id (C13.3), written by the surface once the write has
    // stored something.
    // ======================================================================

    static void created(TechnicalAddress entry, ScopeId scope) {
        LOG.infof("create %s in scope %s", entry, scope);
    }

    static void updated(TechnicalAddress entry, ScopeId scope) {
        LOG.infof("update %s in scope %s", entry, scope);
    }

    static void withdrawn(TechnicalAddress entry, ScopeId scope) {
        LOG.infof("withdraw %s in scope %s", entry, scope);
    }

    static void closed(TechnicalAddress entry, ScopeId scope) {
        LOG.infof("close %s in scope %s", entry, scope);
    }

    static void advanced(TechnicalAddress entry, ScopeId scope) {
        LOG.infof("advance %s in scope %s", entry, scope);
    }

    static void planned(TechnicalAddress entry, ScopeId scope) {
        LOG.infof("plan %s in scope %s", entry, scope);
    }

    static void unplanned(TechnicalAddress entry, ScopeId scope) {
        LOG.infof("unplan %s in scope %s", entry, scope);
    }

    static void claimed(TechnicalAddress entry, ScopeId scope) {
        LOG.infof("claim on %s in scope %s", entry, scope);
    }

    static void released(TechnicalAddress entry, ScopeId scope) {
        LOG.infof("release on %s in scope %s", entry, scope);
    }

    static void drawn(TechnicalAddress entry, ScopeId scope) {
        LOG.infof("claim_next %s in scope %s", entry, scope);
    }

    static void related(TechnicalAddress entry, TechnicalAddress target, ScopeId scope) {
        LOG.infof("relate on %s to %s in scope %s", entry, target, scope);
    }

    static void unrelated(TechnicalAddress entry, TechnicalAddress target, ScopeId scope) {
        LOG.infof("unrelate on %s to %s in scope %s", entry, target, scope);
    }

    static void tagAttached(TechnicalAddress entry, ScopeId scope) {
        LOG.infof("tag_attach on %s in scope %s", entry, scope);
    }

    static void tagDetached(TechnicalAddress entry, ScopeId scope) {
        LOG.infof("tag_detach on %s in scope %s", entry, scope);
    }

    // ======================================================================
    // What a write did, at DEBUG: the lines the domain services wrote before
    // they stopped logging, the demotion of the active milestone and both
    // ends of an edge among them, written by the surface that called them.
    // ======================================================================

    static void itemCreated(TechnicalAddress entry, ScopeId scope) {
        LOG.debugf("item created as %s in scope %s", entry, scope);
    }

    static void itemUpdated(ScopeId scope) {
        LOG.debugf("item updated in scope %s", scope);
    }

    static void itemUnchanged(ScopeId scope) {
        LOG.debugf("update of an item in scope %s changed nothing and wrote nothing", scope);
    }

    static void itemWithdrawn(ScopeId scope) {
        LOG.debugf("item withdrawn in scope %s", scope);
    }

    static void relationAsserted(TechnicalAddress entry, TechnicalAddress target,
                                 ScopeId scope) {
        LOG.debugf("relation asserted from %s to %s in scope %s", entry, target, scope);
    }

    static void relationWithdrawn(TechnicalAddress entry, TechnicalAddress target,
                                  ScopeId scope) {
        LOG.debugf("relation withdrawn from %s to %s in scope %s", entry, target, scope);
    }

    static void iterationCreated(TechnicalAddress iteration, ScopeId scope) {
        LOG.debugf("iteration %s created in scope %s", iteration, scope);
    }

    static void iterationUpdated(TechnicalAddress iteration, ScopeId scope) {
        LOG.debugf("iteration %s updated in scope %s", iteration, scope);
    }

    static void iterationUnchanged(ScopeId scope) {
        LOG.debugf("update of an iteration in scope %s changed nothing and wrote nothing",
            scope);
    }

    static void iterationClosed(TechnicalAddress iteration, ScopeId scope) {
        LOG.debugf("iteration %s closed in scope %s", iteration, scope);
    }

    static void iterationPromoted(TechnicalAddress iteration, ScopeId scope) {
        LOG.debugf("iteration %s promoted to current in scope %s", iteration, scope);
    }

    static void milestoneCreated(TechnicalAddress milestone, ScopeId scope) {
        LOG.debugf("milestone %s created in scope %s", milestone, scope);
    }

    static void milestoneUpdated(TechnicalAddress milestone, ScopeId scope) {
        LOG.debugf("milestone %s updated in scope %s", milestone, scope);
    }

    static void milestoneDemoted(TechnicalAddress milestone, ScopeId scope) {
        LOG.debugf("milestone %s demoted in scope %s", milestone, scope);
    }

    static void milestoneUnchanged(ScopeId scope) {
        LOG.debugf("update of a milestone in scope %s changed nothing and wrote nothing",
            scope);
    }

    static void milestoneClosed(ScopeId scope) {
        LOG.debugf("milestone closed in scope %s", scope);
    }

    static void itemPlanned(TechnicalAddress iteration, ScopeId scope) {
        LOG.debugf("item planned into iteration %s in scope %s", iteration, scope);
    }

    static void itemUnplanned(TechnicalAddress iteration, ScopeId scope) {
        LOG.debugf("item unplanned from iteration %s in scope %s", iteration, scope);
    }

    static void membershipMoved(TechnicalAddress iteration, ScopeId scope) {
        LOG.debugf("membership status moved in iteration %s, scope %s", iteration, scope);
    }

    static void membershipUnchanged(ScopeId scope) {
        LOG.debugf("update of a membership in scope %s changed nothing and wrote nothing",
            scope);
    }

    static void claimTaken(TechnicalAddress entry, ScopeId scope) {
        LOG.debugf("claim taken on item %s in scope %s", entry, scope);
    }

    static void claimReleased(TechnicalAddress entry, ScopeId scope) {
        LOG.debugf("claim released on item %s in scope %s", entry, scope);
    }

    static void claimDrawn(TechnicalAddress entry, ScopeId scope) {
        LOG.debugf("claim drawn and taken on item %s in scope %s", entry, scope);
    }

    static void tagAttachedTo(TechnicalAddress entry, ScopeId scope) {
        LOG.debugf("tag attached to item %s in scope %s", entry, scope);
    }

    static void tagAlreadyOn(ScopeId scope) {
        LOG.debugf("tag already on the item in scope %s; nothing written", scope);
    }

    static void tagDetachedFrom(TechnicalAddress entry, ScopeId scope) {
        LOG.debugf("tag detached from item %s in scope %s", entry, scope);
    }

    static void tagAbsent(ScopeId scope) {
        LOG.debugf("tag absent from the item in scope %s; nothing written", scope);
    }

    // ======================================================================
    // Reads, at DEBUG
    // ======================================================================

    static void scopeResolved(ScopeId scope) {
        LOG.debugf("resolved scope %s", scope);
    }

    static void createRepeated(ScopeId scope) {
        LOG.debugf("create repeated under its key in scope %s", scope);
    }

    static void queried(ScopeId scope, Count hits) {
        LOG.debugf("query in scope %s: %s hit(s)", scope, hits);
    }

    static void validated(ScopeId scope) {
        LOG.debugf("validate over scope %s", scope);
    }

    static void validationReported(Count findings, ScopeId scope) {
        LOG.debugf("validate reported %s finding(s) in scope %s", findings, scope);
    }

    // ======================================================================
    // Refusals: DEBUG by their code, because a refusal is the surface
    // working and a log a caller can fill by sending broken calls is a log a
    // caller writes (C13.3). WARN only for what is a statement about the
    // deployment, written where its refusal arrives at the surface.
    // ======================================================================

    public static void surfaceRefused(Reason code, Status status) {
        LOG.debugf("surface refusal: %s -> %s", code, status);
    }

    public static void domainRefused(Reason code, Status status) {
        LOG.debugf("domain refusal: %s -> %s", code, status);
    }

    public static void assistantSurfaceRefused(Reason code) {
        LOG.debugf("surface refusal: %s", code);
    }

    public static void assistantDomainRefused(Reason code) {
        LOG.debugf("domain refusal: %s", code);
    }

    /**
     * WARN: no scope answered for this subject, with both settings of the
     * session bound. The scope name is the caller's and stays out of the
     * line; the code says which refusal it was.
     */
    public static void scopeUnresolved(Reason code) {
        LOG.warnf("scope unresolved: %s", code);
    }

    /**
     * WARN: a session the read contract could not be bound for is a
     * statement about the deployment, not about the call.
     */
    public static void sessionNotBound(Reason code) {
        LOG.warnf("directory call with unbound session: %s", code);
    }

    /**
     * WARN: a throw site named an argument path the declaration lacks; the
     * spelling stays out of the answer and out of this line (DEC-0043 R18).
     */
    public static void undeclaredPathsLeftOut(Reason code, Count paths) {
        LOG.warnf("refusal %s named %s undeclared path(s); they were left out", code, paths);
    }

    /** That the endpoint was reached, and nothing about who reached it. */
    public static void whoamiReached() {
        LOG.debug("whoami");
    }

    // ======================================================================
    // Start
    // ======================================================================

    static void declarationServed(Count calls, Count reasons) {
        LOG.infof("declaration served: %s calls, %s reasons", calls, reasons);
    }

    static void refusalCatalogueServed(Count codes) {
        LOG.infof("refusal catalogue served: %s codes", codes);
    }

    /**
     * WARN, on a line of its own before the start fails (C13.3): the format
     * prints no exception (C13.1), so the problem is named here by its
     * constant. The exception's own text, which names configuration values,
     * stays out of the line.
     */
    static void startRefused(StartProblem problem) {
        LOG.warnf("start refused: %s", problem);
    }

    // ======================================================================
    // The unforeseen failure (C8.3): ERROR, with the reference the caller is
    // told to report and the class names of the failure's chain.
    // ======================================================================

    static void unexpectedFailure(VerbName call, UnexpectedFailures.FailureReference reference,
                                  UnexpectedFailures.FailureCause cause) {
        LOG.errorf("unexpected failure on %s, reference %s, cause %s", call, reference, cause);
    }

    static void unexpectedFailureBeforeACall(UnexpectedFailures.FailureReference reference,
                                             UnexpectedFailures.FailureCause cause) {
        LOG.errorf("unexpected failure on this request, reference %s, cause %s", reference,
            cause);
    }

    // ======================================================================
    // The types a line takes
    // ======================================================================

    /**
     * An object by the number this service allocated to it in its view: no
     * scope name, no selector, no workstream token, no value its caller
     * wrote. A membership is the two numbers of its iteration and its item.
     * Built from the entity only, and only in this package.
     */
    public static final class TechnicalAddress {

        private final String address;

        private TechnicalAddress(String address) {
            this.address = address;
        }

        static TechnicalAddress of(Item entry) {
            return new TechnicalAddress(String.valueOf(entry.number));
        }

        static TechnicalAddress of(Iteration iteration) {
            return new TechnicalAddress(String.valueOf(iteration.number));
        }

        static TechnicalAddress of(Milestone milestone) {
            return new TechnicalAddress(String.valueOf(milestone.number));
        }

        /** A lease, by the item it is on. */
        static TechnicalAddress of(Claim lease) {
            return new TechnicalAddress(String.valueOf(lease.itemNumber));
        }

        /** A tag assignment, by the item it is on; the tag itself is the platform's. */
        static TechnicalAddress of(ItemTag assignment) {
            return new TechnicalAddress(String.valueOf(assignment.itemNumber));
        }

        static TechnicalAddress of(IterationMembership membership) {
            return new TechnicalAddress(membership.iterationNumber + "/" + membership.itemNumber);
        }

        /** The iteration a membership stands in. */
        static TechnicalAddress iterationOf(IterationMembership membership) {
            return new TechnicalAddress(String.valueOf(membership.iterationNumber));
        }

        @Override
        public String toString() {
            return address;
        }
    }

    /** A scope, by the id the platform's read contract resolved it to. */
    public static final class ScopeId {

        private final UUID scope;

        private ScopeId(UUID scope) {
            this.scope = scope;
        }

        static ScopeId of(UUID resolved) {
            return new ScopeId(resolved);
        }

        @Override
        public String toString() {
            return scope.toString();
        }
    }

    /**
     * The reason code of a refusal, out of one of the service's closed sets:
     * the domain's, or the surface's.
     */
    public static final class Reason {

        private final Enum<?> code;

        private Reason(Enum<?> code) {
            this.code = code;
        }

        public static Reason of(WorklistException.Reason code) {
            return new Reason(code);
        }

        public static Reason of(RefusalCode code) {
            return new Reason(code);
        }

        @Override
        public String toString() {
            return code.name();
        }
    }

    /** The status a refusal is answered with, as its code carries it. */
    public static final class Status {

        private final int code;

        private Status(int code) {
            this.code = code;
        }

        public static Status of(RefusalCode code) {
            return new Status(code.status());
        }

        @Override
        public String toString() {
            return Integer.toString(code);
        }
    }

    /** How many members a collection the service holds has. */
    public static final class Count {

        private final int size;

        private Count(int size) {
            this.size = size;
        }

        public static Count of(Collection<?> members) {
            return new Count(members.size());
        }

        @Override
        public String toString() {
            return Integer.toString(size);
        }
    }

    /**
     * What a start was refused for, without a value: the configuration value
     * that was wrong stays in the exception, out of the line.
     */
    public enum StartProblem {

        /** The principal claim is not pinned to the token's subject. */
        PRINCIPAL_CLAIM_NOT_SUBJECT,

        /** No audience is configured, so none would be validated. */
        AUDIENCE_NOT_SET
    }
}
