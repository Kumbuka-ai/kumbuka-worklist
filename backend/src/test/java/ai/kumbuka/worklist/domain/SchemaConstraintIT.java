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
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * Fixture items take distinct numbers: an item's number is unique in its
     * scope (V18), and every test of this class writes into the one scope.
     * Started well clear of the numbers the probes choose themselves.
     */
    private static final AtomicLong NEXT_NUMBER = new AtomicLong(1000);

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
     * An item's number is unique within its scope.
     *
     * <p>ADR-0042 makes {@code (tenant_id, scope_id, number)} the target of
     * every key onto an item, so the number alone has to name one item in a
     * scope. V18 replaced the rule this probe asserted before, that two
     * selectors may share a number; V20 dropped the item's selector.
     */
    @Test
    void an_items_number_is_unique_in_its_scope()
            throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            insertItemAt(c, "address 1", 51);
            c.commit();

            assertThatThrownBy(() -> insertItemAt(c, "address 2", 51))
                .as("RED STATE, observed: number 51 a second time in the same scope must be "
                    + "refused. A key onto (tenant, scope, number) would otherwise name two "
                    + "items at once, with no error anywhere")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_item_number");
            c.rollback();

            Db.bindTenant(c, tenant);
            insertItemAt(c, "address 3", 52);
            c.commit();

            assertThat(numbersAt(c, 51) + numbersAt(c, 52))
                .as("another number is admitted; the rule is about the number")
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
            Long type = insertRelationType(c);
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
     * selector_pk)}; {@code selector_pk NOT NULL} makes that ordinary rather
     * than partial. A row without a selector is refused at the column, and a
     * second row for the same selector is refused at the index.
     */
    @Test
    void a_scope_holds_exactly_one_counter_per_selector() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            Long first = insertSelector(c);
            Long second = insertSelector(c);
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
                .hasMessageContaining("selector_pk");
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
     * An item's address is its number in its scope, and the store holds no
     * selector beside it.
     *
     * <p>Through V18 an item carried the selector its number was drawn under,
     * and this probe watched a missing one refused; V19 filled it from the
     * view selector {@code item}, the only one any verb ever gave an item.
     * V20 drops the column (ADR-0042 stage R3): the number is unique within
     * the scope and is the address, so a selector beside it would be a second
     * statement of the same fact.
     */
    @Test
    void an_items_address_is_its_number_and_no_selector_is_stored() throws SQLException {
        try (Connection c = Db.asAdmin();
             var st = c.prepareStatement("""
                 SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'worklist' AND table_name = 'item'
                   AND column_name IN ('selector_id', 'number')
                 """);
             ResultSet rs = st.executeQuery()) {
            rs.next();
            assertThat(rs.getInt(1))
                .as("item carries its number and no selector column")
                .isEqualTo(1);
        }
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            long number = NEXT_NUMBER.getAndIncrement();
            insertItemWithAddress(c, "an address is a number", anyStatus(c), number);
            c.commit();
            assertThat(numbersAt(c, number))
                .as("an item stands at its number in its scope, with nothing else to name")
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
            Long status = anyStatus(c);
            c.commit();

            Db.bindTenant(c, tenant);
            assertThatThrownBy(() -> insertItemWithAddress(c, "no number", status, null))
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
            Long status = anyStatus(c);
            c.commit();

            Db.bindTenant(c, tenant);
            assertThatThrownBy(() -> insertItemWithTitle(c, repeat('a', 201), status))
                .as("RED STATE, observed: a title one character over the cap must be "
                    + "refused. A cap that admits 201 is a cap the reader has to guess "
                    + "at, and the predecessor's cap drifted from 150 to whatever the "
                    + "longest historical row happened to carry")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_item_title_length");
            c.rollback();

            Db.bindTenant(c, tenant);
            insertItemWithTitle(c, repeat('a', 200), status);
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
                    (tenant_id, scope_id, key, name, type, sortable)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, SCOPE);
            st.setString(3, key);
            st.setString(4, "Attribute " + key);
            st.setString(5, type);
            st.setBoolean(6, sortable);
            st.executeUpdate();
        }
    }

    private Long insertSelector(Connection c) throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.selector (tenant_id, scope_id, token)
                VALUES (?, ?, ?)
                RETURNING pk
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, SCOPE);
            // Lower case, because the form constraint says so since V6, and
            // deliberately not one of the three views: this class plants rows to
            // prove a constraint on, and the constraint checks form only.
            st.setString(3, "t" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12));
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void insertNumberSpace(Connection c, Long selectorPk) throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.number_space (selector_pk, tenant_id, scope_id)
                VALUES (?, ?, ?)
                """)) {
            if (selectorPk == null) {
                st.setNull(1, java.sql.Types.BIGINT);
            } else {
                st.setLong(1, selectorPk);
            }
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.executeUpdate();
        }
    }

    private Long insertStatus(Connection c) throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.item_status
                    (tenant_id, scope_id, name, actionable, in_progress, closed, successful)
                VALUES (?, ?, 'open', true, false, false, false)
                RETURNING pk
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, SCOPE);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * An item, with the tenant's status — declared once and reused — and a
     * fresh number per insert, which is its address (ADR-0042).
     */
    private UUID insertItem(Connection c, String title) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.item
                    (id, tenant_id, scope_id, title, status_pk, number, workstream_number)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            st.setObject(1, id);
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.setString(4, title);
            st.setLong(5, anyStatus(c));
            st.setLong(6, NEXT_NUMBER.getAndIncrement());
            st.setLong(7, defaultWorkstream(c));
            st.executeUpdate();
        }
        return id;
    }

    /** An item at a chosen number, inserted directly. */
    private void insertItemAt(Connection c, String title, long number) throws SQLException {
        insertItemWithAddress(c, title, anyStatus(c), number);
    }

    /** The number of the scope's default workstream, planted on first use. */
    private long defaultWorkstream(Connection c) throws SQLException {
        return Db.workstreamNumber(c, Db.ensureDefaultWorkstream(c, tenant, SCOPE));
    }

    private Long insertRelationType(Connection c) throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.relation_type (tenant_id, scope_id, name, blocks)
                VALUES (?, ?, 'blocks', true)
                RETURNING pk
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, SCOPE);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** An edge between two items, named by their identities and stored by their numbers. */
    private void insertRelation(Connection c, UUID from, UUID to, Long type)
            throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.item_relation
                    (tenant_id, scope_id, from_item_number, to_item_number, relation_type_pk)
                VALUES (?, ?, (SELECT number FROM worklist.item WHERE id = ?),
                        (SELECT number FROM worklist.item WHERE id = ?), ?)
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, SCOPE);
            st.setObject(3, from);
            st.setObject(4, to);
            st.setLong(5, type);
            st.executeUpdate();
        }
    }

    private void insertReference(Connection c, UUID item, int ordinal, String target,
            String status) throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.item_reference
                    (tenant_id, scope_id, item_number, ordinal, target, status)
                VALUES (?, ?, (SELECT number FROM worklist.item WHERE id = ?), ?, ?, ?)
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, SCOPE);
            st.setObject(3, item);
            st.setInt(4, ordinal);
            st.setString(5, target);
            st.setString(6, status);
            st.executeUpdate();
        }
    }

    /** Withdraw every living entry from that ordinal upward, keeping its content. */
    private void withdrawReferencesFrom(Connection c, UUID item, int from)
            throws SQLException {
        try (var st = c.prepareStatement("""
                UPDATE worklist.item_reference SET status = 'withdrawn'
                WHERE item_number = (SELECT number FROM worklist.item WHERE id = ?)
                  AND ordinal >= ? AND status = 'asserted'
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
                WHERE item_number = (SELECT number FROM worklist.item WHERE id = ?)
                  AND status = 'asserted' ORDER BY ordinal
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
                WHERE item_number = (SELECT number FROM worklist.item WHERE id = ?)
                  AND status = 'withdrawn' ORDER BY ordinal
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
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.milestone
                    (id, tenant_id, scope_id, number, title, kind, status, vision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            st.setObject(1, UUID.randomUUID());
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.setLong(4, number);
            st.setString(5, "milestone " + number);
            st.setString(6, kind);
            st.setString(7, status);
            st.setString(8, vision);
            st.executeUpdate();
        }
    }

    private UUID insertIteration(Connection c) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.iteration
                    (id, tenant_id, scope_id, number, title, motto, description)
                VALUES (?, ?, ?, 1, 'a title', 'a motto', 'a description')
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
                    (tenant_id, scope_id, iteration_number, item_number, position, status)
                VALUES (?, ?, (SELECT number FROM worklist.iteration WHERE id = ?),
                        (SELECT number FROM worklist.item WHERE id = ?), ?, ?)
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
                    (tenant_id, scope_id, item_number, receipt, actor, granted_at, expires_at)
                VALUES (?, ?, (SELECT number FROM worklist.item WHERE id = ?),
                        'a receipt', 'an actor', now(), now() + ?::interval)
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, SCOPE);
            st.setObject(3, item);
            st.setString(4, duration);
            st.executeUpdate();
        }
    }

    /** The tenant's declared status, or a fresh one when it has none yet. */
    private Long anyStatus(Connection c) throws SQLException {
        try (var st = c.prepareStatement(
                "SELECT pk FROM worklist.item_status WHERE tenant_id = ? LIMIT 1")) {
            st.setObject(1, tenant);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
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
     * An item at the number given, or with it deliberately unset — used by
     * the probe that watches the column NOT NULL refuse an insert.
     */
    private void insertItemWithAddress(Connection c, String title, Long status, Long number)
            throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.item
                    (id, tenant_id, scope_id, title, status_pk, number, workstream_number)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            st.setObject(1, UUID.randomUUID());
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.setString(4, title);
            st.setLong(5, status);
            if (number == null) {
                st.setNull(6, java.sql.Types.BIGINT);
            } else {
                st.setLong(6, number);
            }
            st.setLong(7, defaultWorkstream(c));
            st.executeUpdate();
        }
    }

    /** An item at a title of the given length and a fresh number. */
    private void insertItemWithTitle(Connection c, String title, Long status)
            throws SQLException {
        insertItemWithAddress(c, title, status, NEXT_NUMBER.getAndIncrement());
    }

    /**
     * A milestone with a mission of the given length. The kind is
     * {@code milestone} because a marker may not carry a mission at all, and
     * that is a different constraint under test elsewhere.
     */
    private void insertMilestoneWithMission(Connection c, long number, String mission)
            throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.milestone
                    (id, tenant_id, scope_id, number, title, kind, status, mission)
                VALUES (?, ?, ?, ?, ?, 'milestone', 'planned', ?)
                """)) {
            st.setObject(1, UUID.randomUUID());
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.setLong(4, number);
            st.setString(5, "milestone " + number);
            st.setString(6, mission);
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
