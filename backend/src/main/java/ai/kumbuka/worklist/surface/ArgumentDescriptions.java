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
 * The description of every declared argument. An optional argument names its default and says that null equals leaving it out (DEC-0043 R17). Phrased positively (R21).
 *
 * <p>The texts live in {@code src/main/resources/messages/arguments.properties}; {@code @Message}
 * carries no value, so the build fails when the file lacks a text.
 */
@MessageBundle(value = "arguments")
public interface ArgumentDescriptions {

    @Message
    String scope();

    @Message
    String selectorCreate();

    @Message
    String selectorQuery();

    @Message
    String selectorIteration();

    @Message
    String selectorItem();

    @Message
    String address();

    @Message
    String addressMembership();

    @Message
    String conflictToken();

    @Message
    String conflictTokenSetting();

    @Message
    String fields(Bound titleBound);

    @Message
    String filter();

    @Message
    String pageSize(Bound max, Bound size);

    @Message
    String after();

    @Message
    String sort();

    @Message
    String direction();

    @Message
    String status();

    @Message
    String produced();

    @Message
    String durationSeconds();

    @Message
    String receipt();

    @Message
    String toItem();

    @Message
    String type();
}
