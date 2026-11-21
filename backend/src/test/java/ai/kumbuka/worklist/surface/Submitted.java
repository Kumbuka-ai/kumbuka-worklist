/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An item made open on the surface the probe speaks, as a caller makes one:
 * {@code create} makes a draft (REA-0011 D12). Over MCP the draft is submitted
 * in a second act, {@code worklist_submit}; over REST the create carries
 * {@code ?submit=true} and the item is open from the first answer.
 */
public final class Submitted {

    private Submitted() {
    }

    /**
     * Submits the draft an MCP create answered, and answers the submit: the
     * item in the phase open, at the same address.
     */
    public static Map<String, Object> overMcp(Map<String, Object> created) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("address", created.get("address"));
        arguments.put("conflict_token", created.get("conflict_token"));
        return Mcp.answer(Mcp.call("worklist_submit", arguments));
    }

    /** Creates an item over MCP and submits it: the answer of the submit. */
    public static Map<String, Object> createOverMcp(String scope, Map<String, Object> fields) {
        Map<String, Object> created = Mcp.answer(Mcp.call("worklist_create", Map.of(
            "scope", scope, "selector", "item", "fields", fields)));
        return overMcp(created);
    }

    /** The REST create path of a view that submits the item in the same act. */
    public static String restCreate(String collectionPath) {
        return collectionPath + "?submit=true";
    }
}
