/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.fixture.allowed;

import ai.kumbuka.worklist.domain.WorklistException;
import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * Every shape the logging convention allows, for the guard to pass over.
 *
 * <p>The convention names eight things a log line may carry: an address, a
 * selector, a number, a transition, a status, a typed reason, a duration and a
 * scope id. This class writes one log call per item, using the bare identifier
 * a developer would actually reach for — {@code selector}, not
 * {@code theSelectorName} — because the bare form is the one a substring check
 * gets wrong: {@code selector} contains {@code ctor}.
 *
 * <p>A guard that only ever reports is half a guard. Until something asserts
 * that the permitted shapes come back clean, "no offence found over the main
 * sources" is a statement about a tree that happens not to contain the
 * offending shapes, not about a check that can tell them apart.
 *
 * <p>It lives in the test sources, in its own package, and is wired into
 * nothing. The guard points one test at this directory and requires zero
 * offences, so a forbidden call must never be added here; forbidden calls
 * belong one directory up, in {@code ForbiddenLogFixture}.
 */
public class AllowedLogFixture {

    private static final Logger LOG = Logger.getLogger(AllowedLogFixture.class);

    /** An address. The public name of an item, and not its content. */
    public void logAnAddress(String address) {
        LOG.infof("update %s", address);
    }

    /** A selector. On the permitted list, immediately after the address. */
    public void logASelector(String selector) {
        LOG.debugf("addressed under %s", selector);
    }

    /** A number. A count of things, carrying nothing about any of them. */
    public void logANumber(int number) {
        LOG.debugf("number %d allocated", number);
    }

    /** A transition. Two status names and the arrow between them. */
    public void logATransition(String from, String to) {
        LOG.infof("%s -> %s", from, to);
    }

    /** A status. The scope's word for where the item stands. */
    public void logAStatus(String status) {
        LOG.infof("status now %s", status);
    }

    /** A typed reason, reached through a variable. */
    public void logATypedReason(WorklistException.Reason reason) {
        LOG.debugf("refused: %s", reason);
    }

    /**
     * A typed reason, reached through the constant itself: the one reason of
     * this service whose words include one on the forbidden list. The real enum
     * is imported rather than mirrored, so renaming it breaks this file at
     * compile time instead of leaving a copy that quietly stops describing the
     * service.
     */
    public void logAReceiptReason() {
        LOG.debugf("refused: %s", WorklistException.Reason.CLAIM_RECEIPT_UNKNOWN);
    }

    /** A duration. How long it took, which is about the machine. */
    public void logADuration(long durationMillis) {
        LOG.debugf("took %d ms", durationMillis);
    }

    /** A scope id. An opaque identifier, resolvable only with the directory. */
    public void logAScopeId(UUID scopeId) {
        LOG.debugf("scope %s", scopeId);
    }
}
