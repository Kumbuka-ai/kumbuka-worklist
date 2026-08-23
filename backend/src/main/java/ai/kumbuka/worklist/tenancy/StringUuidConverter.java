package ai.kumbuka.worklist.tenancy;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

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
    public UUID convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : UUID.fromString(attribute);
    }

    @Override
    public String convertToEntityAttribute(UUID dbData) {
        return dbData == null ? null : dbData.toString();
    }
}
