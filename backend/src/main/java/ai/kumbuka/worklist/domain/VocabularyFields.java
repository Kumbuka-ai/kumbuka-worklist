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
import java.util.List;
import java.util.Map;

/**
 * The declared vocabulary of a scope as the administration surface answers
 * it: one mapping per kind of declared value (C7.1), under the names a caller
 * declares it with.
 *
 * <p>A status names its phase beside its predicates (REA-0011 D11): the phase
 * is read off {@code closed} and {@code in_progress} by
 * {@link ItemStatus#phase()}, the same binding the column of V33 stores, and
 * it is answered so that a caller reads which phase a status belongs to
 * without deriving it.
 *
 * <p>The surrogate keys stay out of every map (ADR-0042): a declared value is
 * named by its name, an attribute by its key, as the work surface names them.
 */
public final class VocabularyFields {

    private static final String NAME = "name";
    private static final String RANK = "rank";
    private static final String DESCRIPTION = "description";
    private static final String STATUS = "status";

    private VocabularyFields() {
    }

    /** A status, its phase and its seven predicates. */
    public static Map<String, Object> of(ItemStatus status) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(NAME, status.name);
        fields.put(RANK, status.rank);
        fields.put("phase", status.phase().token());
        fields.put("actionable", status.actionable);
        fields.put("in_progress", status.inProgress);
        fields.put("closed", status.closed);
        fields.put("successful", status.successful);
        fields.put("reviewed", status.reviewed);
        fields.put("approved", status.approved);
        fields.put("verified", status.verified);
        described(fields, status);
        return fields;
    }

    /**
     * An attribute definition with its options, the type of an item among
     * them where the scope declares the attribute {@code type}.
     */
    public static Map<String, Object> of(AttributeDefinition definition,
                                         List<AttributeOption> options) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("key", definition.key);
        fields.put(NAME, definition.name);
        fields.put("type", definition.type);
        fields.put(RANK, definition.rank);
        fields.put("sortable", definition.sortable);
        described(fields, definition);
        fields.put("options", options.stream().map(VocabularyFields::of).toList());
        return fields;
    }

    /** One option of an attribute. */
    public static Map<String, Object> of(AttributeOption option) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(NAME, option.name);
        fields.put(RANK, option.rank);
        described(fields, option);
        return fields;
    }

    /**
     * A relation type, whether it blocks, and the target status of
     * {@code duplicate_of} by its name.
     *
     * @param targetStatus the declared status the type closes into; absent
     *                     where the type names none
     */
    public static Map<String, Object> of(RelationType type, @Nullable ItemStatus targetStatus) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(NAME, type.name);
        fields.put("blocks", type.blocks);
        fields.put(RANK, type.rank);
        if (targetStatus != null) {
            fields.put("target_status", targetStatus.name);
        }
        described(fields, type);
        return fields;
    }

    /** What a scope declared it uses. */
    public static Map<String, Object> of(ScopeUsage.Property property) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(NAME, property.token());
        return fields;
    }

    /** The description where one was given, and whether the value is withdrawn. */
    private static void described(Map<String, Object> fields, DeclaredValue value) {
        if (value.description != null) {
            fields.put(DESCRIPTION, value.description);
        }
        fields.put(STATUS, value.status);
    }
}
