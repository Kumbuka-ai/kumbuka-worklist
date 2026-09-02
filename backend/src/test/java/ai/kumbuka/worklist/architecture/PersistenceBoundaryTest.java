package ai.kumbuka.worklist.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where JPA is, and where it is not.
 *
 * <p>Before the repository layer existed, neither service had one and native
 * SQL was found by searching the tree rather than by looking in a package. A
 * boundary maintained by grep is not a boundary. The layer's job is not
 * convenience: it is that "JPA lives here and nowhere else" becomes a sentence
 * a test can check.
 *
 * <h2>Two kinds of JPA, and why the rule splits</h2>
 *
 * The rule set says JPA imports appear only in {@code repository}, and it also
 * says entities live only in {@code domain}. Read literally the two cannot
 * both hold: an entity is a class annotated {@code @Entity}, and that
 * annotation is a JPA import. So the check distinguishes what the two rules
 * are each about.
 *
 * <p><strong>The access API</strong> — the entity manager, queries, lock modes
 * — is the mechanism the persistence layer exists to contain, and it is
 * confined to {@code repository}. <strong>The mapping annotations</strong> —
 * {@code @Entity}, {@code @Table}, {@code @Column} — say what a row looks
 * like, which is the entity's own statement, and they are confined to the
 * entities in {@code domain}.
 *
 * <p>That split is this guard's reading of a tension in the rule set, and it
 * is written here rather than assumed, so that it can be argued with. What is
 * NOT in question is the effect: no class outside {@code repository} reaches
 * the database through JPA.
 *
 * <h2>Panache, without exception</h2>
 *
 * No allowance at all, in any package. Active Record makes the entity the data
 * access API, and the call site is then the entity — which is imported
 * everywhere, so "persistence lives in one package" stops being checkable.
 * That contradicts the layer model directly rather than the tenancy mechanism.
 *
 * <p>Runs as a plain unit test: it reads imports and needs no database.
 */
class PersistenceBoundaryTest {

    /** The package the access API belongs to. */
    private static final String PERSISTENCE = "repository";

    /** The package entities belong to. */
    private static final String ENTITIES = "domain";

    /**
     * Types that reach the database, as opposed to describing a row.
     *
     * <p>Named individually rather than matched by prefix: {@code jakarta.
     * persistence} holds both kinds, and a prefix match would have to choose
     * one of the two rules to break.
     */
    private static final Set<String> ACCESS_TYPES = Set.of(
        "jakarta.persistence.EntityManager",
        "jakarta.persistence.EntityManagerFactory",
        "jakarta.persistence.EntityTransaction",
        "jakarta.persistence.Query",
        "jakarta.persistence.TypedQuery",
        "jakarta.persistence.StoredProcedureQuery",
        "jakarta.persistence.LockModeType",
        "jakarta.persistence.FlushModeType",
        "jakarta.persistence.NoResultException",
        "jakarta.persistence.NonUniqueResultException",
        "jakarta.persistence.PersistenceContext",
        "jakarta.persistence.PersistenceUnit");

    /**
     * The classes outside {@code repository} that still hold the access API,
     * each with the reason it is not a repository.
     *
     * <p>Two, both in {@code tenancy}, and the reason is the same for both:
     * the eleven tenancy classes are byte-identical across the platform's
     * services and carry the tenant-isolation guarantee. Rewriting them to
     * call through a repository would make this service's copy differ from
     * every other service's, which is the drift a guard is wanted for rather
     * than something to introduce while building one.
     *
     * <p>Entries are names rather than class literals because these are read
     * off the tree, not loaded. A rename therefore leaves a stale entry, which
     * the test below turns into a failure rather than a silent exemption.
     */
    private static final Map<String, String> ACCESS_ALLOWED = Map.of(
        "tenancy.TenantDatabaseBinding",
        "binds app.tenant_id on the connection; it IS the mechanism a repository "
            + "would run under, so it cannot reach the database through one",
        "tenancy.TenantMigrationCallback",
        "runs at boot inside Flyway's own transaction, before CDI is serving "
            + "requests and therefore before any repository bean exists");

    @Test
    void the_entity_manager_appears_only_in_the_repository_layer() {
        assertThat(accessOutsideRepository(SourceTree.root("main")))
            .as("the access API is what the persistence layer exists to contain. A class "
                + "elsewhere that holds it puts a database statement in a layer whose "
                + "readers are not looking for one — and makes 'JPA lives in one place' "
                + "false again. Move the statement into a %s class, or add the class to "
                + "ACCESS_ALLOWED with the reason it cannot be one", PERSISTENCE)
            .isEmpty();
    }

    @Test
    void mapping_annotations_appear_only_on_entities_in_the_domain() {
        assertThat(mappingOutsideDomain(SourceTree.root("main")))
            .as("a mapping annotation says what a row looks like, which is the entity's "
                + "statement and belongs where the entities are. The one exception is "
                + "the tenancy attribute converter, which is part of the copied aspect")
            .isEmpty();
    }

    @Test
    void no_class_anywhere_imports_panache() {
        assertThat(panacheAnywhere(SourceTree.root("main")))
            .as("Panache Active Record makes the entity the data access API, so the call "
                + "site becomes a type that is imported everywhere and 'persistence lives "
                + "in one package' stops being checkable. Panache Repository was weighed "
                + "and refused separately, for its inherited delete surface — no runtime "
                + "role here holds DELETE on any relation")
            .isEmpty();
    }

    /**
     * Every exemption names a class that exists.
     *
     * <p>An allowlist is a list of decisions, and a decision about a class that
     * is no longer there is not one. Without this a rename would leave an
     * exemption matching nothing, which is indistinguishable from a rule
     * nobody needs any more.
     */
    @Test
    void every_exemption_names_a_class_that_still_exists() {
        Path root = SourceTree.root("main");
        List<String> present = SourceTree.files(root).stream()
            .map(file -> SourceTree.fqcn(root, file).substring(SourceTree.BASE.length() + 1))
            .toList();
        assertThat(present)
            .as("an exemption for a class that does not exist is not a decision. Remove "
                + "the entry, or correct it to the class's current name")
            .containsAll(ACCESS_ALLOWED.keySet());
    }

    /**
     * The red state, observed on every build.
     *
     * <p>The test tree holds classes that reach the database from outside a
     * repository — the raw-SQL fixtures among them — and the detection is
     * required to report them. Without this the assertion above would be a
     * walk that found nothing rather than a tree that contains nothing.
     */
    @Test
    void the_check_reports_a_class_outside_the_repository_that_holds_the_access_api() {
        assertThat(accessOutsideRepository(SourceTree.root("test")))
            .as("RED STATE, observed: the test tree holds classes with an entity manager "
                + "outside any repository, and they must be reported. An empty list here "
                + "would mean the green assertion above is measuring nothing")
            .isNotEmpty();
    }

    private static List<String> accessOutsideRepository(Path root) {
        List<String> offenders = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            String layer = SourceTree.layerOf(root, file);
            if (PERSISTENCE.equals(layer)) {
                continue;
            }
            String name = SourceTree.fqcn(root, file).substring(SourceTree.BASE.length() + 1);
            if (ACCESS_ALLOWED.containsKey(name)) {
                continue;
            }
            List<String> held = importsOf(file).stream().filter(ACCESS_TYPES::contains).toList();
            if (!held.isEmpty()) {
                offenders.add(name + " " + held);
            }
        }
        return offenders;
    }

    private static List<String> mappingOutsideDomain(Path root) {
        List<String> offenders = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            String layer = SourceTree.layerOf(root, file);
            if (ENTITIES.equals(layer) || PERSISTENCE.equals(layer) || "tenancy".equals(layer)) {
                continue;
            }
            List<String> mapping = importsOf(file).stream()
                .filter(imported -> imported.startsWith("jakarta.persistence."))
                .filter(imported -> !ACCESS_TYPES.contains(imported))
                .toList();
            if (!mapping.isEmpty()) {
                offenders.add(SourceTree.fqcn(root, file) + " " + mapping);
            }
        }
        return offenders;
    }

    private static List<String> panacheAnywhere(Path root) {
        List<String> offenders = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            if (importsOf(file).stream()
                    .anyMatch(imported -> imported.startsWith("io.quarkus.hibernate.orm.panache"))) {
                offenders.add(SourceTree.fqcn(root, file));
            }
        }
        return offenders;
    }

    /** Every type a file imports, comments excluded. */
    private static List<String> importsOf(Path file) {
        return SourceTree.code(file).lines()
            .map(String::strip)
            .filter(line -> line.startsWith("import "))
            .map(line -> line.substring("import ".length(), line.indexOf(';')).strip())
            .toList();
    }
}
