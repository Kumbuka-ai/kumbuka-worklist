/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.contract;

import ai.kumbuka.worklist.surface.SurfaceFixture;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The declaration file the release publishes is what the built service serves.
 *
 * <p>A router builds its surface for this scheme from what {@code GET
 * /api/declaration} answers. So that it can build against the version it pins
 * without starting that version, the release deploys the answer as a file
 * beside the jar, under the same coordinate with the classifier {@code
 * declaration} (the {@code declaration} profile of the POM attaches it).
 *
 * <p>The file cannot be produced beside the service: the declaration's texts
 * come from the message bundles, which exist only in a running application.
 * This test is therefore where the file is made. It reads the body the built
 * service serves, writes it to the path Failsafe names, and then holds the
 * file on disk against a second read of the endpoint, byte for byte -- not as
 * parsed JSON: a reader that hashes the file, or compares its version with the
 * endpoint's {@code ETag}, relies on the bytes. A file that is not exactly the
 * served body fails the build rather than a router.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class DeclarationFileIT {

    /** The system property that names the file to write (set by Failsafe in the POM). */
    private static final String FILE = "kumbuka.worklist.declaration";

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void anyAuthenticatedCaller() {
        SurfaceFixture.asStranger(identity);
    }

    @Test
    void the_published_declaration_is_the_body_the_endpoint_serves() throws IOException {
        Path file = file();
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, served().asByteArray());

        byte[] shipped = Files.readAllBytes(file);
        Response answer = served();
        byte[] body = answer.asByteArray();

        assertThat(firstDifference(shipped, body))
            .as("the declaration file %s and the body of GET /api/declaration differ from "
                + "this byte offset on (-1: they are equal). file: %s / endpoint: %s", file,
                excerpt(shipped, body), excerpt(body, shipped))
            .isEqualTo(-1);

        String version = answer.jsonPath().getString("version");
        assertThat(version).matches("sha256:[0-9a-f]{64}");
        assertThat(answer.header("ETag"))
            .as("the file carries the version the endpoint names as its entity tag")
            .contains(version);
    }

    private static Response served() {
        Response answer = given().get("/api/declaration");
        assertThat(answer.statusCode()).as(answer.asString()).isEqualTo(200);
        return answer;
    }

    private static Path file() {
        String path = System.getProperty(FILE);
        assertThat(path)
            .as("the declaration file is named by the system property %s, which Failsafe "
                + "sets; run this test through `mvn verify`", FILE)
            .isNotBlank();
        return Path.of(path);
    }

    private static int firstDifference(byte[] a, byte[] b) {
        int common = Math.min(a.length, b.length);
        for (int i = 0; i < common; i++) {
            if (a[i] != b[i]) {
                return i;
            }
        }
        return a.length == b.length ? -1 : common;
    }

    /** Some sixty characters of {@code bytes} around where it first differs from {@code other}. */
    private static String excerpt(byte[] bytes, byte[] other) {
        int at = Math.max(0, firstDifference(bytes, other));
        int from = Math.max(0, at - 20);
        int to = Math.min(bytes.length, at + 40);
        return "[" + new String(bytes, from, to - from, StandardCharsets.UTF_8) + "]";
    }
}
