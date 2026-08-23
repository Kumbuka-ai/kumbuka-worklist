package ai.kumbuka.worklist.fixture;

import org.jboss.logging.Logger;

/**
 * Log calls the convention exists to stop, for the guard to find — and one it
 * permits, for the guard to leave alone.
 *
 * <p>The first three are the shapes somebody reaches for while debugging a
 * race at two in the morning: print the title to see which item this is, print
 * the whole object to see everything at once, print who did it. None is
 * malicious and none would be noticed in review — the first ships an item's
 * title out of the container past a boundary built as a missing GRANT, the
 * second ships the title along with everything else, and the third builds the
 * aggregatable record of who-did-what that the audit log exists to keep under
 * its own rules.
 *
 * <p>The fourth is the counter-example and it matters as much as the other
 * three. A selector is an address component and the first thing on the
 * convention's permitted list. The sibling service's guard reports it anyway,
 * because it looks for the substring {@code ctor} and {@code selector}
 * contains one. A guard that fails a correct line teaches a team to widen its
 * allow list, which is how the incorrect lines get through later.
 *
 * <p>It lives in the test sources and is wired into nothing. Its only purpose
 * is to be scanned: without it, the guard's clean result over the main sources
 * would be a statement about a detection nobody has seen work.
 */
public class ForbiddenLogFixture {

    private static final Logger LOG = Logger.getLogger(ForbiddenLogFixture.class);

    public void logATitle(Itemish it) {
        LOG.infof("working on %s", it.title);
    }

    public void logAWholeEntity(Itemish item) {
        LOG.debugf("state now: %s", item);
    }

    public void logAnActor(String actor) {
        LOG.infof("changed by %s", actor);
    }

    /** Permitted, and the guard must say so by staying silent. */
    public void logASelector(String selector) {
        LOG.debugf("addressed under %s", selector);
    }

    /** Stands in for the entity, so the fixture needs no domain import. */
    public static class Itemish {
        public String title = "an item's title";
    }
}
