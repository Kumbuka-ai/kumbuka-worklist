package ai.kumbuka.worklist.platform;

import ai.kumbuka.worklist.domain.WorklistException;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.worklist.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Resolving a scope against the platform's read contract — and, more
 * importantly, what happens when it cannot be resolved.
 *
 * <p>The interesting assertions here are the refusals. Under row-level
 * security an unbound session and an inaccessible scope produce the same
 * observable thing: zero rows. If the service returned an empty result for
 * both, the next person to see it would read "no such scope", and the
 * plausible repairs for that are to widen a privilege or to keep a local copy
 * of the directory — repairs to a symptom whose cause was a forgotten
 * binding, and each one a hole in the boundary the product sells.
 *
 * <p>So the two cases carry different typed reasons, and neither is ever an
 * empty return.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ScopeDirectoryIT {

    @Inject ScopeDirectory directory;
    @Inject TenantContext tenantContext;

    /**
     * The platform's grant, issued once the service's role exists. In a
     * deployment this is the platform's own migration; here it is a fixture,
     * for the same reason and in the same order.
     */
    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @Test
    void a_scope_the_subject_may_enter_resolves() {
        var access = directory.resolve(SubstrateDatabaseResource.PROBE_SUBJECT,
            SubstrateDatabaseResource.PROBE_SCOPE_SLUG);

        assertThat(access.slug()).isEqualTo(SubstrateDatabaseResource.PROBE_SCOPE_SLUG);
        assertThat(access.scopeId())
            .isEqualTo(UUID.fromString(SubstrateDatabaseResource.SCOPE_ID));
        assertThat(access.archived())
            .as("archived is published rather than filtered, so a write into a retired "
                + "scope can be refused with a specific error instead of 'not found'")
            .isFalse();
    }

    @Test
    void a_scope_the_subject_may_not_enter_is_a_refusal_and_not_an_empty_result() {
        assertThatThrownBy(() -> directory.resolve("a-subject-who-is-not-a-member",
            SubstrateDatabaseResource.PROBE_SCOPE_SLUG))
            .isInstanceOfSatisfying(WorklistException.class, e -> assertThat(e.reason())
                .as("the directory answers for the bound subject only, and existence in "
                    + "its answer IS the permission — so a subject who is not a member "
                    + "gets a refusal, never an empty list to be worked around")
                .isEqualTo(WorklistException.Reason.SCOPE_UNRESOLVED));
    }

    @Test
    void a_scope_that_does_not_exist_is_a_refusal_too() {
        assertThatThrownBy(() -> directory.resolve(SubstrateDatabaseResource.PROBE_SUBJECT,
            "no-such-scope"))
            .isInstanceOfSatisfying(WorklistException.class, e ->
                assertThat(e.reason()).isEqualTo(WorklistException.Reason.SCOPE_UNRESOLVED));
    }

    /**
     * The fail-closed probe, both halves.
     *
     * <p>Resolving with no subject must fail with a reason that names the
     * binding — not with the same reason as an inaccessible scope, and not
     * with an empty result. Then the same call with the subject bound must
     * succeed, which is what shows the refusal was about the binding and not
     * about the scope.
     */
    @Test
    void resolving_without_a_bound_session_fails_loudly_and_names_the_binding() {
        assertThatThrownBy(() -> directory.resolve(null,
            SubstrateDatabaseResource.PROBE_SCOPE_SLUG))
            .isInstanceOfSatisfying(WorklistException.class, e -> {
                assertThat(e.reason())
                    .as("an unbound session is a DIFFERENT refusal from an inaccessible "
                        + "scope. Collapsing them is what makes somebody widen a privilege "
                        + "to fix a forgotten set_config")
                    .isEqualTo(WorklistException.Reason.SESSION_NOT_BOUND);
                assertThat(e.getMessage()).contains("app.subject");
            });

        assertThatThrownBy(() -> directory.resolve("  ",
            SubstrateDatabaseResource.PROBE_SCOPE_SLUG))
            .isInstanceOfSatisfying(WorklistException.class, e ->
                assertThat(e.reason()).isEqualTo(WorklistException.Reason.SESSION_NOT_BOUND));

        // The other half: with the subject bound the same scope resolves. Without
        // this, the assertions above would hold just as well against a directory
        // that never resolves anything at all.
        assertThat(directory.resolve(SubstrateDatabaseResource.PROBE_SUBJECT,
                SubstrateDatabaseResource.PROBE_SCOPE_SLUG).slug())
            .as("and with the session bound the very same call succeeds, which is what "
                + "makes the refusals above about the binding rather than about the view")
            .isEqualTo(SubstrateDatabaseResource.PROBE_SCOPE_SLUG);
    }

    /**
     * The tenant axis reaches the directory too: a subject that is a member
     * under one tenant does not resolve the scope while another tenant is
     * bound. The view keys on both settings, and this is the half that would
     * be missed by only ever testing the subject.
     */
    @Test
    void a_foreign_tenant_binding_does_not_resolve_the_scope() throws Exception {
        try (AutoCloseable ignored = tenantContext.bind(UUID.randomUUID())) {
            assertThatThrownBy(() -> directory.resolve(SubstrateDatabaseResource.PROBE_SUBJECT,
                SubstrateDatabaseResource.PROBE_SCOPE_SLUG))
                .isInstanceOfSatisfying(WorklistException.class, e -> assertThat(e.reason())
                    .as("the directory keys on tenant AND subject; a valid subject under "
                        + "the wrong tenant must not reach the scope")
                    .isEqualTo(WorklistException.Reason.SCOPE_UNRESOLVED));
        }
    }
}
