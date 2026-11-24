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
 * A request travels adapter, entry class, domain service, repository, and
 * nothing stands between the adapter and the entry class (C1.1). The entry
 * classes are two: the verb surface, and the administration surface through
 * which a scope's vocabulary is kept (C1.6); the MCP adapter reaches the
 * second never (C4.8).
 *
 * <h2>What "a class that makes a call" is, read from the tree</h2>
 *
 * A class in {@code surface} makes a call when it reaches the transactional
 * domain service: it imports a {@code domain} class whose name ends in {@code
 * Service}, or it names an entry class itself. The two entry classes are the
 * only such classes the rule admits. Any other is a further layer between the
 * adapter and an entry class, whatever it is called, and three statements
 * follow:
 *
 * <ol>
 *   <li>no class in {@code surface} but the two entry classes reaches the
 *       domain service,</li>
 *   <li>no adapter reaches a class in {@code surface} that makes a call, the
 *       entry classes excepted, and</li>
 *   <li>no class in {@code adapter.mcp} reaches the administration surface:
 *       no tool administers a scope's vocabulary.</li>
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

    /** The class of the surface layer an adapter calls for a verb. */
    private static final String VERB_SURFACE = "VerbSurface";

    /** The class of the surface layer the REST adapter calls to keep a scope's vocabulary. */
    private static final String ADMINISTRATION_SURFACE = "AdministrationSurface";

    /** The entry classes C1.1 and C1.6 admit, written here from the contract. */
    private static final Set<String> ENTRY_CLASSES = Set.of(VERB_SURFACE, ADMINISTRATION_SURFACE);

    /** The layer of the MCP adapter, which reaches the verb surface alone (C4.8). */
    private static final String MCP_ADAPTER = "adapter.mcp";

    private static final String SURFACE = "surface";
    private static final String DOMAIN = "domain";
    private static final String ADAPTER = "adapter";

    /** One source file, as the detection reads it. */
    record Source(String layer, String name, List<String> imports, String code) {
    }

    @Test
    void no_class_of_the_surface_but_the_entry_classes_reaches_the_domain_service() {
        assertThat(surfaceClassesReachingTheDomainService(mainSources()))
            .as("the verb surface and the administration surface hold the check order and "
                + "open the transaction; a third class in the surface that reaches the domain "
                + "service is a third way in, and the check order no longer holds for every "
                + "call (C1.1, C1.6)")
            .isEmpty();
    }

    @Test
    void no_adapter_reaches_a_class_of_the_surface_that_makes_a_call() {
        assertThat(adaptersPassingTheEntryClasses(mainSources()))
            .as("the adapters call an entry class directly; a class between them is a "
                + "layer the contract does not have (C1.1). Move form checks into the "
                + "adapter and call the entry class from there")
            .isEmpty();
    }

    @Test
    void no_class_of_the_mcp_adapter_reaches_the_administration_surface() {
        assertThat(mcpClassesReachingTheAdministrationSurface(mainSources()))
            .as("no tool administers a scope's vocabulary: an agent working a scope cannot "
                + "change the scope's model through the surface it works on (C4.8, C1.6)")
            .isEmpty();
    }

    /**
     * The red state, observed on every build.
     *
     * <p>A tree assembled here: a router in the surface that reaches the
     * domain service and is called by an adapter, and an MCP adapter class
     * that calls the administration surface. Each statement must report its
     * offence, and the two entry classes beside them, the administration
     * surface called from the REST adapter, must stay unreported.
     */
    @Test
    void the_check_reports_a_router_between_adapter_and_entry_class() {
        String domainService = SourceTree.BASE + "." + DOMAIN + ".ThingService";
        String administration = SourceTree.BASE + "." + SURFACE + "." + ADMINISTRATION_SURFACE;
        List<Source> planted = List.of(
            new Source(SURFACE, VERB_SURFACE, List.of(domainService), "class VerbSurface {}"),
            new Source(SURFACE, ADMINISTRATION_SURFACE, List.of(domainService),
                "class AdministrationSurface {}"),
            new Source(SURFACE, "CallDispatcher", List.of(domainService),
                "class CallDispatcher { VerbSurface verbs; }"),
            new Source("adapter.rest", "ThingResource",
                List.of(SourceTree.BASE + ".surface.CallDispatcher"),
                "class ThingResource { CallDispatcher calls; }"),
            new Source("adapter.rest", "VocabularyResource", List.of(administration),
                "class VocabularyResource { AdministrationSurface administration; }"),
            new Source(MCP_ADAPTER, "ToolAdapter", List.of(administration),
                "class ToolAdapter { AdministrationSurface administration; }"));

        assertThat(surfaceClassesReachingTheDomainService(planted))
            .as("RED STATE, observed: a third class in the surface that reaches the domain "
                + "service must be reported, and the two entry classes must not be")
            .containsExactly("surface.CallDispatcher");
        assertThat(adaptersPassingTheEntryClasses(planted))
            .as("RED STATE, observed: an adapter calling that class instead of an entry "
                + "class must be reported")
            .containsExactly("adapter.rest.ThingResource -> surface.CallDispatcher");
        assertThat(mcpClassesReachingTheAdministrationSurface(planted))
            .as("RED STATE, observed: an MCP adapter class reaching the administration "
                + "surface must be reported, and the REST resource reaching it must not be")
            .containsExactly("adapter.mcp.ToolAdapter -> surface.AdministrationSurface");
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
        assertThat(sources.stream().filter(s -> s.name().equals(ADMINISTRATION_SURFACE)))
            .as("and the administration surface, or the MCP clause checks nothing")
            .hasSize(1);
        assertThat(sources.stream().filter(s -> s.layer().equals(MCP_ADAPTER)))
            .as("and the MCP adapter's classes, for the same reason")
            .isNotEmpty();
        return sources;
    }

    /** Surface classes other than the entry classes that make a call. */
    static List<String> surfaceClassesReachingTheDomainService(List<Source> sources) {
        return sources.stream()
            .filter(s -> s.layer().equals(SURFACE) && !ENTRY_CLASSES.contains(s.name()))
            .filter(s -> importsDomainService(s) || namesAnEntryClass(s))
            .map(s -> s.layer() + "." + s.name())
            .toList();
    }

    /** Each adapter that reaches a calling surface class other than an entry class. */
    static List<String> adaptersPassingTheEntryClasses(List<Source> sources) {
        Set<String> calling = Set.copyOf(sources.stream()
            .filter(s -> s.layer().equals(SURFACE) && !ENTRY_CLASSES.contains(s.name()))
            .filter(s -> importsDomainService(s) || namesAnEntryClass(s))
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

    /** Each class of the MCP adapter that imports or names the administration surface. */
    static List<String> mcpClassesReachingTheAdministrationSurface(List<Source> sources) {
        String administration = SourceTree.BASE + "." + SURFACE + "." + ADMINISTRATION_SURFACE;
        return sources.stream()
            .filter(s -> s.layer().equals(MCP_ADAPTER))
            .filter(s -> s.imports().contains(administration) || names(s, ADMINISTRATION_SURFACE))
            .map(s -> s.layer() + "." + s.name() + " -> " + SURFACE + "." + ADMINISTRATION_SURFACE)
            .toList();
    }

    private static boolean namesAnEntryClass(Source source) {
        return ENTRY_CLASSES.stream().anyMatch(entry -> names(source, entry));
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
