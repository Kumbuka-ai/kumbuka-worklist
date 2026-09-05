package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.adapter.rest.CustomMethod;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The specification both conformance probes read, and neither writes.
 *
 * <p>It is a file rather than a constant, and it is read rather than derived: a
 * probe that took its expectation from the code would assert that one copy
 * equals itself. The two halves of the probe — closure, read statically from the
 * annotations, and coverage, measured end to end against a running service —
 * read this same file, so they cannot disagree about what was specified even
 * while they disagree about what was built.
 *
 * <p>A reader rather than a test, so both halves can use it and neither owns it.
 * It asserts only about itself: that the file is there, that its rows have the
 * shape they claim, and that it is not empty — an empty expectation passes
 * coverage and closure at once, which is the one failure this file could cause
 * on its own.
 */
public final class VerbSurfaceSpecification {

    /** On the classpath, so the probes read the copy the build ships. */
    private static final String RESOURCE = "verb-surface.tsv";

    /** How many cells a row carries. A short row is a transcription error. */
    private static final int CELLS = 6;

    /** The four classes a verb is in, as opposed to the rows about routes. */
    public static final String CARRIED = "carried";
    public static final String UNCARRIED = "uncarried";
    public static final String UNBUILT = "unbuilt";
    public static final String PLATFORM = "platform";

    private VerbSurfaceSpecification() {
    }

    /**
     * One specified row.
     *
     * @param klass  carried, uncarried, unbuilt, platform, refusal, binding or
     *               extension
     * @param verb   the platform verb, or '-' where the row names none
     * @param method the HTTP method, or '-' where the row has no HTTP form
     * @param path   the form, exactly as it must appear
     * @param depth  item, membership, collection or none
     */
    public record Row(String klass, String verb, String method, String path, String depth) {

        /** {@code METHOD path}, which is how a route is compared. */
        public String route() {
            return method + " " + path;
        }

        /** Whether this row's form is a custom method in colon notation. */
        public boolean isColonForm() {
            return path.lastIndexOf(CustomMethod.SEPARATOR) > path.lastIndexOf('/');
        }
    }

    /** Every row of the specification. */
    public static List<Row> rows() {
        List<Row> rows = new ArrayList<>();

        try (InputStream in = VerbSurfaceSpecification.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            assertThat(in).as("the expectation file must be on the classpath").isNotNull();

            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines().toList()) {
                String row = line.strip();
                if (row.isEmpty() || row.startsWith("#") || row.startsWith("class\t")) {
                    continue;
                }
                String[] cells = row.split("\t");
                assertThat(cells.length)
                    .as("every row of the expectation carries %d cells: '%s'", CELLS, row)
                    .isEqualTo(CELLS);
                rows.add(new Row(cells[0], cells[1], cells[2], cells[3], cells[4]));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(rows).as("an empty expectation would pass every half at once").isNotEmpty();
        return rows;
    }

    /** The rows of the named classes. */
    public static List<Row> of(String... classes) {
        List<String> wanted = List.of(classes);
        return rows().stream().filter(r -> wanted.contains(r.klass())).toList();
    }

    /** The routes of the named classes, as {@code METHOD path}. */
    public static Set<String> routesOf(String... classes) {
        return of(classes).stream()
            .map(Row::route)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** The verbs of the named classes, each named once however many forms it has. */
    public static Set<String> verbsOf(String... classes) {
        return of(classes).stream()
            .map(Row::verb)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * The verbs written in colon notation at one address depth, of any class
     * that has a form at all.
     *
     * <p>All three classes together, because the routing table carries all three
     * and must: a verb that is refused by name is a verb the table has to
     * recognise, or the refusal would be a 405 about a spelling.
     */
    public static Set<String> colonVerbsAt(String depth) {
        return of(CARRIED, UNCARRIED, UNBUILT).stream()
            .filter(Row::isColonForm)
            .filter(r -> depth.equals(r.depth()))
            .map(Row::verb)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** The outward forms, which are what a caller writes and a probe calls. */
    public static List<Row> outwardForms() {
        return of(CARRIED, UNCARRIED, UNBUILT, "refusal");
    }

    /** Every verb the specification names, in any class. */
    public static Set<String> everyVerb() {
        return verbsOf(CARRIED, UNCARRIED, UNBUILT, PLATFORM);
    }
}
