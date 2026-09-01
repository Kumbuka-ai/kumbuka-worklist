package ai.kumbuka.worklist.tenancy;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tripwire on the second way a tenant binding goes missing.
 *
 * <p>{@link RawSqlArchitectureTest} watches the first: a class that issues
 * raw SQL without {@code @TenantBound}, which is invisible to the ORM filter
 * and therefore protected by nothing. This one watches the case where the
 * annotation IS there and does not fire.
 *
 * <h2>What goes wrong, and why it is quiet</h2>
 *
 * {@code @TenantBound} binds {@code app.tenant_id} on the connection INSIDE a
 * transaction — that is where a connection exists to bind it on. A public
 * method with no {@code @Transactional} gets a connection per statement and
 * no binding, so the policy predicate is NULL, and the policy treats that as
 * failing. The read returns nothing.
 *
 * <p>Nothing raises. An empty result is a perfectly ordinary answer, and it
 * is indistinguishable from "there is nothing there" — which is exactly what
 * a lookup method concludes. This was measured in this repository while the
 * item domain was being built: {@code SelectorRegistry.require} raised
 * {@code SELECTOR_UNDECLARED} for a selector that had just been declared
 * successfully, and the message said, with complete confidence, that the
 * caller should declare it first. A typed refusal naming the wrong cause is
 * worse than an untyped one, because it is believed.
 *
 * <h2>The rule this enforces, and why it is the blunt version</h2>
 *
 * Every public instance method of a {@code @TenantBound} class that holds an
 * {@link EntityManager} must carry {@code @Transactional}. Not "every method
 * that touches the entity manager" — deciding that from outside would mean
 * reading method bodies and guessing, and a guard that guesses is one that
 * can be argued with. A method of such a class that genuinely needs no
 * database is static, or belongs somewhere else.
 *
 * <p>Runs as a plain unit test: it reads class files and needs no database.
 */
class TransactionalReadArchitectureTest {

    @Test
    void every_public_method_of_a_tenant_bound_store_carries_a_transaction()
            throws IOException {
        assertThat(offendersUnder(sourceRoot("main")))
            .as("a @TenantBound class reaches the database through a transaction, because "
                + "that is where the session binding lives. A public method without one "
                + "reads under no tenant at all and gets an empty answer that raises "
                + "nothing — and an empty answer is what 'it does not exist' looks like. "
                + "Add @Transactional, or make the method static if it needs no database")
            .isEmpty();
    }

    /**
     * The red state, observed on every build.
     *
     * <p>{@code UnboundReadFixture} is a real violation: bound, holding an
     * entity manager, and public without a transaction. Without this case the
     * assertion above would be a query that finds nothing rather than a tree
     * that contains nothing.
     */
    @Test
    void the_tripwire_catches_a_bound_class_whose_read_has_no_transaction()
            throws IOException {
        assertThat(offendersUnder(sourceRoot("test")))
            .as("RED STATE, observed: the fixture is @TenantBound, holds an EntityManager "
                + "and exposes a public method with no transaction. An empty list here "
                + "would mean the green assertion above measures nothing")
            .anyMatch(offender -> offender.contains("UnboundReadFixture"));
    }

    /**
     * Every public instance method of a bound, database-holding class that
     * carries no transaction — each named with its class.
     *
     * <p>Parameterised on the root so the same detection serves both the
     * assertion and its red state. A red state exercising a second copy of
     * the logic would prove that the copy works.
     */
    private static List<String> offendersUnder(Path root) throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files
                    .filter(f -> f.toString().endsWith(".java"))::iterator) {
                Class<?> clazz = loadClass(root, file);
                if (clazz == null
                    || !clazz.isAnnotationPresent(TenantBound.class)
                    || !holdsAnEntityManager(clazz)) {
                    continue;
                }
                for (Method method : clazz.getDeclaredMethods()) {
                    if (isUnguardedPublicInstanceMethod(clazz, method)) {
                        offenders.add(clazz.getName() + "." + method.getName()
                            + " (public, reaches the database, no @Transactional)");
                    }
                }
            }
        }
        return offenders;
    }

    /**
     * Whether the class can reach the database at all.
     *
     * <p>A {@code @TenantBound} class with no entity manager has nothing for
     * a missing transaction to affect, and demanding one of it would be a
     * rule about annotations rather than about behaviour.
     */
    private static boolean holdsAnEntityManager(Class<?> clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            if (EntityManager.class.isAssignableFrom(field.getType())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnguardedPublicInstanceMethod(Class<?> clazz, Method method) {
        int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers)
            || method.isSynthetic()) {
            return false;
        }
        // A class-level @Transactional covers every method, exactly as a
        // class-level @TenantBound does. Checked on the applied annotation
        // rather than on the file text, so a mention in a comment counts for
        // nothing.
        return !clazz.isAnnotationPresent(Transactional.class)
            && !method.isAnnotationPresent(Transactional.class);
    }

    /** The module's source root for a source set, whichever directory the build runs from. */
    private static Path sourceRoot(String sourceSet) {
        Path direct = Paths.get("src", sourceSet, "java");
        Path fromRepoRoot = Paths.get("backend", "src", sourceSet, "java");
        Path root = Files.isDirectory(direct) ? direct : fromRepoRoot;
        assertThat(Files.isDirectory(root))
            .as("source root %s must exist — run from the module directory", root)
            .isTrue();
        return root;
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
     * from a unit test would drag in CDI.
     */
    private static Class<?> loadClass(Path root, Path file) {
        try {
            return Class.forName(fqcn(root, file), false,
                TransactionalReadArchitectureTest.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError notLoadable) {
            return null;
        }
    }
}
