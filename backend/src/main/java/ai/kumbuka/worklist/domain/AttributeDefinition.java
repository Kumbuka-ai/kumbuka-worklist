package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.StringUuidConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.TenantId;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One attribute a scope declared, with its type.
 *
 * <p><strong>This is the single most important structural change from the
 * predecessor</strong>, whose specification fixes an item at exactly sixteen
 * cells: a seventeenth column was a schema change there, and here it is a
 * row. Cluster, type, priority, size and the component tags were four fixed
 * columns and an array; all five are declarations now, and the service does
 * not know any of them by name — it knows that the scope declared them.
 *
 * <h2>The seven types, and why nothing narrows them</h2>
 *
 * The set is closed at the platform level and the platform asks no question
 * about which one a scope picks. The same business field is {@link #CHOICE}
 * in one scope, {@link #TEXT} in the next and {@link #NUMBER} in a third — an
 * estimate as t-shirt sizes, as person-days typed by hand, or as a figure to
 * be computed with. No rule narrows the admissible types of a particular
 * field, and none could: this service does not know a declared attribute by
 * what it means, so a clause naming one would need a place where the platform
 * recognises that field, and the fixed core has no such place on purpose.
 *
 * <h2>{@link #sortable} is a real column and not documentation</h2>
 *
 * The containment index over {@code item.attributes} answers every filter
 * over every declared attribute with one index and no schema change per
 * attribute, which is the property that makes a declaration cheap. It does
 * NOT order and does NOT answer ranges. Ordering by a declared attribute
 * therefore needs an expression index of its own — so declaring an attribute
 * sortable is what CAUSES that index to exist, and an attribute that is not
 * sortable is filtered and displayed and not ordered by.
 *
 * <p>The index itself is created when the attribute is declared, and that
 * verb belongs to the reading surface's own piece of work. What stands here
 * is the column that makes the capability declarable at all.
 */
@Entity
@Table(name = "attribute_definition", schema = "worklist")
public class AttributeDefinition {

    /** Free text, one line or many. */
    public static final String TEXT = "text";
    /** Integer or decimal; the only type that orders numerically. */
    public static final String NUMBER = "number";
    /** A calendar date, not a timestamp. */
    public static final String DATE = "date";
    /** Present or absent, never a third state. */
    public static final String BOOLEAN = "boolean";
    /** Exactly one option from the declared set. */
    public static final String CHOICE = "choice";
    /** Zero or more options from the declared set. */
    public static final String MULTI_CHOICE = "multi_choice";
    /** An address of another item; a pointer, never a relation. */
    public static final String ITEM_REFERENCE = "item_reference";

    /**
     * The whole set, in one place, so a refusal can say what was possible and
     * the check constraint in V4 has exactly one counterpart in Java rather
     * than a list per caller.
     */
    public static final List<String> TYPES =
        List.of(TEXT, NUMBER, DATE, BOOLEAN, CHOICE, MULTI_CHOICE, ITEM_REFERENCE);

    /** The types whose values are drawn from {@link AttributeOption}. */
    public static final List<String> ENUMERATED = List.of(CHOICE, MULTI_CHOICE);

    public static final String DECLARED = "declared";
    public static final String WITHDRAWN = "withdrawn";

    /**
     * The shape of a key: a leading lower-case letter, then alphanumerics and
     * interior underscores. {@code cluster}, {@code story_points}, {@code t2}
     * pass; a leading digit, a trailing underscore and an empty key do not.
     *
     * <p>Here rather than in the registry that uses it, because
     * {@code ck_attribute_definition_key} in V4 is the same expression and the
     * two must not drift: a Java check that accepted what the database rejects
     * would turn a refusal into a constraint violation, and the other way
     * round would let a key through that nothing can store.
     *
     * <p>The quantifiers are possessive for the reason
     * {@link Selector#TOKEN_PATTERN} gives: the two nested stars otherwise
     * give Java's engine an exponential number of ways to split a long
     * non-matching input. The accepted language is identical.
     */
    public static final java.util.regex.Pattern KEY_PATTERN =
        java.util.regex.Pattern.compile("^[a-z][a-z0-9]*+(?:_[a-z0-9]++)*+$");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    public UUID id;

    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "scope_id", nullable = false)
    public UUID scopeId;

    /**
     * The stable name a caller addresses the attribute by. Immutable, and
     * unique in its scope: a withdrawn definition keeps its key, which is what
     * stops the key from being re-declared to mean something else.
     */
    @Column(name = "key", nullable = false, updatable = false)
    public String key;

    /** What a reader sees. Changeable at will; an item stores {@link #id}. */
    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "description")
    public String description;

    /** One of {@link #TYPES}. */
    @Column(name = "type", nullable = false)
    public String type;

    /** The display order of the declarations, and never the alphabetical one. */
    @Column(name = "rank", nullable = false)
    public int rank;

    /** Whether ordering by this attribute is a capability the scope declared. */
    @Column(name = "sortable", nullable = false)
    public boolean sortable;

    @Column(name = "status", nullable = false)
    public String status = DECLARED;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false)
    public Instant updatedAt;
}
