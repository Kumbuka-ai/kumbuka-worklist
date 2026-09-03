package ai.kumbuka.worklist.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The direction between the layers, enforced rather than intended.
 *
 * <p>Nothing enforced any package direction before this class existed, and the
 * measured consequence was two defects that nobody chose: an import cycle
 * between a package and its own subpackage, and a verb surface sitting inside
 * the adapter package it is supposed to be independent of. Neither is a lapse
 * of care. They are what happens when a boundary exists only in the heads of
 * the people who drew it.
 *
 * <h2>Three statements, and why they are three</h2>
 *
 * <p><strong>Tenancy imports nothing of its own service.</strong> It is an
 * aspect that cuts across every layer, and its eleven classes are the ones
 * that carry the private-memory and tenant-isolation guarantees. An import
 * from tenancy into a layer would put the aspect below something, which is a
 * position it does not have.
 *
 * <p><strong>The verb surface imports no adapter.</strong> That is what
 * "protocol-neutral" means, and it is the half of the rule with a measurement
 * behind it: the surface was carved out of the adapter package and left
 * holding the adapter's wire types, so a change to a published payload shape
 * would have reached into the check order.
 *
 * <p><strong>No cycles, with one named exception.</strong> The exception is
 * {@code domain} and {@code repository} and it is structural rather than
 * accidental — see {@link #ENTITY_COUPLING}.
 *
 * <p>Runs as a plain unit test: it reads imports and needs no database.
 */
class PackageDirectionTest {

    /**
     * The one cycle that is allowed, and the reason it cannot be removed by
     * moving anything.
     *
     * <p>The rule set says entities live only in {@code domain} and that JPA
     * lives only in {@code repository}. A repository therefore has to name the
     * entity types it reads, and the domain service has to name the repository
     * it reads through. Two rules of the same file produce the edge in both
     * directions, and no arrangement of the existing classes removes it:
     * breaking it needs an interface in {@code domain} that {@code repository}
     * implements, and such an interface would carry the {@code …Repository}
     * suffix in a package the suffix rule reserves for another.
     *
     * <p>It is recorded as a decision rather than hidden by a weaker check.
     * Every OTHER cycle is still a defect, which is what makes this list worth
     * having rather than a hole.
     */
    private static final Set<String> ENTITY_COUPLING = Set.of("domain", "repository");

    @Test
    void tenancy_imports_no_package_of_its_own_service() {
        assertThat(importsFrom(SourceTree.root("main")).getOrDefault("tenancy", Set.of()))
            .as("tenancy is an aspect and not a layer: it cuts across all four and its "
                + "classes are byte-identical across the platform's services. An import "
                + "into a layer would give it a position in the stack it does not have, "
                + "and would make the copied set stop being copyable")
            .isEmpty();
    }

    @Test
    void the_verb_surface_imports_no_adapter() {
        assertThat(adapterImportsOfSurface(SourceTree.root("main")))
            .as("the verb surface is protocol-neutral, and an import from an adapter is "
                + "exactly what that denies. Measured before this guard existed: the "
                + "surface carried the adapter's wire shapes in its own signatures, so "
                + "the published payload contract reached into the check order. Give the "
                + "surface its own input types and let the adapter translate")
            .isEmpty();
    }

    @Test
    void the_package_graph_is_acyclic_apart_from_the_entity_coupling() {
        assertThat(cyclesIn(importsFrom(SourceTree.root("main"))))
            .as("a cycle means neither package can be read, changed or moved without the "
                + "other, which is the opposite of what the layers are for. The one "
                + "exception is %s, which two rules of the rule set produce between them "
                + "and no arrangement of classes removes", ENTITY_COUPLING)
            .isEmpty();
    }

    /**
     * The red state, observed on every build.
     *
     * <p>The detection is handed a graph that is known to hold a cycle,
     * assembled here rather than found in the tree. A red state that waited
     * for a real cycle to appear would be a check nobody had ever seen work,
     * and the point of the exception list above is precisely that a wrong list
     * silences the check without anything going red.
     */
    @Test
    void the_check_reports_a_cycle_that_is_not_the_entity_coupling() {
        Map<String, Set<String>> planted = new LinkedHashMap<>();
        planted.put("surface", new LinkedHashSet<>(Set.of("adapter.rest")));
        planted.put("adapter.rest", new LinkedHashSet<>(Set.of("surface")));

        assertThat(cyclesIn(planted))
            .as("RED STATE, observed: a surface that imports an adapter which imports it "
                + "back is a cycle and must be reported. An empty list here would mean "
                + "the assertion above is measuring nothing")
            .isNotEmpty();
    }

    /** Which packages each package imports from, over one source root. */
    private static Map<String, Set<String>> importsFrom(Path root) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (Path file : SourceTree.files(root)) {
            String from = SourceTree.layerOf(root, file);
            if (from.isEmpty()) {
                continue;
            }
            Set<String> targets = graph.computeIfAbsent(from, key -> new LinkedHashSet<>());
            for (String imported : SourceTree.ownImports(file)) {
                String to = packageOfImport(imported);
                if (!to.isEmpty() && !to.equals(from)) {
                    targets.add(to);
                }
            }
        }
        return graph;
    }

    /**
     * The package an import names, relative to the service root.
     *
     * <p>The last dotted segment of an import is the class, so everything
     * before it is the package. A nested type would add a segment; none is
     * imported here and one would show up as an unknown package rather than
     * being silently folded into its outer one.
     */
    private static String packageOfImport(String imported) {
        String rest = imported.substring(SourceTree.BASE.length() + 1);
        int lastDot = rest.lastIndexOf('.');
        return lastDot < 0 ? "" : rest.substring(0, lastDot);
    }

    /** Every adapter package the surface imports from. */
    private static List<String> adapterImportsOfSurface(Path root) {
        return importsFrom(root).getOrDefault("surface", Set.of()).stream()
            .filter(target -> target.startsWith("adapter"))
            .sorted()
            .toList();
    }

    /**
     * Every pair of packages that import each other, minus the named
     * exception.
     *
     * <p>Mutual imports rather than cycles of arbitrary length. A longer cycle
     * is a real defect too, and this deliberately does not claim to find one:
     * the two measured cases were both mutual, a pair is what a reader can act
     * on, and a check that reported "these six packages form a cycle" would
     * name no line to change. The gap is written down rather than papered
     * over.
     */
    private static List<String> cyclesIn(Map<String, Set<String>> graph) {
        List<String> cycles = new ArrayList<>();
        for (Map.Entry<String, Set<String>> edge : graph.entrySet()) {
            String from = edge.getKey();
            for (String to : edge.getValue()) {
                if (from.compareTo(to) >= 0) {
                    continue;
                }
                if (graph.getOrDefault(to, Set.of()).contains(from)
                        && !ENTITY_COUPLING.equals(Set.of(from, to))) {
                    cycles.add(from + " <-> " + to);
                }
            }
        }
        return cycles;
    }
}
