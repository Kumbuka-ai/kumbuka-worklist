/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No production code reads {@code worklist.selector}; one statement writes it.
 *
 * <p>The four views are constants of the service ({@code Selector.VIEWS}), and
 * a scope's counters are keyed by view (V24). Whether a view is open in a
 * scope, and which number it hands out next, is answered by the counter alone.
 * The selector table is still written, by one statement, so that the image
 * before this one finds its rows after a rollback; the contraction release
 * drops the table and that statement together.
 *
 * <p>Until then the table is a trap: it holds a row for every open view, so a
 * read of it gives the right answer today and silently a stale one the day
 * the writes stop. This guard keeps the one write the only access:
 *
 * <ul>
 *   <li>the table is named in exactly one class, {@link #WRITER};</li>
 *   <li>there it is named once, and only as the target of an
 *       {@code INSERT INTO};</li>
 *   <li>no entity maps it, so no JPQL can reach it either.</li>
 * </ul>
 *
 * <p>Runs as a plain unit test: it reads main sources, comments stripped, and
 * needs no database.
 */
class SelectorTableAccessTest {

    /** The one class allowed to name the table, by its path below the service root. */
    private static final String WRITER = "repository/SelectorRepository.java";

    /** The table, schema-qualified, as every statement of this service names it. */
    private static final Pattern TABLE = Pattern.compile(
        "\\bworklist\\.selector\\b", Pattern.CASE_INSENSITIVE);

    /** What may stand directly before the table name: the insert, and nothing else. */
    private static final Pattern INSERT_BEFORE = Pattern.compile(
        "\\bINSERT\\s+INTO\\s+$", Pattern.CASE_INSENSITIVE);

    /** An entity mapping onto the table, which would open it to JPQL. */
    private static final Pattern MAPPING = Pattern.compile(
        "@Table\\s*\\(\\s*name\\s*=\\s*\"selector\"");

    @Test
    void the_selector_table_is_written_once_and_read_nowhere() {
        Path root = SourceTree.root("main");
        List<String> offences = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            String where = root.relativize(file).toString().replace('\\', '/');
            offences.addAll(offences(where.endsWith(WRITER) ? WRITER : where,
                SourceTree.code(file)));
        }
        assertThat(offences)
            .as("worklist.selector is kept only so the previous image runs after a rollback. "
                + "Ask the counter (SelectorRepository.space / lockSpace, keyed by view) "
                + "whether a view is open and what it hands out next; the one write lives in "
                + "SelectorRepository.open and goes with the contraction release")
            .isEmpty();
    }

    /**
     * The red state, observed on every build: a read beside the write.
     *
     * <p>The planted writer carries the shipped insert and one select of the
     * table. A detection that only counted classes, or only counted names,
     * would pass it; this one has to see that the second name is not an
     * insert target.
     */
    @Test
    void a_read_of_the_selector_table_is_reported() {
        String planted = SourceTree.code("""
            class SelectorRepository {
                void open() {
                    em.createNativeQuery("INSERT INTO worklist.selector (token) VALUES (:v)");
                }
                boolean exists() {
                    return em.createNativeQuery(
                        "SELECT 1 FROM worklist.selector WHERE token = :v").getResultList()
                        .isEmpty();
                }
            }
            """);
        assertThat(offences(WRITER, planted))
            .as("RED STATE, observed: a select of worklist.selector beside the one insert "
                + "must be reported, or the empty list above says nothing about reads")
            .anyMatch(offence -> offence.contains("not written by INSERT INTO"));

        assertThat(offences("domain/AddressRegistry.java",
                "String q = \"SELECT token FROM worklist.selector\";"))
            .as("RED STATE, observed: the table named in any other class must be reported")
            .anyMatch(offence -> offence.contains("outside " + WRITER));

        assertThat(offences("domain/Selector.java",
                "@Entity @Table(name = \"selector\", schema = \"worklist\") class Selector {}"))
            .as("RED STATE, observed: an entity mapping the table must be reported, because "
                + "JPQL over it never names the table and the checks above would not see it")
            .anyMatch(offence -> offence.contains("maps worklist.selector"));
    }

    /** What one file does wrong with the table; empty when nothing. */
    private static List<String> offences(String where, String code) {
        List<String> found = new ArrayList<>();
        if (MAPPING.matcher(code).find()) {
            found.add(where + " maps worklist.selector as an entity");
        }
        Matcher m = TABLE.matcher(code);
        int named = 0;
        while (m.find()) {
            named++;
            if (!WRITER.equals(where)) {
                found.add(where + " names worklist.selector outside " + WRITER);
            } else if (!INSERT_BEFORE.matcher(code.substring(0, m.start())).find()) {
                found.add(where + " names worklist.selector where it is not written by "
                    + "INSERT INTO");
            }
        }
        if (WRITER.equals(where) && named > 1) {
            found.add(where + " names worklist.selector " + named + " times; the one "
                + "write is the only place it may stand");
        }
        return found;
    }
}
