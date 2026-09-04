package ai.kumbuka.worklist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One option of a {@code choice} or {@code multi_choice} attribute.
 *
 * <p>A {@link DeclaredValue} like any other, so an option can be renamed and
 * withdrawn without touching a single item: what an item stores in its
 * document column is this row's identity, never its name. The predecessor
 * cannot do that, because there the value IS the identifier, and renaming a
 * cluster would rewrite several hundred items and invalidate every reference
 * to it in prose.
 *
 * <p><strong>Two options may share a display name.</strong> That is not an
 * oversight: the concept makes the name a display property, which argues for
 * permitting it, while a reader confronted with two identical labels argues
 * against — and the question is carried as an open point rather than settled.
 * A uniqueness rule added before it is answered could never be switched on
 * again once two rows had shared a name, so the more expensive mistake is the
 * one that looks tidier.
 */
@Entity
@Table(name = "attribute_option", schema = "worklist")
public class AttributeOption extends DeclaredValue {

    /** The declaration this option belongs to. Immutable: an option does not migrate. */
    @Column(name = "definition_id", nullable = false, updatable = false)
    public UUID definitionId;
}
