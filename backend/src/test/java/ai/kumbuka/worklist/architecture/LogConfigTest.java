/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every log line carries the request id, in the one format of the contract,
 * and no line carries an exception (C13.1, C13.2).
 *
 * <p>The format is held here as the contract writes it, not read from the
 * service: {@code %d{HH:mm:ss} %-5p [%c{2.}] (%t) [%X{request_id}] %s%n}. Every
 * format key the main configuration sets, for any handler and in any profile,
 * is held to it; {@code %e} and its long forms print the exception, which
 * C13.2 keeps out of a line, and a format without {@code %X{request_id}} is a
 * line no call can be found by.
 */
class LogConfigTest {

    /** The format of C13.1, written out. */
    static final String FORMAT = "%d{HH:mm:ss} %-5p [%c{2.}] (%t) [%X{request_id}] %s%n";

    @Test
    void every_log_format_is_the_contract_s() throws IOException {
        assertThat(offences(mainConfiguration()))
            .as("one format in every service, with the request id and without the exception "
                + "(C13.1, C13.2). Set it as the contract writes it")
            .isEmpty();
        assertThat(mainConfiguration().getProperty("quarkus.log.console.format"))
            .as("the console handler is the one a deployment reads").isEqualTo(FORMAT);
    }

    /** The red state, observed on every build. */
    @Test
    void the_check_reports_a_format_without_the_id_and_one_with_the_exception()
            throws IOException {
        Properties planted = new Properties();
        planted.load(new StringReader("""
            quarkus.log.console.format=%d{HH:mm:ss} %-5p [%c{2.}] (%t) %s%e%n
            quarkus.log.file.format=%d{HH:mm:ss} %-5p [%c{2.}] (%t) [%X{request_id}] %s%n
            %prod.quarkus.log.console.format=%d %s%n
            """));

        assertThat(offences(planted))
            .as("RED STATE, observed: the format the service had before, and a profile's "
                + "format without the id, must each be reported; the contract's must not")
            .containsExactly(
                "%prod.quarkus.log.console.format is not the contract's format",
                "quarkus.log.console.format is not the contract's format");
    }

    static List<String> offences(Properties configuration) {
        List<String> offences = new ArrayList<>();
        configuration.stringPropertyNames().stream().sorted()
            .filter(key -> key.replaceFirst("^%[^.]+\\.", "").startsWith("quarkus.log.")
                && key.endsWith(".format"))
            .forEach(key -> {
                if (!FORMAT.equals(configuration.getProperty(key))) {
                    offences.add(key + " is not the contract's format");
                }
            });
        return offences;
    }

    private static Properties mainConfiguration() throws IOException {
        Path direct = Paths.get("src", "main", "resources", "application.properties");
        Path fromRepoRoot = Paths.get("backend").resolve(direct);
        Path file = Files.isRegularFile(direct) ? direct : fromRepoRoot;
        Properties properties = new Properties();
        properties.load(new StringReader(Files.readString(file)));
        assertThat(properties).as("the main configuration must have been read").isNotEmpty();
        return properties;
    }
}
