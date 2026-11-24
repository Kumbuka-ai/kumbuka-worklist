/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.CounterProbe;

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
 * The declaration of the administration surface the probes read, and none
 * writes: {@code administration-surface.tsv}, beside the verb specification
 * (C1.6).
 *
 * <p>A file rather than a constant, read rather than derived, for the reason
 * {@link VerbSurfaceSpecification} gives: a probe that took its expectation
 * from the code would assert that one copy equals itself. It asserts only
 * about itself -- the file is there, its rows have their shape, it is not
 * empty -- because an empty expectation passes closure at once.
 */
@CounterProbe
public final class AdministrationSurfaceSpecification {

    /** On the classpath, so the probes read the copy the build ships. */
    private static final String RESOURCE = "administration-surface.tsv";

    /** How many cells a row carries. A short row is a transcription error. */
    private static final int CELLS = 4;

    /** The access a call asks for: the read right of the scope. */
    public static final String READ = "read";

    /** The access a call asks for: the write right of the scope, until S15. */
    public static final String ADMINISTER = "administer";

    private AdministrationSurfaceSpecification() {
    }

    /**
     * One declared call.
     *
     * @param call   the call, in snake case
     * @param method the HTTP method
     * @param path   the route, exactly as it must be registered
     * @param access {@link #READ} or {@link #ADMINISTER}
     */
    public record Row(String call, String method, String path, String access) {

        /** {@code METHOD path}, which is how a route is compared. */
        public String route() {
            return method + " " + path;
        }
    }

    /** Every row of the declaration. */
    public static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        try (InputStream in = AdministrationSurfaceSpecification.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            assertThat(in).as("the administration declaration must be on the classpath")
                .isNotNull();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines().toList()) {
                String row = line.strip();
                if (row.isEmpty() || row.startsWith("#") || row.startsWith("call\t")) {
                    continue;
                }
                String[] cells = row.split("\t");
                assertThat(cells.length)
                    .as("every row of the declaration carries %d cells: '%s'", CELLS, row)
                    .isEqualTo(CELLS);
                assertThat(cells[3]).as("the access of '%s'", row).isIn(READ, ADMINISTER);
                rows.add(new Row(cells[0], cells[1], cells[2], cells[3]));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(rows).as("an empty expectation would pass closure at once").isNotEmpty();
        return rows;
    }

    /** The routes of the declaration, as {@code METHOD path}. */
    public static Set<String> routes() {
        return rows().stream().map(Row::route)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** The calls of the declaration that ask for the named access. */
    public static Set<String> callsAsking(String access) {
        return rows().stream().filter(row -> access.equals(row.access())).map(Row::call)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Every call of the declaration. */
    public static Set<String> calls() {
        return rows().stream().map(Row::call)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
