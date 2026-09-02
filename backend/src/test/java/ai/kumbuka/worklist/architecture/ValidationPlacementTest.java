package ai.kumbuka.worklist.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean Validation on adapter payloads, and nowhere else.
 *
 * <h2>Not on entities</h2>
 *
 * A constraint on an entity surfaces from the Hibernate event listener at
 * flush time — under JTA, out of the transaction coordinator's completion
 * phase — as a validation exception far from the call site and outside the
 * typed refusal model. It is exactly the path that turns a refusal into the
 * server error the switch with no default was built to prevent. As an
 * innermost net with its own mapping entry it would be defensible; as the
 * primary check it is not.
 *
 * <h2>Not scope-dependent, and this is the sharper half</h2>
 *
 * Bean Validation fires before everything else. The ratified check order
 * answers "not found" for a scope the caller may not see, however broken the
 * rest of the call is, with grammar as the deliberate exception because
 * grammar is decidable without knowing a scope. A constraint that had to know
 * which scope was meant would turn adapter error handling into a scope
 * enumerator, through a path nobody audits.
 *
 * <h2>Measured: Bean Validation is not in this service's tree at all</h2>
 *
 * Neither platform service declares a validator dependency, so both rules hold
 * today by the absence of the thing they govern. That is exactly the situation
 * in which a guard is worth building and worth doubting: an empty result means
 * nothing until the detection has been watched finding something. Hence the two
 * red states below, which plant a constraint rather than wait for one.
 *
 * <p>The planted constraints are source text handed to the detection, not a
 * fixture class. A fixture would have to import annotations the build does not
 * have, and adding the dependency to make a fixture compile would change what
 * the service depends on in order to test it.
 *
 * <p>Runs as a plain unit test: it reads annotations as written and needs no
 * database.
 */
class ValidationPlacementTest {

    /** The one package a constraint may appear in. */
    private static final String PAYLOADS = "adapter.payload";

    /**
     * The constraint annotations, as they are written in source.
     *
     * <p>Matched on the written name rather than on a loaded annotation type:
     * a constraint that is present in the file but does not resolve is still a
     * constraint somebody wrote, and a guard that only saw the ones that
     * compile would go quiet exactly while a dependency was being added.
     */
    private static final Set<String> CONSTRAINTS = Set.of(
        "NotNull", "NotBlank", "NotEmpty", "Size", "Min", "Max", "Pattern",
        "Positive", "PositiveOrZero", "Negative", "NegativeOrZero",
        "Past", "PastOrPresent", "Future", "FutureOrPresent",
        "Email", "AssertTrue", "AssertFalse", "Digits", "DecimalMin", "DecimalMax",
        "Valid");

    /** An annotation as written: at-sign, name, optional argument list. */
    private static final Pattern ANNOTATION = Pattern.compile("@(\\w+)");

    /**
     * Words that mean the constraint would have to know a scope, a selector or
     * a vocabulary to decide.
     *
     * <p>Read out of the annotation's own arguments. A {@code @Pattern} whose
     * regular expression names selectors is the measured shape of this: it
     * looks like a form check and is a vocabulary check, and it fires before
     * the scope has been resolved.
     */
    private static final Set<String> SCOPE_WORDS = Set.of(
        "scope", "selector", "vocabulary", "term", "axis");

    @Test
    void bean_validation_appears_only_on_adapter_payloads() {
        assertThat(constraintsOutsidePayloads(SourceTree.root("main")))
            .as("a constraint on an entity fires at flush, under JTA, outside the typed "
                + "refusal model — which is how a refusal becomes the 500 the switch "
                + "with no default exists to prevent. Put the form check on the payload "
                + "and the business rule in the domain as a typed refusal")
            .isEmpty();
    }

    @Test
    void no_constraint_refers_to_a_scope_a_selector_or_a_vocabulary() {
        assertThat(scopeAwareConstraints(SourceTree.root("main")))
            .as("Bean Validation fires before everything else, and the ratified check "
                + "order answers 404 for a scope the caller may not see. A constraint "
                + "that must know which scope is meant turns the error path into a scope "
                + "enumerator. Check it in the domain, after the scope has resolved")
            .isEmpty();
    }

    /**
     * The red state, observed on every build.
     *
     * <p>Two of them, because the two rules fail differently: one is about
     * where a constraint sits, the other about what it knows.
     */
    @Test
    void the_check_reports_a_constraint_outside_the_payload_package() {
        String entityWithAConstraint = """
            @Entity
            public class Item {
                @NotBlank
                public String title;
            }
            """;

        assertThat(constraintsIn("domain", entityWithAConstraint))
            .as("RED STATE, observed: an entity carrying its check as a Bean Validation "
                + "constraint is the placement the first rule refuses, and the detection "
                + "must report it. An empty list here would mean the green assertion "
                + "above is measuring nothing")
            .containsExactly("@NotBlank");

        assertThat(constraintsIn(PAYLOADS, entityWithAConstraint))
            .as("RED STATE, observed: the same detection must NOT report the constraint "
                + "when it sits on a payload, or it would report every constraint "
                + "everywhere and say nothing about placement")
            .isEmpty();
    }

    @Test
    void the_check_reports_a_constraint_that_has_to_know_a_scope() {
        assertThat(scopeAware("@Pattern(regexp = \"^(FEAT|CHORE)-\\\\d+$\", "
                + "message = \"not a declared selector\")"))
            .as("RED STATE, observed: a constraint whose message or expression names a "
                + "selector is deciding a vocabulary question at the adapter, before the "
                + "scope is known")
            .isTrue();
        assertThat(scopeAware("@Size(max = 200)"))
            .as("RED STATE, observed: the same detection must NOT report an ordinary "
                + "form constraint, or it would be reporting every payload and saying "
                + "nothing")
            .isFalse();
    }

    private static List<String> constraintsOutsidePayloads(Path root) {
        List<String> offenders = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            for (String constraint
                    : constraintsIn(SourceTree.layerOf(root, file), SourceTree.code(file))) {
                offenders.add(SourceTree.fqcn(root, file) + " " + constraint);
            }
        }
        return offenders;
    }

    /**
     * The constraints a source text carries that its layer may not.
     *
     * <p>Parameterised on the layer and the text so the same detection serves
     * the walk over the tree and the red state above. A red state exercising a
     * second copy of the logic would prove that the copy works.
     */
    private static List<String> constraintsIn(String layer, String code) {
        if (PAYLOADS.equals(layer)) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        Matcher at = ANNOTATION.matcher(code);
        while (at.find()) {
            if (CONSTRAINTS.contains(at.group(1))) {
                found.add("@" + at.group(1));
            }
        }
        return found;
    }

    private static List<String> scopeAwareConstraints(Path root) {
        List<String> offenders = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            for (String annotation : annotationsIn(SourceTree.code(file))) {
                if (scopeAware(annotation)) {
                    offenders.add(SourceTree.fqcn(root, file) + " " + annotation);
                }
            }
        }
        return offenders;
    }

    /**
     * Whether a written constraint needs to know a scope, a selector or a
     * vocabulary.
     *
     * <p>Decided on the annotation's arguments, which is where such knowledge
     * has to appear if it is there at all: a regular expression enumerating
     * tokens, or a message naming what the value should have been.
     */
    private static boolean scopeAware(String annotation) {
        int at = annotation.indexOf('(');
        if (at < 0) {
            return false;
        }
        String name = annotation.substring(1, at);
        if (!CONSTRAINTS.contains(name)) {
            return false;
        }
        String arguments = annotation.substring(at).toLowerCase(java.util.Locale.ROOT);
        return SCOPE_WORDS.stream().anyMatch(arguments::contains);
    }

    /** Every annotation with an argument list, as written, one per occurrence. */
    private static List<String> annotationsIn(String code) {
        List<String> found = new ArrayList<>();
        Matcher at = ANNOTATION.matcher(code);
        while (at.find()) {
            int open = at.end();
            if (open >= code.length() || code.charAt(open) != '(') {
                continue;
            }
            int depth = 0;
            for (int i = open; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        found.add(code.substring(at.start(), i + 1));
                        break;
                    }
                }
            }
        }
        return found;
    }
}
