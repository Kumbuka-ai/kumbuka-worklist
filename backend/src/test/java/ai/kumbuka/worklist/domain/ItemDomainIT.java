package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.Db;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Probes C to G — the guarantees of the item domain, each with the state it
 * would be in if the guarantee were absent, observed in the same run.
 *
 * <p>Every one of these could have been written as a green assertion alone,
 * and every one of them would then pass against an implementation with the
 * mechanism removed. So each case establishes what the alternative would have
 * produced and asserts that it did NOT happen — a conflict check that was
 * skipped leaves a rotated token behind, an argument that was dropped
 * silently leaves a rotated token behind, a mark derived from the live rows
 * hands out a number that is already in use. Those traces are what is
 * asserted, because they are what an absent mechanism actually looks like.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ItemDomainIT {

    private static final UUID SCOPE = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);

    @Inject ItemService items;
    @Inject SelectorRegistry selectors;
    @Inject TermRegistry terms;

    /** The tenant the ORM binds, read from the same setting the service runs on. */
    private static UUID boundTenant() {
        return UUID.fromString(
            ConfigProvider.getConfig().getValue("worklist.tenant-id", String.class));
    }

    // ==================================================================
    // Probe C — the conflict token.
    // ==================================================================

    /**
     * A write carrying a stale token is refused, and the refusal carries the
     * CURRENT token.
     *
     * <p>The second half is not a convenience. A caller that has to make an
     * extra read just to learn what it should have sent will end up reading
     * before every write, and a caller that reads in order to overwrite has
     * stopped detecting conflicts — the mechanism would still be there and
     * would no longer be doing anything.
     */
    @Test
    void a_stale_conflict_token_is_refused_and_the_refusal_carries_the_current_one() {
        Map<String, Object> created = items.create(SCOPE, Map.of("title", "conflict probe"));
        UUID id = (UUID) created.get("id");
        String staleToken = (String) created.get("conflict_token");

        // Somebody else gets there first.
        Map<String, Object> moved = items.update(SCOPE, id, Map.of(
            "title", "moved on",
            "conflict_token", staleToken));
        String currentToken = (String) moved.get("conflict_token");

        assertThat(currentToken)
            .as("an effective change rotates the token, or the next writer cannot tell "
                + "that the row moved")
            .isNotEqualTo(staleToken);

        WorklistException refusal = catchWorklistException(() -> items.update(SCOPE, id, Map.of(
            "title", "written over the top",
            "conflict_token", staleToken)));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.CONFLICT);
        assertThat(refusal.offenders())
            .as("the refusal must carry the current token, so the caller can re-read, "
                + "re-apply and retry without a round trip whose only purpose is to find "
                + "out what it should have sent")
            .containsExactly(currentToken);

        // What the absent mechanism would have left behind: the second
        // writer's title, and a token rotated a second time. Neither is here.
        Map<String, Object> after = items.read(SCOPE, id);
        assertThat(after.get("title"))
            .as("RED STATE, by its trace: with the check skipped the losing writer would "
                + "have overwritten the winner and this would read 'written over the top'")
            .isEqualTo("moved on");
        assertThat(after.get("conflict_token"))
            .as("and the token would have rotated again")
            .isEqualTo(currentToken);
    }

    /** An absent token is the same refusal as a stale one, for the same reason. */
    @Test
    void a_write_with_no_conflict_token_is_refused() {
        Map<String, Object> created = items.create(SCOPE, Map.of("title", "tokenless probe"));
        UUID id = (UUID) created.get("id");

        WorklistException refusal = catchWorklistException(() ->
            items.update(SCOPE, id, Map.of("title", "no token given")));

        assertThat(refusal.reason())
            .as("no token and a wrong token are the same fact — this write was not built "
                + "from a read of the row it is changing")
            .isEqualTo(WorklistException.Reason.CONFLICT);
        assertThat(items.read(SCOPE, id).get("title"))
            .as("and nothing was written")
            .isEqualTo("tokenless probe");
    }

    // ==================================================================
    // Probe D — the write that changes nothing.
    // ==================================================================

    /**
     * An update carrying only the values already held moves neither the
     * modification date nor the token.
     *
     * <p>This is the measured predecessor defect in its exact shape: a write
     * assembled out of a read answer, changing nothing, leaving behind a
     * fresh modification date and a rotated conflict token. A false change
     * trail and a false conflict signal, from a call that did nothing and
     * reported success.
     */
    @Test
    void an_update_that_changes_nothing_writes_nothing() throws SQLException {
        Map<String, Object> created = items.create(SCOPE, Map.of(
            "title", "no-op probe",
            "reference", "on file",
            "component", List.of("e2e", "ee-srv")));
        UUID id = (UUID) created.get("id");

        Map<String, Object> before = items.read(SCOPE, id);
        // Read from the database BEFORE the call, because the answer the call
        // returns is built by the same code that decided not to write. Only a
        // value read outside that code can say whether a statement was issued.
        String storedDateBefore = storedUpdatedAt(id);

        // The obvious caller move: read, change nothing, send it all back.
        Map<String, Object> echoed = items.update(SCOPE, id, new HashMap<>(before));

        assertThat(echoed.get("updated_at"))
            .as("the modification date must not move for a write that changed no value — "
                + "a change trail that records non-changes stops being evidence of "
                + "anything")
            .isEqualTo(before.get("updated_at"));
        assertThat(echoed.get("conflict_token"))
            .as("and the token must not rotate, or every other caller holding a valid "
                + "token is told the row moved when it did not")
            .isEqualTo(before.get("conflict_token"));

        assertThat(storedUpdatedAt(id))
            .as("and the row itself is untouched, read back outside the ORM — the same "
                + "modification date the database held before the call")
            .isEqualTo(storedDateBefore);
        assertThat(storedToken(id))
            .as("the token in the database is the one the caller already held")
            .isEqualTo(before.get("conflict_token"));

        // RED STATE: what a write WITHOUT the comparison leaves behind, done
        // here by hand so that the assertions above are shown to be capable
        // of failing. A probe that never sees the timestamp move is a probe
        // that would not notice if it always moved.
        String movedToken = UUID.randomUUID().toString();
        stampByHand(id, movedToken);
        assertThat(storedToken(id))
            .as("RED STATE, observed: a write that stamps unconditionally rotates the "
                + "token, and this is the check that would report it. Without this the "
                + "green assertions above could be passing because they measure nothing")
            .isNotEqualTo(before.get("conflict_token"))
            .isEqualTo(movedToken);
    }

    /** And an update that changes ONE value does move both. */
    @Test
    void an_update_that_changes_one_value_moves_the_date_and_the_token() {
        Map<String, Object> created = items.create(SCOPE, Map.of("title", "effective probe"));
        UUID id = (UUID) created.get("id");
        Map<String, Object> before = items.read(SCOPE, id);

        Map<String, Object> changed = new HashMap<>(before);
        changed.put("title", "an actual change");
        Map<String, Object> after = items.update(SCOPE, id, changed);

        assertThat(after.get("conflict_token"))
            .as("a real change rotates the token. Without this half, 'nothing writes on a "
                + "no-op' would be satisfied by an implementation that never writes")
            .isNotEqualTo(before.get("conflict_token"));
        assertThat(after.get("title")).isEqualTo("an actual change");
    }

    /**
     * The list fields, re-sent in another order, are not a change.
     *
     * <p>{@code component} and {@code depends_on} are sets. Without
     * normalisation, a caller re-sending a read answer whose list came back
     * ordered differently would look like a change — and the row would take a
     * fresh date and a rotated token for a write that changed nothing, which
     * is the same defect arriving by a different road.
     */
    @Test
    void a_reordered_list_is_not_a_change() {
        Map<String, Object> created = items.create(SCOPE, Map.of(
            "title", "set semantics probe",
            "component", List.of("ee-srv", "e2e")));
        UUID id = (UUID) created.get("id");
        Map<String, Object> before = items.read(SCOPE, id);

        Map<String, Object> reordered = new HashMap<>(before);
        reordered.put("component", List.of("ee-srv", "e2e", "e2e"));
        Map<String, Object> after = items.update(SCOPE, id, reordered);

        assertThat(after.get("conflict_token"))
            .as("the same set, in another order and with a repeat, is the same value")
            .isEqualTo(before.get("conflict_token"));
    }

    // ==================================================================
    // Probe E — the unknown argument.
    // ==================================================================

    /**
     * A field name that does not exist is a typed refusal that NAMES it, and
     * nothing is written.
     *
     * <p>This probe is the guard against the measured predecessor defect and
     * is not optional. There, a write verb takes lower-case parameters while
     * a read answers in capitalised column names, so read-modify-write sends
     * names the write path does not know — and it DISCARDS them and writes
     * the row anyway. The caller is told it succeeded, every value is
     * unchanged, and the row carries a fresh date and a rotated token.
     */
    @Test
    void an_unknown_field_is_refused_by_name_and_nothing_is_written() {
        Map<String, Object> created = items.create(SCOPE, Map.of("title", "unknown field probe"));
        UUID id = (UUID) created.get("id");
        Map<String, Object> before = items.read(SCOPE, id);

        Map<String, Object> misspelt = new HashMap<>(before);
        misspelt.remove("title");
        misspelt.put("Titel", "the predecessor's spelling");

        WorklistException refusal = catchWorklistException(() ->
            items.update(SCOPE, id, misspelt));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.UNKNOWN_FIELD);
        assertThat(refusal.offenders())
            .as("the refusal must NAME the argument. One that only states the rule sends "
                + "the caller to diff two vocabularies by eye")
            .containsExactly("Titel");
        assertThat(refusal.getMessage())
            .as("and say what was possible instead")
            .contains("title");

        // RED STATE, by its trace. A silently dropped argument would have
        // written the row: same values, fresh date, rotated token. The token
        // is the tell, and it has not moved.
        Map<String, Object> after = items.read(SCOPE, id);
        assertThat(after.get("conflict_token"))
            .as("RED STATE, observed by its absence: had the unknown argument been "
                + "dropped and the write let through, the token would have rotated. That "
                + "rotation is exactly what made the predecessor's defect invisible — the "
                + "caller saw a success and a changed row, and no value had changed")
            .isEqualTo(before.get("conflict_token"));
        assertThat(after.get("updated_at")).isEqualTo(before.get("updated_at"));
        assertThat(after.get("title")).isEqualTo("unknown field probe");
    }

    /** Several unknown names are reported together, not one per round trip. */
    @Test
    void every_unknown_field_is_named_at_once() {
        Map<String, Object> created = items.create(SCOPE, Map.of("title", "several probe"));
        UUID id = (UUID) created.get("id");

        Map<String, Object> arguments = new HashMap<>();
        arguments.put("conflict_token", created.get("conflict_token"));
        arguments.put("Titel", "one");
        arguments.put("Status", "two");
        arguments.put("Disp", "three");

        WorklistException refusal = catchWorklistException(() ->
            items.update(SCOPE, id, arguments));

        assertThat(refusal.offenders())
            .as("a caller that misspelt three fields learns all three in one round trip")
            .containsExactlyInAnyOrder("Titel", "Status", "Disp");
    }

    /**
     * A read answer sent straight back is ACCEPTED, read-only fields and all.
     *
     * <p>Without this the canonical naming would have bought a loud trap
     * instead of a silent one: every honest round trip would be a refusal
     * because the answer carries {@code id} and {@code created_at}.
     */
    @Test
    void a_read_answer_sent_back_unchanged_is_accepted() {
        Map<String, Object> created = items.create(SCOPE, Map.of("title", "round trip probe"));
        UUID id = (UUID) created.get("id");
        Map<String, Object> before = items.read(SCOPE, id);

        Map<String, Object> roundTrip = new HashMap<>(before);
        roundTrip.put("reference", "changed one thing");

        Map<String, Object> after = items.update(SCOPE, id, roundTrip);

        assertThat(after.get("reference")).isEqualTo("changed one thing");
        assertThat(after.get("id"))
            .as("the read-only fields came back with the answer and were echoed, which is "
                + "the obvious thing for a caller to do and must not be a refusal")
            .isEqualTo(before.get("id"));
    }

    /** But a read-only field carrying a DIFFERENT value is refused by name. */
    @Test
    void a_read_only_field_given_a_new_value_is_refused_by_name() {
        Map<String, Object> created = items.create(SCOPE, Map.of("title", "not settable probe"));
        UUID id = (UUID) created.get("id");
        Map<String, Object> before = items.read(SCOPE, id);

        Map<String, Object> tampered = new HashMap<>(before);
        tampered.put("id", UUID.randomUUID());
        tampered.put("number", 99L);

        WorklistException refusal = catchWorklistException(() ->
            items.update(SCOPE, id, tampered));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.FIELD_NOT_SETTABLE);
        assertThat(refusal.offenders())
            .as("echoing state back is fine; setting it is not, and the difference is "
                + "which fields carried a value other than the one held")
            .containsExactlyInAnyOrder("id", "number");
    }

    // ==================================================================
    // Probe F — the number does not come back.
    // ==================================================================

    /**
     * A withdrawn item keeps its number, and the next allocation is the next
     * one up.
     *
     * <p>The red state is established rather than described: after the
     * withdrawal, the highest number among the LIVE items is lower than the
     * mark, so an implementation deriving the next number from
     * {@code max(number) + 1} over live rows would hand out a number that is
     * already in use. That arithmetic is done here, on the real data, and
     * asserted to differ from what was actually allocated.
     */
    @Test
    void a_withdrawn_item_does_not_give_its_number_back() {
        String token = "PF" + shortId();
        selectors.declare(SCOPE, token);

        UUID first = accepted(token, "number probe 1");
        UUID second = accepted(token, "number probe 2");
        UUID third = accepted(token, "number probe 3");

        assertThat(numberOf(first)).isEqualTo(1L);
        assertThat(numberOf(second)).isEqualTo(2L);
        assertThat(numberOf(third)).isEqualTo(3L);

        // The highest one is taken back. This is what the predecessor's
        // `delete` would have done, and it removed the row.
        items.withdraw(SCOPE, third, (String) items.read(SCOPE, third).get("conflict_token"));
        assertThat(items.read(SCOPE, third).get("number"))
            .as("a withdrawn item keeps its address. That is what makes the mark a mark "
                + "by construction rather than by a rule somebody has to keep")
            .isEqualTo(3L);

        // RED STATE, computed on the real data and BEFORE the next
        // allocation, because that is the moment the alternative
        // implementation would have made its decision. Measuring it
        // afterwards would measure a different question.
        long derivedFromLiveRows = highestLiveNumber(token) + 1;

        long allocated = numberOf(accepted(token, "number probe 4"));
        assertThat(allocated)
            .as("the next allocation is the next number up, whatever happened to the "
                + "items already holding numbers")
            .isEqualTo(4L);

        assertThat(derivedFromLiveRows)
            .as("RED STATE, observed: a mark derived from `max(number) + 1` over the live "
                + "rows would have allocated %d instead of %d. Deriving is the obvious "
                + "implementation and it hands a number back the moment the row holding "
                + "it stops being live", derivedFromLiveRows, allocated)
            .isLessThan(allocated);

        assertThat(derivedFromLiveRows)
            .as("and it is not merely lower — it is EXACTLY the number the withdrawn item "
                + "still holds. Two items would answer to one address, and every "
                + "reference ever written to it would become ambiguous with no error "
                + "anywhere")
            .isEqualTo(numberOf(third));
    }

    /**
     * The mark can be carried forward, for the import that has not happened
     * yet — and never back.
     */
    @Test
    void the_mark_moves_forward_and_refuses_to_move_back() {
        String token = "PM" + shortId();
        selectors.declare(SCOPE, token);

        selectors.carryMarkForward(SCOPE, token, 500L);
        assertThat(numberOf(accepted(token, "after the import")))
            .as("an import arrives with numbers already allocated elsewhere, and the mark "
                + "has to be told where the corpus got to")
            .isEqualTo(501L);

        WorklistException refusal = catchWorklistException(() ->
            selectors.carryMarkForward(SCOPE, token, 100L));
        assertThat(refusal.reason())
            .as("moving a mark back is not a smaller version of moving it forward: it is "
                + "the act of handing out numbers that are already in use")
            .isEqualTo(WorklistException.Reason.MARK_REGRESSION);
        assertThat(selectors.markOf(SCOPE, token)).isEqualTo(501L);
    }

    // ==================================================================
    // Probe G — the undeclared selector.
    // ==================================================================

    /**
     * Accepting under a selector that was never declared is refused, and the
     * selector is NOT created.
     *
     * <p>The second half is the whole point and is asserted separately. A
     * service that creates a selector on first use answers a misspelt one by
     * opening a second address space, and afterwards nothing distinguishes
     * the typo from the intention.
     */
    @Test
    void an_undeclared_selector_is_refused_and_is_not_created_by_the_attempt() {
        String neverDeclared = "PG" + shortId();
        UUID id = (UUID) items.create(SCOPE, Map.of("title", "undeclared probe")).get("id");
        String conflictToken = (String) items.read(SCOPE, id).get("conflict_token");

        WorklistException refusal = catchWorklistException(() ->
            items.accept(SCOPE, id, neverDeclared, conflictToken));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.SELECTOR_UNDECLARED);
        assertThat(refusal.offenders()).containsExactly(neverDeclared);

        // RED STATE, by its trace: had the selector been created on first
        // use, it would be here now.
        assertThat(selectors.inScope(SCOPE).stream().map(s -> s.token).toList())
            .as("RED STATE, observed by its absence: creating the selector on first use "
                + "is the alternative implementation, and it would have left this token "
                + "in the scope's address spaces. A misspelt selector would then be "
                + "indistinguishable from an intended one — both exist, both have items")
            .doesNotContain(neverDeclared);

        assertThat(items.read(SCOPE, id).get("selector"))
            .as("and the item did not acquire an address")
            .isNull();
    }

    /** A declared selector that was withdrawn accepts nothing further. */
    @Test
    void a_withdrawn_selector_accepts_nothing_further() {
        String token = "PW" + shortId();
        selectors.declare(SCOPE, token);
        accepted(token, "before the withdrawal");
        selectors.withdraw(SCOPE, token);

        UUID id = (UUID) items.create(SCOPE, Map.of("title", "after the withdrawal")).get("id");
        String conflictToken = (String) items.read(SCOPE, id).get("conflict_token");

        WorklistException refusal = catchWorklistException(() ->
            items.accept(SCOPE, id, token, conflictToken));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.SELECTOR_WITHDRAWN);

        assertThat(selectors.inScope(SCOPE).stream().map(s -> s.token).toList())
            .as("and the token stays occupied, so it cannot be declared again to mean "
                + "something else — every address ever issued under it keeps resolving")
            .contains(token);
    }

    // ==================================================================
    // The vocabulary, which is the same refusal shape one level down.
    // ==================================================================

    /** A term that was never declared in this scope is refused by axis and token. */
    @Test
    void an_undeclared_term_is_refused_by_axis_and_token() {
        UUID id = (UUID) items.create(SCOPE, Map.of("title", "vocabulary probe")).get("id");
        String conflictToken = (String) items.read(SCOPE, id).get("conflict_token");

        WorklistException refusal = catchWorklistException(() -> items.update(SCOPE, id, Map.of(
            "cluster", "NOT-DECLARED",
            "conflict_token", conflictToken)));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.TERM_UNDECLARED);
        assertThat(refusal.offenders())
            .as("the same token can legitimately exist on another axis, so a refusal that "
                + "named only the token would look wrong to a caller who can see it there")
            .containsExactly("cluster", "NOT-DECLARED");
    }

    /** And a declared one is accepted and comes back as its token. */
    @Test
    void a_declared_term_is_accepted_and_reads_back_as_its_token() {
        String token = "CL" + shortId();
        terms.declare(SCOPE, Term.CLUSTER, token, 1);

        UUID id = (UUID) items.create(SCOPE, Map.of("title", "declared term probe")).get("id");
        String conflictToken = (String) items.read(SCOPE, id).get("conflict_token");

        Map<String, Object> after = items.update(SCOPE, id, Map.of(
            "cluster", token,
            "conflict_token", conflictToken));

        assertThat(after.get("cluster"))
            .as("a vocabulary field is written as a token and read back as the same "
                + "token — one naming, in both directions, whatever the column holds")
            .isEqualTo(token);
    }

    // ==================================================================
    // The status vocabulary, and the value that is deliberately absent.
    // ==================================================================

    /**
     * {@code planned} is not a status here, and the refusal says why.
     *
     * <p>It is the sharpest cut of this whole piece of work. It exists in the
     * predecessor because there was no membership table to derive it from,
     * and there is none here either — so building it would be carrying a
     * property of the Markdown store into SQL and calling it a decision.
     */
    @Test
    void planned_is_not_a_status_of_an_item() {
        UUID id = (UUID) items.create(SCOPE, Map.of("title", "status probe")).get("id");
        String conflictToken = (String) items.read(SCOPE, id).get("conflict_token");

        WorklistException refusal = catchWorklistException(() -> items.update(SCOPE, id, Map.of(
            "status", "planned",
            "conflict_token", conflictToken)));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.getMessage())
            .as("and the message names the five that are there plus the one the "
                + "predecessor's delete became")
            .contains("new", "open", "done", "dropped", "obsolete", "withdrawn");
    }

    // ==================================================================
    // The dependency edge.
    // ==================================================================

    /**
     * The edges are set as a whole, and one that leaves the set is withdrawn
     * rather than deleted.
     *
     * <p>The withdrawal is not visible from the read side — an edge that is
     * no longer asserted is history — so it is asserted by what happens when
     * the same edge comes BACK: the row that was already there is asserted
     * again, and no second row appears. A delete-and-reinsert would be
     * indistinguishable from the outside, which is why the guarantee is
     * carried by the missing DELETE privilege rather than by this test.
     */
    @Test
    void dependencies_are_set_as_a_whole_and_a_removed_edge_comes_back() {
        UUID first = created("dependency probe 1");
        UUID second = created("dependency probe 2");
        UUID third = created("dependency probe 3");

        Map<String, Object> withBoth = updateField(first, "depends_on",
            List.of(second.toString(), third.toString()));
        assertThat(dependsOn(withBoth))
            .as("both edges are asserted, and the answer is sorted so that re-sending it "
                + "is not a change")
            .containsExactlyInAnyOrder(second, third);

        Map<String, Object> withOne = updateField(first, "depends_on", List.of(third));
        assertThat(dependsOn(withOne))
            .as("the edge that left the set is no longer asserted")
            .containsExactly(third);

        Map<String, Object> backAgain = updateField(first, "depends_on",
            List.of(second, third));
        assertThat(dependsOn(backAgain))
            .as("and re-asserting it works — the row was kept, so this is an update of "
                + "the edge that was already there rather than a second one")
            .containsExactlyInAnyOrder(second, third);
    }

    /** Setting the same edges again changes nothing, so nothing is written. */
    @Test
    void re_asserting_the_same_dependencies_is_not_a_change() {
        UUID first = created("dependency no-op 1");
        UUID second = created("dependency no-op 2");

        updateField(first, "depends_on", List.of(second));
        Map<String, Object> before = items.read(SCOPE, first);

        Map<String, Object> after = updateField(first, "depends_on", List.of(second));
        assertThat(after.get("conflict_token"))
            .as("the same edge set is the same value, so the token must not rotate")
            .isEqualTo(before.get("conflict_token"));
    }

    /** The one cycle a single row can express is refused by name. */
    @Test
    void an_item_cannot_depend_on_itself() {
        UUID id = created("self dependency probe");

        WorklistException refusal = catchWorklistException(() ->
            updateField(id, "depends_on", List.of(id)));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly("depends_on");
    }

    /**
     * A dependency on an item that does not exist cannot be written at all.
     *
     * <p>The contract lists a dangling reference as a whole-inventory
     * violation that a validation pass reports, because a comma-separated
     * list of numbers in a text cell can point anywhere. Here the foreign key
     * refuses it, so the violation class does not exist rather than being
     * detected — which is the stronger outcome and is the point of asking
     * what each property would look like in a database.
     *
     * <p>The refusal is a constraint violation and not a typed one, and that
     * is honest: the check is the database's, and dressing it up would
     * suggest the domain decides something it does not.
     */
    @Test
    void a_dependency_on_an_item_that_does_not_exist_cannot_be_written() {
        UUID id = created("dangling probe");

        assertThat(catchThrowable(() ->
            updateField(id, "depends_on", List.of(UUID.randomUUID()))))
            .as("the edge has a foreign key on both ends, so a dangling reference is not "
                + "something to find later — it is something that cannot be stored")
            .isNotNull();
    }

    // ==================================================================
    // The remaining refusals, each named.
    // ==================================================================

    /** An address is allocated once. */
    @Test
    void an_item_is_accepted_once() {
        String token = "PA" + shortId();
        selectors.declare(SCOPE, token);
        UUID id = accepted(token, "double acceptance probe");

        WorklistException refusal = catchWorklistException(() -> items.accept(SCOPE, id, token,
            (String) items.read(SCOPE, id).get("conflict_token")));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.ALREADY_ACCEPTED);
        assertThat(items.read(SCOPE, id).get("number"))
            .as("and the first address is untouched — a re-allocation would make every "
                + "reference to the old one resolve to something else")
            .isEqualTo(1L);
    }

    /** An item of another scope, or of no scope, is not this scope's item. */
    @Test
    void an_item_that_is_not_in_this_scope_is_unknown() {
        UUID id = created("scope probe");
        UUID otherScope = UUID.randomUUID();

        assertThat(catchWorklistException(() -> items.read(otherScope, id)).reason())
            .as("an item is addressed within its scope, and a lookup from another scope "
                + "must not resolve it")
            .isEqualTo(WorklistException.Reason.ITEM_UNKNOWN);
        assertThat(catchWorklistException(() -> items.read(SCOPE, UUID.randomUUID()))
            .reason())
            .isEqualTo(WorklistException.Reason.ITEM_UNKNOWN);
    }

    /** A new item carries no service-derived field, and the refusal names them. */
    @Test
    void a_new_item_may_not_be_given_a_derived_field() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("title", "derived field probe");
        arguments.put("number", 7L);

        WorklistException refusal = catchWorklistException(() -> items.create(SCOPE, arguments));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.FIELD_NOT_SETTABLE);
        assertThat(refusal.offenders())
            .as("an address is allocated by admission and never supplied")
            .containsExactly("number");
    }

    /** And it carries a title, on every status. */
    @Test
    void an_item_without_a_title_is_refused() {
        assertThat(catchWorklistException(() -> items.create(SCOPE, Map.of())).reason())
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);

        UUID id = created("title clearing probe");
        assertThat(catchWorklistException(() -> {
            Map<String, Object> clearing = new HashMap<>(items.read(SCOPE, id));
            clearing.put("title", "   ");
            items.update(SCOPE, id, clearing);
        }).reason())
            .as("the title cannot be cleared either — it is the one field required "
                + "regardless of status")
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);
    }

    /** A malformed selector token is refused before anything is created. */
    @Test
    void a_malformed_selector_token_is_refused() {
        WorklistException refusal =
            catchWorklistException(() -> selectors.declare(SCOPE, "1-not-a-selector"));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(selectors.inScope(SCOPE).stream().map(s -> s.token).toList())
            .doesNotContain("1-not-a-selector");
    }

    /** Declaring twice is the same statement made twice, not a collision. */
    @Test
    void declaring_a_selector_twice_returns_the_one_that_exists() {
        String token = "PI" + shortId();
        UUID first = selectors.declare(SCOPE, token).id;

        assertThat(selectors.declare(SCOPE, token).id)
            .as("declaration states that the space should exist, and a retry after a "
                + "timeout should not have to tell 'created' from 'already there'")
            .isEqualTo(first);
        assertThat(selectors.markOf(SCOPE, token))
            .as("and the second declaration did not reset the address space")
            .isZero();
    }

    // ==================================================================
    // The vocabulary's own lifecycle.
    // ==================================================================

    /** There is no fifth axis: the axes are structure, the tokens are data. */
    @Test
    void there_is_no_axis_beyond_the_four() {
        WorklistException refusal =
            catchWorklistException(() -> terms.declare(SCOPE, "urgency", "HIGH", 1));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.getMessage()).contains("cluster", "type", "priority", "size");
    }

    /** A term token carries no whitespace and is not empty. */
    @Test
    void a_malformed_term_token_is_refused() {
        assertThat(catchWorklistException(() ->
            terms.declare(SCOPE, Term.SIZE, "two words", 1)).reason())
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(catchWorklistException(() ->
            terms.declare(SCOPE, Term.SIZE, "  ", 1)).reason())
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);
    }

    /**
     * A withdrawn term stays resolvable on the items already carrying it.
     *
     * <p>That is why withdrawal is a status here rather than a deletion: an
     * item characterised two years ago has to keep saying what it was
     * characterised as, or its own history stops being legible.
     */
    @Test
    void a_withdrawn_term_still_reads_back_on_the_items_that_carry_it() {
        String token = "TW" + shortId();
        terms.declare(SCOPE, Term.TYPE, token, 3);

        UUID id = created("withdrawn term probe");
        updateField(id, "type", token);

        terms.withdraw(SCOPE, Term.TYPE, token);

        assertThat(items.read(SCOPE, id).get("type"))
            .as("the item keeps reading back the term it was characterised with")
            .isEqualTo(token);
        assertThat(terms.onAxis(SCOPE, Term.TYPE).stream().map(t -> t.token).toList())
            .as("and the term is still on the axis, with its status changed rather than "
                + "its row removed")
            .contains(token);
    }

    /** Declaring a term twice is idempotent, like declaring a selector. */
    @Test
    void declaring_a_term_twice_returns_the_one_that_exists() {
        String token = "TT" + shortId();
        UUID first = terms.declare(SCOPE, Term.PRIORITY, token, 1).id;
        assertThat(terms.declare(SCOPE, Term.PRIORITY, token, 9).id).isEqualTo(first);
    }

    // ==================================================================
    // Reading a scope.
    // ==================================================================

    /** A query of the scope returns the created items in the canonical shape. */
    @Test
    void a_query_returns_the_items_of_the_scope_in_the_canonical_shape() {
        UUID id = created("query probe " + shortId());

        assertThat(items.query(SCOPE))
            .as("the query is the same projection the single read gives, so a caller "
                + "handles one shape rather than two")
            .anySatisfy(item -> {
                assertThat(item.get("id")).isEqualTo(id);
                assertThat(item).containsKeys("title", "status", "component", "depends_on",
                    "conflict_token", "created_at", "updated_at");
            });
    }

    // ==================================================================
    // Helpers.
    // ==================================================================

    /**
     * The asserted dependencies of a projection, typed.
     *
     * <p>An unchecked cast rather than a wildcard, because the field holds
     * item ids by contract and a wildcard leaves the assertion unable to name
     * what it is comparing against.
     */
    @SuppressWarnings("unchecked")
    private static List<UUID> dependsOn(Map<String, Object> projection) {
        return (List<UUID>) projection.get("depends_on");
    }

    /** A created item, returning its id. */
    private UUID created(String title) {
        return (UUID) items.create(SCOPE, Map.of("title", title)).get("id");
    }

    /** One field updated, with the token read immediately before. */
    private Map<String, Object> updateField(UUID id, String field, Object value) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put(field, value);
        arguments.put("conflict_token", items.read(SCOPE, id).get("conflict_token"));
        return items.update(SCOPE, id, arguments);
    }

    /** A created item, accepted under a selector, returning its id. */
    private UUID accepted(String selectorToken, String title) {
        Map<String, Object> created = items.create(SCOPE, Map.of("title", title));
        UUID id = (UUID) created.get("id");
        items.accept(SCOPE, id, selectorToken, (String) created.get("conflict_token"));
        return id;
    }

    private long numberOf(UUID id) {
        return (Long) items.read(SCOPE, id).get("number");
    }

    /** Short and unique, so a probe's selectors do not collide across runs. */
    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    /**
     * The typed refusal a call raised, asserting that it raised one at all.
     *
     * <p>Without the instance check a call that returned normally would give
     * a null here, and every assertion on the refusal below it would fail as
     * a null pointer — which reads as a broken test rather than as the
     * missing refusal it actually is.
     */
    private static WorklistException catchWorklistException(ThrowingCallable call) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(call);
        assertThat(thrown)
            .as("the call must have refused; a typed refusal is the assertion, not an "
                + "incidental exception")
            .isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }

    // --- reading and writing around the ORM, for the red states ----------

    /**
     * The highest number among items NOT withdrawn — the quantity a derived
     * mark would be computed from.
     */
    private long highestLiveNumber(String selectorToken) {
        List<Long> live = new ArrayList<>();
        for (Map<String, Object> item : items.query(SCOPE)) {
            if (selectorToken.equals(item.get("selector"))
                && !Item.WITHDRAWN.equals(item.get("status"))
                && item.get("number") != null) {
                live.add((Long) item.get("number"));
            }
        }
        return live.stream().mapToLong(Long::longValue).max().orElse(0L);
    }

    /**
     * The stamp a write WITHOUT the equality check would have left, applied
     * by hand so that the green assertions are shown capable of failing.
     */
    private void stampByHand(UUID id, String token) throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, boundTenant());
            try (var st = c.prepareStatement(
                    "UPDATE worklist.item SET updated_at = now(), conflict_token = ? "
                        + "WHERE id = ?")) {
                st.setString(1, token);
                st.setObject(2, id);
                st.executeUpdate();
            }
            c.commit();
        }
    }

    private String storedToken(UUID id) throws SQLException {
        return storedColumn(id, "conflict_token");
    }

    private String storedUpdatedAt(UUID id) throws SQLException {
        return storedColumn(id, "updated_at");
    }

    private String storedColumn(UUID id, String column) throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, boundTenant());
            try (var st = c.prepareStatement(
                    "SELECT " + column + "::text FROM worklist.item WHERE id = ?")) {
                st.setObject(1, id);
                try (ResultSet rs = st.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }
    }
}
