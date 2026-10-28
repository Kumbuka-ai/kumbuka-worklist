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
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request travels adapter, verb surface, domain service, repository, and
 * nothing stands between the adapter and the verb surface (C1.1).
 *
 * <h2>What "a class that makes a call" is, read from the tree</h2>
 *
 * A class in {@code surface} makes a call when it reaches the transactional
 * domain service: it imports a {@code domain} class whose name ends in {@code
 * Service}, or it names the verb surface itself. The verb surface is the one
 * such class the rule admits. Any other is a further layer between the adapter
 * and the verb surface, whatever it is called, and two statements follow:
 *
 * <ol>
 *   <li>no class in {@code surface} but the verb surface reaches the domain
 *       service, and</li>
 *   <li>no adapter reaches a class in {@code surface} that makes a call, the
 *       verb surface excepted.</li>
 * </ol>
 *
 * <p>Read from imports and code rather than from a list of forbidden names: a
 * router that came back under another name is caught by what it does.
 *
 * <p>The rule carries no word of one service. The service's own name lives in
 * {@link SourceTree#BASE} alone, so the class is copied into another service
 * with that constant changed.
 */
class EntryPathTest {

    /** The one class of the surface layer an adapter calls for a verb. */
    private static final String VERB_SURFACE = "VerbSurface";

    private static final String SURFACE = "surface";
    private static final String DOMAIN = "domain";
    private static final String ADAPTER = "adapter";

    /** One source file, as the detection reads it. */
    record Source(String layer, String name, List<String> imports, String code) {
    }

    @Test
    void no_class_of_the_surface_but_the_verb_surface_reaches_the_domain_service() {
        assertThat(surfaceClassesReachingTheDomainService(mainSources()))
            .as("the verb surface holds the check order and opens the transaction; a second "
                + "class in the surface that reaches the domain service is a second way in, "
                + "and the check order no longer holds for every call (C1.1)")
            .isEmpty();
    }

    @Test
    void no_adapter_reaches_a_class_of_the_surface_that_makes_a_call() {
        assertThat(adaptersPassingTheVerbSurface(mainSources()))
            .as("both adapters call the verb surface directly; a class between them is a "
                + "layer the contract does not have (C1.1). Move form checks into the "
                + "adapter and call the verb surface from there")
            .isEmpty();
    }

    /**
     * The red state, observed on every build.
     *
     * <p>A tree assembled here: a router in the surface that reaches the
     * domain service and is called by an adapter. Both statements must
     * report it, and the verb surface beside it must stay unreported.
     */
    @Test
    void the_check_reports_a_router_between_adapter_and_verb_surface() {
        String domainService = SourceTree.BASE + "." + DOMAIN + ".ThingService";
        List<Source> planted = List.of(
            new Source(SURFACE, VERB_SURFACE, List.of(domainService), "class VerbSurface {}"),
            new Source(SURFACE, "CallDispatcher", List.of(domainService),
                "class CallDispatcher { VerbSurface verbs; }"),
            new Source("adapter.rest", "ThingResource",
                List.of(SourceTree.BASE + ".surface.CallDispatcher"),
                "class ThingResource { CallDispatcher calls; }"));

        assertThat(surfaceClassesReachingTheDomainService(planted))
            .as("RED STATE, observed: a second class in the surface that reaches the domain "
                + "service must be reported, and the verb surface must not be")
            .containsExactly("surface.CallDispatcher");
        assertThat(adaptersPassingTheVerbSurface(planted))
            .as("RED STATE, observed: an adapter calling that class instead of the verb "
                + "surface must be reported")
            .containsExactly("adapter.rest.ThingResource -> surface.CallDispatcher");
    }

    private static List<Source> mainSources() {
        Path root = SourceTree.root("main");
        List<Source> sources = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            String fqcn = SourceTree.fqcn(root, file);
            sources.add(new Source(SourceTree.layerOf(root, file),
                fqcn.substring(fqcn.lastIndexOf('.') + 1),
                SourceTree.ownImports(file), SourceTree.code(file)));
        }
        assertThat(sources.stream().filter(s -> s.name().equals(VERB_SURFACE)))
            .as("the verb surface must be found, or the guard walks the wrong tree")
            .hasSize(1);
        return sources;
    }

    /** Surface classes other than the verb surface that make a call. */
    static List<String> surfaceClassesReachingTheDomainService(List<Source> sources) {
        return sources.stream()
            .filter(s -> s.layer().equals(SURFACE) && !s.name().equals(VERB_SURFACE))
            .filter(s -> importsDomainService(s) || names(s, VERB_SURFACE))
            .map(s -> s.layer() + "." + s.name())
            .toList();
    }

    /** Each adapter that reaches a calling surface class other than the verb surface. */
    static List<String> adaptersPassingTheVerbSurface(List<Source> sources) {
        Set<String> calling = Set.copyOf(sources.stream()
            .filter(s -> s.layer().equals(SURFACE) && !s.name().equals(VERB_SURFACE))
            .filter(s -> importsDomainService(s) || names(s, VERB_SURFACE))
            .map(Source::name)
            .toList());
        List<String> offenders = new ArrayList<>();
        for (Source source : sources) {
            if (!source.layer().startsWith(ADAPTER)) {
                continue;
            }
            for (String imported : source.imports()) {
                String prefix = SourceTree.BASE + "." + SURFACE + ".";
                if (!imported.startsWith(prefix)) {
                    continue;
                }
                String name = imported.substring(prefix.length());
                if (calling.contains(name)) {
                    offenders.add(source.layer() + "." + source.name() + " -> " + SURFACE + "."
                        + name);
                }
            }
        }
        return offenders;
    }

    private static boolean importsDomainService(Source source) {
        String prefix = SourceTree.BASE + "." + DOMAIN + ".";
        return source.imports().stream()
            .anyMatch(i -> i.startsWith(prefix) && i.endsWith("Service")
                && i.indexOf('.', prefix.length()) < 0);
    }

    private static boolean names(Source source, String simpleName) {
        return Pattern.compile("\\b" + simpleName + "\\b").matcher(source.code()).find();
    }
}
