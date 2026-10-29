/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * no privilege that would let the provider read an item. A log line
 * carrying a title walks around that entirely — the log leaves the container
 * by a different road, and a shipper collecting it delivers exactly the
 * content the database refuses to hand over. The guarantee would still be true
 * of the database and false of the deployment.
 *
 * <p>The actor is excluded for a different reason. It belongs in the audit
 * log, whose collection is governed and whose purpose is to record who changed
 * what. A second, aggregatable stream of the same fact, kept somewhere with
 * different rules, is how not-collecting-behavioural-data gets circumvented
 * without anybody deciding to circumvent it. Correlation runs through a
 * request id.
 *
 * <h2>Identifiers, not substrings</h2>
 *
 * A forbidden thing is looked for as an identifier written in the source, and
 * the identifier is compared by the words it is made of. The earlier version
 * searched for substrings, and searching for a substring of a word catches
 * every longer word that happens to contain it: the actor was looked for as
 * {@code ctor}, so {@code selector} was reported as carrying the actor. The
 * selector is on the permitted list, immediately after the address. The
 * convention is not the thing that was wrong.
 *
 * <p>Comparing words rather than whole identifiers is what keeps the reach the
 * substring version had. {@code subjectId} and {@code getTitle} still fall,
 * because {@code subject} and {@code title} are words inside them.
 * {@code selector} and {@code somebody} do not, because {@code actor} and
 * {@code body} are not words inside them — only letters.
 *
 * <h2>The cardinality trap</h2>
 *
 * A guard that walks a tree and finds no log calls passes. It also passes when
 * the logging was deleted, when the pattern stopped matching, and when it is
 * pointed at the wrong directory. So this asserts a minimum count as well: the
 * check must have had something to check. That applies to the permitted
 * fixture too — a green result over an empty directory says nothing.
 */
class LogContentGuardTest {

    /**
     * Words a log call's arguments must never contain.
     *
     * <p>Matched on the argument expression rather than on a resolved value,
     * because the point is to catch the habit — {@code log.debugf("...%s", e)}
     * while debugging a race — at the moment somebody writes it.
     *
     * <p>Each entry is a whole word, compared case-insensitively against the
     * words an identifier is made of. So {@code actor} covers {@code actor},
     * {@code Actor}, {@code getActor} and {@code e.actor()}, and does not
     * cover {@code selector}.
     */
    private static final List<String> FORBIDDEN_WORDS = List.of(
        // The commission itself. The two fields the operator boundary exists for.
        "title", "body",
        // Free text belonging to the caller.
        "metadata",
        // A bearer token, in a file the provider operates.
        "receipt",
        // Who did it. That is the audit log's business, under its own rules.
        "subject", "principal", "actor");

    /**
     * Arguments that ARE a whole entity.
     *
     * <p>Matched exactly rather than by word: printing an entity prints its
     * title and its metadata along with everything else, and the habit looks
     * harmless because the call site says nothing about content. An exact
     * match is used because a bare identifier is too short to look for any
     * other way — {@code e} is a word inside a great many identifiers.
     *
     * <p>The one list of C13.2 is {@link #FORBIDDEN_WORDS}, the same as the
     * reference's word for word. This list names what an entity of THIS
     * service is called where it is handed around whole: kumbuka-dispatch
     * names its exchange, its task and its view; here a {@code view} is a
     * selector token, the platform's own vocabulary, and the item is the
     * entity.
     */
    private static final List<String> FORBIDDEN_WHOLE_ARGUMENTS = List.of(
        "e", "ex", "item", "it", "entity", "row");

    /** Below this the guard is not measuring the tree it thinks it is. */
    private static final int MINIMUM_LOG_CALLS = 8;

    /**
     * One log call per item the convention permits — address, selector,
     * number, transition, status, typed reason, duration, scope id — plus the
     * typed reason written as a constant for the one reason name of this
     * service that collides with the forbidden list.
     */
    private static final int MINIMUM_ALLOWED_FIXTURE_LOG_CALLS = 9;

    private static final Pattern LOG_CALL = Pattern.compile(
        "LOG\\.(trace|debug|info|warn|error)f?\\(([^;]*)\\)\\s*;", Pattern.DOTALL);

    /** An identifier as Java spells one. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    /**
     * The boundaries inside an identifier: {@code scopeId} is two words,
     * {@code SCOPE_UNRESOLVED} is two words, {@code SELECTOR} is one.
     */
    private static final Pattern WORD_BOUNDARY = Pattern.compile(
        "[_$]+|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

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
     * The green counter-probe, observed on every build.
     *
     * <p>A fixture carries one log call per permitted item, written with the
     * bare identifiers a developer actually reaches for. All of them must come
     * back clean. Without this, the empty result over the main sources is a
     * check nobody has seen say yes — and a check that only ever says no is
     * indistinguishable from one that has quietly started refusing things the
     * convention allows. That is not hypothetical: {@code selector} was
     * reported as carrying the actor, and the first developer to log a
     * selector would have got a red build saying their call carried
     * {@code ctor}.
     */
    @Test
    void the_guard_passes_every_shape_the_convention_permits() throws IOException {
        Findings findings = scan(allowedFixtureRoot());

        assertThat(findings.total())
            .as("the permitted fixture must have been read. A green result over a "
                + "directory the guard found nothing in is the same green it would give "
                + "for a deleted fixture or a stale pattern, and it is the reason this "
                + "counter-probe would otherwise prove nothing")
            .isGreaterThanOrEqualTo(MINIMUM_ALLOWED_FIXTURE_LOG_CALLS);

        assertThat(findings.offenders())
            .as("every one of these is on the permitted list: an address, a selector, a "
                + "number, a transition, a status, a typed reason, a duration and a scope "
                + "id. A guard that reports one of them is refusing what the convention "
                + "allows, and the developer who hits it gets a red build about a rule "
                + "that does not exist")
            .isEmpty();
    }

    /**
     * The red state, observed on every build.
     *
     * <p>A fixture carries the two log calls the convention exists to stop —
     * one printing a title, one printing a whole entity — and the guard has to
     * name both. Without this the empty result over the main sources is a
     * query that finds nothing rather than a tree that contains nothing.
     */
    @Test
    void the_guard_catches_a_log_call_carrying_a_title_and_one_carrying_an_entity()
            throws IOException {
        Findings findings = scan(forbiddenFixtureRoot());

        assertThat(findings.offenders())
            .as("RED STATE, observed: the fixture logs a title and logs a whole item, "
                + "and both must be reported. These are the two shapes somebody reaches "
                + "for while debugging a race, which is why the convention carries a "
                + "guard rather than a javadoc line")
            .hasSizeGreaterThanOrEqualTo(2);
        assertThat(findings.offenders().toString()).contains("ForbiddenLogFixture");
    }

    /**
     * The red state for every other thing the convention withholds.
     *
     * <p>The title and the whole entity have their own test above, and the
     * actor has one below. This covers the rest of the forbidden list one
     * entry at a time, because a count cannot tell which of them is still
     * being caught. That matters most exactly when a rule is added to let
     * something through: the way such a rule fails is by letting one more
     * thing through than intended, and a test that only counts offences would
     * stay green while it happened.
     */
    @Test
    void the_guard_catches_every_thing_the_convention_withholds() throws IOException {
        Findings findings = scan(forbiddenFixtureRoot());

        for (String written : List.of("it.title", "it.body", "it.metadata", "receipt",
                "subject")) {
            assertThat(findings.offenders())
                .as("RED STATE, observed: a log call carrying '%s' must be reported, and "
                    + "the report must name what it carries", written)
                .anySatisfy(offence -> {
                    assertThat(offence).endsWith(", " + written);
                    assertThat(offence.substring(0, offence.indexOf(" — ")))
                        .containsIgnoringCase(written.replace("it.", ""));
                });
        }
    }

    /**
     * The red state for the actor specifically, in each shape it is written.
     *
     * <p>The actor is the entry that motivated the substring search in the
     * first place, and narrowing the check to identifiers is exactly the move
     * that could drop it along with the false positive. Dropping it would be
     * the quiet kind of damage: the guard stays green, the convention still
     * says the actor is forbidden, and nothing runs that would notice the
     * difference. So each of the four shapes gets its own assertion, and each
     * report has to name the actor rather than merely count.
     */
    @Test
    void the_guard_catches_the_actor_in_each_shape_it_is_written() throws IOException {
        Findings findings = scan(forbiddenFixtureRoot());

        for (String shape : List.of("actor", "Actor.EXECUTOR", "it.getActor()", "it.actor()")) {
            assertThat(findings.offenders())
                .as("RED STATE, observed: the actor written as '%s' must be reported, and "
                    + "the report must name the actor — an offence the developer cannot "
                    + "trace back to a word in their own call is how '%s carries ctor' "
                    + "happened", shape, "selector")
                .anySatisfy(offence -> {
                    assertThat(offence).contains("\"changed by %s\", " + shape);
                    assertThat(offence.substring(0, offence.indexOf(" — ")))
                        .containsIgnoringCase("actor");
                });
        }
    }

    /**
     * The red state for an exception handed to the logger (DEC-0051, C13.2).
     *
     * <p>A stack trace carries what the failing code held, and the guard read
     * only what follows the format string, where an exception never stands:
     * {@code LOG.errorf(cause, "...")} passed it on every build until this was
     * added. The fixture now carries that shape and the guard must name it.
     */
    @Test
    void the_guard_catches_an_exception_handed_to_the_logger() throws IOException {
        Findings findings = scan(forbiddenFixtureRoot());

        assertThat(findings.offenders())
            .as("RED STATE, observed: a log call that hands the logger an exception before "
                + "its format string must be reported, naming what it hands over")
            .anySatisfy(offence -> assertThat(offence)
                .startsWith("ForbiddenLogFixture.java: LOG call hands over 'failure'"));
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
                    if (!call.strip().startsWith("\"")) {
                        // The logger's overloads take a Throwable before the
                        // format string; whatever stands there is an object
                        // handed to the logger, never the platform's words.
                        offenders.add(file.getFileName() + ": LOG call hands over '"
                            + call.strip().split(",", 2)[0].strip()
                            + "' (an object before the format string) — " + call.strip());
                        continue;
                    }
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
     * <p>The identifier is returned as it was written rather than as the word
     * that matched it, so the report names something the developer can find by
     * searching their own call. Whole entities are checked after identifiers
     * and by splitting on commas, so that a bare entity can be matched
     * exactly.
     */
    private static String offenceIn(String argumentTail) {
        Matcher identifiers = IDENTIFIER.matcher(argumentTail);
        while (identifiers.find()) {
            String written = identifiers.group();
            if (wordsIn(written).stream().anyMatch(FORBIDDEN_WORDS::contains)) {
                return written;
            }
        }
        for (String argument : argumentTail.split(",")) {
            String bare = argument.strip();
            if (FORBIDDEN_WHOLE_ARGUMENTS.contains(bare)) {
                return bare + " (a whole entity)";
            }
        }
        return null;
    }

    /**
     * The words an identifier is made of, lower-cased.
     *
     * <p>{@code getActor} is {@code get} and {@code actor}; {@code scopeId} is
     * {@code scope} and {@code id}; {@code selector} is one word and is not
     * any of them.
     *
     * <p>A constant is the exception and is one word: its whole name. See
     * {@link #isConstant(String)}.
     */
    private static List<String> wordsIn(String identifier) {
        if (isConstant(identifier)) {
            return List.of(identifier.toLowerCase(Locale.ROOT));
        }
        List<String> words = new ArrayList<>();
        for (String word : WORD_BOUNDARY.split(identifier)) {
            if (!word.isEmpty()) {
                words.add(word.toLowerCase(Locale.ROOT));
            }
        }
        return words;
    }

    /**
     * Whether an identifier is written the way Java writes a constant: no
     * lower-case letter anywhere in it.
     *
     * <p>A constant out of a closed set carries a category, never content.
     * {@code CLAIM_RECEIPT_UNKNOWN} says the receipt named another lease; it
     * does not say what the receipt was. That is precisely why the typed
     * reason is on the permitted list, and it is why a constant's name is
     * compared whole rather than split into words. Split into words,
     * {@code CLAIM_RECEIPT_UNKNOWN} reads as the receipt — a category name
     * reported as the content it exists to describe. The main sources already write this
     * form: {@code ScopeDirectory} logs
     * {@code WorklistException.Reason.SCOPE_UNRESOLVED}.
     *
     * <p>Whole-name comparison is not a blanket exemption. A constant that
     * names nothing but a forbidden thing is still reported, because its whole
     * name IS the forbidden word: {@code ACTOR}, {@code TITLE} and
     * {@code BODY} all fall. What passes is a name that says something about
     * the forbidden thing rather than being it.
     *
     * <p>The price, stated rather than hidden: this opens a gap for an
     * identifier that looks like a constant and carries content, such as a
     * hypothetical {@code ITEM_TITLE}. The gap is narrow, because a
     * constant is fixed in the source and an item's title is not — the
     * content this guard exists to keep out of the log does not exist at
     * compile time. It is weighed against the alternative, which is a guard
     * that reports three permitted reason names as content and teaches the
     * next developer to delete a word from the forbidden list.
     */
    private static boolean isConstant(String identifier) {
        return identifier.chars().noneMatch(Character::isLowerCase)
            && identifier.chars().anyMatch(Character::isLetter);
    }

    private static Path forbiddenFixtureRoot() {
        return sourceRoot("test").resolve("ai/kumbuka/worklist/fixture");
    }

    private static Path allowedFixtureRoot() {
        return forbiddenFixtureRoot().resolve("allowed");
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
