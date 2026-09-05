package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.Selector;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The address grammar: what it accepts, what it refuses, and what it refuses to
 * fix.
 *
 * <p>Runs as a plain unit test. Nothing here reaches a store — that is the
 * point: this class is structurally unable to produce a not-found, because it
 * never looks anything up. A form error and a well-formed address of something
 * absent are two classes produced in two places, and they must never merge.
 */
class AddressParserTest {

    // =======================================================================
    // What is tolerated, and what is rejected
    // =======================================================================

    @Test
    void a_trailing_slash_is_tolerated_and_never_generated() {
        AddressParser.Parts parts = AddressParser.uri(
            "worklist://kumbuka/" + Selector.ITEM + "/562/");

        assertThat(parts.id())
            .as("a trailing slash changes nothing about which object is addressed, because "
                + "the occupied parts decide that")
            .isEqualTo("562");

        assertThat(AddressParser.render("kumbuka",
                new AddressParser.Target(Selector.ITEM, 562L, null)))
            .as("and the canonical form is generated rather than echoed, so a tolerated "
                + "slash does not survive into a Location header other clients treat as an "
                + "identity")
            .isEqualTo("worklist://kumbuka/" + Selector.ITEM + "/562");
    }

    @Test
    void upper_case_is_rejected_and_never_folded() {
        assertThat(malformed(() -> AddressParser.scope("Kumbuka")))
            .as("folding would make two distinct strings resolve to one scope, which is an "
                + "identity statement arrived at by leniency")
            .contains("not a DNS label");

        assertThat(malformed(() -> AddressParser.view("Item")))
            .contains("not a token");
    }

    @Test
    void a_leading_zero_is_a_second_string_for_one_object_and_is_refused() {
        assertThat(malformed(() -> AddressParser.target(Selector.ITEM, "07")))
            .contains("no leading zeroes");
    }

    @Test
    void zero_is_not_a_number_in_this_address_space() {
        assertThat(malformed(() -> AddressParser.target(Selector.ITEM, "0")))
            .as("every counter here hands out its first number as one, so an address that "
                + "can never resolve is better refused than looked up")
            .contains("Counting starts at one");
    }

    // =======================================================================
    // The three views
    // =======================================================================

    @Test
    void the_selector_is_the_view_and_a_family_is_not_one() {
        for (String view : Selector.VIEWS) {
            assertThat(AddressParser.view(view)).isEqualTo(view);
        }

        assertThat(malformed(() -> AddressParser.view("feat")))
            .as("well formed and not a view. An item's family is a scope's own declared "
                + "vocabulary and says something about the item, not about which kind of "
                + "thing stands at the other end of the address")
            .contains("the selector is the view");
    }

    // =======================================================================
    // The membership, and the pointer
    // =======================================================================

    @Test
    void a_membership_occupies_a_second_id_segment_under_its_iteration() {
        AddressParser.Target target =
            AddressParser.membership(Selector.ITERATION, "27", "562");

        assertThat(target.isMembership()).isTrue();
        assertThat(target.number()).isEqualTo(27L);
        assertThat(target.member()).isEqualTo(562L);
        assertThat(target.id()).isEqualTo("27/562");
    }

    @Test
    void nothing_but_an_iteration_carries_a_second_id_segment() {
        assertThat(malformed(() -> AddressParser.membership(Selector.ITEM, "1", "2")))
            .contains("not the iteration view");
    }

    @Test
    void the_uri_form_takes_a_membership_whole() {
        AddressParser.Parts parts = AddressParser.uri(
            "worklist://kumbuka/" + Selector.ITERATION + "/27/562");

        assertThat(parts.view()).isEqualTo(Selector.ITERATION);
        assertThat(parts.id())
            .as("truncation is recognised by which PARTS are occupied and never by counting "
                + "separators, so a four-segment address is complete and not overlong")
            .isEqualTo("27/562");
    }

    @Test
    void the_current_iteration_resolves_as_an_address_and_only_on_its_own_view() {
        AddressParser.Target current =
            AddressParser.target(Selector.ITERATION, AddressParser.CURRENT);
        assertThat(current.isCurrent()).isTrue();
        assertThat(current.number()).isNull();

        assertThat(malformed(() -> AddressParser.target(Selector.ITEM, AddressParser.CURRENT)))
            .as("a scope has no current item and no current milestone: the goal axis "
                + "carries an ACTIVE milestone, which is a status on a row")
            .contains("only the iteration view has one");
    }

    @Test
    void a_membership_is_not_addressed_under_the_moving_pointer() {
        assertThat(malformed(() ->
            AddressParser.membership(Selector.ITERATION, AddressParser.CURRENT, "562")))
            .as("the pointer moves, so the same string would name a different membership "
                + "after every advance — and this is the one address whose read and write "
                + "forms are identical")
            .contains("not addressed under");
    }

    // =======================================================================
    // The scheme, and the shape of a complete address
    // =======================================================================

    @Test
    void an_address_of_another_scheme_is_not_an_address_of_this_one() {
        assertThat(malformed(() -> AddressParser.uri("dispatch://kumbuka/sprint/171.1")))
            .contains("does not name the worklist scheme");
    }

    @Test
    void a_truncated_address_is_refused_where_a_complete_one_is_required() {
        assertThat(malformed(() -> AddressParser.uri("worklist://kumbuka/" + Selector.ITEM)))
            .as("an address without an id part is reserved, which is what stops a "
                + "three-part string meaning 'the objects of this scope'")
            .contains("occupies 2 part(s)");
    }

    @Test
    void an_empty_part_is_a_broken_address_and_not_a_shorter_one() {
        assertThat(malformed(() -> AddressParser.target(Selector.ITEM, "")))
            .contains("is empty");
        assertThat(malformed(() -> AddressParser.scope(null)))
            .contains("is empty");
    }

    /** The message of the form refusal a call raised, asserting that it raised one. */
    private static String malformed(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown)
            .as("the call must be refused, and refused as a FORM error: a grammar "
                + "violation is decidable without knowing any scope, and answering it "
                + "leaks nothing")
            .isInstanceOf(SurfaceException.class);
        assertThat(((SurfaceException) thrown).reason())
            .isEqualTo(SurfaceException.Reason.ADDRESS_MALFORMED);
        return thrown.getMessage();
    }
}
