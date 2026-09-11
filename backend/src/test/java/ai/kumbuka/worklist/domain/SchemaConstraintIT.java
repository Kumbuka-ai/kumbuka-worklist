package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.Db;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the SCHEMA refuses, observed as a refusal rather than described.
 *
 * <h2>Why these are asserted at the database and not through a verb</h2>
 *
 * The study separates what the schema enforces from what the domain enforces,
 * and it separates them because the second list needs a mechanism somebody has
 * to write while the first list has one already. This class is the evidence
 * for the first list: every case below plants a violation directly, under the
 * runtime role and a bound tenant, and requires the database to refuse it.
 *
 * <p>Going around the domain is the point. A refusal asserted through a verb
 * proves the verb checks; it says nothing about what happens when a later verb
 * forgets to. These constraints are what still holds when nothing above them
 * does — and the whole reason for putting a rule in the schema is that it
 * cannot then be walked past.
 *
 * <h2>Every red state has a green one beside it</h2>
 *
 * A constraint that refused everything would satisfy each assertion below
 * without expressing anything. So the cases that have a legitimate neighbour
 * assert it too: one active membership plus any number of inactive ones, the
 * same number under two different selectors, a real milestone carrying the
 * vision a marker may not. The pair is what says the constraint is the shape
 * it is meant to be rather than merely present.
 *
 * <h2>What is NOT here, and why that is not an omission</h2>
 *
 * A cycle over blocking relations, the rule that a scope declares at least one
 * actionable and one closed status, the readiness derivation and the
 * transition rules are all in the study's SECOND list. No constraint expresses
 * any of them; each needs a domain method to live in and a red probe of its
 * own, and neither exists yet. They are absent here because they are absent
 * everywhere, and a test that pretended otherwise would be the worst outcome
 * of the two.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class SchemaConstraintIT {

    private static final UUID SCOPE = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);

    private UUID tenant;

    @BeforeEach
    void freshTenant() {
        // Fresh per method: the suite shares one database, and several of the
        // constraints below are scoped to a tenant. A fixed one would make
        // each case depend on which ran before it, and the failure would look
        // like a broken constraint rather than a shared fixture.
        tenant = UUID.randomUUID();
    }

    // ==================================================================
    // The planning layer's two partial unique indexes.
    // ==================================================================

    /**
     * Exactly one ACTIVE membership per iteration.
     *
     * <p>This is what makes the rule a property of the store rather than one
     * two verbs have to agree about — and they did not agree in the
     * predecessor, where one verb refused a fresh activation on the ground
     * that only the draw may activate while a second set the same value
     * without comment. Two verbs disagreeing about who may write a field is a
     * defect regardless of which of them is right.
     */
    @Test
    void an_iteration_holds_one_active_membership_and_any_number_of_inactive_ones()
            throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            UUID iteration = insertIteration(c);
            UUID first = insertItem(c, "membership 1");
            UUID second = insertItem(c, "membership 2");
            UUID third = insertItem(c, "membership 3");
            // Committed BEFORE the violation is planted. The rollback that
            // clears the refused row would otherwise take the iteration and
            // the items with it, and the green half below would fail on a
            // missing precondition rather than pass on the constraint.
            c.commit();

            Db.bindTenant(c, tenant);
            insertMembership(c, iteration, first, 0, "active");

            assertThatThrownBy(() -> insertMembership(c, iteration, second, 1, "active"))
                .as("RED STATE, observed: a second active membership in one iteration "
                    + "must be refused by the partial unique index. Without it, which "
                    + "item is being worked on would be whatever the last verb to write "
                    + "happened to think")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_iteration_membership_active");
            c.rollback();

            // And the shape the index is actually meant to have: the active
            // one, plus as many non-active ones as the iteration holds.
            Db.bindTenant(c, tenant);
            insertMembership(c, iteration, first, 0, "active");
            insertMembership(c, iteration, second, 1, "todo");
            insertMembership(c, iteration, third, 2, "done");
            c.commit();

            assertThat(count(c, "iteration_membership"))
                .as("one active membership and any number of others is the normal state "
                    + "of an iteration, and the index must admit it — a unique index over "
                    + "the whole column would have refused the second row")
                .isEqualTo(3);
            c.commit();
        }
    }

    /**
     * At most one ACTIVE milestone per scope.
     *
     * <p>Setting one active demotes the current one in the SAME statement, so
     * the invariant never has to hold across two writes — a refusal there
     * would make the operator perform two writes to express one intention.
     * What this index refuses is the state, not the transition.
     */
    @Test
    void a_scope_holds_one_active_milestone_and_any_number_of_others() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            insertMilestone(c, 1, "milestone", "active", null);

            assertThatThrownBy(() -> insertMilestone(c, 2, "milestone", "active", null))
                .as("RED STATE, observed: a second active milestone in one scope must be "
                    + "refused. Which goal the scope is on would otherwise be a question "
                    + "with two answers")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_milestone_active");
            c.rollback();

            Db.bindTenant(c, tenant);
            insertMilestone(c, 1, "milestone", "active", null);
            insertMilestone(c, 2, "milestone", "planned", null);
            insertMilestone(c, 3, "milestone", "closed", null);
            c.commit();

            assertThat(count(c, "milestone"))
                .as("planned and closed milestones stand beside the active one — a closed "
                    + "milestone stays in the table so the allocator counts past it")
                .isEqualTo(3);
            c.commit();
        }
    }

    // ==================================================================
    // The identity triple.
    // ==================================================================

    /**
     * The address is unique on the TRIPLE scope, selector and number — never
     * on the pair without the selector.
     *
     * <p>Both halves are the probe. A store constraining the pair would pass
     * the first assertion and fail the second, and it could never admit
     * per-selector numbering afterwards: once two selectors have shared a
     * number, the constraint can never be switched on again.
     */
    @Test
    void the_address_is_unique_on_the_triple_and_two_selectors_may_share_a_number()
            throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            UUID feat = insertSelector(c);
            UUID chore = insertSelector(c);
            UUID first = insertItem(c, "address 1");
            UUID second = insertItem(c, "address 2");
            UUID third = insertItem(c, "address 3");

            address(c, first, feat, 51);
            c.commit();

            assertThatThrownBy(() -> address(c, second, feat, 51))
                .as("RED STATE, observed: the same scope, selector and number twice must "
                    + "be refused. Two items answering to one address makes every "
                    + "reference ever written to it ambiguous, with no error anywhere")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_item_address");
            c.rollback();

            // The other half, and it is the one a pair-wise constraint would
            // have got wrong.
            Db.bindTenant(c, tenant);
            address(c, third, chore, 51);
            c.commit();

            assertThat(numbersAt(c, 51))
                .as("the same number under two different selectors is admissible, because "
                    + "the identity is the triple. That is what makes per-selector "
                    + "numbering possible at all")
                .isEqualTo(2);
            c.commit();
        }
    }

    // ==================================================================
    // The marker milestone.
    // ==================================================================

    /**
     * A marker is a position on the axis and never a goal, so it carries
     * neither a vision nor a mission.
     *
     * <p>This one check constraint is the whole cost of making the three
     * markers rows. The predecessor keeps them OUT of its milestone table and
     * therefore needs an exemption in its reference check — so that the third
     * marker, on the product path and covered by no vision, does not carry a
     * violation nobody can ever fix.
     */
    @Test
    void a_marker_milestone_carries_no_goal_and_a_real_one_may() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);

            assertThatThrownBy(() ->
                insertMilestone(c, 1, "not_assessed", "planned", "a vision"))
                .as("RED STATE, observed: a marker carrying a vision must be refused. A "
                    + "marker is a position on the axis that never carries a goal, and a "
                    + "marker with one would be a milestone nobody declared")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_milestone_marker_carries_no_goal");
            c.rollback();

            Db.bindTenant(c, tenant);
            insertMilestone(c, 1, "milestone", "planned", "the north star in a sentence");
            insertMilestone(c, 2, "off_path", "planned", null);
            c.commit();

            assertThat(count(c, "milestone"))
                .as("a real milestone may carry a vision and a marker may stand without "
                    + "one — the constraint refuses the combination and not either half")
                .isEqualTo(2);
            c.commit();
        }
    }

    // ==================================================================
    // The relation edge.
    // ==================================================================

    /**
     * A self-relation is the one cycle a single row can express, and the only
     * one a constraint can see.
     *
     * <p>The cycle over several rows is NOT here and its absence is the point
     * of saying so: no constraint expresses "this graph is acyclic", it is
     * enforced in the domain at write time, and it needs a red probe of its
     * own. A rule with no mechanism is exactly the class this project keeps
     * finding.
     */
    @Test
    void an_item_may_not_relate_to_itself_and_may_relate_to_another() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            UUID type = insertRelationType(c);
            UUID first = insertItem(c, "relation source");
            UUID second = insertItem(c, "relation target");
            c.commit();

            Db.bindTenant(c, tenant);
            assertThatThrownBy(() -> insertRelation(c, first, first, type))
                .as("RED STATE, observed: an item relating to itself must be refused. "
                    + "Under a blocking type it would be permanently unready, and the "
                    + "caller could not see why")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_item_relation_not_self");
            c.rollback();

            Db.bindTenant(c, tenant);
            insertRelation(c, first, second, type);
            c.commit();

            assertThat(count(c, "item_relation"))
                .as("and an edge to another item is exactly what the table is for")
                .isEqualTo(1);
            c.commit();
        }
    }

    // ==================================================================
    // The reference list, whose ordinal is not a key.
    // ==================================================================

    /**
     * One LIVING entry per ordinal within an item — and a withdrawn one beside
     * it.
     *
     * <p>Both halves are the probe, and the second is the one a plain unique
     * index would have got wrong. A positional key and a withdrawal status
     * exclude each other: withdraw two entries from a list of five and the
     * ordinals 3 and 4 carry tombstones, and a list growing back to five has
     * to reissue exactly those. Under a full index the write collides; under
     * no index at all two living entries share a position and the reader's
     * order stops being one.
     */
    @Test
    void one_living_reference_per_ordinal_and_a_withdrawn_one_beside_it()
            throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            UUID item = insertItem(c, "reference ordinal probe");
            insertReference(c, item, 0, "docs/a.md", "asserted");
            c.commit();

            Db.bindTenant(c, tenant);
            assertThatThrownBy(() -> insertReference(c, item, 0, "docs/b.md", "asserted"))
                .as("RED STATE, observed: two LIVING entries of one item on one ordinal "
                    + "must be refused. The ordinal is the reader's order, and an order "
                    + "with two things in one place is not an order")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_item_reference_ordinal");
            c.rollback();

            Db.bindTenant(c, tenant);
            insertReference(c, item, 0, "docs/tombstone.md", "withdrawn");
            c.commit();

            assertThat(count(c, "item_reference"))
                .as("a withdrawn entry may share an ordinal with a living one — that is "
                    + "exactly the state a list that shrank and grew back is in, and a "
                    + "plain unique index would have refused it")
                .isEqualTo(2);
            c.commit();
        }
    }

    /**
     * The whole cycle, which is what the decision is actually about: five
     * entries, two withdrawn, back to five.
     *
     * <p>The constraint alone does not establish this. What has to hold at the
     * end is that all SEVEN rows stand, that the two withdrawn ones carry the
     * content they had when they were withdrawn, and that the five living ones
     * carry dense ordinals from 0 to 4 — with the withdrawn pair still holding
     * the ordinals the new entries now occupy.
     *
     * <p>Under a positional key this state is unreachable. The write that
     * grows the list back either collides with the tombstone or overwrites it,
     * and an overwritten tombstone is a free slot that READS like
     * preservation.
     *
     * <p>This case establishes that the SCHEMA can hold the state, planting it
     * with raw SQL. That the WRITE PATH actually produces it — that the verb
     * walks the living entries rather than the ordinals — is the other half,
     * and it is asserted through the verb in
     * {@code ItemDomainIT.a_reference_list_that_shrank_and_grew_back_keeps_its_tombstones}.
     * Neither half stands for the other: a schema that can hold the state
     * says nothing about a verb that never reaches it.
     */
    @Test
    void a_reference_list_shrinks_and_grows_back_without_disturbing_its_tombstones()
            throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            UUID item = insertItem(c, "reference cycle probe");
            for (int i = 0; i < 5; i++) {
                insertReference(c, item, i, "docs/" + i + ".md", "asserted");
            }
            c.commit();

            // Two withdrawn, keeping their ordinals and their content.
            Db.bindTenant(c, tenant);
            withdrawReferencesFrom(c, item, 3);
            c.commit();

            Db.bindTenant(c, tenant);
            assertThat(livingOrdinals(c, item))
                .as("three living entries are left, and their ordinals are still dense")
                .containsExactly(0, 1, 2);

            // And back to five. The two new entries take ordinals 3 and 4 —
            // the very ordinals the tombstones are sitting on.
            insertReference(c, item, 3, "docs/new-3.md", "asserted");
            insertReference(c, item, 4, "docs/new-4.md", "asserted");
            c.commit();

            Db.bindTenant(c, tenant);
            assertThat(count(c, "item_reference"))
                .as("all seven rows stand: five living and two tombstones. Nothing was "
                    + "deleted, because nothing in this schema can delete")
                .isEqualTo(7);

            assertThat(livingOrdinals(c, item))
                .as("and the living entries carry dense ordinals from 0 to 4, on the same "
                    + "positions the tombstones occupy")
                .containsExactly(0, 1, 2, 3, 4);

            assertThat(withdrawnTargets(c, item))
                .as("the withdrawn entries stand unchanged, with the content they had "
                    + "when they were withdrawn. Had the growing write walked by ordinal "
                    + "it would have found them at 3 and 4 and written over them, and "
                    + "this list would read docs/new-3.md and docs/new-4.md twice")
                .containsExactly("docs/3.md", "docs/4.md");
            c.commit();
        }
    }

    // ==================================================================
    // The claim.
    // ==================================================================

    /**
     * A lease has a positive duration.
     *
     * <p>One of three predecessor defects that fall out of the claim being a
     * row under the transaction: a claim with a zero duration reported success
     * for a lease that was inert the moment it was granted. That is a
     * statement about one row, so it is a check constraint.
     */
    @Test
    void a_claim_has_a_positive_duration() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            UUID item = insertItem(c, "claim target");
            c.commit();

            Db.bindTenant(c, tenant);
            assertThatThrownBy(() -> insertClaim(c, item, "0 seconds"))
                .as("RED STATE, observed: a lease that expires the moment it is granted "
                    + "must be refused. Reporting success for it is worse than refusing, "
                    + "because the caller believes it holds something")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_claim_duration");
            c.rollback();

            Db.bindTenant(c, tenant);
            assertThatThrownBy(() -> insertClaim(c, item, "-1 hour"))
                .as("RED STATE, observed: and a negative one, which is the same fact "
                    + "spelled more obviously")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_claim_duration");
            c.rollback();

            Db.bindTenant(c, tenant);
            insertClaim(c, item, "1 hour");
            c.commit();

            assertThat(count(c, "claim"))
                .as("and a lease with a duration is granted")
                .isEqualTo(1);
            c.commit();
        }
    }

    // ==================================================================
    // The per-selector counter.
    // ==================================================================

    /**
     * Exactly one counter per selector in a scope, and none without a
     * selector.
     *
     * <p>The uniqueness rule is expressed directly by
     * {@code uq_number_space_selector} over {@code (tenant_id, scope_id,
     * selector_id)}; {@code selector_id NOT NULL} makes that ordinary rather
     * than partial. A row without a selector is refused at the column, and a
     * second row for the same selector is refused at the index.
     */
    @Test
    void a_scope_holds_exactly_one_counter_per_selector() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            UUID first = insertSelector(c);
            UUID second = insertSelector(c);
            insertNumberSpace(c, first);
            insertNumberSpace(c, second);
            c.commit();

            assertThatThrownBy(() -> insertNumberSpace(c, first))
                .as("RED STATE, observed: a second counter for the same selector must "
                    + "be refused. Two of them would mean the selector's position has "
                    + "two answers, and every allocation would pick whichever the query "
                    + "happened to find")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_number_space_selector");
            c.rollback();

            Db.bindTenant(c, tenant);
            assertThatThrownBy(() -> insertNumberSpace(c, null))
                .as("RED STATE, observed: a counter without a selector must be refused. "
                    + "There is no scope-wide row beside the per-selector ones; every "
                    + "counter belongs to a view")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("selector_id");
            c.rollback();

            Db.bindTenant(c, tenant);
            assertThat(count(c, "number_space"))
                .as("one per selector, and no other")
                .isEqualTo(2);
            c.commit();
        }
    }

    // ==================================================================
    // V7: the address is mandatory, and text fields carry an upper bound.
    // ==================================================================

    /**
     * An item without a selector is refused at the column.
     *
     * <p>Both halves of the address were nullable through V6 as the intake
     * state of a raw call-in — a state no verb reaches any more since the
     * selector became the view and {@code accept} refuses. V7 restates that
     * observation as a column NOT NULL: an item exists at an address, and a
     * row without one cannot exist at all.
     *
     * <p>The green half is what {@link #insertItem} plants at every other test
     * in this class: an item with a selector, a number and a status, admitted
     * without a word about the constraint below.
     */
    @Test
    void an_item_without_a_selector_is_refused_and_a_full_address_stands()
            throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            UUID status = anyStatus(c);
            c.commit();

            Db.bindTenant(c, tenant);
            assertThatThrownBy(() -> insertItemWithAddress(c, "no selector",
                    status, null, 1L))
                .as("RED STATE, observed: an item without a selector must be refused. "
                    + "The address names the object, and half an address names nothing "
                    + "the caller can hold on to")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("selector_id");
            c.rollback();

            Db.bindTenant(c, tenant);
            UUID selector = insertSelector(c);
            insertItemWithAddress(c, "full address", status, selector, 1L);
            c.commit();

            assertThat(count(c, "item"))
                .as("and an item with a selector and a number is admitted, which is the "
                    + "shape every verb produces since the selector became the view")
                .isEqualTo(1);
            c.commit();
        }
    }

    /**
     * An item without a number is refused at the column.
     *
     * <p>Named apart from the selector case rather than folded in with it,
     * because the column checks are two independent NOT NULLs and each has to
     * be observed refusing on its own. A single test that supplied neither
     * would not tell one column from the other.
     */
    @Test
    void an_item_without_a_number_is_refused() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            UUID status = anyStatus(c);
            UUID selector = insertSelector(c);
            c.commit();

            Db.bindTenant(c, tenant);
            assertThatThrownBy(() -> insertItemWithAddress(c, "no number",
                    status, selector, null))
                .as("RED STATE, observed: an item without a number must be refused. "
                    + "The counter is the mechanism that keeps two objects from sharing "
                    + "an address, and a row without one is the state that would let "
                    + "them")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("number");
            c.rollback();
        }
    }

    /**
     * An item title of 201 characters is refused; 200 go through.
     *
     * <p>The boundary case is the case. A cap only ever observed rejecting
     * "obviously too long" is a cap ungoverned at its own edge, and the
     * predecessor's cap-at-150 was measured against inputs of 400+ but never
     * against 151 — which is where drift lives.
     */
    @Test
    void an_item_title_of_201_is_refused_and_200_go_through() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            UUID status = anyStatus(c);
            UUID atCap = insertSelector(c);
            UUID overCap = insertSelector(c);
            c.commit();

            Db.bindTenant(c, tenant);
            assertThatThrownBy(() -> insertItemWithTitle(c, repeat('a', 201),
                    status, overCap))
                .as("RED STATE, observed: a title one character over the cap must be "
                    + "refused. A cap that admits 201 is a cap the reader has to guess "
                    + "at, and the predecessor's cap drifted from 150 to whatever the "
                    + "longest historical row happened to carry")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_item_title_length");
            c.rollback();

            Db.bindTenant(c, tenant);
            insertItemWithTitle(c, repeat('a', 200), status, atCap);
            c.commit();

            assertThat(count(c, "item"))
                .as("and exactly 200 characters go through — the equality case, which is "
                    + "the one only a boundary test catches")
                .isEqualTo(1);
            c.commit();
        }
    }

    /**
     * A milestone mission of 1501 characters is refused; 1500 go through.
     *
     * <p>The mission is the field with the largest cap and therefore the one
     * most likely to be misread as unbounded. The boundary case is asserted
     * at 1500 and 1501 for the same reason as the item title.
     */
    @Test
    void a_milestone_mission_of_1501_is_refused_and_1500_go_through() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);

            assertThatThrownBy(() -> insertMilestoneWithMission(c, 1, repeat('m', 1501)))
                .as("RED STATE, observed: a mission one character over the cap must be "
                    + "refused. 1500 is the largest ceiling this migration writes, and a "
                    + "cap that only rejected 2000 would leave 1501 to 1999 unchecked")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_milestone_mission_length");
            c.rollback();

            Db.bindTenant(c, tenant);
            insertMilestoneWithMission(c, 1, repeat('m', 1500));
            c.commit();

            assertThat(count(c, "milestone"))
                .as("and exactly 1500 characters go through")
                .isEqualTo(1);
            c.commit();
        }
    }

    /**
     * An over-cap row already in the store stays readable.
     *
     * <p>The whole point of a CHECK on the write path is that the read path is
     * untouched: a corpus already carrying an over-length value must not be
     * sealed against its own content. The predecessor learnt this the hard
     * way — a cap on the read path made an over-long row unrepairable through
     * the service.
     *
     * <p>To prove it here the constraint is dropped, an over-cap row is
     * planted, and the constraint is put back as {@code NOT VALID} — which is
     * what would obtain in production if a row like this had somehow been
     * written before the cap was tightened. The service role then reads the
     * row through the same channel a caller would.
     *
     * <p>The constraint is restored so the rest of this class's tests find
     * the schema they were written against; {@code NOT VALID} keeps the
     * planted row and re-checks every subsequent INSERT and UPDATE against
     * the ceiling.
     */
    @Test
    void an_over_cap_mission_planted_by_dropping_the_check_stays_readable()
            throws SQLException {
        String overCap = repeat('m', 2000);

        try (Connection migrator = Db.asMigrator()) {
            // The migrator owns the schema but carries NOBYPASSRLS, so the
            // planting insert is subject to the same tenant policy any writer
            // would face. Binding the tenant is not a workaround: it is the
            // policy this insert satisfies, the same way every other insert
            // in this class does.
            Db.bindTenant(migrator, tenant);
            Db.exec(migrator, "ALTER TABLE worklist.milestone "
                + "DROP CONSTRAINT ck_milestone_mission_length");
            insertMilestoneWithMissionAs(migrator, 42, overCap);
            Db.exec(migrator, "ALTER TABLE worklist.milestone "
                + "ADD CONSTRAINT ck_milestone_mission_length "
                + "CHECK (mission IS NULL OR char_length(mission) <= 1500) NOT VALID");
            migrator.commit();
        }

        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            assertThat(missionAtNumber(c, 42))
                .as("the row planted at 2000 characters must remain readable through the "
                    + "runtime role. A cap on the read path would have sealed the store "
                    + "against its own content, and the content could not then be "
                    + "repaired through the service")
                .hasSize(2000);

            // And the constraint is still on the write path — a new over-cap
            // row is refused even though the planted one stands.
            assertThatThrownBy(() -> insertMilestoneWithMission(c, 43, overCap))
                .as("NOT VALID leaves existing rows alone and checks every subsequent "
                    + "write; a second over-cap row must therefore be refused")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_milestone_mission_length");
            c.rollback();
        }
    }

    // ==================================================================
    // The attribute-definition type set (V14 widens it to include text_list).
    // ==================================================================

    /**
     * The type of a declared attribute is one of the eight the platform
     * carries; anything else is refused at the check constraint.
     *
     * <p>The green half is what makes the constraint the shape it is meant to
     * be: a text_list declaration goes through, as would any other admitted
     * type — the refusal is not "any name for a type" but "one of these
     * eight". Without both halves a constraint that refused everything would
     * satisfy the red assertion without saying anything about the schema.
     */
    @Test
    void an_unknown_attribute_type_is_refused_and_text_list_goes_through()
            throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);

            assertThatThrownBy(() -> insertAttributeDefinition(c, "colour_attr", "colour",
                    false))
                .as("RED STATE, observed: a type outside the platform's set must be "
                    + "refused. The set is closed at the platform level and a new type "
                    + "would be a schema change here")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_attribute_definition_type");
            c.rollback();

            Db.bindTenant(c, tenant);
            insertAttributeDefinition(c, "steps_attr", "text_list", false);
            c.commit();

            assertThat(count(c, "attribute_definition"))
                .as("a text_list declaration is admitted — the eighth type the "
                    + "constraint now names")
                .isEqualTo(1);
            c.commit();
        }
    }

    /**
     * A text_list declaration with {@code sortable = true} is refused;
     * {@code sortable = false} goes through.
     *
     * <p>An expression index that ordered a list would pick whichever total
     * order the operator class happened to have — and no such order is the
     * value's own. A rule with no mechanism above the schema is exactly the
     * class this project keeps finding, so the refusal lives here.
     */
    @Test
    void a_sortable_text_list_declaration_is_refused_and_a_non_sortable_one_stands()
            throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);

            assertThatThrownBy(() -> insertAttributeDefinition(c, "sorted_steps",
                    "text_list", true))
                .as("RED STATE, observed: a text_list with sortable = true must be "
                    + "refused. Ordering by a list would be a promise the value cannot "
                    + "keep, and the read path would pick whichever total order the "
                    + "operator class had, silently")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_attribute_definition_text_list_not_sortable");
            c.rollback();

            Db.bindTenant(c, tenant);
            insertAttributeDefinition(c, "unsorted_steps", "text_list", false);
            c.commit();

            assertThat(count(c, "attribute_definition"))
                .as("and a non-sortable list is admitted — the constraint refuses the "
                    + "combination, not the type on its own")
                .isEqualTo(1);
            c.commit();
        }
    }

    // ==================================================================
    // Planting. Every statement is issued as the runtime role, under a bound
    // tenant, so a refusal is the constraint and never the policy.
    // ==================================================================

    private void insertAttributeDefinition(Connection c, String key, String type,
            boolean sortable) throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.attribute_definition
                    (id, tenant_id, scope_id, key, name, type, sortable)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            st.setObject(1, UUID.randomUUID());
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.setString(4, key);
            st.setString(5, "Attribute " + key);
            st.setString(6, type);
            st.setBoolean(7, sortable);
            st.executeUpdate();
        }
    }

    private UUID insertSelector(Connection c) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.selector (id, tenant_id, scope_id, token)
                VALUES (?, ?, ?, ?)
                """)) {
            st.setObject(1, id);
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            // Lower case, because the form constraint says so since V6, and
            // deliberately not one of the three views: this class plants rows to
            // prove a constraint on, and the constraint checks form only.
            st.setString(4, "t" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12));
            st.executeUpdate();
        }
        return id;
    }

    private void insertNumberSpace(Connection c, UUID selectorId) throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.number_space (id, selector_id, tenant_id, scope_id)
                VALUES (?, ?, ?, ?)
                """)) {
            st.setObject(1, UUID.randomUUID());
            st.setObject(2, selectorId);
            st.setObject(3, tenant);
            st.setObject(4, SCOPE);
            st.executeUpdate();
        }
    }

    private UUID insertStatus(Connection c) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.item_status
                    (id, tenant_id, scope_id, name, actionable, in_progress, closed,
                     successful)
                VALUES (?, ?, ?, 'open', true, false, false, false)
                """)) {
            st.setObject(1, id);
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.executeUpdate();
        }
        return id;
    }

    /**
     * An item, with the tenant's status — declared once and reused — and a
     * fresh address per insert.
     *
     * <p>Since V7 both halves of the address are NOT NULL at the column, so an
     * item without them is refused. A fresh selector per insert keeps the
     * number at 1 for every planted row without threading a counter through
     * every caller.
     */
    private UUID insertItem(Connection c, String title) throws SQLException {
        UUID id = UUID.randomUUID();
        UUID selector = insertSelector(c);
        UUID workstream = Db.ensureDefaultWorkstream(c, tenant, SCOPE);
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.item
                    (id, tenant_id, scope_id, title, status_id, selector_id, number,
                     workstream_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            st.setObject(1, id);
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.setString(4, title);
            st.setObject(5, anyStatus(c));
            st.setObject(6, selector);
            st.setLong(7, 1L);
            st.setObject(8, workstream);
            st.executeUpdate();
        }
        return id;
    }

    /** The address of an item, set after the fact so the insert is not the test. */
    private void address(Connection c, UUID item, UUID selector, long number)
            throws SQLException {
        try (var st = c.prepareStatement(
                "UPDATE worklist.item SET selector_id = ?, number = ? WHERE id = ?")) {
            st.setObject(1, selector);
            st.setLong(2, number);
            st.setObject(3, item);
            st.executeUpdate();
        }
    }

    private UUID insertRelationType(Connection c) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.relation_type (id, tenant_id, scope_id, name, blocks)
                VALUES (?, ?, ?, 'blocks', true)
                """)) {
            st.setObject(1, id);
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.executeUpdate();
        }
        return id;
    }

    private void insertRelation(Connection c, UUID from, UUID to, UUID type)
            throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.item_relation
                    (tenant_id, scope_id, from_item_id, to_item_id, relation_type_id)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, SCOPE);
            st.setObject(3, from);
            st.setObject(4, to);
            st.setObject(5, type);
            st.executeUpdate();
        }
    }

    private void insertReference(Connection c, UUID item, int ordinal, String target,
            String status) throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.item_reference
                    (id, tenant_id, scope_id, item_id, ordinal, target, status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            st.setObject(1, UUID.randomUUID());
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.setObject(4, item);
            st.setInt(5, ordinal);
            st.setString(6, target);
            st.setString(7, status);
            st.executeUpdate();
        }
    }

    /** Withdraw every living entry from that ordinal upward, keeping its content. */
    private void withdrawReferencesFrom(Connection c, UUID item, int from)
            throws SQLException {
        try (var st = c.prepareStatement("""
                UPDATE worklist.item_reference SET status = 'withdrawn'
                WHERE item_id = ? AND ordinal >= ? AND status = 'asserted'
                """)) {
            st.setObject(1, item);
            st.setInt(2, from);
            st.executeUpdate();
        }
    }

    /** The ordinals of the living entries, in the reader's order. */
    private List<Integer> livingOrdinals(Connection c, UUID item) throws SQLException {
        List<Integer> out = new ArrayList<>();
        try (var st = c.prepareStatement("""
                SELECT ordinal FROM worklist.item_reference
                WHERE item_id = ? AND status = 'asserted' ORDER BY ordinal
                """)) {
            st.setObject(1, item);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getInt(1));
                }
            }
        }
        return out;
    }

    /** The targets of the withdrawn entries, by the ordinal they kept. */
    private List<String> withdrawnTargets(Connection c, UUID item) throws SQLException {
        List<String> out = new ArrayList<>();
        try (var st = c.prepareStatement("""
                SELECT target FROM worklist.item_reference
                WHERE item_id = ? AND status = 'withdrawn' ORDER BY ordinal
                """)) {
            st.setObject(1, item);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private void insertMilestone(Connection c, long number, String kind, String status,
            String vision) throws SQLException {
        UUID workstream = Db.ensureDefaultWorkstream(c, tenant, SCOPE);
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.milestone
                    (id, tenant_id, scope_id, number, title, kind, status, vision,
                     workstream_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            st.setObject(1, UUID.randomUUID());
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.setLong(4, number);
            st.setString(5, "milestone " + number);
            st.setString(6, kind);
            st.setString(7, status);
            st.setString(8, vision);
            st.setObject(9, workstream);
            st.executeUpdate();
        }
    }

    private UUID insertIteration(Connection c) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.iteration
                    (id, tenant_id, scope_id, number, motto, description)
                VALUES (?, ?, ?, 1, 'a motto', 'a description')
                """)) {
            st.setObject(1, id);
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.executeUpdate();
        }
        return id;
    }

    private void insertMembership(Connection c, UUID iteration, UUID item, int position,
            String status) throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.iteration_membership
                    (tenant_id, scope_id, iteration_id, item_id, position, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, SCOPE);
            st.setObject(3, iteration);
            st.setObject(4, item);
            st.setInt(5, position);
            st.setString(6, status);
            st.executeUpdate();
        }
    }

    /**
     * A claim whose expiry is the given interval after the grant.
     *
     * <p>The interval is sent as text and added in SQL rather than computed
     * here, so that the two timestamps come from the same clock. Computed in
     * Java they would come from a different one, and a "zero" duration could
     * land on either side of the constraint depending on clock skew — which
     * would make this probe flake rather than fail.
     */
    private void insertClaim(Connection c, UUID item, String duration) throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.claim
                    (tenant_id, scope_id, item_id, receipt, actor, granted_at, expires_at)
                VALUES (?, ?, ?, 'a receipt', 'an actor', now(), now() + ?::interval)
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, SCOPE);
            st.setObject(3, item);
            st.setString(4, duration);
            st.executeUpdate();
        }
    }

    /** The tenant's declared status, or a fresh one when it has none yet. */
    private UUID anyStatus(Connection c) throws SQLException {
        try (var st = c.prepareStatement(
                "SELECT id FROM worklist.item_status WHERE tenant_id = ? LIMIT 1")) {
            st.setObject(1, tenant);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    return UUID.fromString(rs.getString(1));
                }
            }
        }
        return insertStatus(c);
    }

    /** The rows of one table this tenant holds. */
    private long count(Connection c, String table) throws SQLException {
        try (var st = c.prepareStatement(
                "SELECT count(*) FROM worklist." + table + " WHERE tenant_id = ?")) {
            st.setObject(1, tenant);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** How many items of this tenant carry that number, whatever their selector. */
    private long numbersAt(Connection c, long number) throws SQLException {
        try (var st = c.prepareStatement(
                "SELECT count(*) FROM worklist.item WHERE tenant_id = ? AND number = ?")) {
            st.setObject(1, tenant);
            st.setLong(2, number);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * An item at the address given, or with either half deliberately unset —
     * used by the two probes that watch a column NOT NULL refuse an insert.
     * Every other insert path in this class uses {@link #insertItem}, which
     * always supplies both halves.
     */
    private void insertItemWithAddress(Connection c, String title, UUID status,
            UUID selector, Long number) throws SQLException {
        UUID workstream = Db.ensureDefaultWorkstream(c, tenant, SCOPE);
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.item
                    (id, tenant_id, scope_id, title, status_id, selector_id, number,
                     workstream_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            st.setObject(1, UUID.randomUUID());
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.setString(4, title);
            st.setObject(5, status);
            st.setObject(6, selector);
            if (number == null) {
                st.setNull(7, java.sql.Types.BIGINT);
            } else {
                st.setLong(7, number);
            }
            st.setObject(8, workstream);
            st.executeUpdate();
        }
    }

    /**
     * An item at a title of the given length — the address is a fresh valid
     * pair so the refusal, when one comes, names the length check and nothing
     * else.
     */
    private void insertItemWithTitle(Connection c, String title, UUID status,
            UUID selector) throws SQLException {
        insertItemWithAddress(c, title, status, selector, 1L);
    }

    /**
     * A milestone with a mission of the given length. The kind is
     * {@code milestone} because a marker may not carry a mission at all, and
     * that is a different constraint under test elsewhere.
     */
    private void insertMilestoneWithMission(Connection c, long number, String mission)
            throws SQLException {
        UUID workstream = Db.ensureDefaultWorkstream(c, tenant, SCOPE);
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.milestone
                    (id, tenant_id, scope_id, number, title, kind, status, mission,
                     workstream_id)
                VALUES (?, ?, ?, ?, ?, 'milestone', 'planned', ?, ?)
                """)) {
            st.setObject(1, UUID.randomUUID());
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.setLong(4, number);
            st.setString(5, "milestone " + number);
            st.setString(6, mission);
            st.setObject(7, workstream);
            st.executeUpdate();
        }
    }

    /**
     * The same insert on any given connection — used from the migrator
     * connection in the read-path probe, so a row can be planted after the
     * check has been dropped and before it is put back.
     *
     * <p>Kept apart from {@link #insertMilestoneWithMission} as a NAMED call
     * site, so the two probes read at their call sites as "same shape,
     * different connection". The body delegates to the same helper — this
     * method exists to name the intention, not to duplicate the code.
     */
    private void insertMilestoneWithMissionAs(Connection c, long number, String mission)
            throws SQLException {
        insertMilestoneWithMission(c, number, mission);
    }

    /** The mission of one milestone, as the CURRENT session sees it. */
    private String missionAtNumber(Connection c, long number) throws SQLException {
        try (var st = c.prepareStatement(
                "SELECT mission FROM worklist.milestone "
                    + "WHERE tenant_id = ? AND number = ?")) {
            st.setObject(1, tenant);
            st.setLong(2, number);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static String repeat(char ch, int times) {
        char[] buf = new char[times];
        java.util.Arrays.fill(buf, ch);
        return new String(buf);
    }
}
