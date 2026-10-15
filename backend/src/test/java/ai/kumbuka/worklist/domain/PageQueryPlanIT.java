/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.Db;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.worklist.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The planner walks an index in the order of a page and sorts nothing, on every
 * view, for every sort a page can be asked in, from the start and from a
 * position deep in the set — asked about the statement the service issues for
 * that page, as the service role, with the tenant bound.
 *
 * <p>Each view holds several thousand objects in the scope under test, as many
 * again in a second scope of the same tenant and in a scope of a neighbour
 * tenant, with creation instants and ranks scattered, and the tables are
 * analysed. The statement is the one Hibernate prepares for the page, recorded
 * by {@link RecordedStatements} and handed to {@code EXPLAIN ANALYZE}
 * unchanged with the values the page binds, so the plan belongs to the
 * statement that runs and not to one written to resemble it.
 *
 * <p>Two things are asserted of each plan: no sort node, and no scan that
 * reads — returns or removes by filter — more rows than about a page. A plan
 * that walks the right index but reads every row before the cursor and
 * filters them away is as wrong as one that sorts, and only the second half
 * catches it. Every plan is written to {@code target/page-plans/}.
 *
 * <p>RED STATE, observed 2026-10-13 on {@code 0213866}: the continuation was
 * written as {@code lead > :a OR (lead = :a AND number > :n)}, which the
 * planner cannot take as an index condition, so a continued page in creation
 * or rank order walked the index from the start of the scope and removed every
 * row before the position by filter; and no index served the rank order, so
 * every rank page sorted the whole scope. The plans are in the return of the
 * dispatch that fixed both.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class PageQueryPlanIT {

    /** Objects per view and scope: enough that a sort or a filtered walk shows. */
    private static final int OBJECTS = 4000;
    private static final int PAGE = 50;

    /** The most rows one scan may touch for a page: the page, its look-ahead, slack. */
    private static final int READ_BOUND = 2 * (PAGE + 1);

    private static final UUID NEIGHBOUR = UUID.fromString("5e1f0c2a-3b4d-4e6f-8a9b-0c1d2e3f4a5b");

    private static final Path PLANS = Path.of("target", "page-plans");

    private static UUID scope;
    private static UUID tenant;

    @Inject ItemService items;
    @Inject IterationService iterations;
    @Inject MilestoneService milestones;
    @Inject WorkstreamService workstreams;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject ScopeSettingService settings;
    @Inject TenantContext tenants;

    @BeforeEach
    void stageOnce() throws Exception {
        if (scope != null) {
            return;
        }
        tenant = UUID.fromString(ConfigProvider.getConfig()
            .getValue("worklist.tenant-id", String.class));
        UUID under = aScopeWithOneOfEach();
        UUID beside = aScopeWithOneOfEach();
        UUID neighbour;
        try (AutoCloseable inNeighbour = tenants.bind(NEIGHBOUR)) {
            neighbour = aScopeWithOneOfEach();
        }
        try (Connection admin = Db.asAdmin()) {
            for (String table : Selector.VIEWS) {
                for (UUID in : List.of(under, beside, neighbour)) {
                    multiply(admin, table, in);
                }
                Db.exec(admin, "ANALYZE worklist." + table);
            }
            admin.commit();
        }
        scope = under;
    }

    // ==================================================================
    // The probe
    // ==================================================================

    static Stream<Arguments> pages() {
        List<Arguments> cases = new ArrayList<>();
        for (String view : Selector.VIEWS) {
            for (SortKey sort : SortKey.values()) {
                if (!sort.declaredOn(view)) {
                    continue;
                }
                for (SortKey.Direction direction : SortKey.Direction.values()) {
                    cases.add(Arguments.of(view, sort, direction, false));
                    cases.add(Arguments.of(view, sort, direction, true));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} by {1} {2}, continued: {3}")
    @MethodSource("pages")
    void a_page_walks_an_index_in_its_order_and_reads_about_a_page(
            String view, SortKey sort, SortKey.Direction direction, boolean continued)
            throws Exception {
        PageCursor.Position after = continued ? middleOf(view, sort, direction) : null;
        PageRequest request = new PageRequest(scope, view, Map.of(), PAGE, sort, direction, after);

        String sql = statementOf(view, request);
        String plan = explain(sql, after);
        keepPlan(view, sort, direction, continued, sql, plan);

        assertThat(plan)
            .as("the planner sorts nothing for a page in the order of an index:%n%s", plan)
            .doesNotContain("Sort");
        assertThat(touched(plan))
            .as("no scan reads more than about a page, kept or removed by filter:%n%s", plan)
            .allMatch(rows -> rows <= READ_BOUND);
    }

    // ==================================================================
    // The statement and its plan
    // ==================================================================

    private String statementOf(String view, PageRequest request) {
        BiFunction<UUID, PageRequest, Page> query = switch (view) {
            case Selector.ITEM -> items::query;
            case Selector.ITERATION -> iterations::query;
            case Selector.MILESTONE -> milestones::query;
            case Selector.WORKSTREAM -> workstreams::query;
            default -> throw new IllegalArgumentException(view);
        };
        RecordedStatements.start();
        List<String> recorded;
        try {
            assertThat(query.apply(scope, request).objects()).as("a full page").hasSize(PAGE);
        } finally {
            recorded = RecordedStatements.stop();
        }
        return recorded.stream()
            .filter(sql -> sql.contains("from worklist." + view + " ") && sql.contains("order by"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no page statement among " + recorded));
    }

    /** What precedes a placeholder decides the value it takes. */
    private static final Pattern SCOPE_PARAMETER = Pattern.compile("scope_id\\s*=\\s*$");
    private static final Pattern TENANT_PARAMETER = Pattern.compile("tenant_id\\s*=\\s*$");
    private static final Pattern LEAD_PARAMETER =
        Pattern.compile("(created_at|rank)\\s*(<|>|=)\\s*$|(<|>)\\s*\\(\\s*$");
    private static final Pattern NUMBER_PARAMETER = Pattern.compile("number\\s*(<|>)\\s*$");
    private static final Pattern TUPLE_SECOND = Pattern.compile(",\\s*$");
    private static final Pattern BOUND_PARAMETER = Pattern.compile("(fetch first|limit)\\s*$");

    /**
     * The plan of a statement as the service role runs it, with the tenant bound
     * and each placeholder given the value its context names: the scope, the
     * tenant, the position's lead and number, the bound of the page.
     */
    private String explain(String sql, PageCursor.Position after) throws SQLException {
        StringBuilder numbered = new StringBuilder();
        List<String> literals = new ArrayList<>();
        boolean leadTaken = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch != '?') {
                numbered.append(ch);
                continue;
            }
            String before = sql.substring(Math.max(0, i - 40), i).toLowerCase();
            String literal;
            if (SCOPE_PARAMETER.matcher(before).find()) {
                literal = "'" + scope + "'";
            } else if (TENANT_PARAMETER.matcher(before).find()) {
                literal = "'" + tenant + "'";
            } else if (LEAD_PARAMETER.matcher(before).find()) {
                literal = literalOf(after.lead());
                leadTaken = true;
            } else if (NUMBER_PARAMETER.matcher(before).find()
                    || (leadTaken && TUPLE_SECOND.matcher(before).find())) {
                literal = String.valueOf(after.number());
            } else if (BOUND_PARAMETER.matcher(before).find()) {
                literal = String.valueOf(PAGE + 1);
            } else {
                throw new AssertionError("no value for the placeholder after '" + before
                    + "' in " + sql);
            }
            literals.add(literal);
            numbered.append('$').append(literals.size());
        }
        try (Connection c = Db.asService(); Statement s = c.createStatement()) {
            try {
                Db.bindTenant(c, tenant);
                s.execute("PREPARE page_probe AS " + numbered);
                List<String> plan = new ArrayList<>();
                try (ResultSet rs = s.executeQuery("EXPLAIN (ANALYZE, TIMING OFF, SUMMARY OFF) "
                        + "EXECUTE page_probe" + (literals.isEmpty() ? ""
                        : "(" + String.join(", ", literals) + ")"))) {
                    while (rs.next()) {
                        plan.add(rs.getString(1));
                    }
                }
                return String.join("\n", plan);
            } finally {
                c.rollback();
            }
        }
    }

    private static String literalOf(Object lead) {
        return switch (lead) {
            case Number number -> number.toString();
            default -> "'" + lead + "'";
        };
    }

    private static final Pattern SCAN_ROWS = Pattern.compile("Scan.*actual rows=(\\d+)");
    private static final Pattern REMOVED = Pattern.compile("Rows Removed by Filter: (\\d+)");

    /** Per scan node, the rows it returned plus the rows it read and removed by filter. */
    private static List<Integer> touched(String plan) {
        List<Integer> touched = new ArrayList<>();
        for (String line : plan.split("\n")) {
            Matcher scan = SCAN_ROWS.matcher(line);
            if (scan.find()) {
                touched.add(Integer.parseInt(scan.group(1)));
                continue;
            }
            Matcher removed = REMOVED.matcher(line);
            if (removed.find() && !touched.isEmpty()) {
                int last = touched.size() - 1;
                touched.set(last, touched.get(last) + Integer.parseInt(removed.group(1)));
            }
        }
        assertThat(touched).as("the plan scans something:%n%s", plan).isNotEmpty();
        return touched;
    }

    private static void keepPlan(String view, SortKey sort, SortKey.Direction direction,
                               boolean continued, String sql, String plan) throws IOException {
        Files.createDirectories(PLANS);
        Files.writeString(PLANS.resolve(view + "-" + sort.wireName() + "-" + direction.wireName()
            + (continued ? "-continued" : "-first") + ".txt"), sql + "\n\n" + plan + "\n");
    }

    // ==================================================================
    // The data
    // ==================================================================

    /** The position in the middle of the scope under test, in this order. */
    private PageCursor.Position middleOf(String view, SortKey sort, SortKey.Direction direction)
            throws SQLException {
        String lead = switch (sort) {
            case ID -> "number";
            case CREATED_AT -> "created_at";
            case RANK -> "rank";
        };
        String way = direction == SortKey.Direction.ASC ? " ASC" : " DESC";
        try (Connection admin = Db.asAdmin();
             PreparedStatement middle = admin.prepareStatement("SELECT number, created_at, "
                 + (SortKey.RANK.declaredOn(view) ? "rank" : "0") + " FROM worklist." + view
                 + " WHERE scope_id = ? ORDER BY " + lead + way + ", number" + way
                 + " OFFSET " + (OBJECTS / 2) + " LIMIT 1")) {
            middle.setObject(1, scope);
            try (ResultSet rs = middle.executeQuery()) {
                assertThat(rs.next()).as("a middle of the " + view + " view").isTrue();
                Timestamp created = rs.getTimestamp(2);
                return PageCursor.Position.of(sort, rs.getLong(1), created.toInstant(),
                    rs.getInt(3));
            } finally {
                admin.rollback();
            }
        }
    }

    /** A scope with every view declared and one object of each, numbered 1 or more. */
    private UUID aScopeWithOneOfEach() {
        UUID id = UUID.randomUUID();
        settings.create(id, Map.of(
            "max_planned_iterations", 10,
            "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10,
            "warn_memberships_per_iteration", 9));
        for (String view : Selector.VIEWS) {
            selectors.declare(id, view);
        }
        vocabulary.declareStatus(id, "open", 1, true, false, false, false);
        workstreams.requireDefault(id);
        workstreams.declare(id, "probe", "the template of the probe's workstreams");
        items.create(id, Map.of("title", "probe", "status", "open"));
        milestones.create(id, Map.of("title", "probe"));
        iterations.create(id, Map.of("title", "probe", "motto", "m", "description", "d"));
        return id;
    }

    /**
     * Copies the scope's highest-numbered object of the view {@link #OBJECTS}
     * times under fresh numbers, with scattered creation instants and ranks.
     * The column list is read from the catalogue, so a column added later is
     * copied rather than forgotten.
     */
    private static void multiply(Connection admin, String table, UUID in) throws SQLException {
        List<String[]> columns = new ArrayList<>();
        try (PreparedStatement s = admin.prepareStatement("SELECT column_name, data_type "
                + "FROM information_schema.columns WHERE table_schema = 'worklist' "
                + "AND table_name = ? AND is_identity = 'NO' AND is_generated = 'NEVER' "
                + "ORDER BY ordinal_position")) {
            s.setString(1, table);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    columns.add(new String[] {rs.getString(1), rs.getString(2)});
                }
            }
        }
        String names = columns.stream().map(c -> c[0]).collect(Collectors.joining(", "));
        String values = columns.stream().map(c -> switch (c[0]) {
            case "number" -> "t.number + g";
            case "created_at" ->
                "timestamptz '2026-01-01' + (random() * 1000000)::int * interval '1 second'";
            case "rank" -> "(random() * 1000)::int";
            case "token" -> "t.token || '-' || g";
            case "id" -> "uuid".equals(c[1]) ? "gen_random_uuid()" : "t.id";
            default -> "t." + c[0];
        }).collect(Collectors.joining(", "));
        Db.exec(admin, "INSERT INTO worklist." + table + " (" + names + ") SELECT " + values
            + " FROM worklist." + table + " t, generate_series(1, " + OBJECTS + ") g "
            + "WHERE t.scope_id = '" + in + "' AND t.number = (SELECT max(number) FROM worklist."
            + table + " WHERE scope_id = '" + in + "')");
    }
}
