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
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conflict token is the modification column at microsecond precision,
 * written by the application from the injected clock as {@code max(now,
 * previous + 1 µs)} on every write that changes the root, and left as it is
 * by a write that changes nothing (C10.1, C20.1). Each of the five roots is
 * shown on its own, because each moves its own column.
 *
 * <p>The clock is the test's: {@link SettableClock} holds one instant, so two
 * writes fall into the same microsecond by construction and the expected
 * tokens are written here from that instant, not read from the service.
 *
 * <p>The column {@code conflict_token} the previous image compares is kept
 * in step with the token (N-1): read here off the row, under the runtime
 * role, it carries the token the answer handed out.
 *
 * <p>RED STATE, observed against worklist {@code b20d27f}: the token was a
 * random uuid, so the three cases that expect an instant failed on the first
 * token they read ({@code expected: "2031-03-04T05:06:07.123456Z" but was} a
 * uuid); the case on the write that changes nothing was green there too, as
 * it must be.
 */
@QuarkusTest
@TestProfile(SettableClock.Profile.class)
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ConflictTokenIT {

    private static final Instant T = Instant.parse("2031-03-04T05:06:07.123456Z");

    @Inject ItemService items;
    @Inject IterationService iterations;
    @Inject MilestoneService milestones;
    @Inject WorkstreamService workstreams;
    @Inject ScopeSettingService settings;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject SettableClock clock;

    private UUID scope;
    private String openStatus;

    @BeforeEach
    void aScopeOfItsOwn() {
        clock.set(T.minusSeconds(86_400));
        scope = UUID.randomUUID();
        selectors.declare(scope, Selector.ITEM);
        openStatus = vocabulary.declareStatus(scope, "open", 1,
            true, false, false, false).name;
        settings.create(scope, Map.of(
            "max_planned_iterations", 10,
            "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10,
            "warn_memberships_per_iteration", 9));
        clock.set(T);
    }

    @Test
    void two_writes_in_the_same_microsecond_yield_two_tokens() throws SQLException {
        Map<String, Object> created = item("first");
        assertThat(tokenOf(created))
            .as("the token of a new item is the clock's instant at the microsecond")
            .isEqualTo(token(T));

        Map<String, Object> once = renameItem(created, "second");
        Map<String, Object> twice = renameItem(once, "third");

        assertThat(tokenOf(once))
            .as("a write in the same microsecond moves the token by one microsecond")
            .isEqualTo(token(T.plus(1, ChronoUnit.MICROS)));
        assertThat(tokenOf(twice)).isEqualTo(token(T.plus(2, ChronoUnit.MICROS)));
        assertThat(storedToken("item", idOf(twice)))
            .as("the column the previous image compares carries the same token")
            .isEqualTo(tokenOf(twice));
    }

    @Test
    void the_token_follows_the_clock_and_never_returns_to_an_earlier_value() {
        Map<String, Object> created = item("first");

        Instant later = T.plusSeconds(60);
        clock.set(later);
        Map<String, Object> forward = renameItem(created, "second");
        assertThat(tokenOf(forward)).as("the clock moved on, so does the token")
            .isEqualTo(token(later));

        clock.set(T.minusSeconds(3600));
        Map<String, Object> back = renameItem(forward, "third");
        assertThat(tokenOf(back))
            .as("a clock set back moves the token past the last one, never to the clock")
            .isEqualTo(token(later.plus(1, ChronoUnit.MICROS)));
    }

    @Test
    void a_write_that_changes_nothing_keeps_the_token() throws SQLException {
        Map<String, Object> created = item("unchanged");
        clock.set(T.plusSeconds(5));

        Map<String, Object> same = renameItem(created, "unchanged");

        assertThat(tokenOf(same))
            .as("the title written is the title stored: nothing changed, neither does the token")
            .isEqualTo(tokenOf(created));
        assertThat(tokenOf(items.read(scope, idOf(created)))).isEqualTo(tokenOf(created));
        assertThat(storedToken("item", idOf(created))).isEqualTo(tokenOf(created));
    }

    @Test
    void an_iteration_takes_its_token_the_same_way() throws SQLException {
        Map<String, Object> created = iterations.create(scope, Map.of(
            Field.TITLE.canonicalName(), "first",
            Field.MOTTO.canonicalName(), "a motto",
            Field.DESCRIPTION.canonicalName(), "a description"));
        assertThat(tokenOf(created)).isEqualTo(token(T));

        Map<String, Object> once = iterations.update(scope, idOf(created), Map.of(
            Field.TITLE.canonicalName(), "second",
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(created)));
        Map<String, Object> same = iterations.update(scope, idOf(created), Map.of(
            Field.TITLE.canonicalName(), "second",
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(once)));

        assertThat(tokenOf(once)).isEqualTo(token(T.plus(1, ChronoUnit.MICROS)));
        assertThat(tokenOf(same)).as("a no-op keeps it").isEqualTo(tokenOf(once));
        assertThat(storedToken("iteration", idOf(created))).isEqualTo(tokenOf(once));
    }

    @Test
    void a_milestone_takes_its_token_the_same_way() throws SQLException {
        Map<String, Object> created = milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), "first",
            Field.KIND.canonicalName(), Milestone.GOAL,
            Field.VISION.canonicalName(), "a vision",
            Field.MISSION.canonicalName(), "a mission"));
        assertThat(tokenOf(created)).isEqualTo(token(T));

        Map<String, Object> once = milestones.update(scope, idOf(created), Map.of(
            Field.TITLE.canonicalName(), "second",
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(created)));
        Map<String, Object> twice = milestones.update(scope, idOf(created), Map.of(
            Field.TITLE.canonicalName(), "third",
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(once)));
        Map<String, Object> same = milestones.update(scope, idOf(created), Map.of(
            Field.TITLE.canonicalName(), "third",
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(twice)));

        assertThat(tokenOf(once)).isEqualTo(token(T.plus(1, ChronoUnit.MICROS)));
        assertThat(tokenOf(twice)).isEqualTo(token(T.plus(2, ChronoUnit.MICROS)));
        assertThat(tokenOf(same)).as("a no-op keeps it").isEqualTo(tokenOf(twice));
        assertThat(storedToken("milestone", idOf(created))).isEqualTo(tokenOf(twice));
    }

    @Test
    void a_workstream_takes_its_token_the_same_way() throws SQLException {
        Workstream created = workstreams.declare(scope, "tokened", "first");
        assertThat(created.conflictToken()).isEqualTo(token(T));

        String once = workstreams.update(scope, created.id, "second",
            created.conflictToken()).conflictToken();
        String twice = workstreams.update(scope, created.id, "third", once).conflictToken();
        String same = workstreams.update(scope, created.id, "third", twice).conflictToken();

        assertThat(once).isEqualTo(token(T.plus(1, ChronoUnit.MICROS)));
        assertThat(twice).isEqualTo(token(T.plus(2, ChronoUnit.MICROS)));
        assertThat(same).as("a no-op keeps it").isEqualTo(twice);
        assertThat(tokenOf(workstreams.read(scope, created.id))).isEqualTo(twice);
        assertThat(storedToken("workstream", created.id)).isEqualTo(twice);
    }

    @Test
    void a_scope_setting_takes_its_token_the_same_way() throws SQLException {
        Field warn = Field.WARN_PLANNED_ITERATIONS;
        Map<String, Object> created = settings.read(scope);

        Map<String, Object> once = settings.update(scope, Map.of(
            warn.canonicalName(), 8,
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(created)));
        Map<String, Object> twice = settings.update(scope, Map.of(
            warn.canonicalName(), 7,
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(once)));
        Map<String, Object> same = settings.update(scope, Map.of(
            warn.canonicalName(), 7,
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(twice)));

        assertThat(tokenOf(once))
            .as("the settings were created a day earlier, so the first write takes the clock")
            .isEqualTo(token(T));
        assertThat(tokenOf(twice)).isEqualTo(token(T.plus(1, ChronoUnit.MICROS)));
        assertThat(tokenOf(same)).as("a no-op keeps it").isEqualTo(tokenOf(twice));
        assertThat(storedToken("scope_setting", "scope_id", scope)).isEqualTo(tokenOf(twice));
    }

    // -----------------------------------------------------------------------

    private Map<String, Object> item(String title) {
        return items.create(scope, Map.of(
            Field.TITLE.canonicalName(), title,
            Field.STATUS.canonicalName(), openStatus));
    }

    private Map<String, Object> renameItem(Map<String, Object> item, String title) {
        return items.update(scope, idOf(item), Map.of(
            Field.TITLE.canonicalName(), title,
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(item)));
    }

    /** What the row's {@code conflict_token} column holds, read under the runtime role. */
    private static String storedToken(String table, UUID id) throws SQLException {
        return storedToken(table, "id", id);
    }

    /** The same, for a table whose row is named by another column than {@code id}. */
    private static String storedToken(String table, String key, UUID id) throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, PlanningFixture.boundTenant());
            try (var st = c.prepareStatement(
                    "SELECT conflict_token FROM worklist." + table + " WHERE " + key + " = ?")) {
                st.setObject(1, id);
                try (ResultSet rows = st.executeQuery()) {
                    assertThat(rows.next()).as("the row is there").isTrue();
                    return rows.getString(1);
                }
            }
        }
    }

    private static UUID idOf(Map<String, Object> fields) {
        return UUID.fromString(String.valueOf(fields.get(Field.ID.canonicalName())));
    }

    private static String tokenOf(Map<String, Object> fields) {
        return String.valueOf(fields.get(Field.CONFLICT_TOKEN.canonicalName()));
    }

    /** The token an instant renders as: ISO-8601, UTC, cut to the microsecond. */
    private static String token(Instant at) {
        return at.truncatedTo(ChronoUnit.MICROS).toString();
    }
}
