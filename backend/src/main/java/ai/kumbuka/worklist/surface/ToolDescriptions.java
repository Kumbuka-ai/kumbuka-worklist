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
 * The description of every tool, one per declared verb (DEC-0043 R3): what the call does, the states it moves between, who calls it and what usually follows. Phrased positively (R21).
 *
 * <p>The texts live in {@code src/main/resources/messages/tools.properties}; {@code @Message}
 * carries no value, so the build fails when the file lacks a text.
 */
@MessageBundle(value = "tools")
public interface ToolDescriptions {

    @Message
    String create();

    @Message
    String read();

    @Message
    String update();

    @Message
    String query();

    @Message
    String accept();

    @Message
    String withdraw();

    @Message
    String close();

    @Message
    String advance();

    @Message
    String plan();

    @Message
    String unplan();

    @Message
    String claim();

    @Message
    String release();

    @Message
    String claimNext();

    @Message
    String relate();

    @Message
    String unrelate();

    @Message
    String validate();
}
