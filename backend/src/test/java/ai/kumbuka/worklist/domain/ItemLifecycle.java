/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The lifecycle of an item as the probes drive it through the service: create
 * makes a draft (REA-0011 D12), and an item that a probe needs open, in
 * progress or closed is moved there by the transitions, as a caller moves it
 * (REA-0011 D1). Nothing here writes a phase around the service.
 *
 * <p>Each step reads the item's current token, so a probe calls one line per
 * transition. The answers are the service's own; nothing is asserted here.
 */
public final class ItemLifecycle {

    /** The subject every item of these probes is created by. */
    public static final String CALLER = "subject-of-the-caller";

    /** The key of the attribute an item's type is stored under (TAR-0002 B18). */
    public static final String TYPE = "type";

    private ItemLifecycle() {
    }

    /** Creates a draft and answers its id. */
    public static UUID draft(ItemService items, UUID scope, Map<String, ?> fields) {
        return idOf(items.create(scope, CALLER, fields).answer());
    }

    /** Creates a draft and submits it: an open item, as an item read before S9 was. */
    public static UUID open(ItemService items, UUID scope, Map<String, ?> fields) {
        UUID item = draft(items, scope, fields);
        submit(items, scope, item);
        return item;
    }

    /** Creates an open item and closes it into the status named. */
    public static UUID closed(ItemService items, UUID scope, Map<String, ?> fields,
                              String status) {
        UUID item = open(items, scope, fields);
        close(items, scope, item, status);
        return item;
    }

    /**
     * Creates an open item, gives it a type and starts it into the status
     * named: an item in progress. The scope declares the attribute
     * {@link #TYPE} where it lacks it, and the item's status at creation
     * answers {@code actionable}.
     */
    public static UUID inProgress(ItemService items, VocabularyRegistry vocabulary, UUID scope,
                                  Map<String, ?> fields, @Nullable String status) {
        UUID item = open(items, scope, fields);
        typed(items, vocabulary, scope, item);
        start(items, scope, item, status);
        return item;
    }

    /** Submits a draft, with its current token. */
    public static Map<String, Object> submit(ItemService items, UUID scope, UUID item) {
        return items.submit(scope, item, tokenOf(items, scope, item)).answer();
    }

    /** Closes an open item into the status named; from the phase open no reason is asked. */
    public static Map<String, Object> close(ItemService items, UUID scope, UUID item,
                                            @Nullable String status) {
        return items.close(scope, item, tokenOf(items, scope, item), null, status).answer();
    }

    /** Closes an item, with a reason, into the status named. */
    public static Map<String, Object> close(ItemService items, UUID scope, UUID item,
                                            String reason, @Nullable String status) {
        return items.close(scope, item, tokenOf(items, scope, item), reason, status).answer();
    }

    /** Starts an open item into the status named. */
    public static Map<String, Object> start(ItemService items, UUID scope, UUID item,
                                            @Nullable String status) {
        return items.start(scope, item, tokenOf(items, scope, item), status).answer();
    }

    /** Returns an item in progress to the phase open, with a reason. */
    public static Map<String, Object> returnToOpen(ItemService items, UUID scope, UUID item,
                                                   @Nullable String status) {
        return items.return_(scope, item, tokenOf(items, scope, item), "back to the pool",
            status).answer();
    }

    /** Discards a draft, with its current token. */
    public static Map<String, Object> discard(ItemService items, UUID scope, UUID item) {
        return items.discard(scope, item, tokenOf(items, scope, item)).answer();
    }

    /**
     * Gives an item a type: declares the text attribute {@link #TYPE} in the
     * scope where it lacks it, and writes a value under it.
     */
    public static Map<String, Object> typed(ItemService items, VocabularyRegistry vocabulary,
                                            UUID scope, UUID item) {
        vocabulary.declareAttribute(scope, TYPE, "Type", AttributeDefinition.TEXT, 1, false);
        Map<String, Object> read = items.read(scope, item);
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = new LinkedHashMap<>(
            (Map<String, Object>) read.get("attributes"));
        attributes.put(TYPE, "task");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("attributes", attributes);
        fields.put("conflict_token", read.get("conflict_token"));
        return items.update(scope, item, fields, CALLER).answer();
    }

    /** The current conflict token of an item. */
    public static String tokenOf(ItemService items, UUID scope, UUID item) {
        return String.valueOf(items.read(scope, item).get("conflict_token"));
    }

    /** The id an answer carries. */
    public static UUID idOf(Map<String, Object> answer) {
        return (UUID) answer.get("id");
    }
}
