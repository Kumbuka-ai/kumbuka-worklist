package ai.kumbuka.worklist.platform;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Grants the runtime role access to {@code platform.scope_access} once the
 * Quarkus test container has booted.
 *
 * <p>The grant belongs to the platform's own migration in a deployment; in the
 * test suite the platform side of the substrate is a fixture that plants the
 * view without granting on it, on purpose — the service role only exists after
 * the app's V2 migration runs, which is later than the substrate's own start.
 * A startup observer inside the test tree runs at the right moment:
 * dependencies are wired, the migration has completed, and no test has begun.
 *
 * <p>Present only on the test classpath. In production the grant is a
 * deployment concern and this class is deliberately absent.
 */
@ApplicationScoped
public class PlatformGrantOnStartup {

    void grantOnBoot(@Observes StartupEvent event) {
        PlatformFixture.grantDirectoryAccess();
    }
}
