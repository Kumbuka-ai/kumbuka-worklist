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
 *
 * <h2>What changed when the vocabulary became a declaration</h2>
 *
 * The probes below used to characterise items with four fixed attribute axes
 * and a status drawn from five literals this service knew by name. Both are
 * gone: a status is a value the SCOPE declared, carrying the four predicates,
 * and cluster, type, priority and size are declared attributes travelling in
 * one document column. Every probe that stood on those two is rewritten
 * against the declaration rather than dropped — the guarantee each one made
 * survives, and only what it is made of has changed.
 *
 * <p>The most visible consequence is that every item needs a declared status
 * before it can exist at all, which is why {@link #openStatus()} is the first
 * thing most of these probes do. That is not scaffolding: it is the same
 * order in which a selector has to be declared before an address can be
 * allocated.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ItemDomainIT {

    private static final UUID SCOPE = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);

    /**
     * The scope's two statuses, declared once for the whole class.
     *
     * <p>Static and lazily filled, because a status is scope data rather than
     * test data: declaring one per test would leave the scope carrying dozens
     * of vocabularies that mean the same thing, and the probes that read the
     * declaration back would then be reading whichever one they happened to
     * make.
     */
    private static UUID openStatusId;
    private static UUID closedStatusId;

    @Inject ItemService items;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;

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
        Map<String, Object> created = created("conflict probe");
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
        UUID id = createdId("tokenless probe");

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
     *
     * <p>The item is given one of each kind of composite field — a
     * description, a declared attribute, a reference entry — so that the
     * comparison is exercised on the shapes that can most easily be normalised
     * wrongly, rather than only on a scalar.
     */
    @Test
    void an_update_that_changes_nothing_writes_nothing() throws SQLException {
        String key = attribute("text");
        Map<String, Object> created = items.create(SCOPE, Map.of(
            "title", "no-op probe",
            "status", String.valueOf(openStatus()),
            "description", "what it is and why",
            "attributes", Map.of(key, "a declared value"),
            "references", List.of(Map.of("label", "the design", "target", "docs/thing.md"))));
        UUID id = (UUID) created.get("id");

        Map<String, Object> before = items.read(SCOPE, id);
        // Read from the database BEFORE the call, because the answer the call
        // returns is built by the same code that decided not to write. Only a
        // value read outside that code can say whether a statement was issued.
        String storedDateBefore = storedChangedAt(id);

        // The obvious caller move: read, change nothing, send it all back.
        Map<String, Object> echoed = items.update(SCOPE, id, new HashMap<>(before));

        assertThat(echoed.get("changed_at"))
            .as("the modification date must not move for a write that changed no value — "
                + "a change trail that records non-changes stops being evidence of "
                + "anything")
            .isEqualTo(before.get("changed_at"));
        assertThat(echoed.get("conflict_token"))
            .as("and the token must not rotate, or every other caller holding a valid "
                + "token is told the row moved when it did not")
            .isEqualTo(before.get("conflict_token"));

        assertThat(storedChangedAt(id))
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
        UUID id = createdId("effective probe");
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
     * The relation set, re-sent in another order and with a repeat, is not a
     * change.
     *
     * <p>{@code relations} is a set: an edge is asserted or it is not, and
     * listing it twice or in another order says nothing different. Without
     * normalisation, a caller re-sending a read answer whose list came back
     * ordered differently would look like a change — and the row would take a
     * fresh date and a rotated token for a write that changed nothing, which
     * is the same defect arriving by a different road.
     *
     * <p>This probe used to stand on the component tag array, which was a set
     * for the same reason. Component tags are a declared attribute now, so the
     * property is asserted where a set still lives.
     */
    @Test
    void a_reordered_list_is_not_a_change() {
        UUID first = createdId("set semantics probe");
        UUID second = createdId("set semantics target 1");
        UUID third = createdId("set semantics target 2");
        UUID type = relationType("blocks", true);

        updateField(first, "relations", List.of(
            relation(type, second), relation(type, third)));
        Map<String, Object> before = items.read(SCOPE, first);

        Map<String, Object> reordered = new HashMap<>(before);
        reordered.put("relations", List.of(
            relation(type, third), relation(type, second), relation(type, third)));
        Map<String, Object> after = items.update(SCOPE, first, reordered);

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
        UUID id = createdId("unknown field probe");
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
        assertThat(after.get("changed_at")).isEqualTo(before.get("changed_at"));
        assertThat(after.get("title")).isEqualTo("unknown field probe");
    }

    /**
     * A scope's own attribute is not a field, and misspelling one is caught by
     * the declaration rather than by this enum.
     *
     * <p>The distinction matters and is new. {@code cluster} used to be a
     * field name; it is a declared attribute now, so sending it as a top-level
     * argument is an unknown FIELD, while sending an undeclared key inside
     * {@code attributes} is an undeclared VALUE. Two different mistakes, two
     * different refusals, and a caller that confused them would look for the
     * fix in the wrong place.
     */
    @Test
    void every_unknown_field_is_named_at_once() {
        Map<String, Object> created = created("several probe");
        UUID id = (UUID) created.get("id");

        Map<String, Object> arguments = new HashMap<>();
        arguments.put("conflict_token", created.get("conflict_token"));
        arguments.put("Titel", "one");
        arguments.put("Status", "two");
        arguments.put("cluster", "three");

        WorklistException refusal = catchWorklistException(() ->
            items.update(SCOPE, id, arguments));

        assertThat(refusal.offenders())
            .as("a caller that misspelt three fields learns all three in one round trip, "
                + "and a declared attribute sent as a field is one of the three: it "
                + "travels inside `attributes` under the key it was declared with")
            .containsExactlyInAnyOrder("Titel", "Status", "cluster");
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
        UUID id = createdId("round trip probe");
        Map<String, Object> before = items.read(SCOPE, id);

        Map<String, Object> roundTrip = new HashMap<>(before);
        roundTrip.put("description", "changed one thing");

        Map<String, Object> after = items.update(SCOPE, id, roundTrip);

        assertThat(after.get("description")).isEqualTo("changed one thing");
        assertThat(after.get("id"))
            .as("the read-only fields came back with the answer and were echoed, which is "
                + "the obvious thing for a caller to do and must not be a refusal")
            .isEqualTo(before.get("id"));
    }

    /** But a read-only field carrying a DIFFERENT value is refused by name. */
    @Test
    void a_read_only_field_given_a_new_value_is_refused_by_name() {
        UUID id = createdId("not settable probe");
        Map<String, Object> before = items.read(SCOPE, id);

        Map<String, Object> tampered = new HashMap<>(before);
        tampered.put("id", UUID.randomUUID());
        tampered.put("number", 99L);
        tampered.put("milestone", UUID.randomUUID());

        WorklistException refusal = catchWorklistException(() ->
            items.update(SCOPE, id, tampered));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.FIELD_NOT_SETTABLE);
        assertThat(refusal.offenders())
            .as("echoing state back is fine; setting it is not, and the difference is "
                + "which fields carried a value other than the one held. The milestone is "
                + "among them because setting it is a planning act")
            .containsExactlyInAnyOrder("id", "number", "milestone");
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
        UUID first = createdId("number probe 1");
        UUID second = createdId("number probe 2");
        UUID third = createdId("number probe 3");

        // Relative and not absolute: the counter is the SCOPE's now, one for
        // every view, and the probes of this class share a scope. What the
        // guarantee is about is that consecutive allocations are consecutive
        // and that none comes back — neither of which says where counting
        // started.
        long thirdNumber = numberOf(third);
        assertThat(numberOf(second)).isEqualTo(numberOf(first) + 1);
        assertThat(thirdNumber).isEqualTo(numberOf(second) + 1);

        // The highest one is taken back. This is what the predecessor's
        // `delete` would have done, and it removed the row.
        items.withdraw(SCOPE, third, closedStatus(),
            (String) items.read(SCOPE, third).get("conflict_token"));
        assertThat(items.read(SCOPE, third).get("number"))
            .as("a withdrawn item keeps its address. That is what makes the mark a mark "
                + "by construction rather than by a rule somebody has to keep")
            .isEqualTo(thirdNumber);

        // RED STATE, computed on the real data and BEFORE the next
        // allocation, because that is the moment the alternative
        // implementation would have made its decision. Measuring it
        // afterwards would measure a different question.
        long derivedFromLiveRows = highestLiveNumber() + 1;

        long allocated = numberOf(createdId("number probe 4"));
        assertThat(allocated)
            .as("the next allocation is the next number up, whatever happened to the "
                + "items already holding numbers")
            .isEqualTo(thirdNumber + 1);

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
            .isEqualTo(thirdNumber);
    }


    /**
     * The mark can be carried forward, for the import that has not happened
     * yet — and never back.
     *
     * <p>Carried on the view now, which is where an import of the predecessor's
     * corpus would arrive: one counter for the scope, and the families that used
     * to have counters of their own are not address spaces any more.
     */
    @Test
    void the_mark_moves_forward_and_refuses_to_move_back() {
        itemView();
        long standing = selectors.markOf(SCOPE, Selector.ITEM);
        long imported = standing + 500L;

        selectors.carryMarkForward(SCOPE, Selector.ITEM, imported);
        assertThat(numberOf(createdId("after the import")))
            .as("an import arrives with numbers already allocated elsewhere, and the mark "
                + "has to be told where the corpus got to")
            .isEqualTo(imported + 1);

        WorklistException refusal = catchWorklistException(() ->
            selectors.carryMarkForward(SCOPE, Selector.ITEM, standing));
        assertThat(refusal.reason())
            .as("moving a mark back is not a smaller version of moving it forward: it is "
                + "the act of handing out numbers that are already in use")
            .isEqualTo(WorklistException.Reason.MARK_REGRESSION);
        assertThat(selectors.markOf(SCOPE, Selector.ITEM)).isEqualTo(imported + 1);
    }


    // ==================================================================
    // Probe G — the undeclared view.
    // ==================================================================

    /**
     * Creating in a scope whose view was never declared is refused, and the
     * view is NOT created by the attempt.
     *
     * <p>The second half is the whole point and is asserted separately. A
     * service that creates a selector on first use answers a misspelt one by
     * opening a second address space, and afterwards nothing distinguishes the
     * typo from the intention.
     *
     * <p>What moved with the view model is WHEN this is reached: the address is
     * allocated at creation now, so the refusal arrives at the first write of an
     * item rather than at its ratification. What it protects is unchanged.
     */
    @Test
    void an_undeclared_view_is_refused_and_is_not_created_by_the_attempt() {
        UUID untouchedScope = UUID.randomUUID();

        WorklistException refusal = catchWorklistException(() ->
            items.create(untouchedScope, Map.of(
                "title", "undeclared probe",
                "status", String.valueOf(openStatus()))));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.SELECTOR_UNDECLARED);
        assertThat(refusal.offenders()).containsExactly(Selector.ITEM);

        // RED STATE, by its trace: had the view been created on first use, it
        // would be here now.
        assertThat(selectors.inScope(untouchedScope))
            .as("RED STATE, observed by its absence: creating the selector on first use "
                + "is the alternative implementation, and it would have left the item view "
                + "in this scope's address spaces — with an item under it, in a scope "
                + "nobody had opened")
            .isEmpty();
    }

    /** A declared view that was withdrawn accepts nothing further. */
    @Test
    void a_withdrawn_view_accepts_nothing_further() {
        UUID closingScope = UUID.randomUUID();
        selectors.declare(closingScope, Selector.ITEM);
        selectors.withdraw(closingScope, Selector.ITEM);

        WorklistException refusal = catchWorklistException(() ->
            items.create(closingScope, Map.of(
                "title", "after the withdrawal",
                "status", String.valueOf(openStatus()))));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.SELECTOR_WITHDRAWN);

        assertThat(selectors.inScope(closingScope).stream().map(s -> s.token).toList())
            .as("and the token stays occupied, so it cannot be declared again to mean "
                + "something else — every address ever issued under it keeps resolving")
            .contains(Selector.ITEM);
    }

    /**
     * Both counters exist and both move, whatever position the scope is in.
     *
     * <p>That is what makes the allocation mode a setting rather than a
     * migration: switching it is a read against a counter that was maintained
     * all along. A scope-wide counter that only started being kept when the
     * mode was switched would have to be reconstructed from rows that no
     * longer say what was handed out — and the burnt numbers, which are
     * exactly the ones that must stay burnt, are the ones no row records.
     */
    @Test
    void both_counters_are_maintained_whatever_the_mode_reads() {
        itemView();
        long wideBefore = scopeWideMark();
        long perViewBefore = perViewMark();

        createdId("counter probe 1");
        createdId("counter probe 2");

        assertThat(perViewMark())
            .as("the per-selector counter advanced by both allocations, though the scope "
                + "is in the position that does not read it. Keeping it only in the other "
                + "mode would make switching a reconstruction rather than a read")
            .isEqualTo(perViewBefore + 2);

        assertThat(scopeWideMark())
            .as("and so did the scope-wide one, which is the counter this scope's mode "
                + "reads")
            .isEqualTo(wideBefore + 2);

        assertThat(selectors.markOf(SCOPE, Selector.ITEM))
            .as("what the registry reports as the standing mark is the counter the mode "
                + "names — asking where the space stands is asking what the next number "
                + "will be built on")
            .isEqualTo(scopeWideMark());
    }

    // ==================================================================
    // The declared attributes, which are the vocabulary one level down.
    // ==================================================================

    /** An attribute that was never declared in this scope is refused by key. */
    @Test
    void an_undeclared_attribute_is_refused_by_key() {
        UUID id = createdId("vocabulary probe");
        String conflictToken = (String) items.read(SCOPE, id).get("conflict_token");

        WorklistException refusal = catchWorklistException(() -> items.update(SCOPE, id, Map.of(
            "attributes", Map.of("not_declared", "anything"),
            "conflict_token", conflictToken)));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.VALUE_UNDECLARED);
        assertThat(refusal.offenders())
            .as("the refusal names the key, because a scope's attribute set is a "
                + "declaration and a value under an undeclared key is one nothing can "
                + "read back")
            .containsExactly("not_declared");
    }

    /**
     * A declared attribute is accepted and reads back under its key.
     *
     * <p>Under its KEY and not its identity, which is the round trip that
     * makes a rename cheap: the column keys by identity, the answer keys by
     * the key, and a scope renaming the display name moves neither.
     */
    @Test
    void a_declared_attribute_is_accepted_and_reads_back_under_its_key() {
        String key = attribute("text");
        UUID id = createdId("declared attribute probe");

        Map<String, Object> after = updateField(id, "attributes", Map.of(key, "SEC"));

        assertThat(attributesOf(after))
            .as("an attribute is written under its declared key and read back under the "
                + "same one — one naming, in both directions, whatever the column holds")
            .containsEntry(key, "SEC");
    }

    /**
     * An enumerated attribute takes an option's identity, and an identity from
     * another declaration is refused.
     *
     * <p>The five other types are stored as given: the platform asks no
     * question about what a {@code text} or a {@code number} means. It can ask
     * about an option, because an option that was never declared is a value
     * nothing can render.
     */
    @Test
    void an_enumerated_attribute_takes_a_declared_option_and_nothing_else() {
        String size = attribute("choice");
        String other = attribute("choice");
        UUID small = vocabulary.declareOption(SCOPE, size, "S", 1).id;
        UUID foreign = vocabulary.declareOption(SCOPE, other, "S", 1).id;

        UUID id = createdId("option probe");
        Map<String, Object> after =
            updateField(id, "attributes", Map.of(size, String.valueOf(small)));
        assertThat(attributesOf(after))
            .as("what an item stores is the option's identity, so the option can be "
                + "renamed without touching a single item")
            .containsEntry(size, String.valueOf(small));

        WorklistException refusal = catchWorklistException(() ->
            updateField(id, "attributes", Map.of(size, String.valueOf(foreign))));
        assertThat(refusal.reason())
            .as("an option of another declaration is not an option of this one, however "
                + "identically it is spelled — which is exactly why the name is not the "
                + "identity")
            .isEqualTo(WorklistException.Reason.VALUE_UNDECLARED);
    }

    // ==================================================================
    // The status vocabulary, which this service no longer knows by name.
    // ==================================================================

    /**
     * A status is a declared value, and an undeclared one is refused.
     *
     * <p>This probe stood on the literal vocabulary before — it asserted that
     * {@code planned} was not one of the six values this service knew. There
     * are no six values now, and that is the sharper cut: {@code planned} is
     * not refused because it is missing from a list here, it is refused
     * because no scope declared it. The reason it was never in the list
     * survives unchanged and is now structural — {@code planned} means
     * membership of an iteration, membership is an entity, and an item reading
     * planned with no membership cannot exist.
     */
    @Test
    void a_status_is_a_declared_value_and_an_undeclared_one_is_refused() {
        UUID id = createdId("status probe");
        String conflictToken = (String) items.read(SCOPE, id).get("conflict_token");

        WorklistException refusal = catchWorklistException(() -> items.update(SCOPE, id, Map.of(
            "status", String.valueOf(UUID.randomUUID()),
            "conflict_token", conflictToken)));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.VALUE_UNDECLARED);
        assertThat(refusal.getMessage())
            .as("and the message says where the vocabulary comes from, because a caller "
                + "reading 'no such status' would otherwise look for a list in this "
                + "service — which is precisely what is not here any more")
            .contains("declared");
    }

    /**
     * The predicates hang off the declaration and never off the item.
     *
     * <p>The item stores an identity; whether it is closed is a join. A copy
     * on the row would be a second truth that drifts, and it is the drift
     * rather than the redundancy that matters: an item saying closed while its
     * status says otherwise is a state nothing can repair, because neither
     * side is obviously the wrong one.
     */
    @Test
    void the_predicates_are_read_from_the_declaration_and_not_from_the_item() {
        UUID id = createdId("predicate probe");

        assertThat(items.read(SCOPE, id))
            .as("the answer carries the status identity and none of the four predicates: "
                + "they are the declaration's and are read through it")
            .doesNotContainKeys("actionable", "in_progress", "closed", "successful");

        assertThat(vocabulary.requireStatus(SCOPE, openStatus()).actionable)
            .as("and the predicates are there, on the declared value where they belong")
            .isTrue();
        assertThat(vocabulary.requireStatus(SCOPE, closedStatus()).closed).isTrue();
    }

    /** A status cannot be both closed and in progress, and the refusal says so. */
    @Test
    void a_status_cannot_be_both_finished_and_being_worked_on() {
        WorklistException refusal = catchWorklistException(() ->
            vocabulary.declareStatus(SCOPE, "impossible", 0, false, true, true, false));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.getMessage())
            .as("finished means there is nothing further to do, and in progress means "
                + "somebody is doing it — the two cannot both hold of one value")
            .contains("closed");
    }

    /** Withdrawing an item moves it to a status the scope declared as closed. */
    @Test
    void a_withdrawal_is_terminal_and_the_value_is_the_scopes_own() {
        UUID id = createdId("withdrawal probe");

        WorklistException refusal = catchWorklistException(() -> items.withdraw(SCOPE, id,
            openStatus(), (String) items.read(SCOPE, id).get("conflict_token")));
        assertThat(refusal.reason())
            .as("that a withdrawal is terminal is the platform's; which value a scope "
                + "closes with is the scope's own declaration")
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);

        Map<String, Object> after = items.withdraw(SCOPE, id, closedStatus(),
            (String) items.read(SCOPE, id).get("conflict_token"));
        assertThat(after.get("status")).isEqualTo(closedStatus());
    }

    // ==================================================================
    // The typed relation.
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
    void relations_are_set_as_a_whole_and_a_removed_edge_comes_back() {
        UUID first = createdId("relation probe 1");
        UUID second = createdId("relation probe 2");
        UUID third = createdId("relation probe 3");
        UUID type = relationType("blocks", true);

        Map<String, Object> withBoth = updateField(first, "relations",
            List.of(relation(type, second), relation(type, third)));
        assertThat(targetsOf(withBoth))
            .as("both edges are asserted, and the answer is sorted so that re-sending it "
                + "is not a change")
            .containsExactlyInAnyOrder(second, third);

        Map<String, Object> withOne =
            updateField(first, "relations", List.of(relation(type, third)));
        assertThat(targetsOf(withOne))
            .as("the edge that left the set is no longer asserted")
            .containsExactly(third);

        Map<String, Object> backAgain = updateField(first, "relations",
            List.of(relation(type, second), relation(type, third)));
        assertThat(targetsOf(backAgain))
            .as("and re-asserting it works — the row was kept, so this is an update of "
                + "the edge that was already there rather than a second one")
            .containsExactlyInAnyOrder(second, third);
    }

    /**
     * Two items may carry two edges of DIFFERENT types and never two of the
     * same.
     *
     * <p>The key is the triple, and this is the rule the untyped edge could
     * not state: there, a second kind of relation between the same two items
     * would have overwritten the first, and nothing would have said so.
     */
    @Test
    void two_items_may_carry_two_edges_of_different_types() {
        UUID first = createdId("two types probe 1");
        UUID second = createdId("two types probe 2");
        UUID blocks = relationType("blocks", true);
        UUID relates = relationType("relates to", false);

        Map<String, Object> after = updateField(first, "relations",
            List.of(relation(blocks, second), relation(relates, second)));

        assertThat(relationsOf(after))
            .as("the key is the triple from, to and type, so both edges stand")
            .hasSize(2);
    }

    /** Setting the same edges again changes nothing, so nothing is written. */
    @Test
    void re_asserting_the_same_relations_is_not_a_change() {
        UUID first = createdId("relation no-op 1");
        UUID second = createdId("relation no-op 2");
        UUID type = relationType("blocks", true);

        updateField(first, "relations", List.of(relation(type, second)));
        Map<String, Object> before = items.read(SCOPE, first);

        Map<String, Object> after =
            updateField(first, "relations", List.of(relation(type, second)));
        assertThat(after.get("conflict_token"))
            .as("the same edge set is the same value, so the token must not rotate")
            .isEqualTo(before.get("conflict_token"));
    }

    /** The one cycle a single row can express is refused by name. */
    @Test
    void an_item_cannot_relate_to_itself() {
        UUID id = createdId("self relation probe");
        UUID type = relationType("blocks", true);

        WorklistException refusal = catchWorklistException(() ->
            updateField(id, "relations", List.of(relation(type, id))));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly("relations");
    }

    /** An edge of a type nobody declared is refused before it reaches the table. */
    @Test
    void a_relation_of_an_undeclared_type_is_refused() {
        UUID first = createdId("undeclared type probe 1");
        UUID second = createdId("undeclared type probe 2");

        WorklistException refusal = catchWorklistException(() ->
            updateField(first, "relations", List.of(relation(UUID.randomUUID(), second))));

        assertThat(refusal.reason())
            .as("the one thing the platform reads out of a type is whether it blocks, and "
                + "an undeclared type is an edge that question cannot be asked of")
            .isEqualTo(WorklistException.Reason.VALUE_UNDECLARED);
    }

    /**
     * A relation to an item that does not exist cannot be written at all.
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
    void a_relation_to_an_item_that_does_not_exist_cannot_be_written() {
        UUID id = createdId("dangling probe");
        UUID type = relationType("blocks", true);

        assertThat(catchThrowable(() ->
            updateField(id, "relations", List.of(relation(type, UUID.randomUUID())))))
            .as("the edge has a foreign key on both ends, so a dangling reference is not "
                + "something to find later — it is something that cannot be stored")
            .isNotNull();
    }

    // ==================================================================
    // The reference list.
    // ==================================================================

    /**
     * The references are an ordered list, rewritten as a whole, and a list
     * that gets shorter actually gets shorter.
     *
     * <p>The second half is the one worth asserting. Nothing in this schema
     * holds a DELETE privilege, so a shrinking list is the case an
     * implementation gets wrong by leaving the tail behind — and a reference
     * list that could only ever grow would be a worse version of the single
     * free-text field it replaces.
     */
    @Test
    void the_reference_list_keeps_its_order_and_can_get_shorter() {
        UUID id = createdId("reference probe");

        Map<String, Object> three = updateField(id, "references", List.of(
            Map.of("label", "first", "target", "docs/a.md"),
            Map.of("label", "second", "target", "docs/b.md"),
            Map.of("target", "https://example.invalid/c")));

        assertThat(targetsOfReferences(three))
            .as("the ordinal is the reader's order and it is what came in")
            .containsExactly("docs/a.md", "docs/b.md", "https://example.invalid/c");

        Map<String, Object> one = updateField(id, "references", List.of(
            Map.of("label", "second", "target", "docs/b.md")));
        assertThat(targetsOfReferences(one))
            .as("a shorter list is shorter: the entries above it are withdrawn, which is "
                + "what withdrawal-instead-of-deletion has to mean for an ordered list")
            .containsExactly("docs/b.md");

        Map<String, Object> none = updateField(id, "references", List.of());
        assertThat(targetsOfReferences(none))
            .as("and it can be emptied altogether")
            .isEmpty();
    }

    /**
     * The cycle the identity change exists for: five entries, down to three,
     * back to five — through the VERB.
     *
     * <p>This is the case a positional key cannot survive. Withdrawing two
     * entries leaves tombstones on the ordinals 3 and 4; growing the list back
     * needs exactly those ordinals again. A write path walking by ordinal
     * finds the tombstones sitting there and either collides with them or
     * writes over them — and an overwritten tombstone is not preservation, it
     * is a free slot that READS like preservation, which is worse than a
     * delete because it is invisible.
     *
     * <p>So the assertion is not that the write succeeded. It is that
     * <strong>seven rows stand afterwards</strong>: the five living entries
     * with dense ordinals, and the two withdrawn ones still carrying the
     * targets they had when they were withdrawn. Had the write overwritten
     * them, the count would be five and the old targets would be gone — which
     * is the state this whole change was made against.
     *
     * <p>{@code SchemaConstraintIT} establishes that the schema can HOLD this
     * state, planting it with raw SQL. This case establishes that the verb
     * PRODUCES it. Neither stands for the other.
     */
    @Test
    void a_reference_list_that_shrank_and_grew_back_keeps_its_tombstones()
            throws SQLException {
        UUID id = createdId("reference cycle probe");

        updateField(id, "references", List.of(
            Map.of("target", "docs/0.md"), Map.of("target", "docs/1.md"),
            Map.of("target", "docs/2.md"), Map.of("target", "docs/3.md"),
            Map.of("target", "docs/4.md")));

        Map<String, Object> shrunk = updateField(id, "references", List.of(
            Map.of("target", "docs/0.md"), Map.of("target", "docs/1.md"),
            Map.of("target", "docs/2.md")));
        assertThat(targetsOfReferences(shrunk))
            .as("three entries are left in the reader's order")
            .containsExactly("docs/0.md", "docs/1.md", "docs/2.md");

        Map<String, Object> grown = updateField(id, "references", List.of(
            Map.of("target", "docs/0.md"), Map.of("target", "docs/1.md"),
            Map.of("target", "docs/2.md"), Map.of("target", "docs/new-3.md"),
            Map.of("target", "docs/new-4.md")));
        assertThat(targetsOfReferences(grown))
            .as("and back to five, with the two new entries at the end")
            .containsExactly("docs/0.md", "docs/1.md", "docs/2.md",
                "docs/new-3.md", "docs/new-4.md");

        assertThat(storedReferences(id))
            .as("SEVEN rows stand, read around the ORM: the five living entries at "
                + "ordinals 0 to 4, and the two tombstones still on the ordinals they "
                + "were withdrawn at, still carrying docs/3.md and docs/4.md. A write "
                + "path walking by ordinal would have found the tombstones at 3 and 4 "
                + "and written over them — five rows instead of seven, and the "
                + "withdrawn targets gone with no error anywhere")
            .containsExactly(
                "0 asserted docs/0.md",
                "1 asserted docs/1.md",
                "2 asserted docs/2.md",
                "3 asserted docs/new-3.md",
                "3 withdrawn docs/3.md",
                "4 asserted docs/new-4.md",
                "4 withdrawn docs/4.md");
    }

    /**
     * A reorder moves the ordinals of the living entries and creates no rows.
     *
     * <p>An entry is addressed by its identity now, so re-ordering is an
     * update of the position rather than a rewrite of the list. Without that,
     * every reorder would leave the old list behind as tombstones and the
     * table would grow with each one.
     */
    @Test
    void a_reorder_moves_the_living_entries_and_writes_no_new_rows() throws SQLException {
        UUID id = createdId("reference reorder probe");

        updateField(id, "references", List.of(
            Map.of("target", "docs/a.md"), Map.of("target", "docs/b.md")));

        Map<String, Object> reordered = updateField(id, "references", List.of(
            Map.of("target", "docs/b.md"), Map.of("target", "docs/a.md")));

        assertThat(targetsOfReferences(reordered))
            .as("the reader's order is what came in")
            .containsExactly("docs/b.md", "docs/a.md");
        assertThat(storedReferences(id))
            .as("and it is still two rows: a reorder rewrites positions, not the list. "
                + "Rewriting would have withdrawn both and inserted two more")
            .containsExactly("0 asserted docs/b.md", "1 asserted docs/a.md");
    }

    /** An entry pointing at nothing is not an entry. */
    @Test
    void a_reference_entry_carries_a_target() {
        UUID id = createdId("empty reference probe");

        WorklistException refusal = catchWorklistException(() ->
            updateField(id, "references", List.of(Map.of("label", "a label and no target"))));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly("references");
    }

    // ==================================================================
    // The remaining refusals, each named.
    // ==================================================================

    /**
     * The intake gate refuses, and names the decision it is waiting for.
     *
     * <p>This probe used to assert that an address is allocated once, by
     * accepting twice. Under the view model the address is allocated with the
     * object, so there is no second allocation to refuse — and what {@code
     * accept} would allocate instead is an open question about this store. The
     * guarantee the probe made is not dropped: it is asserted below, on the
     * address the item already has, which the refusal must leave exactly where
     * it was.
     */
    @Test
    void the_intake_gate_refuses_and_leaves_the_address_untouched() {
        UUID id = createdId("intake gate probe");
        long allocated = numberOf(id);

        WorklistException refusal = catchWorklistException(() -> items.accept(SCOPE, id,
            (String) items.read(SCOPE, id).get("conflict_token")));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.IDENTIFIER_UNDECIDED);
        assertThat(refusal.offenders()).containsExactly(String.valueOf(id));

        assertThat(numberOf(id))
            .as("and the address is untouched — a gate that quietly re-allocated would "
                + "make every reference to the old address resolve to something else")
            .isEqualTo(allocated);
    }

    /** An item of another scope, or of no scope, is not this scope's item. */
    @Test
    void an_item_that_is_not_in_this_scope_is_unknown() {
        UUID id = createdId("scope probe");
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
        arguments.put("status", String.valueOf(openStatus()));
        arguments.put("number", 7L);

        WorklistException refusal = catchWorklistException(() -> items.create(SCOPE, arguments));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.FIELD_NOT_SETTABLE);
        assertThat(refusal.offenders())
            .as("an address is allocated by admission and never supplied")
            .containsExactly("number");
    }

    /** And it carries a title and a status, on every path. */
    @Test
    void an_item_without_a_title_or_a_status_is_refused() {
        assertThat(catchWorklistException(() ->
            items.create(SCOPE, Map.of("status", String.valueOf(openStatus())))).reason())
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);

        WorklistException statusless = catchWorklistException(() ->
            items.create(SCOPE, Map.of("title", "no status given")));
        assertThat(statusless.reason())
            .as("a status is a declared value now, so there is no default to fall back "
                + "on — inventing one here would be this service deciding what a scope's "
                + "list means")
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(statusless.offenders()).containsExactly("status");

        UUID id = createdId("title clearing probe");
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
    void declaring_a_view_twice_returns_the_one_that_exists() {
        UUID freshScope = UUID.randomUUID();
        UUID first = selectors.declare(freshScope, Selector.MILESTONE).id;

        assertThat(selectors.declare(freshScope, Selector.MILESTONE).id)
            .as("declaration states that the space should exist, and a retry after a "
                + "timeout should not have to tell 'created' from 'already there'")
            .isEqualTo(first);
        assertThat(selectors.markOf(freshScope, Selector.MILESTONE))
            .as("and the second declaration did not reset the address space")
            .isZero();
    }

    // ==================================================================
    // The vocabulary's own lifecycle.
    // ==================================================================

    /**
     * There is no eighth attribute type: the types are structure, the values
     * are data.
     *
     * <p>This is what the four fixed axes became. The axis set was structure
     * because each axis was a column, so a fifth was a migration; the TYPE set
     * is structure because the platform has to know what it can do with a
     * value. The difference is that a fifth ATTRIBUTE is now a row, which was
     * the whole point of the change.
     */
    @Test
    void there_is_no_attribute_type_beyond_the_seven() {
        WorklistException refusal = catchWorklistException(() ->
            vocabulary.declareAttribute(SCOPE, "urgency", "Urgency", "colour", 1, false));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.getMessage())
            .contains("text", "number", "date", "boolean", "choice", "multi_choice",
                "item_reference");
    }

    /** An attribute key is a token: lower case, no whitespace, not empty. */
    @Test
    void a_malformed_attribute_key_is_refused() {
        assertThat(catchWorklistException(() ->
            vocabulary.declareAttribute(SCOPE, "two words", "Two", "text", 1, false)).reason())
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(catchWorklistException(() ->
            vocabulary.declareAttribute(SCOPE, "  ", "Blank", "text", 1, false)).reason())
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(catchWorklistException(() ->
            vocabulary.declareAttribute(SCOPE, "1st", "Leading digit", "text", 1, false))
            .reason())
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);
    }

    /**
     * A withdrawn attribute stays resolvable on the items already carrying it.
     *
     * <p>That is why withdrawal is a status here rather than a deletion: an
     * item characterised two years ago has to keep saying what it was
     * characterised as, or its own history stops being legible.
     */
    @Test
    void a_withdrawn_attribute_still_reads_back_on_the_items_that_carry_it() {
        String key = attribute("text");
        UUID id = createdId("withdrawn attribute probe");
        updateField(id, "attributes", Map.of(key, "CORE"));

        vocabulary.withdrawAttribute(SCOPE, key);

        assertThat(attributesOf(items.read(SCOPE, id)))
            .as("the item keeps reading back the value it was characterised with")
            .containsEntry(key, "CORE");
        assertThat(vocabulary.attributes(SCOPE).stream().map(d -> d.key).toList())
            .as("and the declaration is still there, with its status changed rather than "
                + "its row removed — the key stays occupied so it cannot come to mean "
                + "something else")
            .contains(key);
    }

    /** Declaring an attribute twice is idempotent, like declaring a selector. */
    @Test
    void declaring_an_attribute_twice_returns_the_one_that_exists() {
        String key = "k" + shortId().toLowerCase();
        UUID first = vocabulary.declareAttribute(SCOPE, key, "First", "text", 1, false).id;
        assertThat(vocabulary.declareAttribute(SCOPE, key, "Second", "number", 9, true).id)
            .isEqualTo(first);
    }

    /** An option belongs to an enumerated attribute and to no other. */
    @Test
    void an_option_belongs_to_an_enumerated_attribute() {
        String free = attribute("text");

        WorklistException refusal = catchWorklistException(() ->
            vocabulary.declareOption(SCOPE, free, "S", 1));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.getMessage())
            .as("a free-text attribute draws its values from no declared set, so an "
                + "option under it would be a declaration nothing reads")
            .contains("choice");
    }

    // ==================================================================
    // Reading a scope.
    // ==================================================================

    /** A query of the scope returns the created items in the canonical shape. */
    @Test
    void a_query_returns_the_items_of_the_scope_in_the_canonical_shape() {
        UUID id = createdId("query probe " + shortId());

        assertThat(items.query(SCOPE))
            .as("the query is the same projection the single read gives, so a caller "
                + "handles one shape rather than two")
            .anySatisfy(item -> {
                assertThat(item.get("id")).isEqualTo(id);
                assertThat(item).containsKeys("title", "description", "status", "attributes",
                    "references", "relations", "milestone", "conflict_token", "created_at",
                    "changed_at");
            });
    }

    // ==================================================================
    // Helpers.
    // ==================================================================

    /**
     * The scope's actionable status, declared on first use.
     *
     * <p>Every item needs one, because a status is a declared value and there
     * is no default. That is not a cost this fixture pays around: it is the
     * order the design has, and a service that invented a status here would be
     * deciding what a scope's list means.
     */
    private UUID openStatus() {
        if (openStatusId == null) {
            openStatusId =
                vocabulary.declareStatus(SCOPE, "open", 1, true, false, false, false).id;
        }
        return openStatusId;
    }

    /** The scope's terminal status, for the withdrawal probes. */
    private UUID closedStatus() {
        if (closedStatusId == null) {
            closedStatusId =
                vocabulary.declareStatus(SCOPE, "done", 9, false, false, true, true).id;
        }
        return closedStatusId;
    }

    /** A freshly declared attribute of that type, returning its key. */
    private String attribute(String type) {
        String key = "a" + shortId().toLowerCase();
        vocabulary.declareAttribute(SCOPE, key, "Attribute " + key, type, 1, false);
        return key;
    }

    /** A freshly declared relation type, returning its identity. */
    private UUID relationType(String name, boolean blocks) {
        return vocabulary.declareRelationType(SCOPE, name, blocks, 1).id;
    }

    /** One relation entry, as a caller writes it. */
    private static Map<String, Object> relation(UUID type, UUID target) {
        return Map.of("type", String.valueOf(type), "item", String.valueOf(target));
    }

    /** The declared attributes of a projection, typed. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributesOf(Map<String, Object> projection) {
        return (Map<String, Object>) projection.get("attributes");
    }

    /** The relation entries of a projection, typed. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> relationsOf(Map<String, Object> projection) {
        return (List<Map<String, Object>>) projection.get("relations");
    }

    /** The other end of every asserted relation of a projection. */
    private static List<UUID> targetsOf(Map<String, Object> projection) {
        return relationsOf(projection).stream().map(e -> (UUID) e.get("item")).toList();
    }

    /** The reference entries of a projection, typed. */
    @SuppressWarnings("unchecked")
    private static List<String> targetsOfReferences(Map<String, Object> projection) {
        return ((List<Map<String, Object>>) projection.get("references")).stream()
            .map(e -> (String) e.get("target"))
            .toList();
    }

    /** A created item, with the scope's actionable status. */
    private Map<String, Object> created(String title) {
        itemView();
        return items.create(SCOPE, Map.of(
            "title", title,
            "status", String.valueOf(openStatus())));
    }

    /**
     * The scope's item view, declared before anything is created under it.
     *
     * <p>Not scaffolding, and not a shortcut around the declaration rule: it is
     * the same order the store has always required, moved to where the address
     * is now allocated. An item acquires its address at creation, so the view it
     * is addressed under has to exist by then — exactly as a status did before
     * an item could carry one.
     *
     * <p>Idempotent, because declaring is: a second declaration returns the row
     * that is already there rather than refusing, so calling this from every
     * fixture costs one query and states the precondition where a reader will
     * see it.
     */
    private void itemView() {
        selectors.declare(SCOPE, Selector.ITEM);
    }

    /** A created item, returning its id. */
    private UUID createdId(String title) {
        return (UUID) created(title).get("id");
    }

    /** One field updated, with the token read immediately before. */
    private Map<String, Object> updateField(UUID id, String field, Object value) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put(field, value);
        arguments.put("conflict_token", items.read(SCOPE, id).get("conflict_token"));
        return items.update(SCOPE, id, arguments);
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
     * The highest number among items NOT closed — the quantity a derived mark
     * would be computed from.
     *
     * <p>Over the whole view rather than one family: there is one number space
     * per scope now, so the alternative implementation this stands in for —
     * {@code max(number) + 1} — would compute exactly this.
     */
    private long highestLiveNumber() {
        List<Long> live = new ArrayList<>();
        for (Map<String, Object> item : items.query(SCOPE)) {
            if (!closedStatus().equals(item.get("status")) && item.get("number") != null) {
                live.add((Long) item.get("number"));
            }
        }
        return live.stream().mapToLong(Long::longValue).max().orElse(0L);
    }

    /**
     * Every reference row of an item as "ordinal status target", read around
     * the ORM and ordered so that a tombstone and the living entry sharing its
     * ordinal stand next to each other.
     *
     * <p>Around the ORM deliberately: the projection shows the living entries
     * only, which is right for a reader and useless for this assertion. What
     * has to be seen here is the row that the answer does NOT carry.
     */
    private List<String> storedReferences(UUID id) {
        List<String> out = new ArrayList<>();
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, boundTenant());
            try (var st = c.prepareStatement("""
                    SELECT ordinal, status, target FROM worklist.item_reference
                    WHERE item_id = ? ORDER BY ordinal, status
                    """)) {
                st.setObject(1, id);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getInt(1) + " " + rs.getString(2) + " " + rs.getString(3));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the reference rows", e);
        }
        return out;
    }

    /** The per-view counter, read around the ORM for the red states above. */
    private long perViewMark() {
        return markFromCatalog("SELECT n.high_water_mark FROM worklist.number_space n "
            + "JOIN worklist.selector s ON s.id = n.selector_id "
            + "WHERE n.scope_id = ? AND s.token = '" + Selector.ITEM + "'");
    }

    private long scopeWideMark() {
        return markFromCatalog("SELECT high_water_mark FROM worklist.number_space "
            + "WHERE scope_id = ? AND selector_id IS NULL");
    }

    private long markFromCatalog(String sql) {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, boundTenant());
            try (var st = c.prepareStatement(sql)) {
                st.setObject(1, SCOPE);
                try (ResultSet rs = st.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read a high-water mark", e);
        }
    }

    /**
     * The stamp a write WITHOUT the equality check would have left, applied
     * by hand so that the green assertions are shown capable of failing.
     */
    private void stampByHand(UUID id, String token) throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, boundTenant());
            try (var st = c.prepareStatement(
                    "UPDATE worklist.item SET changed_at = now(), conflict_token = ? "
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

    private String storedChangedAt(UUID id) throws SQLException {
        return storedColumn(id, "changed_at");
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
