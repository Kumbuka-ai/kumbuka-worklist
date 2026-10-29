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
 * The description of every value of a fixed set an argument takes, such as the views and the values of sort and direction. Phrased positively (R21).
 *
 * <p>The texts live in {@code src/main/resources/messages/value.properties}; {@code @Message}
 * carries no value, so the build fails when the file lacks a text.
 */
@MessageBundle(value = "value")
public interface ValueDescriptions {

    @Message
    String viewItem();

    @Message
    String viewIteration();

    @Message
    String viewMilestone();

    @Message
    String viewWorkstream();

    @Message
    String sortId();

    @Message
    String sortCreatedAt();

    @Message
    String sortRank();

    @Message
    String directionAsc();

    @Message
    String directionDesc();
}
