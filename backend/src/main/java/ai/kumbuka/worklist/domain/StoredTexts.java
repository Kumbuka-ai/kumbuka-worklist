/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import io.quarkus.qute.i18n.Message;
import io.quarkus.qute.i18n.MessageBundle;

/**
 * The texts the service writes into the store on its own account, which a
 * caller later reads back as part of an object.
 *
 * <p>Today one: the description the default workstream is created with. It
 * lives here, in the domain, because the domain writes it; it lives in a
 * message bundle, because no text a caller reads lives in Java code. The words
 * are in {@code src/main/resources/messages/stored.properties}.
 */
@MessageBundle(value = "stored")
public interface StoredTexts {

    @Message
    String defaultWorkstreamDescription();
}
