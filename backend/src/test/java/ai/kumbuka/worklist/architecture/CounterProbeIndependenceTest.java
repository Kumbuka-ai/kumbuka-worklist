/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.architecture;

import ai.kumbuka.worklist.CounterProbe;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A counter-probe reaches nothing of the production packages (C22.5,
 * ADR-0039).
 *
 * <p>Every class of the test tree marked {@link CounterProbe} is read as code,
 * comments removed, and refused for three ways in which a production type
 * reaches it: an import of a type the main tree declares, a fully qualified
 * name of one, and a simple name of a type the main tree declares in the
 * probe's own package, which needs no import at all. A type of the test tree
 * under the service's package, a fixture or a resource, is not production.
 *
 * <p>Generalised from the check over three named classes in kumbuka-memory's
 * {@code DeclarationEndpointIT}: the marker replaces the list, so a probe
 * written later is held to the rule by being marked, not by being remembered
 * here. Taken from kumbuka-dispatch with {@link SourceTree#BASE} as the only
 * change to the check; the planted source names this service's types.
 */
class CounterProbeIndependenceTest {

    /** The marker as it is written on a type. */
    private static final Pattern MARKED = Pattern.compile("^@CounterProbe\\b", Pattern.MULTILINE);

    @Test
    void a_counter_probe_imports_nothing_from_the_production_packages() {
        Path test = SourceTree.root("test");
        Path main = SourceTree.root("main");
        List<String> probes = new ArrayList<>();
        List<String> offences = new ArrayList<>();
        for (Path file : SourceTree.files(test)) {
            String code = SourceTree.code(file);
            if (!MARKED.matcher(code).find()) {
                continue;
            }
            String name = SourceTree.fqcn(test, file);
            probes.add(name);
            offences.addAll(offences(name, SourceTree.packageOf(test, file), code, main));
        }
        assertThat(probes).as("the guard finds the marked counter-probes").hasSizeGreaterThan(3);
        assertThat(offences)
            .as("a counter-probe whose expectation comes from the artefact it checks agrees "
                + "with it by construction and proves nothing (C22.5). Write the expectation "
                + "in the test tree and read the production answer as data")
            .isEmpty();
    }

    /** The red state, observed on every build. */
    @Test
    void the_check_reports_an_import_a_qualified_name_and_a_type_of_the_own_package() {
        Path main = SourceTree.root("main");
        String planted = """
            package %1$s.surface;

            import %1$s.surface.RefusalCode;
            import %1$s.tenancy.SubstrateDatabaseResource;

            @CounterProbe
            class Planted {
                Object kind = %1$s.domain.HolderState.NOBODY;
                Object form = Bound.MAX;
            }
            """.formatted(SourceTree.BASE);
        assertThat(offences("Planted", SourceTree.BASE + ".surface", planted, main))
            .as("RED STATE, observed: a planted import of a production type, a fully "
                + "qualified production name and a production type of the probe's own "
                + "package must each be reported, and a type of the test tree must not")
            .containsExactly(
                "Planted imports " + SourceTree.BASE + ".surface.RefusalCode",
                "Planted names " + SourceTree.BASE + ".domain.HolderState",
                "Planted uses Bound of its own package");
    }

    static List<String> offences(String probe, String pkg, String code, Path main) {
        List<String> offences = new ArrayList<>();
        Pattern imported = Pattern.compile("^\\s*import\\s+(?:static\\s+)?("
            + Pattern.quote(SourceTree.BASE) + "\\.[\\w.]+)\\s*;", Pattern.MULTILINE);
        Matcher m = imported.matcher(code);
        List<String> imports = new ArrayList<>();
        while (m.find()) {
            imports.add(m.group(1));
            if (declaredInMain(main, m.group(1))) {
                offences.add(probe + " imports " + m.group(1));
            }
        }
        String body = imported.matcher(code).replaceAll("")
            .replaceAll("(?m)^\\s*package\\s+[\\w.]+\\s*;", "");
        Matcher qualified = Pattern.compile("\\b" + Pattern.quote(SourceTree.BASE)
            + "(?:\\.[a-z]\\w*)+\\.[A-Z]\\w*").matcher(body);
        while (qualified.find()) {
            if (declaredInMain(main, qualified.group())) {
                offences.add(probe + " names " + qualified.group());
            }
        }
        Path own = main.resolve(pkg.replace('.', '/'));
        if (Files.isDirectory(own)) {
            for (Path type : SourceTree.files(own)) {
                if (!type.getParent().equals(own)) {
                    continue;
                }
                String simple = type.getFileName().toString().replace(".java", "");
                if (Pattern.compile("(?<![\\w.])" + simple + "\\b").matcher(body).find()) {
                    offences.add(probe + " uses " + simple + " of its own package");
                }
            }
        }
        return offences;
    }

    /** Whether a name, or the type a member of it belongs to, is declared in the main tree. */
    private static boolean declaredInMain(Path main, String name) {
        String candidate = name;
        while (candidate.contains(".")) {
            if (Files.exists(main.resolve(candidate.replace('.', '/') + ".java"))) {
                return true;
            }
            candidate = candidate.substring(0, candidate.lastIndexOf('.'));
        }
        return false;
    }
}
