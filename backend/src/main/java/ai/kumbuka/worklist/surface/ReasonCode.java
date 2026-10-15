/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

/**
 * A declared reason code, as a placeholder of a text.
 *
 * <p>One of the five placeholder types. It is made from a {@link RefusalCode}
 * and from nothing else, so a text can name a code and never a string that
 * arrived with a call.
 */
public final class ReasonCode {

    private final RefusalCode code;

    private ReasonCode(RefusalCode code) {
        this.code = code;
    }

    public static ReasonCode of(RefusalCode code) {
        return new ReasonCode(code);
    }

    public RefusalCode code() {
        return code;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ReasonCode that && code == that.code;
    }

    @Override
    public int hashCode() {
        return code.hashCode();
    }

    @Override
    public String toString() {
        return code.name();
    }
}
