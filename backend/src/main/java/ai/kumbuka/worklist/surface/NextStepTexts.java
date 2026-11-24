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
 * What each call an answer names under {@code next} does, one sentence per
 * call and kind of address (C6.1, C12.2). Phrased positively (DEC-0043 R21).
 *
 * <p>The texts live in {@code src/main/resources/messages/next.properties};
 * {@code @Message} carries no value, so the build fails when the file lacks a
 * text.
 */
@MessageBundle(value = "next")
public interface NextStepTexts {

    @Message
    String read();

    @Message
    String update();

    @Message
    String close();

    @Message
    String advance();

    @Message
    String planItem();

    @Message
    String planIteration();

    @Message
    String unplan();

    @Message
    String relate();

    @Message
    String unrelate();

    @Message
    String tagAttach();

    @Message
    String tagDetach();

    @Message
    String book();

    @Message
    String addendum();

    @Message
    String submit();

    @Message
    String discard();

    @Message
    String start();

    /** What {@code return} does, whose name is a keyword of Java. */
    @Message
    String returnItem();

    /** What {@code close} does on an item. */
    @Message
    String closeItem();
}
