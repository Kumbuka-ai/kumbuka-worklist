/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Three kinds of test, told apart by the name's suffix (C22.1 to C22.3).
 *
 * <ul>
 *   <li>{@code *Test}: one class or a few plain classes. No container, no CDI,
 *       no Mockito.</li>
 *   <li>{@code *ModuleTest}: a component against mocked interfaces with
 *       Mockito. No container.</li>
 *   <li>{@code *IT}: against the real database in a Testcontainers
 *       container, by Failsafe. Either the running service, as a {@code
 *       QuarkusTest} with a test resource that starts the database, or a
 *       probe that starts the database itself and drives it without the
 *       service.</li>
 * </ul>
 *
 * <p>A class that starts the database is one of the test tree whose code
 * creates a {@code PostgreSQLContainer}; the check finds them in the tree and
 * holds no name of its own. The second way into an {@code *IT} is beyond the
 * wording of C22.3, which names the {@code QuarkusTest} only: the migration
 * probes drive Flyway against a container of their own because a running
 * application cannot un-register a callback, and they are admitted for that.
 *
 * <p>A {@code QuarkusMainTest} boots the service as a {@code QuarkusTest}
 * does, by launching it whole, and is held to the same rule: it needs a test
 * resource that starts the database. This service has none today; the rule
 * is carried as in kumbuka-memory, beyond the reference, so that one written
 * later cannot run without its database unnoticed.
 *
 * <p>The suffix decides which plugin runs a class and how long the build
 * waits for it, so a {@code *Test} that boots the service is a slow test in
 * the fast lane, and a mock in a {@code *Test} is a component test that claims
 * to test one class. Every class that holds a test method carries one of the
 * three suffixes.
 */
class TestTaxonomyTest {

    private static final Pattern TEST_METHOD = Pattern.compile(
        "@(Test|ParameterizedTest|RepeatedTest|TestFactory)\\b");
    private static final Pattern CONTAINER = Pattern.compile(
        "@(QuarkusTest|QuarkusMainTest|QuarkusIntegrationTest|QuarkusTestResource|TestProfile)"
            + "\\b");
    private static final Pattern MOCKITO = Pattern.compile("\\borg\\.mockito\\b|@Mock\\b");
    private static final Pattern CDI = Pattern.compile("@Inject\\b");
    private static final Pattern SERVICE = Pattern.compile("@Quarkus(?:Main)?Test\\b");
    private static final Pattern STARTS_DATABASE = Pattern.compile(
        "\\bnew\\s+PostgreSQLContainer\\b");
    private static final Pattern TEST_RESOURCE = Pattern.compile(
        "@(?:QuarkusTestResource|WithTestResource)\\s*\\(\\s*(?:value\\s*=\\s*)?(\\w+)\\.class");

    @Test
    void every_test_class_keeps_to_the_kind_its_name_says() {
        assertThat(offenders(SourceTree.root("test")))
            .as("the suffix says which plugin runs a class and what it may use (C22). Rename "
                + "a class that boots the service to *IT, one that mocks to *ModuleTest, or "
                + "take the container and the mocks out of it")
            .isEmpty();
    }

    /** The red state, observed on every build: one planted class per rule. */
    @Test
    void the_check_reports_each_kind_out_of_place() {
        assertThat(kindOffences("RouterTest",
                "import org.mockito.Mockito; class RouterTest { @Test void t() {} }", Set.of()))
            .as("RED STATE, observed: Mockito in a *Test").containsExactly(
                "RouterTest uses Mockito; a *Test is plain (C22.1)");
        assertThat(kindOffences("ConformanceTest",
                "@QuarkusTest class ConformanceTest { @Inject X x; @Test void t() {} }", Set.of()))
            .as("RED STATE, observed: a container and CDI in a *Test").containsExactly(
                "ConformanceTest boots the service; a *Test is plain (C22.1)",
                "ConformanceTest injects; a *Test is plain (C22.1)");
        assertThat(kindOffences("SurfaceModuleTest",
                "@QuarkusTest class SurfaceModuleTest { @Test void t() {} }", Set.of()))
            .as("RED STATE, observed: a container in a *ModuleTest").containsExactly(
                "SurfaceModuleTest boots the service; a *ModuleTest has no container (C22.2)");
        assertThat(kindOffences("Probe", "class Probe { @Test void t() {} }", Set.of()))
            .as("RED STATE, observed: a test class with none of the three suffixes")
            .containsExactly("Probe holds tests and is none of *Test, *ModuleTest, *IT");
        assertThat(kindOffences("FlowIT",
                "@QuarkusTest @QuarkusTestResource(value = Db.class, restrictToAnnotatedClass = "
                    + "true) class FlowIT { @Inject X x; @Test void t() {} }", Set.of("Db")))
            .as("RED STATE, observed: the same in an *IT on the database is admitted").isEmpty();
    }

    /** The red state of C22.3, observed on every build: each way an *IT leaves it. */
    @Test
    void the_check_reports_an_integration_test_off_the_real_database() {
        assertThat(kindOffences("FlowIT", "class FlowIT { @Test void t() {} }", Set.of("Db")))
            .as("RED STATE, observed: an *IT that is no QuarkusTest and starts no database")
            .containsExactly("FlowIT is no @QuarkusTest and starts no database; an *IT runs "
                + "against the real database (C22.3)");
        assertThat(kindOffences("FlowIT",
                "@QuarkusTest @QuarkusTestResource(Idp.class) class FlowIT { @Test void t() {} }",
                Set.of("Db")))
            .as("RED STATE, observed: a QuarkusTest *IT whose resources start no database")
            .containsExactly("FlowIT boots the service with no test resource that starts the "
                + "database; an *IT runs against the real database (C22.3)");
        assertThat(kindOffences("FlowIT",
                "@QuarkusTest @WithTestResource(Db.class) class FlowIT { @Test void t() {} }",
                Set.of("Db")))
            .as("RED STATE, observed: the resource read under its other annotation").isEmpty();
        assertThat(kindOffences("StartIT",
                "@QuarkusMainTest @TestProfile(P.class) class StartIT { @Test void t() {} }",
                Set.of("Db")))
            .as("RED STATE, observed: a QuarkusMainTest boots the service too, and needs the "
                + "database as a QuarkusTest does")
            .containsExactly("StartIT boots the service with no test resource that starts the "
                + "database; an *IT runs against the real database (C22.3)");
        assertThat(kindOffences("StartIT",
                "@QuarkusMainTest @QuarkusTestResource(value = Db.class) class StartIT { "
                    + "@Test void t() {} }", Set.of("Db")))
            .as("RED STATE, observed: and with it, it is admitted").isEmpty();
        assertThat(kindOffences("MigrationIT",
                "class MigrationIT { @BeforeAll static void up() { db = Harness.start(); } "
                    + "@Test void t() {} }", Set.of("Harness")))
            .as("RED STATE, observed: a probe that starts the database itself is admitted")
            .isEmpty();
        assertThat(databaseStarters(List.of(
                "class Harness { static Harness start() { new PostgreSQLContainer<>(I); } }",
                "class Idp { Object c = new KeycloakContainer(); }"), List.of("Harness", "Idp")))
            .as("RED STATE, observed: a class that creates the database container is found, "
                + "and one that creates another container is not")
            .containsExactly("Harness");
    }

    private static List<String> offenders(Path root) {
        List<String> names = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            String fqcn = SourceTree.fqcn(root, file);
            names.add(fqcn.substring(fqcn.lastIndexOf('.') + 1));
            codes.add(withoutLiterals(SourceTree.code(file)));
        }
        Set<String> starters = databaseStarters(codes, names);
        assertThat(starters)
            .as("the test tree must hold a class that starts the database, or every *IT "
                + "would be reported for the guard's want of looking")
            .isNotEmpty();
        List<String> offenders = new ArrayList<>();
        int tests = 0;
        for (int i = 0; i < codes.size(); i++) {
            if (TEST_METHOD.matcher(codes.get(i)).find()) {
                tests++;
            }
            offenders.addAll(kindOffences(names.get(i), codes.get(i), starters));
        }
        assertThat(tests).as("test classes must have been found").isGreaterThan(20);
        return offenders;
    }

    /** The simple names of the classes whose code creates the database container. */
    static Set<String> databaseStarters(List<String> codes, List<String> names) {
        Set<String> starters = new LinkedHashSet<>();
        for (int i = 0; i < codes.size(); i++) {
            if (STARTS_DATABASE.matcher(codes.get(i)).find()) {
                starters.add(names.get(i));
            }
        }
        return starters;
    }

    /**
     * The code with its text blocks and string literals blanked: a guard that
     * plants a class as text, this one included, names annotations in strings
     * that are not annotations of the file.
     */
    static String withoutLiterals(String code) {
        String blanked = Pattern.compile("\"\"\".*?\"\"\"", Pattern.DOTALL).matcher(code)
            .replaceAll("\"\"");
        return blanked.replaceAll("\"(\\\\.|[^\"\\\\\\n])*\"", "\"\"");
    }

    static List<String> kindOffences(String name, String code, Set<String> starters) {
        if (!TEST_METHOD.matcher(code).find()) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        boolean container = CONTAINER.matcher(code).find();
        if (name.endsWith("IT")) {
            if (SERVICE.matcher(code).find()) {
                if (!hasDatabaseResource(code, starters)) {
                    found.add(name + " boots the service with no test resource that starts the "
                        + "database; an *IT runs against the real database (C22.3)");
                }
            } else if (!startsDatabase(code, starters)) {
                found.add(name + " is no @QuarkusTest and starts no database; an *IT runs "
                    + "against the real database (C22.3)");
            }
            return found;
        }
        if (name.endsWith("ModuleTest")) {
            if (container) {
                found.add(name + " boots the service; a *ModuleTest has no container (C22.2)");
            }
            return found;
        }
        if (!name.endsWith("Test")) {
            found.add(name + " holds tests and is none of *Test, *ModuleTest, *IT");
            return found;
        }
        if (MOCKITO.matcher(code).find()) {
            found.add(name + " uses Mockito; a *Test is plain (C22.1)");
        }
        if (container) {
            found.add(name + " boots the service; a *Test is plain (C22.1)");
        }
        if (CDI.matcher(code).find()) {
            found.add(name + " injects; a *Test is plain (C22.1)");
        }
        return found;
    }

    private static boolean hasDatabaseResource(String code, Set<String> starters) {
        Matcher resource = TEST_RESOURCE.matcher(code);
        while (resource.find()) {
            if (starters.contains(resource.group(1))) {
                return true;
            }
        }
        return false;
    }

    /** The code starts a database class itself: {@code X.start(} or {@code new X(}. */
    private static boolean startsDatabase(String code, Set<String> starters) {
        return starters.stream().anyMatch(starter -> Pattern.compile(
                "\\b" + starter + "\\s*\\.\\s*start\\s*\\(|\\bnew\\s+" + starter + "\\s*[(<]")
            .matcher(code).find());
    }
}
