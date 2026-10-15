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
 * The message of every refusal and of every error of the protocol this service words itself: what was found. A message may state a finding in the negative (DEC-0043 R21); it never repeats the caller's input or the tenant's vocabulary (R18). One method per {@link RefusalCode}, named after it in lower camel case, plus the protocol's own errors under the prefix {@code protocol}.
 *
 * <p>The texts live in {@code src/main/resources/messages/refusals.properties}; {@code @Message}
 * carries no value, so the build fails when the file lacks a text.
 */
@MessageBundle(value = "refusals")
public interface RefusalMessages {

    @Message
    String notFound();

    @Message
    String addressMalformed();

    @Message
    String verbUncarried(VerbName attempted);

    @Message
    String verbUnbuilt(VerbName attempted);

    @Message
    String selectorDeclaredNotWritten(VerbName attempted);

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
    String pageSizeRejected();

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
    String protocolEnvelope();

    @Message
    String protocolMethod();
}
