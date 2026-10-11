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
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
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
 *     400 on REST.</li>
 * <li>An argument the read does not take at all — a REST query parameter or an
 *     MCP argument outside the declared set — is refused, not ignored. The
 *     measured defect of 2026-09-24 ({@code filter {milestone: 6}} answering
 *     the full stock) is exactly a narrowing that never reached the filter.</li>
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
            assertRefused(answer, "UNKNOWN_FIELD", "filter.colour", view);
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
        assertRefused(answer, "UNKNOWN_FIELD", "filter.milestone", "iteration");
    }

    /** The measured shape of 2026-09-24: a narrowing beside the filter, not in it. */
    @Test
    void a_rest_parameter_the_read_does_not_take_is_refused() {
        Answer answer = Transport.REST.raw(Selector.ITEM, Map.of("milestone", "6"));
        assertRefused(answer, "UNKNOWN_FIELD", "milestone", "item");
    }

    @Test
    void an_mcp_argument_the_read_does_not_take_is_refused() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("scope", SCOPE);
        args.put("selector", Selector.ITEM);
        args.put("milestone", 6);
        assertRefused(Transport.mcp(args), "UNKNOWN_FIELD", "milestone", "item");
    }

    // ======================================================================
    // Probe group 3 — a value the read cannot interpret is refused
    // ======================================================================

    @ParameterizedTest
    @EnumSource(Transport.class)
    void an_uninterpretable_value_of_every_declared_filter_is_refused(Transport via) {
        assertRefused(via.query(Selector.ITEM, Map.of("milestone", "six"), null, null),
            "INVALID_VALUE", "filter.milestone", "item milestone 'six'");
        assertRefused(via.query(Selector.ITEM, Map.of("status", "  "), null, null),
            "INVALID_VALUE", "filter.status", "item status blank");
        assertRefused(via.query(Selector.ITEM, Map.of("workstream", ""), null, null),
            "INVALID_VALUE", "filter.workstream", "item workstream empty");
        assertRefused(via.query(Selector.ITERATION, Map.of("closed", "maybe"), null, null),
            "INVALID_VALUE", "filter.closed", "iteration closed 'maybe'");
        assertRefused(via.query(Selector.MILESTONE, Map.of("status", "finished"), null, null),
            "INVALID_VALUE", "filter.status", "milestone status 'finished'");
        assertRefused(via.query(Selector.MILESTONE, Map.of("kind", "epic"), null, null),
            "INVALID_VALUE", "filter.kind", "milestone kind 'epic'");
        assertRefused(via.query(Selector.WORKSTREAM, Map.of("status", "paused"), null, null),
            "INVALID_VALUE", "filter.status", "workstream status 'paused'");
    }

    /**
     * Well-formed, and naming nothing this scope declared. A refusal and not an
     * empty answer, because an empty answer cannot be told from a typo; and a
     * refusal naming the filter rather than {@code NOT_FOUND}, because the value
     * is an argument and not an address.
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

    @Test
    void an_mcp_filter_that_is_not_an_object_is_refused() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("scope", SCOPE);
        args.put("selector", Selector.ITEM);
        args.put("filter", "{\"milestone\": 6}");
        assertRefused(Transport.mcp(args), "INVALID_VALUE", "filter", "filter as a string");
    }

    @Test
    void an_mcp_filter_value_that_is_not_a_scalar_is_refused() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("scope", SCOPE);
        args.put("selector", Selector.ITEM);
        args.put("filter", Map.of("status", List.of(OPEN, DONE)));
        assertRefused(Transport.mcp(args), "INVALID_VALUE", "filter.status", "a list value");
    }

    @Test
    void a_repeated_rest_filter_is_refused() {
        JsonPath body = given()
            .queryParam("filter.status", OPEN, DONE)
            .when().get(SurfaceFixture.collection(Selector.ITEM).replace(
                SurfaceFixture.SCOPE, SCOPE))
            .then().statusCode(422)
            .extract().jsonPath();
        assertThat(body.getString("reason")).isEqualTo("INVALID_VALUE");
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
     * {@code QuerySpec}: {@code page_size} 0 answered an empty page with a
     * cursor and 201 answered a page of 201.
     */
    @ParameterizedTest
    @EnumSource(Transport.class)
    void a_page_size_outside_its_range_or_form_is_refused(Transport via) {
        for (Object size : List.<Object>of(0, -1, QuerySpec.MAX_PAGE_SIZE + 1, "ten", 2.5)) {
            assertRefused(via.query(Selector.ITEM, Map.of(), size, null),
                "PAGE_SIZE_REJECTED", "page_size", "page_size " + size);
        }
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void the_earlier_limit_and_cursor_are_refused_as_unknown(Transport via) {
        assertRefused(via.call(Selector.ITEM, Map.of(), Map.of("limit", 1)),
            "UNKNOWN_FIELD", "limit", "limit");
        String cursor = via.query(Selector.ITEM, Map.of(), 1, null).next();
        assertRefused(via.call(Selector.ITEM, Map.of(), Map.of("cursor", cursor)),
            "UNKNOWN_FIELD", "cursor", "cursor");
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
     * envelope. RED STATE, observed 2026-10-11 with each binding skipped in
     * {@code PageCursor.decode}: the cursor was applied and answered a page of
     * the other order.
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
     * RED STATE, observed 2026-10-11 with the declaration check removed from
     * {@code QuerySpec}: {@code sort=title} answered in number order, and
     * {@code rank} on the assistant surface answered in rank order.
     */
    @ParameterizedTest
    @EnumSource(Transport.class)
    void an_undeclared_sort_or_direction_is_refused(Transport via) {
        assertRefused(via.call(Selector.ITEM, Map.of(), Map.of("sort", "title")),
            "SORT_UNDECLARED", "sort", "sort by title");
        assertRefused(via.call(Selector.ITEM, Map.of(), Map.of("sort", "rank")),
            "SORT_UNDECLARED", "sort", "rank on the item view, which carries none");
        assertRefused(via.call(Selector.ITEM, Map.of(), Map.of("sort", "uuid")),
            "SORT_UNDECLARED", "sort", "sort by the platform identity");
        assertRefused(via.call(Selector.ITEM, Map.of(), Map.of("direction", "up")),
            "SORT_UNDECLARED", "direction", "direction up");
    }

    @Test
    void the_assistant_surface_offers_the_standard_sorts_only() {
        assertRefused(Transport.MCP.call(Selector.MILESTONE, Map.of(), Map.of("sort", "rank")),
            "SORT_UNDECLARED", "sort", "rank on the assistant surface");
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
        assertThat(body.getList("data.offenders", String.class)).containsExactly("workstream");
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
                .isEqualTo(PAGING_REFUSALS.contains(reason) ? 400 : 422);
        }
        assertThat(answer.reason()).as("%s refused with a typed reason", what).isEqualTo(reason);
        assertThat(answer.offenders()).as("%s names the argument", what).containsExactly(offender);
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

    /** The refusals of the paging contract, answered 400 on REST. */
    private static final java.util.Set<String> PAGING_REFUSALS = java.util.Set.of(
        "PAGE_SIZE_REJECTED", "CURSOR_MALFORMED", "CURSOR_FOREIGN", "SORT_UNDECLARED");

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
            var response = given().queryParams(params)
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

        private static JsonPath mcpRpc(Map<String, Object> arguments) {
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
