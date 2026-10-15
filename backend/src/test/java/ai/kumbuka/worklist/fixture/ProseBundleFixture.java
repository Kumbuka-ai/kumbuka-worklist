/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.fixture;

import io.quarkus.qute.i18n.Message;

/**
 * A bundle-shaped interface that breaks the text conventions on purpose: a
 * {@code @Message} that carries its text, and a placeholder of type
 * {@code String}. {@code MessageBundleTest} and
 * {@code MessagePlaceholderTypeTest} run their checks against it to observe
 * their red state on every build.
 *
 * <p>It carries no {@code @MessageBundle}, so Qute leaves it alone and the
 * service never serves it.
 */
public interface ProseBundleFixture {

    @Message("the scope {name} was refused")
    String refused(String name);
}
