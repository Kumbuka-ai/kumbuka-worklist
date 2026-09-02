package ai.kumbuka.worklist.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The eight packages, and nothing beside them.
 *
 * <p>The layer model is only worth having if the tree says which layer a class
 * is in. A ninth package does not break a build; it makes the question "which
 * layer is this" unanswerable from the path, and from then on layer membership
 * is something a reader infers rather than reads.
 *
 * <h2>Existence is not required, only membership</h2>
 *
 * A service may be missing a package. The worklist has no verb surface yet,
 * and that is deliberate: it is left unbuilt so that the rule set gets a first
 * consumer rather than another input. An empty {@code surface} is therefore not
 * a defect. What is a defect is a package that is not one of the eight.
 *
 * <p>The check runs over {@code src/main}. Test sources carry fixtures,
 * probes and the guards themselves, which are not layers of the service and
 * have no business being named as if they were.
 *
 * <p>Runs as a plain unit test: it reads paths and needs no database.
 */
class PackageStructureTest {

    /**
     * The eight, as the rule set names them.
     *
     * <p>A literal list rather than a scan of the tree. An expectation derived
     * from the thing under test is internally consistent and proves nothing —
     * a tree scanned for its own packages would accept any tree at all.
     */
    private static final Set<String> LAYERS = Set.of(
        "adapter.rest", "adapter.mcp", "adapter.payload",
        "surface", "domain", "repository", "platform", "tenancy");

    @Test
    void every_package_under_the_service_root_is_one_of_the_eight() {
        assertThat(strangersUnder(SourceTree.root("main")))
            .as("the package names the layer, and a package outside the eight leaves a "
                + "class with no layer a reader can read off the path. The eight are "
                + "%s. Move the class into the layer it belongs to, or make the case "
                + "for a ninth layer in the rule set first — the rule set is where a "
                + "layer comes into existence, not the tree", LAYERS)
            .isEmpty();
    }

    /**
     * The red state, observed on every build.
     *
     * <p>The test tree carries packages that are deliberately not layers —
     * {@code fixture}, {@code boundary}, {@code architecture} among them — and
     * the detection is required to report them. Without this the assertion
     * above would be a walk that found nothing rather than a tree that
     * contains nothing, and the two look identical from the outside.
     *
     * <p>This is the same detection the green case uses, applied to another
     * root. A red state exercising a second copy of the logic would prove that
     * the copy works.
     */
    @Test
    void the_check_reports_a_package_that_is_not_a_layer() {
        assertThat(strangersUnder(SourceTree.root("test")))
            .as("RED STATE, observed: the test tree holds packages that are not layers, "
                + "and they must be reported. An empty list here would mean the green "
                + "assertion above is measuring nothing")
            .isNotEmpty();
    }

    /** Every package under the service root that is not one of the eight. */
    private static Set<String> strangersUnder(Path root) {
        Set<String> strangers = new LinkedHashSet<>();
        List<Path> files = SourceTree.files(root);
        for (Path file : files) {
            String layer = SourceTree.layerOf(root, file);
            if (!layer.isEmpty() && !LAYERS.contains(layer)) {
                strangers.add(layer);
            }
        }
        return strangers;
    }
}
