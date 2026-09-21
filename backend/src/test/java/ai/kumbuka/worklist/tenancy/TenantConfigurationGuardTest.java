package ai.kumbuka.worklist.tenancy;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deployment's tenant is mandatory, and the service will not start
 * without it.
 *
 * <h2>Three things have to hold, and each is a separate case</h2>
 *
 * The property has to ARRIVE unusable when nobody supplied it — otherwise a
 * guard that refuses an unusable value guards nothing. The guard has to REFUSE
 * each unusable shape. And it has to run AT START — a condition that is only
 * reached on the first request is not a refusal to start.
 *
 * <p>They are separate because the failure modes are separate, and a single
 * case covering all three would go green again if any one of them was quietly
 * restored. Putting a default back in the properties file is the one the
 * dispatch names as a red probe, and it is
 * {@link #the_key_carries_no_usable_default} alone that turns red for it.
 *
 * <h2>Why the start is not observed by starting</h2>
 *
 * An application that refuses to start cannot be observed from a
 * {@code @QuarkusTest}: the refusal fails the class rather than a case, and
 * there is no assertion left to make. Quarkus' own prod-mode harness can
 * assert a start-up failure, and it is not used here for a reason that would
 * make the case dishonest: it would start against no database, so the
 * application would fail at the Flyway datasource whatever this guard did, and
 * the assertion could not tell the two apart.
 *
 * <p>So the start is established from its three parts instead, and the seam
 * between them is named rather than papered over: what is NOT established here
 * is that Quarkus fires {@link StartupEvent} — that is the framework's, and
 * every observer in every Quarkus application rests on it.
 *
 * <p>Runs as a plain unit test: it reads a file, a method signature, and a
 * static condition. None of the three needs an application.
 */
class TenantConfigurationGuardTest {

    /** The main properties file, which is the one a deployment reads. */
    private static final Path PROPERTIES =
        Path.of("src/main/resources/application.properties");

    // =======================================================================
    // 1. What arrives when nobody supplied the variable
    // =======================================================================

    /**
     * The key carries the sentinel and no usable value.
     *
     * <p>RED PROBE (dispatch 187.4, probe 1): put the uuid default back —
     * {@code worklist.tenant-id=${WORKLIST_TENANT_ID:00000000-...-000000000001}}
     * — and this case goes red on the second assertion, because the expression
     * default is then a uuid. Observed on 2026-09-21.
     */
    @Test
    void the_key_carries_no_usable_default() throws Exception {
        String line = lineOf(TenantConfigurationGuard.KEY);

        assertThat(line)
            .as("the key has to be declared at all; a key that is absent from the "
                + "properties file is one the guard never sees a value for")
            .isNotNull()
            .as("an unsupplied WORKLIST_TENANT_ID must resolve to the sentinel, which "
                + "is what the guard refuses. Any other expression default is a tenant "
                + "id this service picked for a deployment that did not")
            .contains("${" + TenantConfigurationGuard.ENV_VAR + ":"
                + TenantConfigurationGuard.SENTINEL + "}");

        assertThatThrownBy(() -> TenantConfigurationGuard.requireUsable(
                expressionDefaultOf(line)))
            .as("and the value that expression yields must be one the guard refuses. "
                + "This is the assertion that closes the gap between 'the guard refuses "
                + "X' and 'X is what arrives when nobody configured anything'")
            .isInstanceOf(TenantConfigurationGuard.TenantNotConfigured.class);
    }

    /**
     * The red state of the detection, observed on every build.
     *
     * <p>The line below is the one this key carried until 2026-09-21, copied
     * out of it, and BOTH halves of the detection are watched against it: the
     * line check must report it, and the value its expression yields must sail
     * through the guard. The second half is the defect itself — a service that
     * starts, migrates, and serves requests on a tenant nobody chose — and
     * without it the assertions above would hold against a guard that had
     * simply never met a real default.
     *
     * <p>Marked rather than described because this file's own subject is the
     * difference between a claim and an observation: a comment saying the red
     * run was performed once is exactly what the guard over guards refuses.
     */
    @Test
    void the_check_reports_the_default_this_key_carried_before() {
        String previous =
            "worklist.tenant-id=${WORKLIST_TENANT_ID:00000000-0000-0000-0000-000000000001}";

        assertThat(previous.contains("${" + TenantConfigurationGuard.ENV_VAR + ":"
                + TenantConfigurationGuard.SENTINEL + "}"))
            .as("RED STATE, observed: the line check must REPORT the uuid default this "
                + "key carried until 2026-09-21. If it accepted it, the case above "
                + "would pass against the very configuration this pass removed")
            .isFalse();

        assertThatCode(() -> TenantConfigurationGuard.requireUsable(
                expressionDefaultOf(previous)))
            .as("RED STATE, observed: and the value that line yields passes the guard "
                + "untouched — which IS the defect. A guessed tenant id is enforced "
                + "perfectly by the ORM filter, the GUC and the policy, on the wrong "
                + "tenant, and nothing anywhere reports it")
            .doesNotThrowAnyException();
    }

    // =======================================================================
    // 2. What the guard refuses
    // =======================================================================

    @Test
    void an_unsupplied_variable_is_refused_and_the_message_names_the_key() {
        assertThatThrownBy(() -> TenantConfigurationGuard.requireUsable(
                TenantConfigurationGuard.SENTINEL))
            .isInstanceOf(TenantConfigurationGuard.TenantNotConfigured.class)
            .as("the operator reading this line has to be told which variable to set "
                + "and which key it feeds; a refusal that names neither sends them "
                + "looking through the image for it")
            .hasMessageContaining(TenantConfigurationGuard.ENV_VAR)
            .hasMessageContaining(TenantConfigurationGuard.KEY);
    }

    /**
     * An empty variable is not an unset one.
     *
     * <p>This is the case a plain {@code ${WORKLIST_TENANT_ID}} would NOT
     * catch: SmallRye counts an empty environment variable as supplied, so the
     * expression default does not apply and the empty string reaches the
     * service. Measured in the platform repository against smallrye-config
     * 3.16.0 on 2026-09-20, on the audience key, and it is the shape
     * {@code docker compose config} produces for an unset variable.
     */
    @Test
    void an_empty_value_is_refused_and_the_message_names_the_key() {
        for (String empty : new String[] {"", "   ", null}) {
            assertThatThrownBy(() -> TenantConfigurationGuard.requireUsable(empty))
                .as("value %s", empty == null ? "null" : "'" + empty + "'")
                .isInstanceOf(TenantConfigurationGuard.TenantNotConfigured.class)
                .hasMessageContaining(TenantConfigurationGuard.ENV_VAR)
                .hasMessageContaining(TenantConfigurationGuard.KEY);
        }
    }

    @Test
    void a_value_that_is_not_a_uuid_is_refused() {
        assertThatThrownBy(() -> TenantConfigurationGuard.requireUsable("tenant-alpha"))
            .as("a tenant id is a uuid everywhere else in this service — the column, "
                + "the GUC, the policy — so a value that is not one would be refused "
                + "by the database instead, at the first statement of the first request")
            .isInstanceOf(TenantConfigurationGuard.TenantNotConfigured.class)
            .hasMessageContaining("tenant-alpha");
    }

    /**
     * The counter-probe.
     *
     * <p>Without it every assertion above would hold against a guard that
     * refused everything, which would refuse every deployment as well.
     */
    @Test
    void a_configured_uuid_passes_and_is_the_tenant_that_comes_back() {
        UUID tenant = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertThat(TenantConfigurationGuard.requireUsable(tenant.toString()))
            .isEqualTo(tenant);
        assertThat(TenantConfigurationGuard.requireUsable("  " + tenant + "  "))
            .as("a value that arrived with whitespace around it is the same tenant; "
                + "an operator pasting one into a compose file should not be refused "
                + "for a trailing space")
            .isEqualTo(tenant);
    }

    // =======================================================================
    // 3. That the condition runs at start
    // =======================================================================

    /**
     * The guard observes {@link StartupEvent}, and the observation is read off
     * the method rather than assumed.
     *
     * <p>This service already carries the lesson: a Flyway callback written as
     * a bean and left out of its configuration key is never registered, with
     * no warning and no error — see {@code TenantMigrationCallback}. An
     * observer is registered by its parameter annotation, and a parameter that
     * lost it would leave a class that looks exactly like this one and runs at
     * no point in the lifecycle.
     */
    @Test
    void the_guard_runs_on_the_startup_event() throws Exception {
        Method onStart = TenantConfigurationGuard.class
            .getDeclaredMethod("onStart", StartupEvent.class);
        Parameter event = onStart.getParameters()[0];

        assertThat(event.getAnnotation(Observes.class))
            .as("without @Observes this class is a bean nothing ever calls, and the "
                + "service would start on a tenant it refused — silently, which is the "
                + "failure mode this whole guard exists against")
            .isNotNull();
    }

    /**
     * The observer applies the same condition, on the same value.
     *
     * <p>Instantiated directly rather than injected: the point is that
     * {@code onStart} routes to {@link TenantConfigurationGuard#requireUsable},
     * and a container-managed instance would carry the test profile's
     * (perfectly valid) configuration, which is the one value that proves
     * nothing.
     */
    @Test
    void the_observer_refuses_the_unusable_value_it_is_given() {
        TenantConfigurationGuard guard = new TenantConfigurationGuard();

        guard.tenantId = TenantConfigurationGuard.SENTINEL;
        assertThatThrownBy(() -> guard.onStart(null))
            .isInstanceOf(TenantConfigurationGuard.TenantNotConfigured.class);

        guard.tenantId = "00000000-0000-0000-0000-000000000001";
        assertThatCode(() -> guard.onStart(null))
            .as("and it lets a configured tenant through, which is what keeps the "
                + "assertion above about the value rather than about the observer")
            .doesNotThrowAnyException();
    }

    // =======================================================================

    /** The whole line declaring a key, or null. */
    private static String lineOf(String key) throws Exception {
        return Files.readAllLines(PROPERTIES).stream()
            .filter(line -> line.startsWith(key + "="))
            .findFirst()
            .orElse(null);
    }

    /**
     * What the expression on a property line yields when the variable is not
     * supplied — the text after the colon, up to the closing brace.
     */
    private static String expressionDefaultOf(String line) {
        int colon = line.indexOf(':');
        int brace = line.lastIndexOf('}');
        return colon < 0 || brace < colon ? "" : line.substring(colon + 1, brace);
    }
}
