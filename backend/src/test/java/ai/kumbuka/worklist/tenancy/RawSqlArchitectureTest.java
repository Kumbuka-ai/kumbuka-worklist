/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.tenancy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tripwire on the seam between the two enforcement layers.
 *
 * <p>Layer 1 rewrites every statement the ORM builds. Raw and native SQL is
 * not rewritten — that is what makes it raw — so its only protection is the
 * database policy, and the policy only applies where the session setting was
 * bound. That binding happens in {@code @TenantBound} methods and nowhere
 * else. It follows that a class issuing raw SQL without {@code @TenantBound}
 * can read or write under whatever tenant the pooled connection last carried,
 * or under none.
 *
 * <p>This test fails the moment such a class appears. The author then either
 * annotates it or, if the class legitimately manages the setting itself, adds
 * it to {@link #ALLOWLIST} with the reason — a decision that gets made rather
 * than a rule that gets forgotten.
 *
 * <p><strong>The annotation is read from the loaded class, never from the
 * file text.</strong> The file walk finds raw SQL; whether the class is bound
 * is decided by reflection. An earlier generation of this check, in the
 * service this one is modelled on, matched the literal string anywhere in the
 * source — so a class that merely mentioned the annotation in a comment
 * passed unguarded, and one did. A check that derives its expectation from
 * the artifact it is checking proves nothing.
 *
 * <p>Runs as a plain unit test: it reads sources and class files and needs no
 * database, which is also why it is fast enough to be worth running on every
 * build.
 */
class RawSqlArchitectureTest {

    /**
     * Classes allowed to issue raw SQL without {@code @TenantBound}, each
     * with the reason it cannot cross a tenant boundary. Entries are class
     * literals rather than names, so a rename breaks the build instead of
     * quietly leaving an exemption that no longer matches anything.
     */
    private static final Set<Class<?>> ALLOWLIST = Set.of(
        // Sets app.tenant_id itself, with is_local = true. Touches no
        // tenant-scoped table, so there is no row for it to cross with.
        TenantDatabaseBinding.class,
        // Flyway beforeEachMigrate: binds the setting for the migration's own
        // transaction, at boot, before any request is served.
        TenantMigrationCallback.class);

    // The sibling service carries a third entry here, for the afterMigrate
    // callback that normalises object ownership. There is no such callback in
    // this service: the migrator keeps ownership and V2 enumerates the
    // runtime role's privileges instead, so the exemption has nothing to
    // exempt. Leaving a stale entry would have been harmless and wrong — an
    // allowlist is a list of decisions, and a decision about a class that
    // does not exist is not one.

    private static final List<String> RAW_SQL_MARKERS = List.of(
        "createNativeQuery", ".getConnection(", ".createStatement(", ".prepareStatement(");

    @Test
    void raw_sql_is_only_issued_from_tenant_bound_classes() throws IOException {
        assertThat(offendersUnder(sourceRoot("main"), ALLOWLIST))
            .as("raw SQL is invisible to the ORM's tenant filter, so it must run under "
                + "@TenantBound — which is what binds app.tenant_id and brings the database "
                + "policy into effect. Presence is read from the applied annotation, so a "
                + "comment naming @TenantBound does not count. Annotate these classes, or "
                + "add them to ALLOWLIST with the reason they cannot leak")
            .isEmpty();
    }

    /**
     * The red state, observed on every build.
     *
     * <p>A tripwire that has never caught anything is a tripwire nobody has
     * checked is connected. {@code UnboundRawSqlFixture} in the test sources
     * is a genuine violation — raw SQL against a tenant-scoped table with no
     * binding — and the detection is required to find it. That is what makes
     * the empty result over the main sources mean something.
     *
     * <p>The fixture also names {@code @TenantBound} in its javadoc. A check
     * that decided on file text would be fooled by that mention; this one
     * reads the applied annotation, so the fixture is still reported. The
     * check is therefore observed being right for the right reason, not just
     * arriving at the right answer.
     */
    @Test
    void the_tripwire_catches_an_unbound_class_that_issues_raw_sql() throws IOException {
        assertThat(offendersUnder(sourceRoot("test"), Set.of()))
            .as("RED STATE, observed: the fixture issues raw SQL without an applied "
                + "@TenantBound and must be reported. An empty list here would mean the "
                + "green assertion above is measuring nothing")
            .anyMatch(offender -> offender.contains("UnboundRawSqlFixture"));
    }

    /** One class's native SQL: how many statements, and why JPQL does not serve. */
    record NativeUse(int statements, String reason) {
    }

    /**
     * C17.2: every class that issues native SQL, by its package and name below
     * the service root, with its count of native statements and its written
     * reason. A statement added, removed or moved changes the count and turns
     * this red, so the reason is read again whenever the SQL changes.
     */
    static final Map<String, NativeUse> NATIVE = Map.of(
        "repository.SelectorRepository", new NativeUse(2, "opens a view: the selector row "
            + "kept for the previous image and the counter, each an INSERT ... ON CONFLICT DO "
            + "NOTHING, which JPQL cannot express"),
        "repository.ScopeAccessRepository", new NativeUse(5, "reads the platform's "
            + "subject-filtered scope directory, a view no entity of this service maps, and "
            + "binds and reads the session settings app.subject and app.tenant_id"),
        "tenancy.TenantDatabaseBinding", new NativeUse(1, "binds app.tenant_id for the "
            + "transaction with set_config: a session setting has no entity"),
        "tenancy.TenantMigrationCallback", new NativeUse(1, "binds app.tenant_id on the "
            + "migration's own connection, before Hibernate exists"));

    /** C17.1, C17.2: native SQL lives in repository, and in the two named tenancy classes. */
    private static final Set<String> NATIVE_HOMES = Set.of("repository");
    private static final Set<String> TENANCY_EXCEPTIONS = Set.of(
        "tenancy.TenantDatabaseBinding", "tenancy.TenantMigrationCallback");

    private static final Pattern NATIVE_STATEMENT = Pattern.compile(
        "createNativeQuery\\(|\\.prepareStatement\\(|\\.createStatement\\(");

    @Test
    void native_sql_is_confined_counted_and_reasoned_per_class() throws IOException {
        assertThat(nativeOffences(nativeCountsUnder(sourceRoot("main")), NATIVE))
            .as("native SQL lives in repository and the two named tenancy classes, and each "
                + "class that issues it is listed in NATIVE with its count of statements and "
                + "the reason JPQL does not serve (C17.2). Update the entry with the change")
            .isEmpty();
    }

    /** The red state, observed on every build. */
    @Test
    void the_check_reports_a_statement_more_and_a_class_out_of_place() {
        Map<String, Integer> found = new LinkedHashMap<>();
        NATIVE.forEach((name, use) -> found.put(name, use.statements()));
        found.put("repository.ScopeAccessRepository",
            NATIVE.get("repository.ScopeAccessRepository").statements() + 1);
        found.put("domain.ItemService", 1);

        assertThat(nativeOffences(found, NATIVE))
            .as("RED STATE, observed: one statement more than declared, and native SQL in "
                + "the domain, must each be reported")
            .containsExactlyInAnyOrder(
                "repository.ScopeAccessRepository issues 6 native statements; NATIVE declares 5",
                "domain.ItemService issues native SQL outside repository",
                "domain.ItemService issues native SQL and is not listed in NATIVE");
    }

    static List<String> nativeOffences(Map<String, Integer> found,
                                       Map<String, NativeUse> declared) {
        List<String> offences = new ArrayList<>();
        found.forEach((name, count) -> {
            String layer = name.substring(0, name.lastIndexOf('.'));
            if (!NATIVE_HOMES.contains(layer) && !TENANCY_EXCEPTIONS.contains(name)) {
                offences.add(name + " issues native SQL outside repository");
            }
            NativeUse use = declared.get(name);
            if (use == null) {
                offences.add(name + " issues native SQL and is not listed in NATIVE");
            } else if (use.statements() != count) {
                offences.add(name + " issues " + count + " native statements; NATIVE declares "
                    + use.statements());
            }
        });
        declared.forEach((name, use) -> {
            if (!found.containsKey(name)) {
                offences.add(name + " is listed in NATIVE and issues no native SQL");
            }
            if (use.reason() == null || use.reason().isBlank()) {
                offences.add(name + " is listed without a reason");
            }
        });
        return offences;
    }

    /** Native statements per class, counted in code with its comments removed. */
    private static Map<String, Integer> nativeCountsUnder(Path root) throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        String base = "ai.kumbuka.worklist.";
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files
                    .filter(f -> f.toString().endsWith(".java"))::iterator) {
                String code = Files.readString(file)
                    .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
                Matcher m = NATIVE_STATEMENT.matcher(code);
                int count = 0;
                while (m.find()) {
                    count++;
                }
                if (count > 0) {
                    counts.put(fqcn(root, file).substring(base.length()), count);
                }
            }
        }
        assertThat(counts).as("native SQL must have been found at all").isNotEmpty();
        return counts;
    }

    /** The module's source root for a given source set, whichever directory the build runs from. */
    private static Path sourceRoot(String sourceSet) {
        Path direct = Paths.get("src", sourceSet, "java");
        Path fromRepoRoot = Paths.get("backend", "src", sourceSet, "java");
        Path root = Files.isDirectory(direct) ? direct : fromRepoRoot;
        assertThat(Files.isDirectory(root))
            .as("source root %s must exist — run from the module directory", root)
            .isTrue();
        return root;
    }

    /**
     * Every class under {@code root} that issues raw SQL without an applied
     * {@code @TenantBound} and without an exemption.
     *
     * <p>Parameterised on the root and the allowlist so the same detection
     * serves both the assertion and its red state. A red state exercising a
     * second copy of the logic would prove that the copy works.
     */
    private static List<String> offendersUnder(Path root, Set<Class<?>> allowlist)
            throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files
                    .filter(f -> f.toString().endsWith(".java"))::iterator) {
                // File text locates raw SQL and decides nothing else.
                String source = Files.readString(file);
                if (RAW_SQL_MARKERS.stream().noneMatch(source::contains)) {
                    continue;
                }
                Class<?> clazz = loadClass(root, file);
                if (clazz == null) {
                    // A raw-SQL file that cannot be loaded cannot be checked,
                    // and an unverifiable file is reported rather than skipped.
                    offenders.add(fqcn(root, file)
                        + " (issues raw SQL but could not be loaded for a structural check)");
                    continue;
                }
                if (allowlist.contains(clazz)) {
                    continue;
                }
                if (!isTenantBound(clazz)) {
                    offenders.add(clazz.getName() + " (" + root.relativize(file) + ")");
                }
            }
        }
        return offenders;
    }

    /**
     * Class-level {@code @TenantBound} covers every method; a method-level one
     * covers that method. Either satisfies the tripwire, and neither can be
     * faked by a string appearing in the file.
     */
    private static boolean isTenantBound(Class<?> clazz) {
        if (clazz.isAnnotationPresent(TenantBound.class)) {
            return true;
        }
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(TenantBound.class)) {
                return true;
            }
        }
        return false;
    }

    private static String fqcn(Path root, Path file) {
        Path relative = root.relativize(file);
        StringBuilder sb = new StringBuilder();
        for (Path part : relative) {
            if (!sb.isEmpty()) {
                sb.append('.');
            }
            sb.append(part);
        }
        return sb.substring(0, sb.length() - ".java".length());
    }

    /**
     * Loads the class WITHOUT initialising it — annotation reflection needs
     * the class linked, not initialised, and initialising application classes
     * from a unit test would drag in CDI. Returns null when the file holds no
     * loadable top-level class of the expected name, and the caller turns
     * that into a reported offender rather than a silent skip.
     */
    private static Class<?> loadClass(Path root, Path file) {
        try {
            return Class.forName(fqcn(root, file), false,
                RawSqlArchitectureTest.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError notLoadable) {
            return null;
        }
    }
}
