/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * How every answer of this service is written (C7.2, C7.3), and the one place
 * that says it.
 *
 * <p><strong>A member with no value is absent</strong>, in a record and in a
 * map alike -- the content of {@code fields} included: {@code null} means
 * absent on the way in (DEC-0043 R17), and a member written as {@code null} on
 * the way out would be a second spelling of the same thing. A member that must
 * stand even where it is null -- the JSON-RPC id of an envelope that could not
 * be read -- says so on itself.
 *
 * <p><strong>An instant is ISO-8601 in UTC, to the microsecond</strong>: the
 * precision the store holds. An instant that went out with more would differ
 * from every later read of the same unchanged value.
 */
@Singleton
public class SerialisationCustomizer implements ObjectMapperCustomizer {

    /**
     * Applied last, after the customizer that registers Jackson's own module
     * for {@code java.time}: applied earlier, that module's serializer for an
     * instant would replace the one below.
     */
    @Override
    public int priority() {
        return MINIMUM_PRIORITY;
    }

    @Override
    public void customize(ObjectMapper mapper) {
        mapper.setDefaultPropertyInclusion(
            JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
                JsonInclude.Include.NON_NULL));
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.registerModule(new SimpleModule("instants-to-the-microsecond")
            .addSerializer(Instant.class, new InstantSerializer()));
    }

    /** An instant in UTC, truncated to the microsecond. */
    static final class InstantSerializer extends StdSerializer<Instant> {

        private static final long serialVersionUID = 1L;

        InstantSerializer() {
            super(Instant.class);
        }

        @Override
        public void serialize(Instant instant, JsonGenerator out, SerializerProvider provider)
                throws IOException {
            out.writeString(instant.truncatedTo(ChronoUnit.MICROS).toString());
        }
    }
}
