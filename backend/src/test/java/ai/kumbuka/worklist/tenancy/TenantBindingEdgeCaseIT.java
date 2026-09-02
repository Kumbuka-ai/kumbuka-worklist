package ai.kumbuka.worklist.tenancy;

import ai.kumbuka.worklist.domain.ItemService;
import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The paths the tenancy machinery takes when something is wrong.
 *
 * <p>Every one of these is a decision to fail loudly rather than to carry on
 * with a plausible-looking value: an unbind out of order, a binding with no
 * transaction to hold it, a resolver that answered null, an identifier read
 * from configuration that is not an identifier. They exist precisely because
 * the quiet alternative — repairing the situation and continuing — would
 * produce a request running under the wrong tenant, which is the one failure
 * row-level security cannot catch, because the query is then genuinely
 * well-formed for the wrong tenant.
 *
 * <p>Until this class existed, all of that was written down and none of it
 * was observed. A failure path that has never been taken is a comment about
 * intent, and the first time it runs is in production.
 *
 * <p>It is a {@code @QuarkusTest} rather than a plain unit test for two
 * reasons. Several of these paths only exist inside a real CDI and
 * transaction context — "no transaction is open" is not a state a mock can
 * honestly present. And coverage is measured through the Quarkus classloader,
 * so a plain JUnit test exercising these classes would be invisible to the
 * report and the paths would still read as untested.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TenantBindingEdgeCaseIT {

    @Inject TenantContext tenantContext;
    @Inject TenantDatabaseBinding binding;
    @Inject TenantResolver resolver;
    // The qualifier is not decoration: @PersistenceUnitExtension is how
    // Quarkus tells this bean apart from the service's own resolver SPI,
    // which shares its simple name. Without it here the injection point
    // matches by type and is rejected for want of a matching qualifier.
    @Inject @PersistenceUnitExtension HibernateTenantResolver hibernateResolver;

    @Inject ItemService items;

    // -----------------------------------------------------------------------
    // The bind stack
    // -----------------------------------------------------------------------

    @Test
    void binding_a_null_tenant_is_refused() {
        assertThatThrownBy(() -> tenantContext.bind(null))
            .as("a null tenant would put the stack into a state where current() cannot "
                + "distinguish 'nothing bound' from 'bound to nothing'")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be null");
    }

    @Test
    void binds_nest_and_the_innermost_one_wins() throws Exception {
        UUID outer = UUID.randomUUID();
        UUID inner = UUID.randomUUID();

        try (AutoCloseable ignoredOuter = tenantContext.bind(outer)) {
            assertThat(tenantContext.current()).isEqualTo(outer);
            try (AutoCloseable ignoredInner = tenantContext.bind(inner)) {
                assertThat(tenantContext.current())
                    .as("a nested bind must take effect, or a scoped override silently "
                        + "operates under its caller's tenant")
                    .isEqualTo(inner);
            }
            assertThat(tenantContext.current())
                .as("and closing it must restore the outer one exactly")
                .isEqualTo(outer);
        }
    }

    @Test
    void an_unbind_out_of_order_fails_loudly() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        AutoCloseable outer = tenantContext.bind(first);
        AutoCloseable inner = tenantContext.bind(second);

        // Closing the OUTER handle while the inner one is still open. Popping
        // regardless would leave the inner tenant bound on a pooled thread
        // with nobody holding it — a foreign tenant inherited by the next
        // request on that thread.
        assertThatThrownBy(outer::close)
            .as("an out-of-order unbind must fail rather than repair itself, because the "
                + "repair leaves a foreign tenant bound on a thread that will be reused")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("out of order");

        // The refusal must not have spent the handle. Unwinding in the right
        // order now has to work — and this is the half that matters: if the
        // failed attempt marked the handle closed, the two closes below become
        // no-ops, `first` stays on the stack, and every later request on this
        // thread inherits it. The guard would then have caused exactly the leak
        // it exists to prevent, and it would do so silently.
        closeQuietly(inner);
        closeQuietly(outer);
        assertThat(tenantContext.current())
            .as("after unwinding in the correct order the stack must be empty again, so "
                + "the resolver answers. A failed close that consumed the handle would "
                + "leave the first tenant bound here forever")
            .isEqualTo(resolver.currentTenant());
    }

    @Test
    void closing_the_same_handle_twice_is_a_no_op() throws Exception {
        UUID tenant = UUID.randomUUID();
        AutoCloseable handle = tenantContext.bind(tenant);
        handle.close();

        // The second close must not pop the enclosing binding. A try-with-
        // resources nested inside a manual close is exactly how that happens.
        handle.close();

        assertThat(tenantContext.current())
            .as("a repeated close must not consume somebody else's binding")
            .isEqualTo(resolver.currentTenant());
    }

    @Test
    void with_nothing_bound_the_resolver_answers() {
        assertThat(tenantContext.current())
            .as("the bottom of the stack is the configured resolver")
            .isEqualTo(resolver.currentTenant());
    }

    // -----------------------------------------------------------------------
    // The database binding
    // -----------------------------------------------------------------------

    @Test
    void binding_outside_a_transaction_is_skipped_rather_than_failing() {
        // There is no envelope for SET LOCAL here, so the binding has nothing
        // to attach to. It returns quietly: the ORM filter still applies, and
        // raw SQL outside a transaction is caught by the architecture probe.
        assertThat(binding.isBoundOnCurrentTransaction())
            .as("no transaction is open, so nothing can be bound to one")
            .isFalse();

        binding.bindCurrentTransaction();

        assertThat(binding.isBoundOnCurrentTransaction())
            .as("and the call must not have invented a binding out of thin air")
            .isFalse();
    }

    @Test
    void the_hibernate_resolver_offers_no_default_tenant() {
        assertThat(hibernateResolver.getDefaultTenantId())
            .as("a default here would be the one value that makes an unbound session look "
                + "like a legitimate one; every session must arrive with a tenant")
            .isNull();
    }

    @Test
    void the_hibernate_resolver_reports_the_bound_tenant() throws Exception {
        UUID tenant = UUID.randomUUID();
        try (AutoCloseable ignored = tenantContext.bind(tenant)) {
            assertThat(hibernateResolver.resolveTenantId())
                .as("the ORM filter and the database policy must read the same value, or "
                    + "each scopes the query to a different tenant and the result is empty "
                    + "in a way that looks like correct isolation")
                .isEqualTo(tenant.toString());
        }
    }

    // -----------------------------------------------------------------------
    // The repository's count, which the fail-closed probe asserts on
    // -----------------------------------------------------------------------

    @Test
    void the_orm_read_is_scoped_to_the_bound_tenant() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID scope = UUID.fromString("00000000-0000-0000-0000-000000000010");

        try (AutoCloseable ignored = tenantContext.bind(tenant)) {
            // The answer carries no tenant, deliberately: the tenancy axis is
            // never a field a caller reads or writes. So the claim "the write
            // carried the bound tenant" is checked one level down, by asking
            // the database under exactly that tenant — where row-level
            // security is what makes the row visible at all.
            var created = items.create(scope, java.util.Map.of("title", "count-probe"));
            assertThat(created.get("title")).isEqualTo("count-probe");

            assertThat(items.query(scope))
                .as("a write through the ORM carries the bound tenant without the caller "
                    + "supplying it — that is what the @TenantId filter is for — and the "
                    + "read gives back exactly what it wrote and only that: the scope "
                    + "holds rows of other tenants planted by other cases, and none of "
                    + "them may appear here")
                .singleElement()
                .satisfies(item -> assertThat(item.get("title")).isEqualTo("count-probe"));
        }
    }

    // -----------------------------------------------------------------------
    // Callback registration surface
    // -----------------------------------------------------------------------

    @Test
    void each_callback_answers_for_its_own_event_and_no_other() {
        var migration = new TenantMigrationCallback();

        assertThat(migration.supports(Event.BEFORE_EACH_MIGRATE, null)).isTrue();
        assertThat(migration.supports(Event.AFTER_MIGRATE, null))
            .as("a callback that answered for every event would run its statement at times "
                + "it was never written for")
            .isFalse();

        // This service registers ONE callback. The sibling registers a second,
        // which normalises object ownership to the runtime role after every
        // migration; there is none here because the migrator keeps ownership
        // and V2 enumerates the runtime role's privileges instead.

        // Instantiated above with the no-argument constructor, which is how
        // the Flyway extension instantiates it. A constructor that grew a
        // dependency would break here rather than at boot.
        assertThat(migration).isNotNull();
    }

    // -----------------------------------------------------------------------
    // The tenant-id converter, whose null branches carry the nullable column
    // -----------------------------------------------------------------------

    @Test
    void the_tenant_id_converter_carries_null_in_both_directions() {
        var converter = new StringUuidConverter();
        UUID tenant = UUID.randomUUID();

        assertThat(converter.convertToDatabaseColumn(tenant.toString())).isEqualTo(tenant);
        assertThat(converter.convertToEntityAttribute(tenant)).isEqualTo(tenant.toString());

        // Null must survive as null. Converting it to a value would write a
        // row under a tenant nobody asked for; throwing would break the ORM's
        // own null handling on an unset attribute.
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    private static void closeQuietly(AutoCloseable handle) {
        try {
            handle.close();
        } catch (Exception ignored) {
            // Best effort: this is cleanup after a deliberately broken unwind.
        }
    }
}
