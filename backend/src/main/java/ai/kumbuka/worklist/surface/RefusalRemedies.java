/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import io.quarkus.qute.i18n.Message;
import io.quarkus.qute.i18n.MessageBundle;

/**
 * The remedy of every refusal: the act the caller performs next, phrased positively (DEC-0043 R21, linted by {@code PositivePhrasingLintTest}). Method names mirror {@link RefusalMessages}; the wire message is the message followed by the remedy. The errors of the protocol are worded whole in {@link ProtocolMessages}.
 *
 * <p>The texts live in {@code src/main/resources/messages/remedy.properties}; {@code @Message}
 * carries no value, so the build fails when the file lacks a text.
 */
@MessageBundle(value = "remedy")
public interface RefusalRemedies {

    @Message
    String notFound();

    @Message
    String addressMalformed();

    @Message
    String verbNotCarried();

    @Message
    String headerInvalid();

    @Message
    String verbUncarried(VerbName attempted);

    @Message
    String verbUnbuilt(VerbName attempted);

    @Message
    String selectorDeclaredNotWritten();

    @Message
    String writeOnTruncatedAddress();

    @Message
    String addressReadOnly();

    @Message
    String conflictTokenMissing();

    @Message
    String payloadMalformed();

    @Message
    String argumentInvalid();

    @Message
    String argumentMissing();

    @Message
    String argumentUnknown();

    @Message
    String unexpectedFailure();

    @Message
    String sessionNotBound();

    @Message
    String scopeKindUnsupported();

    @Message
    String scopeLocked();

    @Message
    String scopeReadOnly();

    @Message
    String fieldRetracted();

    @Message
    String fieldNotSettable();

    @Message
    String invalidValue();

    @Message
    String conflict();

    @Message
    String selectorUndeclared();

    @Message
    String selectorWithdrawn();

    @Message
    String viewUnknown();

    @Message
    String valueUndeclared();

    @Message
    String pageSizeRejected(Bound bound);

    @Message
    String cursorMalformed();

    @Message
    String cursorForeign();

    @Message
    String sortUndeclared();

    @Message
    String predicateUnknown();

    @Message
    String identifierUndecided();

    @Message
    String markRegression();

    @Message
    String membershipPresent();

    @Message
    String iterationClosed();

    @Message
    String iterationIncomplete();

    @Message
    String iterationAbsent();

    @Message
    String itemUnplannable();

    @Message
    String settingAbsent();

    @Message
    String settingPresent();

    @Message
    String cardinalityExceeded();

    @Message
    String claimHeld();

    @Message
    String claimAbsent();

    @Message
    String claimReceiptUnknown();

    @Message
    String drawEmpty();

    @Message
    String workstreamWithdrawn();

    @Message
    String workstreamHasReferences();

    @Message
    String workstreamDefaultLocked();

    @Message
    String workstreamMilestoneMismatch();

    @Message
    String itemWorkstreamMissing();

    @Message
    String iterationProducedMissing();

    @Message
    String idempotencyKeyReused();
}
