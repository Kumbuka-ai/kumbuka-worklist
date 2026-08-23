package ai.kumbuka.worklist;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A guard on what a log line may carry.
 *
 * <p>The convention says: address, selector, number, transition, status, typed
 * reason, duration, scope id — and never a title, a body, metadata text, a
 * token, a receipt or the actor. Without something that runs, that sentence is
 * a request.
 *
 * <h2>Why it matters more than it looks</h2>
 *
 * The operator boundary of this service is built as a missing GRANT: there is
 * no privilege that would let the provider read an item. A log line carrying a
 * title walks around that entirely — the log leaves the container by a
 * different road, and a shipper collecting it delivers exactly the content the
 * database refuses to hand over. The guarantee would still be true of the
 * database and false of the deployment.
 *
 * <p>The actor is excluded for a different reason. It belongs in the audit
 * log, whose collection is governed and whose purpose is to record who changed
 * what. A second, aggregatable stream of the same fact, kept somewhere with
 * different rules, is how not-collecting-behavioural-data gets circumvented
 * without anybody deciding to circumvent it. Correlation runs through a
 * request id.
 *
 * <h2>The cardinality trap</h2>
 *
 * A guard that walks a tree and finds no log calls passes. It also passes when
 * the logging was deleted, when the pattern stopped matching, and when it is
 * pointed at the wrong directory. So this asserts a minimum count as well: the
 * check must have had something to check.
 *
 * <h2>One correction to the convention as it was inherited</h2>
 *
 * The sibling service's guard looks for the fragment {@code "ctor"} in order
 * to catch {@code actor} and {@code Actor}. {@code selector} contains
 * {@code ctor}, so the guard reports a word the convention explicitly PERMITS
 * — an address component, which is the first thing on the allowed list. That
 * is a defect of the copy and is not reproduced here: the actor is matched as
 * a word rather than as a substring, and
 * {@link #the_guard_permits_the_word_selector_which_the_convention_allows}
 * watches that distinction hold. Repairing the original is a separate act and
 * is not this repository's.
 */
class LogContentGuardTest {

    /**
     * Arguments a log call must never carry, matched as substrings.
     *
     * <p>Matched on the argument expression rather than on a resolved value,
     * because the point is to catch the habit — {@code log.debugf("...%s", e)}
     * while debugging a race — at the moment somebody writes it.
     */
    private static final List<String> FORBIDDEN_FRAGMENTS = List.of(
        // The item's content. The fields the operator boundary exists for.
        ".title", ".body", "getTitle()", "getBody()",
        // Free text belonging to the caller.
        "etadata",
        // A bearer token, in a file the provider operates.
        "eceipt",
        // Who did it. That is the audit log's business, under its own rules.
        "subject", "getPrincipal", "getActor(");

    /**
     * The actor, matched as a WORD.
     *
     * <p>The leading boundary rejects a letter, so {@code refactor} and
     * {@code benefactor} are not the actor and {@code selector} — which
     * contains no {@code actor} at all — could never have been. The trailing
     * boundary rejects only a LOWER-CASE letter, so {@code factory} is out
     * while {@code actorId} is still caught: an identifier for the actor is
     * the actor.
     *
     * <p>{@code getActor(} cannot be expressed this way, because its
     * {@code Actor} is preceded by a letter. It is a literal fragment above.
     */
    private static final Pattern FORBIDDEN_ACTOR =
        Pattern.compile("(?<![A-Za-z])[Aa]ctor(?![a-z])");

    /**
     * Arguments that ARE a whole entity.
     *
     * <p>Matched exactly rather than as a fragment: printing an entity prints
     * its title along with everything else, and the habit looks harmless
     * because the call site says nothing about content. An exact match is used
     * because a bare identifier is too short to look for as a substring
     * without hitting every other word.
     */
    private static final List<String> FORBIDDEN_WHOLE_ARGUMENTS = List.of(
        "e", "item", "it", "entity", "row");

    /**
     * Below this the guard is not measuring the tree it thinks it is.
     *
     * <p>Lower than the sibling service's eight, because this repository is
     * the substrate alone and has fewer places that log at all. It is a floor
     * against a stale pattern or a wrong directory, not a target.
     */
    private static final int MINIMUM_LOG_CALLS = 5;

    private static final Pattern LOG_CALL = Pattern.compile(
        "LOG\\.(trace|debug|info|warn|error)f?\\(([^;]*)\\)\\s*;", Pattern.DOTALL);

    @Test
    void no_log_call_carries_content_the_operator_boundary_withholds() throws IOException {
        Findings findings = scan(sourceRoot("main"));

        assertThat(findings.total())
            .as("the guard must have found log calls at all. A walk that finds none passes "
                + "for every reason including the wrong ones: logging deleted, pattern "
                + "stale, directory wrong")
            .isGreaterThanOrEqualTo(MINIMUM_LOG_CALLS);

        assertThat(findings.offenders())
            .as("a log line may carry an address, a selector, a number, a transition, a "
                + "status, a typed reason, a duration and a scope id. Never a title, a "
                + "body, metadata text, a token, a receipt or the actor — the operator "
                + "boundary is a missing GRANT, and a shipper carrying a title out of the "
                + "container delivers what the database refuses")
            .isEmpty();
    }

    /**
     * The red state, observed on every build.
     *
     * <p>A fixture carries the log calls the convention exists to stop — one
     * printing a title, one printing a whole entity, one printing the actor —
     * and the guard has to name them all. Without this the empty result over
     * the main sources is a query that finds nothing rather than a tree that
     * contains nothing.
     */
    @Test
    void the_guard_catches_a_title_an_entity_and_an_actor() throws IOException {
        Findings findings = scan(sourceRoot("test").resolve("ai/kumbuka/worklist/fixture"));

        assertThat(findings.offenders())
            .as("RED STATE, observed: the fixture logs a title, a whole item and the "
                + "actor, and all three must be reported. These are the shapes somebody "
                + "reaches for while debugging a race, which is why the convention "
                + "carries a guard rather than a javadoc line")
            .hasSizeGreaterThanOrEqualTo(3);
        assertThat(findings.offenders().toString()).contains("ForbiddenLogFixture");
        assertThat(findings.offenders().toString())
            .as("and the actor specifically, since it is matched by a different mechanism "
                + "from the rest")
            .containsIgnoringCase("actor");
    }

    /**
     * The inherited defect, as a standing assertion rather than a note.
     *
     * <p>{@code selector} is an address component and the convention's first
     * permitted item. A guard looking for the substring {@code ctor} reports
     * it, which is how a correct log line becomes a build failure and how a
     * team learns to widen the allow list. The fixture logs a selector on
     * purpose; it must NOT be among the offenders.
     */
    @Test
    void the_guard_permits_the_word_selector_which_the_convention_allows() throws IOException {
        Findings findings = scan(sourceRoot("test").resolve("ai/kumbuka/worklist/fixture"));

        assertThat(findings.offenders())
            .as("a log call whose only argument is a selector is exactly what the "
                + "convention permits. Reporting it would be the substring defect this "
                + "guard was written to avoid reproducing")
            // Matched on the call's own format string, which IS captured in the
            // offender text. Matching on the method name would assert nothing:
            // the scanner never records it, so the check would pass whatever
            // the guard decided.
            .noneSatisfy(offender -> assertThat(offender).contains("addressed under"));
    }

    private static Findings scan(Path root) throws IOException {
        List<String> offenders = new ArrayList<>();
        int total = 0;

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files
                    .filter(f -> f.toString().endsWith(".java"))::iterator) {
                String source = Files.readString(file);
                Matcher m = LOG_CALL.matcher(source);
                while (m.find()) {
                    total++;
                    String call = m.group(2);
                    // The format string is quoted; only what follows it can be
                    // an argument, and only arguments can carry content.
                    String tail = call.substring(Math.max(call.lastIndexOf('"') + 1, 0));
                    String offence = offenceIn(tail);
                    if (offence != null) {
                        offenders.add(file.getFileName() + ": LOG call carries '" + offence
                            + "' — " + call.strip());
                    }
                }
            }
        }
        return new Findings(total, offenders);
    }

    /**
     * The first forbidden thing among a call's arguments, or null.
     *
     * <p>Arguments are split rather than searched as one string for the
     * whole-argument case, so that a bare entity can be matched exactly.
     * Searching for {@code "e"} as a substring would match almost every line
     * ever written.
     */
    private static String offenceIn(String argumentTail) {
        for (String fragment : FORBIDDEN_FRAGMENTS) {
            if (argumentTail.contains(fragment)) {
                return fragment;
            }
        }
        Matcher actor = FORBIDDEN_ACTOR.matcher(argumentTail);
        if (actor.find()) {
            return actor.group() + " (the actor)";
        }
        for (String argument : argumentTail.split(",")) {
            String bare = argument.strip();
            if (FORBIDDEN_WHOLE_ARGUMENTS.contains(bare)) {
                return bare + " (a whole entity)";
            }
        }
        return null;
    }

    private static Path sourceRoot(String sourceSet) {
        Path direct = Paths.get("src", sourceSet, "java");
        Path fromRepoRoot = Paths.get("backend", "src", sourceSet, "java");
        Path root = Files.isDirectory(direct) ? direct : fromRepoRoot;
        assertThat(Files.isDirectory(root))
            .as("source root %s must exist — run from the module directory", root)
            .isTrue();
        return root;
    }

    private record Findings(int total, List<String> offenders) {
    }
}
