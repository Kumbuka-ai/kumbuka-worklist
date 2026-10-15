/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.ItemService;
import ai.kumbuka.worklist.domain.IterationService;
import ai.kumbuka.worklist.domain.Milestone;
import ai.kumbuka.worklist.domain.MilestoneService;
import ai.kumbuka.worklist.domain.QuerySpec;
import ai.kumbuka.worklist.domain.ScopeSettingService;
import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.domain.SortKey;
import ai.kumbuka.worklist.domain.VocabularyRegistry;
import ai.kumbuka.worklist.domain.Workstream;
import ai.kumbuka.worklist.domain.WorkstreamService;
import ai.kumbuka.worklist.domain.WorklistException;
import ai.kumbuka.worklist.platform.PlatformFixture;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.worklist.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every filter and every page bound of {@code query} narrows the answer or is
 * refused, on REST and on MCP alike (REQ-0156).
 *
 * <h2>What is being defended</h2>
 *
 * <p>A filter accepted and dropped answers the whole collection, and the whole
 * collection looks exactly like a correct narrow one. So every probe below is
 * run against a data set in which the narrowing MUST exclude at least one
 * object, and it asserts the exclusion by address, not by count alone.
 *
 * <ul>
 * <li>Each filter the four views declare ({@code QueryFilter}) admits the
 *     object it must and excludes the one it must.</li>
 * <li>A filter name a view does not declare, a value a declared filter cannot
 *     interpret, and a value the scope never declared are each refused with a
 *     typed refusal naming the argument under {@code data.offenders} — never
 *     answered with the collection.</li>
 * <li>A page size answers at most that many objects and a {@code next_cursor};
 *     two or more pages followed to the end carry every object exactly once, in
 *     the one-page order, on every view, in every sort and direction offered.</li>
 * <li>A page size out of range, a sort the view does not declare or the
 *     transport does not offer, and a cursor this service did not hand out or
 *     handed out to another query are refused as the paging refusals —
 *     {@code PAGE_SIZE_REJECTED}, {@code SORT_UNDECLARED},
 *     {@code CURSOR_MALFORMED}, {@code CURSOR_FOREIGN} — in the one envelope,
 *     400 on REST. A direction other than {@code asc} or {@code desc} is
 *     {@code ARGUMENT_INVALID}, 400.</li>
 * <li>An argument the read does not take at all — a REST query parameter or an
 *     MCP argument outside the declared set — is refused, not ignored, as
 *     {@code ARGUMENT_UNKNOWN} with 400; a filter field the view does not
 *     declare is {@code PREDICATE_UNKNOWN} with 422, and only behind scope
 *     visibility (DEC-0042). The measured defect of 2026-09-24
 *     ({@code filter {milestone: 6}} answering the full stock) is exactly a
 *     narrowing that never reached the filter.</li>
 * <li>{@code Kumbuka-Surface: assistant} makes a REST read on the assistant
 *     surface, and the service MCP's text content is its answer as JSON.</li>
 * </ul>
 *
 * <p>Both transports run every shared probe, because the obligation is that the
 * check sits in one place and both adapters reach it; a probe on one transport
 * alone would not see an adapter that bypasses it.
 *
 * <h2>The red state, and how it was observed</h2>
 *
 * The class was written before the change and run against {@code origin/main}
 * at {@code ca0b5cf}. RED STATE, observed 2026-10-09: the axis filters were
 * refused as {@code PAYLOAD_MALFORMED} without offenders, an MCP filter sent
 * as a JSON string and an MCP limit sent as a string were dropped silently
 * (full set, default limit), a REST parameter {@code ?milestone=} was ignored
 * and the full stock answered, a limit of 0 or below answered the default
 * silently, an empty status value answered nothing at all, a milestone
 * number nobody holds answered {@code NOT_FOUND} without naming the filter,
 * and no answer carried a way to the rest.
 *
 * <p>After the change, each rule was removed on its own and this class run
 * again (2026-10-09); every removal turned the named probes red on both
 * transports, and nothing else:
 *
 * <ul>
 * <li>axis filter ignored in {@code QueryAnswer.page} — the iteration,
 *     milestone and workstream narrowing probes;</li>
 * <li>item filter dropped in {@code ItemRepository.inScope} — the three item
 *     narrowing probes and the filtered page walk;</li>
 * <li>untaken arguments ignored — the REST parameter and MCP argument
 *     probes;</li>
 * <li>a non-object filter read as none — the MCP filter-as-string probe;</li>
 * <li>a limit below 1 read as the default — the limit range probe;</li>
 * <li>the cursor ignored — the cursor refusal and every page walk;</li>
 * <li>no {@code next_cursor} handed out — every page walk and the default
 *     limit probe;</li>
 * <li>the item keyset dropped — the item page walks;</li>
 * <li>an undeclared filter name skipped — the two undeclared-name
 *     probes;</li>
 * <li>a lenient boolean form — the uninterpretable-value probe;</li>
 * <li>an undeclared status answered as empty — the undeclared-value
 *     probe;</li>
 * <li>a workstream token refused as not-found — the create probe and the
 *     undeclared-value probe.</li>
 * </ul>
 *
 * <p>The paging contract changed on 2026-10-11: {@code limit} and
 * {@code cursor} became {@code page_size} and {@code after}, the answer lost
 * {@code truncated}, the axes page by keyset in the database instead of in
 * memory, and a sort was added. The probes of that change carry their own red
 * state where they stand; the keyset itself is probed in {@code KeysetPagingIT}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class QueryNarrowingIT {

    private static final String SCOPE = "narrowing-probe";
    private static final UUID SCOPE_ID = UUID.fromString("5b0c1d4e-6a2f-4b8e-9c3d-1e2f3a4b5c6d");

    /** A second scope, so a cursor has another scope to come from. */
    private static final String OTHER_SCOPE = "narrowing-probe-other";
    private static final UUID OTHER_SCOPE_ID =
        UUID.fromString("6c1d2e5f-7b3a-4c9f-8d4e-2f3a4b5c6d7e");

    /** The header a hop names the caller's surface with, and its one value. */
    private static final String SURFACE = "Kumbuka-Surface";
    private static final String ASSISTANT = "assistant";

    /** A second tenant, holding a scope of the same name as the probe scope. */
    private static final UUID TENANT_B = UUID.fromString("7d2e3f60-8c4b-4d0a-9e5f-3a4b5c6d7e8f");
    private static final UUID SCOPE_IN_B = UUID.fromString("8e3f4a71-9d5c-4e1b-8f60-4b5c6d7e8f90");

    private static final String OPEN = "open";
    private static final String DONE = "done";

    @Inject TestIdentityAssociation identity;
    @Inject ScopeSettingService settings;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject WorkstreamService workstreams;
    @Inject MilestoneService milestones;
    @Inject IterationService iterations;
    @Inject ItemService items;
    @Inject VerbSurface verbs;
    @Inject TenantContext tenants;

    /** The staged objects, by role, as canonical addresses. Staged once. */
    private static Staged staged;

    @BeforeEach
    void aScopeWhereEveryFilterHasSomethingToExclude() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);
        if (staged == null) {
            staged = stage();
        }
    }

    // ======================================================================
    // Probe group 1 — every declared filter admits and excludes
    // ======================================================================

    @ParameterizedTest
    @EnumSource(Transport.class)
    void item_status_narrows(Transport via) {
        Answer answer = via.query(Selector.ITEM, Map.of("status", OPEN), null, null);

        assertNarrowed(answer, staged.openItems(), staged.doneItems(), "item status");
        answer.fields().forEach(row -> assertThat(row.get("status")).isEqualTo(OPEN));
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void item_milestone_narrows(Transport via) {
        Answer answer = via.query(Selector.ITEM,
            Map.of("milestone", staged.activeMilestoneNumber()), null, null);

        assertNarrowed(answer, List.of(staged.itemOnActiveMilestone()),
            without(staged.allItems(), staged.itemOnActiveMilestone()), "item milestone");
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void item_workstream_narrows(Transport via) {
        Answer answer = via.query(Selector.ITEM, Map.of("workstream", "alpha"), null, null);

        assertNarrowed(answer, staged.alphaItems(),
            without(staged.allItems(), staged.alphaItems()), "item workstream");
        answer.fields().forEach(row -> assertThat(row.get("workstream")).isEqualTo("alpha"));
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void iteration_closed_narrows(Transport via) {
        Answer closed = via.query(Selector.ITERATION, Map.of("closed", true), null, null);
        assertNarrowed(closed, List.of(staged.closedIteration()),
            List.of(staged.openIteration()), "iteration closed=true");

        Answer open = via.query(Selector.ITERATION, Map.of("closed", "false"), null, null);
        assertNarrowed(open, List.of(staged.openIteration()),
            List.of(staged.closedIteration()), "iteration closed=false");
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void milestone_status_narrows(Transport via) {
        Answer answer = via.query(Selector.MILESTONE,
            Map.of("status", Milestone.ACTIVE), null, null);

        assertNarrowed(answer, List.of(staged.activeMilestone()),
            List.of(staged.plannedMilestone(), staged.markerMilestone()), "milestone status");
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void milestone_kind_narrows(Transport via) {
        Answer answer = via.query(Selector.MILESTONE,
            Map.of("kind", Milestone.OFF_PATH), null, null);

        assertNarrowed(answer, List.of(staged.markerMilestone()),
            List.of(staged.activeMilestone(), staged.plannedMilestone()), "milestone kind");
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void workstream_status_narrows(Transport via) {
        Answer answer = via.query(Selector.WORKSTREAM,
            Map.of("status", Workstream.WITHDRAWN), null, null);

        assertNarrowed(answer, List.of(staged.withdrawnWorkstream()),
            List.of(staged.alphaWorkstream()), "workstream status");
    }

    // ======================================================================
    // Probe group 2 — a filter name the view does not declare is refused
    // ======================================================================

    @ParameterizedTest
    @EnumSource(Transport.class)
    void an_undeclared_filter_name_is_refused_on_every_view(Transport via) {
        for (String view : Selector.VIEWS) {
            Answer answer = via.query(view, Map.of("colour", "blue"), null, null);
            assertRefused(answer, "PREDICATE_UNKNOWN", "filter", view);
        }
    }

    /**
     * The filter that names a field of ANOTHER view: {@code milestone} is an
     * item filter and means nothing on the iteration view. Refused, not
     * answered with every iteration.
     */
    @ParameterizedTest
    @EnumSource(Transport.class)
    void a_filter_declared_on_another_view_is_refused(Transport via) {
        Answer answer = via.query(Selector.ITERATION, Map.of("milestone", 6), null, null);
        assertRefused(answer, "PREDICATE_UNKNOWN", "filter", "iteration");
    }

    /** The measured shape of 2026-09-24: a narrowing beside the filter, not in it. */
    @Test
    void a_rest_parameter_the_read_does_not_take_is_refused() {
        Answer answer = Transport.REST.raw(Selector.ITEM, Map.of("milestone", "6"));
        assertRefused(answer, "ARGUMENT_UNKNOWN", null, "item");
    }

    @Test
    void an_mcp_argument_the_read_does_not_take_is_refused() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("scope", SCOPE);
        args.put("selector", Selector.ITEM);
        args.put("milestone", 6);
        assertRefused(Transport.mcp(args), "ARGUMENT_UNKNOWN", null, "item");
    }

    // ======================================================================
    // Probe group 3 — a value the read cannot interpret is refused
    // ======================================================================

    /**
     * A value whose form a filter does not admit is decidable from the verb's
     * declaration alone, without any scope: {@code ARGUMENT_INVALID} with 400,
     * naming the filter as its path (DEC-0042). The fixed sets of the milestone
     * and workstream status and of the milestone kind are the platform's, not a
     * scope's, so a value outside them is a form fault too.
     *
     * <p>RED STATE, observed 2026-10-13 on {@code 4012fa1}: every case answered
     * {@code INVALID_VALUE} with 422, on both transports.
     */
    @ParameterizedTest
    @EnumSource(Transport.class)
    void an_uninterpretable_value_of_every_declared_filter_is_refused(Transport via) {
        assertRefused(via.query(Selector.ITEM, Map.of("milestone", "six"), null, null),
            "ARGUMENT_INVALID", "filter.milestone", "item milestone 'six'");
        assertRefused(via.query(Selector.ITEM, Map.of("status", "  "), null, null),
            "ARGUMENT_INVALID", "filter.status", "item status blank");
        assertRefused(via.query(Selector.ITEM, Map.of("workstream", ""), null, null),
            "ARGUMENT_INVALID", "filter.workstream", "item workstream empty");
        assertRefused(via.query(Selector.ITERATION, Map.of("closed", "maybe"), null, null),
            "ARGUMENT_INVALID", "filter.closed", "iteration closed 'maybe'");
        assertRefused(via.query(Selector.MILESTONE, Map.of("status", "finished"), null, null),
            "ARGUMENT_INVALID", "filter.status", "milestone status 'finished'");
        assertRefused(via.query(Selector.MILESTONE, Map.of("kind", "epic"), null, null),
            "ARGUMENT_INVALID", "filter.kind", "milestone kind 'epic'");
        assertRefused(via.query(Selector.WORKSTREAM, Map.of("status", "paused"), null, null),
            "ARGUMENT_INVALID", "filter.status", "workstream status 'paused'");
    }

    /**
     * Well-formed, and naming nothing this scope declared. A refusal and not an
     * empty answer, because an empty answer cannot be told from a typo; and a
     * refusal naming the filter rather than {@code NOT_FOUND}, because the value
     * is an argument and not an address. 422, because it is a statement about
     * the vocabulary of one scope.
     */
    @ParameterizedTest
    @EnumSource(Transport.class)
    void a_value_the_scope_never_declared_is_refused_by_filter(Transport via) {
        assertRefused(via.query(Selector.ITEM, Map.of("status", "parked"), null, null),
            "VALUE_UNDECLARED", "filter.status", "item status 'parked'");
        assertRefused(via.query(Selector.ITEM, Map.of("milestone", 9999), null, null),
            "VALUE_UNDECLARED", "filter.milestone", "item milestone 9999");
        assertRefused(via.query(Selector.ITEM, Map.of("workstream", "nowhere"), null, null),
            "VALUE_UNDECLARED", "filter.workstream", "item workstream 'nowhere'");
    }

    /**
     * A value the vocabulary of a scope does not declare is consulted only
     * once the caller has been found to see the scope; for a scope the caller
     * cannot see, the answer is {@code NOT_FOUND} and confirms nothing about
     * its vocabulary (DEC-0042). A form fault answers the same there.
     *
     * <p>RED STATE, observed 2026-10-13 with the scope visibility check in
     * {@code VerbSurface.query} made to fall through for the stranger onto the
     * probe scope: the undeclared status answered {@code VALUE_UNDECLARED}
     * with 422 on REST and on the service MCP.
     */
    @ParameterizedTest
    @EnumSource(Transport.class)
    void an_undeclared_value_on_an_invisible_scope_is_not_found(Transport via) {
        SurfaceFixture.asStranger(identity);
        Map<String, Answer> calls = new LinkedHashMap<>();
        calls.put("filter.status=parked", via.query(Selector.ITEM, Map.of("status", "parked"),
            null, null));
        calls.put("filter.milestone=six", via.query(Selector.ITEM, Map.of("milestone", "six"),
            null, null));
        calls.forEach((what, answer) -> {
            assertThat(answer.status()).as("%s: refused, not answered", what).isNotEqualTo(200);
            if (answer.status() != Answer.MCP_REFUSAL) {
                assertThat(answer.status()).as("%s: the not-found status", what).isEqualTo(404);
            }
            assertThat(answer.reason()).as(what).isEqualTo("NOT_FOUND");
            assertThat(answer.offenders()).as("%s: not-found carries no data", what).isEmpty();
        });
    }

    @Test
    void an_mcp_filter_that_is_not_an_object_is_refused() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("scope", SCOPE);
        args.put("selector", Selector.ITEM);
        args.put("filter", "{\"milestone\": 6}");
        assertRefused(Transport.mcp(args), "ARGUMENT_INVALID", "filter", "filter as a string");
    }

    @Test
    void an_mcp_filter_value_that_is_not_a_scalar_is_refused() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("scope", SCOPE);
        args.put("selector", Selector.ITEM);
        args.put("filter", Map.of("status", List.of(OPEN, DONE)));
        assertRefused(Transport.mcp(args), "ARGUMENT_INVALID", "filter.status", "a list value");
    }

    @Test
    void a_repeated_rest_filter_is_refused() {
        JsonPath body = given()
            .queryParam("filter.status", OPEN, DONE)
            .when().get(SurfaceFixture.collection(Selector.ITEM).replace(
                SurfaceFixture.SCOPE, SCOPE))
            .then().statusCode(400)
            .extract().jsonPath();
        assertThat(body.getString("reason")).isEqualTo("ARGUMENT_INVALID");
        assertThat(body.getList("data.offenders", String.class)).containsExactly("filter.status");
    }

    // ======================================================================
    // Probe group 4 — the page bound bounds, and the rest is reachable
    // ======================================================================

    @ParameterizedTest
    @EnumSource(Transport.class)
    void every_view_pages_in_every_standard_sort_without_gap_or_double(Transport via) {
        for (String view : Selector.VIEWS) {
            for (SortKey sort : SortKey.STANDARD) {
                for (SortKey.Direction direction : SortKey.Direction.values()) {
                    assertPagesCoverTheWhole(via, view, sort, direction);
                }
            }
        }
    }

    /** The declared field beyond the standard pair, on the transport that offers it. */
    @Test
    void the_axes_page_by_rank_on_rest() {
        for (String view : List.of(Selector.ITERATION, Selector.MILESTONE)) {
            for (SortKey.Direction direction : SortKey.Direction.values()) {
                assertPagesCoverTheWhole(Transport.REST, view, SortKey.RANK, direction);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void a_filtered_page_continues_inside_the_filter(Transport via) {
        Answer first = via.query(Selector.ITEM, Map.of("status", OPEN), 1, null);
        assertThat(first.addresses()).hasSize(1);
        assertThat(first.next()).as("three open items, one per page: more follows").isNotNull();

        List<String> seen = new ArrayList<>(first.addresses());
        String cursor = first.next();
        while (cursor != null) {
            Answer page = via.query(Selector.ITEM, Map.of("status", OPEN), 1, cursor);
            assertThat(page.addresses()).hasSizeLessThanOrEqualTo(1);
            seen.addAll(page.addresses());
            cursor = page.next();
            assertThat(seen)
                .as("the pages end — a cursor that is ignored answers the first page forever")
                .hasSizeLessThanOrEqualTo(staged.openItems().size());
        }
        assertThat(seen).containsExactlyElementsOf(staged.openItems());
    }

    /**
     * RED STATE, observed 2026-10-11 with the range check removed from
     * {@code QuerySpec}: {@code page_size} 0 was answered with 200 instead of
     * refused, on both transports.
     */
    @ParameterizedTest
    @EnumSource(Transport.class)
    void a_page_size_outside_its_range_or_form_is_refused(Transport via) {
        for (Object size : List.<Object>of(0, -1, QuerySpec.MAX_PAGE_SIZE + 1, "ten", 2.5)) {
            assertRefused(via.query(Selector.ITEM, Map.of(), size, null),
                "PAGE_SIZE_REJECTED", "page_size", "page_size " + size);
        }
    }

    /**
     * An argument the read does not take and a filter field the view does not
     * declare are two conditions, with two codes and two statuses (DEC-0042):
     * the first is decidable from the verb alone and answers 400, the second
     * says something about the vocabulary of a scope and answers 422.
     *
     * <p>RED STATE, observed 2026-10-12 on {@code e86e373}: both were
     * {@code UNKNOWN_FIELD} with 422, on REST and on the service MCP.
     */
    @ParameterizedTest
    @EnumSource(Transport.class)
    void the_earlier_limit_and_cursor_are_refused_as_unknown(Transport via) {
        assertRefused(via.call(Selector.ITEM, Map.of(), Map.of("limit", 1)),
            "ARGUMENT_UNKNOWN", null, "limit");
        String cursor = via.query(Selector.ITEM, Map.of(), 1, null).next();
        assertRefused(via.call(Selector.ITEM, Map.of(), Map.of("cursor", cursor)),
            "ARGUMENT_UNKNOWN", null, "cursor");
        assertRefused(via.call(Selector.ITEM, Map.of(), Map.of("foo", "1")),
            "ARGUMENT_UNKNOWN", null, "foo");
        assertRefused(via.call(Selector.ITEM, Map.of("foo", "1"), Map.of()),
            "PREDICATE_UNKNOWN", "filter", "filter.foo");
    }

    /**
     * An undeclared filter field is a statement about a scope's vocabulary, so
     * it is produced only once the caller has been found to see the scope. For
     * a scope the caller cannot see the answer is {@code NOT_FOUND}, whatever
     * else is wrong with the call (DEC-0042) — a 422 here would confirm that
     * the scope exists.
     *
     * <p>RED STATE, observed 2026-10-12 with the paging read moved in front of
     * the scope resolution in {@code VerbSurface.query}: the stranger was
     * answered {@code PREDICATE_UNKNOWN} with 422 on REST and on the service
     * MCP.
     */
    @ParameterizedTest
    @EnumSource(Transport.class)
    void an_undeclared_filter_on_an_invisible_scope_is_not_found(Transport via) {
        SurfaceFixture.asStranger(identity);
        Map<String, Answer> calls = new LinkedHashMap<>();
        calls.put("filter.foo", via.call(Selector.ITEM, Map.of("foo", "1"), Map.of()));
        calls.put("limit", via.call(Selector.ITEM, Map.of(), Map.of("limit", 1)));
        calls.forEach((what, answer) -> {
            assertThat(answer.status()).as("%s: refused, not answered", what).isNotEqualTo(200);
            if (answer.status() != Answer.MCP_REFUSAL) {
                assertThat(answer.status()).as("%s: the not-found status", what).isEqualTo(404);
            }
            assertThat(answer.reason()).as(what).isEqualTo("NOT_FOUND");
            assertThat(answer.offenders()).as("%s: not-found carries no data", what).isEmpty();
        });
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void a_cursor_this_service_did_not_hand_out_is_malformed(Transport via) {
        assertRefused(via.query(Selector.ITEM, Map.of(), 2, "not-a-cursor"),
            "CURSOR_MALFORMED", "after", "garbage cursor");
        String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            "v2|a|item|b|id|asc|1|1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertRefused(via.query(Selector.ITEM, Map.of(), 2, forged),
            "CURSOR_MALFORMED", "after", "a cursor of another version");
    }

    /**
     * A cursor handed out by another query, through the surface and in the one
     * envelope. RED STATE, observed 2026-10-11 with every binding skipped in
     * {@code PageCursor.decode}: an item cursor on the milestone view was
     * answered instead of refused, on both transports. Each binding on its own
     * is probed in {@code KeysetPagingIT}.
     */
    @ParameterizedTest
    @EnumSource(Transport.class)
    void a_cursor_of_another_view_filter_sort_or_direction_is_foreign(Transport via) {
        String itemCursor = via.query(Selector.ITEM, Map.of(), 1, null).next();
        assertThat(itemCursor).isNotNull();

        assertRefused(via.query(Selector.MILESTONE, Map.of(), 1, itemCursor),
            "CURSOR_FOREIGN", "after", "an item cursor on the milestone view");
        assertRefused(via.query(Selector.ITEM, Map.of("status", OPEN), 1, itemCursor),
            "CURSOR_FOREIGN", "after", "an unfiltered cursor on a filtered query");
        assertRefused(via.call(Selector.ITEM, Map.of(), Map.of("page_size", 1,
                "after", itemCursor, "sort", "created_at")),
            "CURSOR_FOREIGN", "after", "an id cursor on the created_at sort");
        assertRefused(via.call(Selector.ITEM, Map.of(), Map.of("page_size", 1,
                "after", itemCursor, "direction", "desc")),
            "CURSOR_FOREIGN", "after", "an ascending cursor on a descending query");
    }

    /** A cursor of another scope, through the surface: the second staged scope. */
    @ParameterizedTest
    @EnumSource(Transport.class)
    void a_cursor_of_another_scope_is_foreign(Transport via) {
        String otherCursor = via.callIn(OTHER_SCOPE, Selector.ITEM, Map.of(),
            Map.of("page_size", 1)).next();
        assertThat(otherCursor).isNotNull();
        assertRefused(via.query(Selector.ITEM, Map.of(), 1, otherCursor),
            "CURSOR_FOREIGN", "after", "a cursor of another scope");
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void the_default_page_size_applies_without_one(Transport via) {
        int missing = QuerySpec.DEFAULT_PAGE_SIZE + 1 - staged.allItems().size();
        if (missing > 0) {
            stageFiller(missing);
        }
        Answer answer = via.query(Selector.ITEM, Map.of(), null, null);
        assertThat(answer.addresses())
            .as("without a page size the documented default of %d applies",
                QuerySpec.DEFAULT_PAGE_SIZE)
            .hasSize(QuerySpec.DEFAULT_PAGE_SIZE);
        assertThat(answer.next()).as("and the rest is reachable").isNotNull();
    }

    /**
     * The answer carries the objects and {@code next_cursor}, and nothing else
     * about the page: no {@code truncated} beside it, and on the last page no
     * {@code next_cursor} at all rather than a null one.
     */
    @ParameterizedTest
    @EnumSource(Transport.class)
    void the_last_page_carries_no_cursor_and_no_page_answers_truncated(Transport via) {
        Map<String, Object> first = via.body(Selector.MILESTONE, Map.of("page_size", 1));
        assertThat(first).containsOnlyKeys("objects", "next_cursor");

        Map<String, Object> last = via.body(Selector.MILESTONE,
            Map.of("page_size", QuerySpec.MAX_PAGE_SIZE));
        assertThat(last)
            .as("the whole axis fits one page: the answer is the objects and nothing else")
            .containsOnlyKeys("objects");
    }

    // ======================================================================
    // Probe group 4b — the sort is declared, and it orders
    // ======================================================================

    @ParameterizedTest
    @EnumSource(Transport.class)
    void the_default_sort_is_by_number_ascending(Transport via) {
        for (String view : Selector.VIEWS) {
            List<Long> numbers = numbersOf(via.query(view, Map.of(), QuerySpec.MAX_PAGE_SIZE, null));
            assertThat(numbers).as("%s without a sort", view).isSorted();
            assertThat(numbersOf(via.call(view, Map.of(),
                    Map.of("direction", "desc", "page_size", QuerySpec.MAX_PAGE_SIZE))))
                .as("%s by number descending", view)
                .containsExactlyElementsOf(numbers.reversed());
        }
    }

    @Test
    void rest_sorts_the_axes_by_rank_then_number() {
        List<Map<String, Object>> fields = Transport.REST.call(Selector.ITERATION, Map.of(),
            Map.of("sort", "rank")).fields();
        assertThat(fields).extracting(f -> ((Number) f.get("rank")).intValue())
            .as("the open iteration was ranked 1, the closed one 2")
            .isSorted();
    }

    /**
     * RED STATE, observed 2026-10-11 with the declaration and offer checks
     * removed from {@code QuerySpec}: {@code rank} on the item view was answered
     * on both transports, and {@code rank} on the assistant surface was
     * answered. A name that is no sort key at all stays refused by the parse.
     */
    @ParameterizedTest
    @EnumSource(Transport.class)
    void an_undeclared_sort_is_refused(Transport via) {
        assertRefused(via.call(Selector.ITEM, Map.of(), Map.of("sort", "title")),
            "SORT_UNDECLARED", "sort", "sort by title");
        assertRefused(via.call(Selector.ITEM, Map.of(), Map.of("sort", "rank")),
            "SORT_UNDECLARED", "sort", "rank on the item view, which carries none");
        assertRefused(via.call(Selector.ITEM, Map.of(), Map.of("sort", "uuid")),
            "SORT_UNDECLARED", "sort", "sort by the platform identity");
    }

    /**
     * A direction is not a sort field: a value other than {@code asc} or
     * {@code desc} is a value the argument does not admit (ADR-0049).
     *
     * <p>RED STATE, observed 2026-10-12 on {@code e86e373}: both were refused
     * {@code SORT_UNDECLARED}.
     */
    @ParameterizedTest
    @EnumSource(Transport.class)
    void a_direction_other_than_asc_or_desc_is_an_invalid_argument(Transport via) {
        assertRefused(via.call(Selector.ITEM, Map.of(), Map.of("direction", "sideways")),
            "ARGUMENT_INVALID", "direction", "direction sideways");
        assertRefused(via.call(Selector.ITEM, Map.of(), Map.of("direction", "up")),
            "ARGUMENT_INVALID", "direction", "direction up");
    }

    @Test
    void the_assistant_surface_offers_the_standard_sorts_only() {
        assertRefused(Transport.MCP.call(Selector.MILESTONE, Map.of(), Map.of("sort", "rank")),
            "SORT_UNDECLARED", "sort", "rank on the assistant surface");
    }

    // ======================================================================
    // Probe group 4c — the assistant surface over REST, named by the hop
    // ======================================================================

    /**
     * A router that publishes {@code query} as an assistant tool reaches this
     * service over REST and says so with {@code Kumbuka-Surface: assistant}.
     * The read is then made on the assistant surface: the sort the REST surface
     * declares beyond the standard pair is refused there too (ADR-0049).
     *
     * <p>RED STATE, observed 2026-10-12 with the header ignored in
     * {@code WorklistResource.collectionGet}: {@code sort=rank} on the
     * milestone view answered 200 with the header.
     */
    @Test
    void the_assistant_header_offers_the_standard_sorts_only() {
        Answer named = Transport.rawIn(SCOPE, Selector.MILESTONE, Map.of("sort", "rank"),
            ASSISTANT);
        assertRefused(named, "SORT_UNDECLARED", "sort", "rank under the assistant header");

        Answer unnamed = Transport.rawIn(SCOPE, Selector.MILESTONE, Map.of("sort", "rank"), null);
        assertThat(unnamed.status()).as("without the header REST offers rank").isEqualTo(200);
    }

    /** With the header, the REST answer is the one the service MCP gives. */
    @Test
    void the_assistant_header_answers_as_the_service_mcp_does() {
        Map<String, Object> paging = Map.of("page_size", 2, "sort", "created_at",
            "direction", "desc");
        Map<String, Object> viaRest = given().header(SURFACE, ASSISTANT).queryParams(paging)
            .when().get("/api/" + SCOPE + "/" + Selector.MILESTONE)
            .then().statusCode(200)
            .extract().jsonPath().getMap("");
        assertThat(viaRest).isEqualTo(Transport.MCP.body(Selector.MILESTONE, paging));
    }

    /** The header takes one value; any other is refused and the read not made. */
    @Test
    void the_surface_header_takes_only_the_assistant() {
        var response = given().header(SURFACE, "console")
            .when().get("/api/" + SCOPE + "/" + Selector.MILESTONE)
            .then().extract();
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("reason")).isEqualTo("ARGUMENT_INVALID");
        assertThat(response.jsonPath().getMap("$"))
            .as("the header is not an argument of the call, so there is no offender to name")
            .doesNotContainKey("data");
        assertThat(response.jsonPath().getString("message")).isNotBlank()
            .doesNotContain("console");
    }

    // ======================================================================
    // Probe group 4d — the service MCP's text content is the answer as JSON
    // ======================================================================

    /**
     * {@code content[0].text} carries the answer as JSON, the same answer as
     * {@code structuredContent}, for a page and for a refusal alike.
     *
     * <p>RED STATE, observed 2026-10-12 on {@code e86e373}: the text was Java's
     * {@code toString()} of the payload ({@code Listing[objects=[...}), which
     * no client can parse.
     */
    @Test
    void the_mcp_text_content_is_the_structured_answer_as_json() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();
        for (Map<String, Object> paging : List.<Map<String, Object>>of(
                Map.of("page_size", 1), Map.of("page_size", 0))) {
            Map<String, Object> args = new LinkedHashMap<>(paging);
            args.put("scope", SCOPE);
            args.put("selector", Selector.ITEM);
            JsonPath rpc = Transport.mcpRpc(args);
            String text = rpc.getString("result.content[0].text");
            assertThat(json.readValue(text, Map.class))
                .as("the text content of %s parses and is the structured content", paging)
                .isEqualTo(rpc.getMap("result.structuredContent"));
        }
    }

    // ======================================================================
    // Probe group 4e — a cursor does not cross tenants on a scope's name
    // ======================================================================

    /**
     * A scope of the same name in another tenant is another scope: the cursor
     * is bound to the scope's technical identity, never to its name, since a
     * name recurs across tenants (ADR-0049). The REST surface serves one tenant
     * per instance, so the second tenant is bound in-process and the read made
     * through the verb surface both transports call.
     *
     * <p>RED STATE, observed 2026-10-12 with the scope's identity removed from
     * the cursor's fingerprint in {@code PageCursor}: the cursor of tenant A
     * placed the walk in tenant B's same-named scope and answered 200.
     */
    @Test
    void a_cursor_of_a_same_named_scope_in_another_tenant_is_foreign() throws Exception {
        String cursorOfA = Transport.REST.query(Selector.ITEM, Map.of(), 1, null).next();
        assertThat(cursorOfA).isNotNull();

        stageSameNamedScopeInTenantB();
        try (AutoCloseable inB = tenants.bind(TENANT_B)) {
            QuerySpec own = new QuerySpec(null, 1, null, null, null, List.of(), SortKey.STANDARD);
            String cursorOfB = verbs.query(SurfaceFixture.MEMBER, SCOPE, Selector.ITEM, own)
                .next();
            assertThat(cursorOfB).as("the control: tenant B pages its own scope").isNotNull();
            assertThat(verbs.query(SurfaceFixture.MEMBER, SCOPE, Selector.ITEM,
                    new QuerySpec(null, 1, cursorOfB, null, null, List.of(), SortKey.STANDARD))
                .objects()).as("and its own cursor continues it").hasSize(1);

            Throwable refused = org.assertj.core.api.Assertions.catchThrowable(() ->
                verbs.query(SurfaceFixture.MEMBER, SCOPE, Selector.ITEM,
                    new QuerySpec(null, 1, cursorOfA, null, null, List.of(), SortKey.STANDARD)));
            assertThat(refused).isInstanceOf(WorklistException.class);
            WorklistException typed = (WorklistException) refused;
            assertThat(typed.reason()).isEqualTo(WorklistException.Reason.CURSOR_FOREIGN);
            assertThat(typed.offenders()).containsExactly(QuerySpec.AFTER_ARGUMENT);
        }
    }

    // ======================================================================
    // Probe group 5 — the token check the workstream filter shares with create
    // ======================================================================

    /**
     * A workstream token nobody declared, on an item create: refused naming the
     * field, not as {@code NOT_FOUND}. The filter and the write resolve the
     * token through one method, so the two refusals cannot drift apart. RED
     * STATE, observed 2026-10-09 on {@code ca0b5cf}: 404 {@code NOT_FOUND} with
     * the membership sentence and no {@code data}.
     */
    @Test
    void a_create_naming_an_undeclared_workstream_is_refused_by_field() {
        JsonPath body = given()
            .contentType(ContentType.JSON)
            .body(Map.of("title", "an item in a stream nobody declared", "status", OPEN,
                "workstream", "nowhere"))
            .when().post("/api/" + SCOPE + "/" + Selector.ITEM)
            .then().statusCode(422)
            .extract().jsonPath();
        assertThat(body.getString("reason")).isEqualTo("VALUE_UNDECLARED");
        assertThat(body.getList("data.offenders", String.class)).containsExactly("fields.workstream");
    }

    // ======================================================================
    // Assertions
    // ======================================================================

    private static void assertNarrowed(Answer answer, List<String> admitted,
                                       List<String> excluded, String what) {
        assertThat(answer.status()).as("%s answers", what).isEqualTo(200);
        assertThat(excluded).as("the data set gives %s something to exclude", what).isNotEmpty();
        assertThat(answer.addresses())
            .as("%s admits every object it must", what)
            .containsAll(admitted);
        assertThat(answer.addresses())
            .as("%s excludes every object it must — a dropped filter answers them too", what)
            .doesNotContainAnyElementsOf(excluded);
    }

    private static void assertRefused(Answer answer, String reason, String offender, String what) {
        assertThat(answer.status()).as("%s is refused, not answered", what).isNotEqualTo(200);
        if (answer.status() != Answer.MCP_REFUSAL) {
            assertThat(answer.status()).as("%s answers the status of its class", what)
                .isEqualTo(REFUSED_WITHOUT_SCOPE.contains(reason) ? 400 : 422);
        }
        assertThat(answer.reason()).as("%s refused with a typed reason", what).isEqualTo(reason);
        if (offender == null) {
            // DEC-0043 R19: a name the call does not declare is answered with
            // the declared ones and never named itself.
            assertThat(answer.offenders()).as("%s names no argument that arrived", what)
                .isEmpty();
        } else {
            assertThat(answer.offenders()).as("%s names the argument", what)
                .containsExactly(offender);
        }
    }

    private static void assertPagesCoverTheWhole(Transport via, String view, SortKey sort,
                                                 SortKey.Direction direction) {
        String what = view + " by " + sort.wireName() + " " + direction.wireName();
        Map<String, Object> order = Map.of("sort", sort.wireName(),
            "direction", direction.wireName());
        Map<String, Object> wholeArgs = new LinkedHashMap<>(order);
        wholeArgs.put("page_size", QuerySpec.MAX_PAGE_SIZE);
        Answer whole = via.call(view, Map.of(), wholeArgs);
        assertThat(whole.next()).as("%s: the one-page read is complete", what).isNull();
        assertThat(whole.addresses()).as(what).hasSizeGreaterThan(1);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Map<String, Object> args = new LinkedHashMap<>(order);
            args.put("page_size", 1);
            if (cursor != null) {
                args.put("after", cursor);
            }
            Answer page = via.call(view, Map.of(), args);
            assertThat(page.status()).as(what).isEqualTo(200);
            assertThat(page.addresses()).as("%s: a page holds at most its size", what)
                .hasSizeLessThanOrEqualTo(1);
            seen.addAll(page.addresses());
            cursor = page.next();
            pages++;
            assertThat(pages).as("%s: the pages end", what)
                .isLessThanOrEqualTo(whole.addresses().size());
        } while (cursor != null);

        assertThat(pages).as("%s: the walk spans at least two pages", what)
            .isGreaterThanOrEqualTo(2);
        assertThat(new LinkedHashSet<>(seen)).as("%s: no double", what).hasSize(seen.size());
        assertThat(seen).as("%s: no gap, in the one-page order", what)
            .containsExactlyElementsOf(whole.addresses());
    }

    private static List<Long> numbersOf(Answer answer) {
        assertThat(answer.status()).isEqualTo(200);
        return answer.addresses().stream()
            .map(a -> Long.valueOf(a.substring(a.lastIndexOf('/') + 1)))
            .toList();
    }

    private static List<String> without(List<String> all, List<String> removed) {
        return all.stream().filter(a -> !removed.contains(a)).toList();
    }

    private static List<String> without(List<String> all, String removed) {
        return without(all, List.of(removed));
    }

    // ======================================================================
    // Transports
    // ======================================================================

    /**
     * The refusals decidable without the vocabulary of a scope, answered 400 on
     * REST (DEC-0042): the paging contract, an argument the read does not take,
     * and a value an argument does not admit. Every other refusal of a query
     * depends on the scope's vocabulary and answers 422.
     */
    private static final java.util.Set<String> REFUSED_WITHOUT_SCOPE = java.util.Set.of(
        "PAGE_SIZE_REJECTED", "CURSOR_MALFORMED", "CURSOR_FOREIGN", "SORT_UNDECLARED",
        "ARGUMENT_UNKNOWN", "ARGUMENT_INVALID");

    /** One answer, whichever transport carried it. */
    record Answer(int status, String reason, List<String> offenders, List<String> addresses,
                  List<Map<String, Object>> fields, String next) {

        /** The status an MCP refusal is recorded under: it has no HTTP status of its own. */
        static final int MCP_REFUSAL = -1;

        static Answer of(int status, JsonPath body) {
            if (status != 200) {
                List<String> offenders = body.getList("data.offenders", String.class);
                return new Answer(status, body.getString("reason"),
                    offenders == null ? List.of() : offenders, List.of(), List.of(), null);
            }
            return new Answer(status, null, List.of(),
                body.getList("objects.address", String.class),
                body.getList("objects.fields"),
                body.getString("next_cursor"));
        }
    }

    enum Transport {
        REST {
            @Override
            Answer callIn(String scope, String view, Map<String, Object> filter,
                          Map<String, Object> paging) {
                Map<String, Object> params = new LinkedHashMap<>();
                filter.forEach((name, value) -> params.put("filter." + name, value));
                params.putAll(paging);
                return rawIn(scope, view, params);
            }

            @Override
            Map<String, Object> body(String view, Map<String, Object> paging) {
                return given().queryParams(paging)
                    .when().get("/api/" + SCOPE + "/" + view)
                    .then().statusCode(200)
                    .extract().jsonPath().getMap("");
            }
        },
        MCP {
            @Override
            Answer callIn(String scope, String view, Map<String, Object> filter,
                          Map<String, Object> paging) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("scope", scope);
                args.put("selector", view);
                if (!filter.isEmpty()) {
                    args.put("filter", filter);
                }
                args.putAll(paging);
                return mcp(args);
            }

            @Override
            Map<String, Object> body(String view, Map<String, Object> paging) {
                Map<String, Object> args = new LinkedHashMap<>(paging);
                args.put("scope", SCOPE);
                args.put("selector", view);
                return mcpRpc(args).getMap("result.structuredContent");
            }
        };

        /** One call in the staged scope, with any paging arguments. */
        Answer call(String view, Map<String, Object> filter, Map<String, Object> paging) {
            return callIn(SCOPE, view, filter, paging);
        }

        abstract Answer callIn(String scope, String view, Map<String, Object> filter,
                               Map<String, Object> paging);

        /** The answer of a call as the transport carried it, every key included. */
        abstract Map<String, Object> body(String view, Map<String, Object> paging);

        /** One page: the page size and the cursor, each left out when null. */
        Answer query(String view, Map<String, Object> filter, Object pageSize, String after) {
            Map<String, Object> paging = new LinkedHashMap<>();
            if (pageSize != null) {
                paging.put("page_size", pageSize);
            }
            if (after != null) {
                paging.put("after", after);
            }
            return call(view, filter, paging);
        }

        Answer raw(String view, Map<String, Object> params) {
            return rawIn(SCOPE, view, params);
        }

        static Answer rawIn(String scope, String view, Map<String, Object> params) {
            return rawIn(scope, view, params, null);
        }

        /** A raw REST read, naming the caller's surface when {@code surface} is set. */
        static Answer rawIn(String scope, String view, Map<String, Object> params,
                            String surface) {
            var request = given().queryParams(params);
            if (surface != null) {
                request = request.header(SURFACE, surface);
            }
            var response = request
                .when().get("/api/" + scope + "/" + view)
                .then().extract();
            return Answer.of(response.statusCode(), response.jsonPath());
        }

        static Answer mcp(Map<String, Object> arguments) {
            JsonPath rpc = mcpRpc(arguments);
            JsonPath content = JsonPath.from(rpc.prettify()).setRootPath("result.structuredContent");
            boolean refused = rpc.getBoolean("result.isError");
            return Answer.of(refused ? Answer.MCP_REFUSAL : 200, content);
        }

        static JsonPath mcpRpc(Map<String, Object> arguments) {
            return given()
                .contentType(ContentType.JSON)
                .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                    "params", Map.of("name", "query", "arguments", arguments)))
                .when().post("/mcp")
                .then().statusCode(200)
                .extract().jsonPath();
        }
    }

    // ======================================================================
    // Staging
    // ======================================================================

    /** The staged objects, as canonical addresses. */
    record Staged(List<String> allItems, List<String> openItems, List<String> doneItems,
                  List<String> alphaItems, String itemOnActiveMilestone,
                  long activeMilestoneNumber, String activeMilestone, String plannedMilestone,
                  String markerMilestone, String openIteration, String closedIteration,
                  String alphaWorkstream, String withdrawnWorkstream) {
    }

    private Staged stage() {
        SurfaceFixture.publishScope(SCOPE, SCOPE_ID, "project", false);
        settings.create(SCOPE_ID, Map.of(
            "max_planned_iterations", 10,
            "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10,
            "warn_memberships_per_iteration", 9));
        for (String view : Selector.VIEWS) {
            selectors.declare(SCOPE_ID, view);
        }
        vocabulary.declareStatus(SCOPE_ID, OPEN, 1, true, false, false, false);
        vocabulary.declareStatus(SCOPE_ID, DONE, 2, false, false, true, true);

        workstreams.requireDefault(SCOPE_ID);
        Workstream alpha = workstreams.declare(SCOPE_ID, "alpha", "the alpha stream");
        Workstream gamma = workstreams.declare(SCOPE_ID, "gamma", "a stream to withdraw");
        workstreams.withdraw(SCOPE_ID, gamma.id, gamma.conflictToken);

        Map<String, Object> active = milestones.create(SCOPE_ID, Map.of(
            "title", "the active goal", "vision", "a north star"));
        milestones.update(SCOPE_ID, (UUID) active.get("id"), Map.of(
            "status", Milestone.ACTIVE, "conflict_token", active.get("conflict_token")));
        Map<String, Object> planned = milestones.create(SCOPE_ID, Map.of(
            "title", "the planned goal", "vision", "another north star"));
        Map<String, Object> marker = milestones.create(SCOPE_ID, Map.of(
            "title", "off the path", "kind", Milestone.OFF_PATH));

        String a = item("open on the active goal in alpha", OPEN, "alpha", (Long) active.get("number"));
        String b = item("done in the default stream", DONE, null, null);
        String c = item("open in the default stream", OPEN, null, null);
        String d = item("done on the planned goal in alpha", DONE, "alpha", (Long) planned.get("number"));
        String e = item("open again in the default stream", OPEN, null, null);

        Map<String, Object> openIteration = iterations.create(SCOPE_ID, Map.of(
            "title", "open", "motto", "open", "description", "stays open", "rank", 1));
        Map<String, Object> closedIteration = iterations.create(SCOPE_ID, Map.of(
            "title", "closed", "motto", "closed", "description", "gets closed", "rank", 2));
        iterations.close(SCOPE_ID, (UUID) closedIteration.get("id"), "nothing, deliberately",
            String.valueOf(closedIteration.get("conflict_token")));

        stageOtherScope();

        return new Staged(List.of(a, b, c, d, e), List.of(a, c, e), List.of(b, d), List.of(a, d),
            a, (Long) active.get("number"),
            address(Selector.MILESTONE, active.get("number")),
            address(Selector.MILESTONE, planned.get("number")),
            address(Selector.MILESTONE, marker.get("number")),
            address(Selector.ITERATION, openIteration.get("number")),
            address(Selector.ITERATION, closedIteration.get("number")),
            address(Selector.WORKSTREAM, alpha.number),
            address(Selector.WORKSTREAM, gamma.number));
    }

    /** Two items in the second scope, enough for one page and a cursor. */
    private void stageOtherScope() {
        SurfaceFixture.publishScope(OTHER_SCOPE, OTHER_SCOPE_ID, "project", false);
        settings.create(OTHER_SCOPE_ID, Map.of(
            "max_planned_iterations", 10,
            "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10,
            "warn_memberships_per_iteration", 9));
        selectors.declare(OTHER_SCOPE_ID, Selector.ITEM);
        vocabulary.declareStatus(OTHER_SCOPE_ID, OPEN, 1, true, false, false, false);
        workstreams.requireDefault(OTHER_SCOPE_ID);
        for (int i = 0; i < 2; i++) {
            items.create(OTHER_SCOPE_ID, Map.of("title", "elsewhere " + i, "status", OPEN));
        }
    }

    /**
     * A scope of tenant B under the probe scope's own name, the member an
     * account of tenant B too, and two items in it. Idempotent on the scope id.
     */
    private void stageSameNamedScopeInTenantB() throws Exception {
        PlatformFixture.run(
            "SELECT set_config('app.tenant_id', '" + TENANT_B + "', false)",
            "INSERT INTO public.scope (id, tenant_id, slug, kind) SELECT '" + SCOPE_IN_B
                + "', '" + TENANT_B + "', '" + SCOPE + "', 'project' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.scope WHERE id = '" + SCOPE_IN_B + "')",
            "INSERT INTO public.user_account (tenant_id, subject) SELECT '" + TENANT_B + "', '"
                + SurfaceFixture.MEMBER + "' WHERE NOT EXISTS (SELECT 1 FROM public.user_account "
                + "WHERE tenant_id = '" + TENANT_B + "' AND subject = '" + SurfaceFixture.MEMBER
                + "')",
            "RESET app.tenant_id");
        try (AutoCloseable inB = tenants.bind(TENANT_B)) {
            if (!selectors.inScope(SCOPE_IN_B).isEmpty()) {
                return;
            }
            settings.create(SCOPE_IN_B, Map.of(
                "max_planned_iterations", 10,
                "warn_planned_iterations", 9,
                "max_memberships_per_iteration", 10,
                "warn_memberships_per_iteration", 9));
            selectors.declare(SCOPE_IN_B, Selector.ITEM);
            vocabulary.declareStatus(SCOPE_IN_B, OPEN, 1, true, false, false, false);
            workstreams.requireDefault(SCOPE_IN_B);
            for (int i = 0; i < 2; i++) {
                items.create(SCOPE_IN_B, Map.of("title", "in tenant B " + i, "status", OPEN));
            }
        }
    }

    private String item(String title, String status, String workstream, Long milestone) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("status", status);
        if (workstream != null) {
            fields.put("workstream", workstream);
        }
        Map<String, Object> created = items.create(SCOPE_ID, fields);
        if (milestone != null) {
            items.update(SCOPE_ID, (UUID) created.get("id"), Map.of(
                "milestone", milestone, "conflict_token", created.get("conflict_token")));
        }
        return address(Selector.ITEM, created.get("number"));
    }

    /** Items beyond the default page size, all done in the default stream. */
    private void stageFiller(int count) {
        List<String> added = new ArrayList<>(staged.allItems());
        List<String> done = new ArrayList<>(staged.doneItems());
        for (int i = 0; i < count; i++) {
            String at = item("filler " + i, DONE, null, null);
            added.add(at);
            done.add(at);
        }
        staged = new Staged(added, staged.openItems(), done, staged.alphaItems(),
            staged.itemOnActiveMilestone(), staged.activeMilestoneNumber(),
            staged.activeMilestone(), staged.plannedMilestone(), staged.markerMilestone(),
            staged.openIteration(), staged.closedIteration(), staged.alphaWorkstream(),
            staged.withdrawnWorkstream());
    }

    private static String address(String view, Object number) {
        return AddressParser.SCHEME + "://" + SCOPE + "/" + view + "/" + number;
    }
}
