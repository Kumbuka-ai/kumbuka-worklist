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
 * The fixed notices an answer carries, such as the warnings of a planning write. A notice may state a finding in the negative; it never repeats the caller's input or the tenant's vocabulary (DEC-0043 R18).
 *
 * <p>The texts live in {@code src/main/resources/messages/notice.properties}; {@code @Message}
 * carries no value, so the build fails when the file lacks a text.
 */
@MessageBundle(value = "notice")
public interface Notices {

    @Message
    String plannedIterationsAtThreshold();

    @Message
    String membershipsAtThreshold();

    @Message
    String blockingCycle();
}
