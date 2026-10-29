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
 * The errors of the protocol this service speaks itself, JSON-RPC under MCP:
 * what was found, then the act the caller performs next, in one text. An
 * error repeats nothing the caller sent (DEC-0043 R19, R20).
 *
 * <p>The texts live in {@code src/main/resources/messages/protocol.properties};
 * {@code @Message} carries no value, so the build fails when the file lacks a
 * text.
 */
@MessageBundle(value = "protocol")
public interface ProtocolMessages {

    @Message
    String envelopeRequired();

    @Message
    String methodUnknown();

    @Message
    String toolUnknown();
}
