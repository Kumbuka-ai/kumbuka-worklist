/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.tenancy;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Bridges Hibernate's String tenant identifier to the {@code uuid} column.
 *
 * <p>The type of a {@code @TenantId} field must match what Quarkus' Hibernate
 * tenant resolver returns, and that SPI is String-only; the column is
 * {@code uuid}. Applied per attribute with {@code @Convert} so the round trip
 * stays explicit and greppable rather than global and invisible.
 */
@Converter
public class StringUuidConverter implements AttributeConverter<String, UUID> {

    @Override
    public @Nullable UUID convertToDatabaseColumn(@Nullable String attribute) {
        return attribute == null ? null : UUID.fromString(attribute);
    }

    @Override
    public @Nullable String convertToEntityAttribute(@Nullable UUID dbData) {
        return dbData == null ? null : dbData.toString();
    }
}
