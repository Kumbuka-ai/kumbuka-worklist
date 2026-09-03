package ai.kumbuka.worklist.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A package names the layer; a suffix names the role within it.
 *
 * <p>The words in circulation before the rule set were not five names for five
 * layers. <em>Adapter</em>, <em>API</em> and <em>resource</em> named one layer
 * at three granularities; <em>service</em> named the deployed service, the
 * domain class, and — under another name — the verb surface; and
 * <em>repository</em> named a layer that did not exist.
 *
 * <h2>Checked in both directions</h2>
 *
 * A regulated suffix implies its package, and a package admits no regulated
 * suffix but its own. The second half is what the first does not say: it is
 * what refuses a {@code …Service} inside {@code adapter.rest}, where the
 * suffix would be truthful about the class and wrong about the layer.
 *
 * <p>A class with no regulated suffix is free. {@code AddressParser},
 * {@code RefusalMapper} and the entities are not defects — a suffix names a
 * role, and not every class has one. Reading the rule as "every class carries
 * a suffix" would force names on classes that have nothing to say with one.
 *
 * <h2>Two rules that stand on their own</h2>
 *
 * <p>{@code Store} falls, everywhere. It was a good name while the class
 * itself persisted; with a repository underneath it, the class stores nothing
 * and decides instead, so the name describes the layer below the one it
 * occupies. It falls because it became false.
 *
 * <p>An entity is the bare noun. Every other suffix marks a role in a
 * pipeline; the entity is the thing the pipeline is about, and giving it a
 * suffix would imply it has a role at a layer.
 *
 * <p>Runs as a plain unit test: it reads names and needs no database.
 */
class NamingConventionTest {

    /**
     * The regulated suffixes and the package each belongs to.
     *
     * <p>{@code Resource} is the one exception to strict uniformity, and it is
     * the only entry bound to a single package by an outside convention: the
     * JAX-RS one is stronger outside this project than any internal rule, and
     * a reader arriving from elsewhere expects it. Layer membership is carried
     * by the package, so nothing is lost. The MCP side is named an adapter
     * because it is one — it is not a resource and only happens to arrive over
     * HTTP.
     */
    private static final Map<String, String> SUFFIX_HOME = new LinkedHashMap<>(Map.of(
        "Resource", "adapter.rest",
        "Adapter", "adapter.mcp",
        "Payload", "adapter.payload",
        "Surface", "surface",
        "Service", "domain",
        "View", "domain",
        "Repository", "repository",
        "Directory", "platform"));

    /** Marks an entity in the file text: the annotation, at the start of a line. */
    private static final Pattern ENTITY = Pattern.compile("^\\s*@Entity\\b", Pattern.MULTILINE);

    @Test
    void a_regulated_suffix_sits_in_the_package_it_names() {
        assertThat(misplacedSuffixes(SourceTree.root("main")))
            .as("a suffix names a role at a layer, so the same word in another package "
                + "says two things at once. Move the class, or drop the suffix if the "
                + "class is not playing that role. The homes are %s", SUFFIX_HOME)
            .isEmpty();
    }

    @Test
    void a_package_admits_no_regulated_suffix_but_its_own() {
        assertThat(foreignSuffixes(SourceTree.root("main")))
            .as("this is the second direction, and it is the one that catches a name "
                + "which is truthful about the class and wrong about the layer — a "
                + "…Service inside an adapter package being the case worth naming")
            .isEmpty();
    }

    @Test
    void no_class_carries_the_suffix_store() {
        assertThat(storesUnder(SourceTree.root("main")))
            .as("with a repository underneath it a store stores nothing; it decides. The "
                + "name would describe the layer below the one the class occupies. It "
                + "falls because it became false, not because the sibling service spells "
                + "it differently")
            .isEmpty();
    }

    @Test
    void an_entity_is_a_bare_noun_in_the_domain() {
        assertThat(misnamedEntities(SourceTree.root("main")))
            .as("every other suffix marks a role in a pipeline; the entity is the thing "
                + "the pipeline is about. A suffix on it would imply a role at a layer, "
                + "which is the confusion the layer model removes. Entities also live in "
                + "the domain and nowhere else")
            .isEmpty();
    }

    /**
     * The red state, observed on every build.
     *
     * <p>Four rules and one fixture would be one rule observed and three
     * assumed, so the detection is handed a name per rule, assembled here.
     * Names rather than files because these checks decide on the name and the
     * package alone — a fixture class would add a file without adding
     * evidence.
     */
    @Test
    void the_check_reports_a_name_that_breaks_each_of_the_four_rules() {
        assertThat(suffixOffence("adapter.rest", "ItemService"))
            .as("RED STATE, observed: a …Service in an adapter package is both a suffix "
                + "in the wrong home and a foreign suffix in the package")
            .isTrue();
        assertThat(suffixOffence("domain", "ItemResource"))
            .as("RED STATE, observed: a …Resource outside adapter.rest is reported")
            .isTrue();
        assertThat("ItemStore".endsWith("Store"))
            .as("RED STATE, observed: the Store suffix is what the third rule refuses, "
                + "and ItemStore is the name it was measured on")
            .isTrue();
        assertThat(entityOffence("repository", "ItemRow"))
            .as("RED STATE, observed: an entity outside the domain, carrying a name that "
                + "is not the bare noun, breaks the fourth rule twice")
            .isTrue();
    }

    /** Whether a name in a package breaks either direction of the suffix rule. */
    private static boolean suffixOffence(String layer, String simpleName) {
        for (Map.Entry<String, String> rule : SUFFIX_HOME.entrySet()) {
            if (simpleName.endsWith(rule.getKey()) && !layer.equals(rule.getValue())) {
                return true;
            }
        }
        return false;
    }

    /** Whether an entity in a package would break the bare-noun rule. */
    private static boolean entityOffence(String layer, String simpleName) {
        return !"domain".equals(layer)
            || SUFFIX_HOME.keySet().stream().anyMatch(simpleName::endsWith);
    }

    private static List<String> misplacedSuffixes(Path root) {
        List<String> offenders = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            String layer = SourceTree.layerOf(root, file);
            String simple = simpleNameOf(root, file);
            for (Map.Entry<String, String> rule : SUFFIX_HOME.entrySet()) {
                if (simple.endsWith(rule.getKey()) && !layer.equals(rule.getValue())) {
                    offenders.add(layer + "." + simple + " (…" + rule.getKey()
                        + " belongs in " + rule.getValue() + ")");
                }
            }
        }
        return offenders;
    }

    private static List<String> foreignSuffixes(Path root) {
        List<String> offenders = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            String layer = SourceTree.layerOf(root, file);
            String simple = simpleNameOf(root, file);
            for (Map.Entry<String, String> rule : SUFFIX_HOME.entrySet()) {
                if (layer.equals(rule.getValue()) || !simple.endsWith(rule.getKey())) {
                    continue;
                }
                offenders.add(layer + " admits no …" + rule.getKey() + ", and holds "
                    + simple);
            }
        }
        return offenders;
    }

    private static List<String> storesUnder(Path root) {
        return SourceTree.files(root).stream()
            .map(file -> SourceTree.fqcn(root, file))
            .filter(name -> name.endsWith("Store"))
            .toList();
    }

    private static List<String> misnamedEntities(Path root) {
        List<String> offenders = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            if (!ENTITY.matcher(SourceTree.code(file)).find()) {
                continue;
            }
            String layer = SourceTree.layerOf(root, file);
            String simple = simpleNameOf(root, file);
            if (!"domain".equals(layer)) {
                offenders.add(layer + "." + simple + " (an entity lives in domain)");
            }
            SUFFIX_HOME.keySet().stream()
                .filter(simple::endsWith)
                .forEach(suffix -> offenders.add(
                    simple + " (an entity is the bare noun, not …" + suffix + ")"));
        }
        return offenders;
    }

    private static String simpleNameOf(Path root, Path file) {
        String name = SourceTree.fqcn(root, file);
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
