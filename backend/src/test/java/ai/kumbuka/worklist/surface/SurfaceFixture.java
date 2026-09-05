package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.platform.PlatformFixture;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.security.TestIdentityAssociation;

/**
 * The two identities the surface probes call as, and the scope one of them can
 * see.
 *
 * <p>Two are needed rather than one because the check order's whole point is
 * observable only with both: a call against a scope the caller may not see
 * answers 404 however broken the rest of the call is, and "however broken"
 * cannot be measured without a second caller for whom the same call answers
 * something else.
 *
 * <p>The identity is switched programmatically rather than by minting tokens.
 * Which issuer this service trusts is a different question, answered by
 * {@code RealmBindingIT} against a Keycloak carrying two realms; what is asked
 * here is what the surface does with an authenticated subject, and a container
 * in front of every status-code assertion would not make that answer any truer.
 */
public final class SurfaceFixture {

    /** The scope the staged directory publishes, by the name a caller uses. */
    public static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;

    /** The subject the directory answers for. */
    public static final String MEMBER = SubstrateDatabaseResource.PROBE_SUBJECT;

    /**
     * A subject with no membership anywhere.
     *
     * <p>Authenticated and a stranger, which is the caller the check order is
     * written against: not an attacker with no token, but somebody who has one
     * and is asking about a scope that is none of their business.
     */
    public static final String STRANGER = "probe-stranger";

    private SurfaceFixture() {
    }

    /**
     * Grants the directory read and registers the member as an account of the
     * tenant.
     *
     * <p>Membership is what the read contract answers on: existence in its
     * result IS the permission, so a subject with no account resolves no scope.
     * The stranger is deliberately NOT registered — that is what makes it one.
     */
    public static void stage() {
        PlatformFixture.grantDirectoryAccess();
    }

    /**
     * A second scope of the same tenant, visible and empty of vocabulary.
     *
     * <p>The probe scope acquires declarations as other classes run against it,
     * so it cannot be used to observe what an UNDECLARED view answers — the
     * assertion would pass or fail by execution order. This one is published for
     * the probe that needs it and nothing declares anything in it.
     *
     * <p>Visible because the read contract joins a scope to the accounts of its
     * tenant: publishing the row is enough, and the member sees it for the same
     * reason it sees the other one.
     *
     * @return the slug, so the caller addresses it by the name it published
     */
    public static String publishEmptyScope(String slug, java.util.UUID id) {
        PlatformFixture.run(
            "SELECT set_config('app.tenant_id', '"
                + SubstrateDatabaseResource.TENANT_ID + "', false)",
            "INSERT INTO public.scope (id, tenant_id, slug, kind) SELECT '" + id + "', '"
                + SubstrateDatabaseResource.TENANT_ID + "', '" + slug + "', 'project' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.scope WHERE id = '" + id + "')",
            "RESET app.tenant_id");
        return slug;
    }

    /** Calls as the subject the scope is open to. */
    public static void asMember(TestIdentityAssociation identity) {
        as(identity, MEMBER);
    }

    /** Calls as an authenticated subject the scope is closed to. */
    public static void asStranger(TestIdentityAssociation identity) {
        as(identity, STRANGER);
    }

    private static void as(TestIdentityAssociation identity, String subject) {
        identity.setTestIdentity(QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal(subject))
            .build());
    }

    /** The collection URI of one view. */
    public static String collection(String view) {
        return "/api/" + SCOPE + "/" + view;
    }

    /** The item URI of one object. */
    public static String item(String view, Object number) {
        return collection(view) + "/" + number;
    }

    /** The URI of a membership, which is an id segment under its iteration. */
    public static String membership(Object iteration, Object item) {
        return item("iteration", iteration) + "/" + item;
    }

    /** The complete address of an object, as the MCP form spells one. */
    public static String address(String view, Object number) {
        return AddressParser.SCHEME + "://" + SCOPE + "/" + view + "/" + number;
    }
}
