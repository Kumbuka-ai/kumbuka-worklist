/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.rest;

import ai.kumbuka.worklist.surface.VerbName;
import jakarta.enterprise.context.RequestScoped;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * The call a REST request became, for the refusal mapper.
 *
 * <p>A refusal reaches {@link RefusalMapper} as an exception and nothing else;
 * which verb it refuses, and in which surface's names, is recorded here by
 * {@link WorklistResource} as soon as the route has named the verb, and read
 * back by the mapper for {@code attempted} (C6.2, C8.7). A request that never
 * became a call -- a header refused before the route, a path under a reserved
 * name, a colon that names no verb -- leaves it empty, and its refusal carries
 * no {@code attempted}.
 */
@RequestScoped
public class CallContext {

    /** The call, in the names of the caller's surface; absent until the route named a verb. */
    private @Nullable String attempted;

    /**
     * Records the verb the route named.
     *
     * @param assistant whether the request named the assistant surface, whose
     *                  names are the tool names (C14.4)
     */
    void call(String verb, boolean assistant) {
        VerbName name = VerbName.declared(verb);
        this.attempted = assistant ? name.toolName() : name.name();
    }

    /** The call as the caller's surface names it; empty where the request became none. */
    Optional<String> attempted() {
        return Optional.ofNullable(attempted);
    }
}
