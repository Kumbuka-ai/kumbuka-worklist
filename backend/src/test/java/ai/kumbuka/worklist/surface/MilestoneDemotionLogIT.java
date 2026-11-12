/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.Milestone;
import ai.kumbuka.worklist.domain.MilestoneService;
import ai.kumbuka.worklist.domain.ScopeSettingService;
import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.domain.WorkstreamService;
import ai.kumbuka.worklist.fixture.LogLines;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Activating a milestone demotes the one that was active, and the demotion
 * leaves exactly one DEBUG line on the platform facade: by the number of the
 * demoted milestone and the scope's id (DEC-0051). An activation with no
 * milestone active before it leaves none.
 *
 * <p>The write goes through the REST surface, so the line is the one
 * {@link VerbSurface} writes when the domain's answer names a demotion. Each
 * case has a scope of its own, so neither sees the other's active milestone.
 *
 * <p>The expected text is written here from the order that brought the line
 * back: {@code milestone <number> demoted in scope <scope-id>}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class MilestoneDemotionLogIT {

    private static final String WITH_ACTIVE = "demotion-log-probe";
    private static final UUID WITH_ACTIVE_ID =
        UUID.fromString("a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d");

    private static final String WITHOUT_ACTIVE = "demotion-log-probe-empty";
    private static final UUID WITHOUT_ACTIVE_ID =
        UUID.fromString("b2c3d4e5-6f7a-4b8c-9d0e-1f2a3b4c5d6e");

    @Inject TestIdentityAssociation identity;
    @Inject ScopeSettingService settings;
    @Inject SelectorRegistry selectors;
    @Inject WorkstreamService workstreams;
    @Inject MilestoneService milestones;

    /** DEBUG is below the configured level of the service's category, so it is lifted here. */
    private final Logger category = Logger.getLogger(PlatformLog.class.getName());
    private Level configured;

    @BeforeEach
    void debugOn() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);
        configured = category.getLevel();
        category.setLevel(Level.FINE);
    }

    @AfterEach
    void levelBack() {
        category.setLevel(configured);
    }

    @Test
    void activating_a_milestone_while_another_is_active_logs_one_demotion() {
        stage(WITH_ACTIVE, WITH_ACTIVE_ID);
        Map<String, Object> first = milestones.create(WITH_ACTIVE_ID, Map.of(
            "title", "the goal that was active", "vision", "a north star")).answer();
        milestones.update(WITH_ACTIVE_ID, (UUID) first.get("id"), Map.of(
            "status", Milestone.ACTIVE, "conflict_token", first.get("conflict_token")));
        Map<String, Object> second = milestones.create(WITH_ACTIVE_ID, Map.of(
            "title", "the goal that takes over", "vision", "another north star")).answer();

        List<LogRecord> lines = LogLines.of(PlatformLog.class, () ->
            activate(WITH_ACTIVE, second));

        assertThat(demotions(lines))
            .as("exactly one DEBUG line, by the number of the milestone that was active and "
                + "the scope's id")
            .singleElement()
            .satisfies(line -> {
                assertThat(line.getLevel().intValue()).isEqualTo(Level.FINE.intValue());
                assertThat(LogLines.text(line)).isEqualTo("milestone " + first.get("number")
                    + " demoted in scope " + WITH_ACTIVE_ID);
            });
    }

    @Test
    void activating_a_milestone_with_none_active_logs_no_demotion() {
        stage(WITHOUT_ACTIVE, WITHOUT_ACTIVE_ID);
        Map<String, Object> only = milestones.create(WITHOUT_ACTIVE_ID, Map.of(
            "title", "the first goal", "vision", "a north star")).answer();

        List<LogRecord> lines = LogLines.of(PlatformLog.class, () ->
            activate(WITHOUT_ACTIVE, only));

        assertThat(lines)
            .as("the control: the activation was written and logged at all, so the absence "
                + "below is not a silent logger")
            .extracting(LogLines::text)
            .contains("update " + only.get("number") + " in scope " + WITHOUT_ACTIVE_ID);
        assertThat(demotions(lines))
            .as("no milestone was active, so nothing was demoted and no line says so")
            .isEmpty();
    }

    /** Sets the milestone active over the REST surface, with its conflict token. */
    private static void activate(String scope, Map<String, Object> milestone) {
        given()
            .header("If-Match", String.valueOf(milestone.get("conflict_token")))
            .contentType(ContentType.JSON)
            .body(Map.of("status", Milestone.ACTIVE))
            .when().patch("/api/" + scope + "/" + Selector.MILESTONE + "/"
                + milestone.get("number"))
            .then().statusCode(200);
    }

    private static List<LogRecord> demotions(List<LogRecord> lines) {
        return lines.stream()
            .filter(line -> LogLines.text(line).contains(" demoted in scope "))
            .toList();
    }

    /** A scope of this case's own, visible to the member, with the goal axis open. */
    private void stage(String slug, UUID id) {
        SurfaceFixture.publishScope(slug, id, "project", false);
        settings.create(id, Map.of(
            "max_planned_iterations", 10,
            "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10,
            "warn_memberships_per_iteration", 9));
        selectors.open(id, Selector.MILESTONE);
        workstreams.requireDefault(id);
    }
}
