/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.fixture.LogLines;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C15.2 -- the acting identity is the token's subject, and the audience is
 * checked; the service does not start otherwise.
 *
 * <p>The principal is the author of every write and the holder of every
 * claim, so an unpinned principal does not merely record the wrong name: a
 * holder whose principal changes is refused its own claim.
 *
 * <h2>Why the shipped file is read as well</h2>
 *
 * The test profile disables the bearer tenant, so the guard stands down when
 * most of the suite boots and the shipped values are not exercised by it.
 * Reading the artefact closes that gap: the running configuration cannot
 * answer a question about the file a deployment ships.
 */
class IdentityConfigurationGuardTest {

    private static final Path SHIPPED = Path.of("src/main/resources/application.properties");
    private static final String AUDIENCE = "https://platform.kumbuka.ai/mcp";

    @Test
    void the_shipped_configuration_pins_the_principal_and_names_an_audience()
            throws IOException {
        String shipped = Files.readString(SHIPPED);

        assertThat(shipped)
            .as("the acting identity is derived from the token's stable subject: Quarkus "
                + "falls through upn -> preferred_username -> sub for a service application "
                + "unless it is pinned")
            .contains(IdentityConfigurationGuard.PRINCIPAL_CLAIM_KEY + "="
                + IdentityConfigurationGuard.EXPECTED_PRINCIPAL_CLAIM);
        assertThat(shipped)
            .as("and the platform's one audience (ADR-0034) is configured, with the "
                + "environment variable a deployment overrides it with")
            .contains(IdentityConfigurationGuard.AUDIENCE_KEY + "=${WORKLIST_OIDC_AUDIENCE:");
    }

    @Test
    void the_refusal_stands_in_a_log_line_of_its_own() {
        List<LogRecord> lines = LogLines.of(IdentityConfigurationGuard.class, () ->
            IdentityConfigurationGuard.verify(configOf(
                IdentityConfigurationGuard.PRINCIPAL_CLAIM_KEY,
                IdentityConfigurationGuard.EXPECTED_PRINCIPAL_CLAIM)));

        assertThat(lines)
            .as("one WARN line says why the start was refused, and names what to set")
            .singleElement()
            .satisfies(line -> {
                assertThat(line.getLevel().intValue()).isEqualTo(Level.WARNING.intValue());
                assertThat(LogLines.text(line)).contains(IdentityConfigurationGuard.AUDIENCE_KEY)
                    .contains("WORKLIST_OIDC_AUDIENCE");
                assertThat(line.getThrown()).as("the line carries no exception").isNull();
            });
    }

    @Test
    void a_principal_resolved_from_anything_but_the_subject_refuses_the_start() {
        assertThatThrownBy(() -> IdentityConfigurationGuard.verify(configOf(
                IdentityConfigurationGuard.PRINCIPAL_CLAIM_KEY, "preferred_username",
                IdentityConfigurationGuard.AUDIENCE_KEY, AUDIENCE)))
            .as("RED STATE, observed: a principal resolved from a claim other than the "
                + "subject refuses the start, and says why")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(IdentityConfigurationGuard.PRINCIPAL_CLAIM_KEY)
            .hasMessageContaining("refused its own claim");
    }

    @Test
    void an_absent_principal_claim_refuses_the_start_too() {
        assertThatThrownBy(() -> IdentityConfigurationGuard.verify(configOf(
                IdentityConfigurationGuard.AUDIENCE_KEY, AUDIENCE)))
            .as("RED STATE, observed: unset is exactly the state in which Quarkus falls "
                + "through to preferred_username")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(IdentityConfigurationGuard.PRINCIPAL_CLAIM_KEY);
    }

    @Test
    void an_unset_audience_refuses_the_start_because_unset_means_unchecked() {
        assertThatThrownBy(() -> IdentityConfigurationGuard.verify(configOf(
                IdentityConfigurationGuard.PRINCIPAL_CLAIM_KEY, "sub")))
            .as("RED STATE, observed: an unset audience means no audience is validated")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(IdentityConfigurationGuard.AUDIENCE_KEY);
        assertThatThrownBy(() -> IdentityConfigurationGuard.verify(configOf(
                IdentityConfigurationGuard.PRINCIPAL_CLAIM_KEY, "sub",
                IdentityConfigurationGuard.AUDIENCE_KEY, " ")))
            .as("RED STATE, observed: a blank audience is no audience")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(IdentityConfigurationGuard.AUDIENCE_KEY);
    }

    @Test
    void a_pinned_principal_with_an_audience_starts() {
        assertThatCode(() -> IdentityConfigurationGuard.verify(configOf(
                IdentityConfigurationGuard.PRINCIPAL_CLAIM_KEY, "sub",
                IdentityConfigurationGuard.AUDIENCE_KEY, AUDIENCE)))
            .as("the other half: without it every refusal above would hold just as well "
                + "against a guard that refuses everything")
            .doesNotThrowAnyException();
    }

    @Test
    void a_disabled_bearer_tenant_stands_the_guard_down() {
        assertThatCode(() -> IdentityConfigurationGuard.verify(configOf(
                IdentityConfigurationGuard.TENANT_ENABLED_KEY, "false")))
            .as("a disabled tenant validates no token, so there is no principal to pin; the "
                + "default is true, so silence never disarms the guard")
            .doesNotThrowAnyException();
    }

    private static Config configOf(String... namesAndValues) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            properties.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return new SmallRyeConfigBuilder()
            .withSources(new PropertiesConfigSource(properties, "the probe's own", 100))
            .build();
    }
}
