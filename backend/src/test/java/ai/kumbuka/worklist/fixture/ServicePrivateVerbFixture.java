package ai.kumbuka.worklist.fixture;

/**
 * A deliberate violation, so that the verb-vocabulary guard is watched
 * catching one.
 *
 * <p>Two public methods: one spelled as the platform vocabulary spells it, and
 * one under a service-private name of exactly the kind the guard exists
 * against. This is not hypothetical — an earlier build of the item domain
 * carried six such names, chosen to satisfy a rule that has since been retired,
 * and they outlived it by a sprint.
 *
 * <p>Never instantiated and never wired: the guard reflects over the class, it
 * does not run it. It is a planted violation rather than the alignment undone,
 * because a red state produced by removing a fix measures the fix rather than
 * the guard.
 */
public class ServicePrivateVerbFixture {

    /** A platform verb, spelled identically. The guard must not report this one. */
    public void read() {
        // Nothing to do: the guard reads the signature, never the body.
    }

    /** A service-private name where a platform verb belongs. Must be reported. */
    public void registerIntake() {
        // Nothing to do: the guard reads the signature, never the body.
    }
}
