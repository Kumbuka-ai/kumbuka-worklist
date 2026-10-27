/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reading the architecture guards share, and nothing they decide.
 *
 * <p>Seven guards walk the same tree and load the same classes. Written seven
 * times that is seven places for the walk to drift, and the drift would be
 * invisible: a guard that walks the wrong root reports nothing and reads as a
 * clean bill of health. So the walk lives once and every guard decides on top
 * of it.
 *
 * <p>Nothing here holds an expectation. What counts as a violation is each
 * guard's own statement, and a helper that started answering that question
 * would be a second place for the rules to live.
 */
final class SourceTree {

    /** The service's own root package, under which every rule applies. */
    static final String BASE = "ai.kumbuka.worklist";

    private SourceTree() {
    }

    /**
     * The module's source root for a source set, whichever directory the build
     * runs from.
     *
     * <p>Both spellings are tried because the suite runs from the module under
     * Maven and from the repository root under some IDEs, and a root that
     * silently does not exist is a guard that walks nothing and passes.
     */
    static Path root(String sourceSet) {
        Path direct = Paths.get("src", sourceSet, "java");
        Path fromRepoRoot = Paths.get("backend", "src", sourceSet, "java");
        Path root = Files.isDirectory(direct) ? direct : fromRepoRoot;
        assertThat(Files.isDirectory(root))
            .as("source root %s must exist — run from the module directory", root)
            .isTrue();
        return root;
    }

    /** Every {@code .java} file under a source root, in a stable order. */
    static List<Path> files(Path root) {
        try (Stream<Path> found = Files.walk(root)) {
            return found.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** The fully qualified name a file declares, derived from its path. */
    static String fqcn(Path root, Path file) {
        Path relative = root.relativize(file);
        StringBuilder name = new StringBuilder();
        for (Path part : relative) {
            if (!name.isEmpty()) {
                name.append('.');
            }
            name.append(part);
        }
        return name.substring(0, name.length() - ".java".length());
    }

    /** The package a file sits in, as the directory says. */
    static String packageOf(Path root, Path file) {
        String name = fqcn(root, file);
        return name.substring(0, name.lastIndexOf('.'));
    }

    /**
     * The package relative to the service root: {@code adapter.rest} rather
     * than the whole name. Empty for a file directly under the root.
     */
    static String layerOf(Path root, Path file) {
        String pkg = packageOf(root, file);
        return pkg.equals(BASE) ? "" : pkg.substring(BASE.length() + 1);
    }

    /** A file's text, for the checks that are about what is written. */
    static String text(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /**
     * The text with comments removed.
     *
     * <p>Every guard that asks "does this file use X" has to ask it of code.
     * A class named in javadoc is a reference and not a use, and a guard that
     * cannot tell them apart reports the documentation as a violation — which
     * teaches its readers to stop writing documentation.
     */
    static String code(Path file) {
        return code(text(file));
    }

    /** The same for a source given as text: a guard's planted class goes through here. */
    static String code(String source) {
        String stripped = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(source)
            .replaceAll(" ");
        return stripped.replaceAll("//[^\\n]*", " ");
    }

    /** The service's own imports a file declares, as package-qualified names. */
    static List<String> ownImports(Path file) {
        return code(file).lines()
            .map(String::strip)
            .filter(line -> line.startsWith("import " + BASE + "."))
            .map(line -> line.substring("import ".length(), line.indexOf(';')))
            .map(String::strip)
            .toList();
    }

    /**
     * The name of a migration file: its version, a step within that version
     * where it has one, and its description.
     */
    static final Pattern MIGRATION = Pattern.compile("V(\\d+)(?:_(\\d+))?__([^.]*)\\.sql");

    /**
     * Every migration file of the service, in the order Flyway applies them:
     * by the number of the version, not by the spelling, so V10 follows V9.
     * A file that does not have the form of a versioned migration is answered
     * too, last, so a guard can refuse it rather than never see it.
     */
    static List<Path> migrations() {
        Path direct = Paths.get("src", "main", "resources", "db", "migration");
        Path fromRepoRoot = Paths.get("backend", "src", "main", "resources", "db", "migration");
        Path dir = Files.isDirectory(direct) ? direct : fromRepoRoot;
        assertThat(Files.isDirectory(dir))
            .as("migration directory %s must exist — run from the module directory", dir)
            .isTrue();
        try (Stream<Path> found = Files.list(dir)) {
            return found.filter(Files::isRegularFile)
                .sorted(Comparator.comparingInt(SourceTree::versionOf)
                    .thenComparingInt(SourceTree::stepOf)
                    .thenComparing(Path::toString))
                .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** The version a migration file names; past every version where it names none. */
    static int versionOf(Path migration) {
        Matcher m = MIGRATION.matcher(migration.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    /** The step within its version a migration file names; 0 where it names none. */
    static int stepOf(Path migration) {
        Matcher m = MIGRATION.matcher(migration.getFileName().toString());
        return m.matches() && m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
    }
}
