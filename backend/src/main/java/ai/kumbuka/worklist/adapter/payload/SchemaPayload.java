/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A JSON Schema, of the kind a tool's input takes: an object closed at its
 * level with its properties, and for a property its type, its description and,
 * where the value is of a closed set, the set with a description per value.
 * The member names are JSON Schema's.
 *
 * @param description absent on the outer object of a call
 * @param properties the properties of an object, by name, in declaration order;
 *                   absent on a schema of no object
 * @param required absent on a schema of no object
 * @param additionalProperties absent on a schema of no object
 * @param values absent where the value is not of a closed set
 * @param oneOf absent where the value is not of a closed set
 * @param minimum absent where the value is no bounded number
 * @param maximum absent where the value is no bounded number
 */
public record SchemaPayload(
    @JsonProperty("type") String type,
    @JsonProperty("description") @Nullable String description,
    @JsonProperty("properties") @Nullable Map<String, SchemaPayload> properties,
    @JsonProperty("required") @Nullable List<String> required,
    @JsonProperty("additionalProperties") @Nullable Boolean additionalProperties,
    @JsonProperty("enum") @Nullable List<String> values,
    @JsonProperty("oneOf") @Nullable List<SchemaValuePayload> oneOf,
    @JsonProperty("minimum") @Nullable Integer minimum,
    @JsonProperty("maximum") @Nullable Integer maximum) {

    /** An object closed at its level. */
    public static SchemaPayload object(Map<String, SchemaPayload> properties,
                                       List<String> required) {
        return new SchemaPayload("object", null, properties, required, false, null, null, null,
            null);
    }

    /**
     * A property.
     *
     * @param values the closed set; empty where the value is of none, and then
     *               neither {@code enum} nor {@code oneOf} is written
     * @param minimum the least number, absent where there is none
     * @param maximum the greatest number, absent where there is none
     */
    public static SchemaPayload property(String type, String description,
                                         List<SchemaValuePayload> values,
                                         @Nullable Integer minimum, @Nullable Integer maximum) {
        return values.isEmpty()
            ? new SchemaPayload(type, description, null, null, null, null, null, minimum, maximum)
            : new SchemaPayload(type, description, null, null, null,
                values.stream().map(SchemaValuePayload::value).toList(), values, minimum, maximum);
    }
}
