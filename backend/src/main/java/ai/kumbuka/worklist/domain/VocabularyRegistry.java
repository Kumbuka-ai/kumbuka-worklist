package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.repository.VocabularyRepository;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * The declared vocabularies of a scope: its statuses, its attributes and
 * their options, and its relation types.
 *
 * <p>The predecessor holds all of this as constants in its own source and
 * gates every row against them, which makes a customer's way of characterising
 * work into a release of this service. Here they are rows, declared per scope,
 * and this service's business is only that a value IS declared.
 *
 * <h2>Four vocabularies, one set of rules</h2>
 *
 * Every declared value has an identity, a display name, a rank and an optional
 * description, and every one of them is WITHDRAWN rather than deleted. Those
 * rules are the same for all four, which is why they live in one class: three
 * tables exist only because the platform properties differ — a status carries
 * the four predicates, a relation type carries whether it blocks, an option
 * carries neither and belongs to a definition.
 *
 * <h2>The rank is the order of the vocabulary and never the alphabetical one</h2>
 *
 * Without it a size axis sorts L before M before S, and the defect surfaces in
 * the console rather than at declaration.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <strong>The coherence rule that a scope declares at least one actionable and
 * at least one closed status.</strong> It is a statement about a SET of rows,
 * no constraint expresses it, and it needs a red probe of its own. It belongs
 * with the other domain rules that are enforced at declaration time, and those
 * are a separate piece of work — asserting it here without that probe would be
 * a rule that exists in prose only.
 *
 * <p><strong>The expression index a sortable attribute needs.</strong>
 * Declaring an attribute sortable is what causes that index to exist, and
 * creating it is a schema act by a role that holds no CREATE on its own
 * schema. The column is declared here; the index arrives with the reading
 * surface that needs it.
 */
@ApplicationScoped
@TenantBound
public class VocabularyRegistry {

    /** An identity, a scope id, a key. Never a description, never an actor. */
    private static final Logger LOG = Logger.getLogger(VocabularyRegistry.class);

    @Inject VocabularyRepository vocabulary;

    // ------------------------------------------------------------------
    // Statuses
    // ------------------------------------------------------------------

    /**
     * Declare a status value and the four predicates it maps onto.
     *
     * <p>The predicates are the whole of the meaning the platform guarantees
     * about a status, and they are mandatory for that reason: a status that
     * answered none of them would be a word this service cannot reason with.
     */
    @Transactional
    public ItemStatus declareStatus(UUID scopeId, String name, int rank,
            boolean actionable, boolean inProgress, boolean closed, boolean successful) {
        requireName(name, "a status");
        if (closed && inProgress) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "a status cannot be both closed and in progress: finished means there is "
                    + "nothing further to do, and in progress means somebody is doing it. "
                    + "Refused: " + name,
                List.of(name));
        }

        ItemStatus status = new ItemStatus();
        status.scopeId = scopeId;
        status.name = name.trim();
        status.rank = rank;
        status.actionable = actionable;
        status.inProgress = inProgress;
        status.closed = closed;
        status.successful = successful;
        vocabulary.insert(status);

        LOG.infof("status %s declared in scope %s", status.id, scopeId);
        return status;
    }

    /** Every status a scope declared, by rank then name. */
    @Transactional
    public List<ItemStatus> statuses(UUID scopeId) {
        return vocabulary.statusesIn(scopeId);
    }

    /**
     * The declared status of that identity in this scope, or a typed refusal.
     *
     * <p>By identity and not by name, because the name is a display property
     * that may be changed at will — a caller addressing a status by what it is
     * called would be addressing something that is allowed to move under it.
     */
    @Transactional
    public ItemStatus requireStatus(UUID scopeId, UUID statusId) {
        ItemStatus status = vocabulary.statusById(statusId);
        if (status == null || !status.scopeId.equals(scopeId)) {
            throw new WorklistException(
                WorklistException.Reason.VALUE_UNDECLARED,
                "no status " + statusId + " is declared in scope " + scopeId
                    + ". The statuses of a scope are its own data, and each one carries "
                    + "the four predicates the platform reasons about — declare the "
                    + "status before setting it on an item",
                List.of(String.valueOf(statusId)));
        }
        return status;
    }

    /**
     * Withdraw a status: it may not be set on anything new, and everything
     * already carrying it stays readable.
     *
     * <p>That second half is why this is a status change and not a delete. An
     * item that was closed as {@code obsolete} two years ago has to keep
     * saying so, or its own history stops being legible.
     */
    @Transactional
    public ItemStatus withdrawStatus(UUID scopeId, UUID statusId) {
        ItemStatus status = requireStatus(scopeId, statusId);
        if (!DeclaredValue.WITHDRAWN.equals(status.status)) {
            status.status = DeclaredValue.WITHDRAWN;
            vocabulary.flush();
            LOG.infof("status %s withdrawn in scope %s", statusId, scopeId);
        }
        return status;
    }

    // ------------------------------------------------------------------
    // Attributes
    // ------------------------------------------------------------------

    /**
     * Declare an attribute: a key, a display name, and one of the seven
     * types.
     *
     * <p>Idempotent for the same reason declaring a selector is: the caller is
     * stating that the declaration should exist, and a retry after a timeout
     * should not have to distinguish "created" from "already there".
     */
    @Transactional
    public AttributeDefinition declareAttribute(UUID scopeId, String key, String name,
            String type, int rank, boolean sortable) {
        if (key == null || !AttributeDefinition.KEY_PATTERN.matcher(key).matches()) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "an attribute key is a leading lower-case letter followed by "
                    + "alphanumerics and interior underscores — cluster, story_points. "
                    + "Refused: " + key,
                List.of(String.valueOf(key)));
        }
        requireName(name, "an attribute");
        if (!AttributeDefinition.TYPES.contains(type)) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "there is no attribute type " + type + ". The set is "
                    + AttributeDefinition.TYPES + " and it is closed at the platform "
                    + "level. Which of them a field gets is the scope's own choice — the "
                    + "same field is a choice in one scope, free text in the next and a "
                    + "number in a third, and no rule here narrows that",
                List.of(String.valueOf(type)));
        }

        AttributeDefinition existing = vocabulary.definitionByKey(scopeId, key);
        if (existing != null) {
            return existing;
        }

        AttributeDefinition definition = new AttributeDefinition();
        definition.scopeId = scopeId;
        definition.key = key;
        definition.name = name.trim();
        definition.type = type;
        definition.rank = rank;
        definition.sortable = sortable;
        vocabulary.insert(definition);

        LOG.infof("attribute %s declared in scope %s", key, scopeId);
        return definition;
    }

    /** Every attribute a scope declared, by rank then key. */
    @Transactional
    public List<AttributeDefinition> attributes(UUID scopeId) {
        return vocabulary.definitionsIn(scopeId);
    }

    /**
     * The declaration of that key in this scope, or a typed refusal naming it.
     *
     * <p>Addressed by key rather than by identity, unlike every other declared
     * value here, and the difference is what the key IS: immutable, unique in
     * its scope, and the name a caller writes an attribute under. The display
     * name is the part that may move.
     */
    @Transactional
    public AttributeDefinition requireAttribute(UUID scopeId, String key) {
        AttributeDefinition definition = vocabulary.definitionByKey(scopeId, key);
        if (definition == null) {
            throw new WorklistException(
                WorklistException.Reason.VALUE_UNDECLARED,
                "no attribute " + key + " is declared in scope " + scopeId
                    + ". An attribute is not created by being written to: a scope's "
                    + "attribute set is a declaration, and a value under an undeclared "
                    + "key is a value nothing can read back",
                List.of(String.valueOf(key)));
        }
        return definition;
    }

    /**
     * The declaration of that identity, or null.
     *
     * <p>The one lookup by identity on this side, and it exists because the
     * stored form of an attribute value keys by identity while the
     * caller-facing form keys by the key. Reading an item means translating
     * the first into the second, and a value under a declaration that is gone
     * is an absence rather than a refusal — the column still carries it, and
     * an answer naming a key no declaration can render would be worse.
     */
    @Transactional
    public AttributeDefinition attributeById(UUID definitionId) {
        return vocabulary.definitionById(definitionId);
    }

    /** Withdraw an attribute. Its key stays occupied; the items keep their values. */
    @Transactional
    public AttributeDefinition withdrawAttribute(UUID scopeId, String key) {
        AttributeDefinition definition = requireAttribute(scopeId, key);
        if (!DeclaredValue.WITHDRAWN.equals(definition.status)) {
            definition.status = DeclaredValue.WITHDRAWN;
            vocabulary.flush();
            LOG.infof("attribute %s withdrawn in scope %s", key, scopeId);
        }
        return definition;
    }

    /** Declare one option of a {@code choice} or {@code multi_choice} attribute. */
    @Transactional
    public AttributeOption declareOption(UUID scopeId, String key, String name, int rank) {
        AttributeDefinition definition = requireAttribute(scopeId, key);
        requireName(name, "an option");
        if (!AttributeDefinition.ENUMERATED.contains(definition.type)) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "attribute " + key + " is of type " + definition.type + " and draws its "
                    + "values from no declared set. Options belong to "
                    + AttributeDefinition.ENUMERATED,
                List.of(key));
        }

        AttributeOption option = new AttributeOption();
        option.scopeId = scopeId;
        option.definitionId = definition.id;
        option.name = name.trim();
        option.rank = rank;
        vocabulary.insert(option);

        LOG.infof("option %s declared under attribute %s in scope %s",
            option.id, key, scopeId);
        return option;
    }

    /** Every option of an attribute, by rank then name. */
    @Transactional
    public List<AttributeOption> options(UUID scopeId, String key) {
        return vocabulary.optionsOf(requireAttribute(scopeId, key).id);
    }

    /** The option of that identity under that declaration, or a typed refusal. */
    @Transactional
    public AttributeOption requireOption(AttributeDefinition definition, UUID optionId) {
        AttributeOption option = vocabulary.optionById(optionId);
        if (option == null || !option.definitionId.equals(definition.id)) {
            throw new WorklistException(
                WorklistException.Reason.VALUE_UNDECLARED,
                "no option " + optionId + " is declared under attribute " + definition.key
                    + ". An option has an identity separate from its name, so what an "
                    + "item stores is the identity — and an identity that is not in the "
                    + "declared set is a value nothing can render",
                List.of(definition.key, String.valueOf(optionId)));
        }
        return option;
    }

    // ------------------------------------------------------------------
    // Relation types
    // ------------------------------------------------------------------

    /**
     * Declare a relation type, and whether it blocks.
     *
     * <p>{@code blocks} is the ONE property of a type the platform reasons
     * about. Everything else the type means belongs to the scope, and the
     * platform never asks.
     */
    @Transactional
    public RelationType declareRelationType(UUID scopeId, String name, boolean blocks,
            int rank) {
        requireName(name, "a relation type");

        RelationType type = new RelationType();
        type.scopeId = scopeId;
        type.name = name.trim();
        type.blocks = blocks;
        type.rank = rank;
        vocabulary.insert(type);

        LOG.infof("relation type %s declared in scope %s", type.id, scopeId);
        return type;
    }

    /** Every relation type a scope declared, by rank then name. */
    @Transactional
    public List<RelationType> relationTypes(UUID scopeId) {
        return vocabulary.relationTypesIn(scopeId);
    }

    /** The relation type of that identity in this scope, or a typed refusal. */
    @Transactional
    public RelationType requireRelationType(UUID scopeId, UUID typeId) {
        RelationType type = vocabulary.relationTypeById(typeId);
        if (type == null || !type.scopeId.equals(scopeId)) {
            throw new WorklistException(
                WorklistException.Reason.VALUE_UNDECLARED,
                "no relation type " + typeId + " is declared in scope " + scopeId
                    + ". An edge carries a declared type, and the one thing the platform "
                    + "reads out of a type is whether it blocks — an undeclared type is "
                    + "an edge nothing can answer that question about",
                List.of(String.valueOf(typeId)));
        }
        return type;
    }

    /** Withdraw a relation type. The edges carrying it stay readable. */
    @Transactional
    public RelationType withdrawRelationType(UUID scopeId, UUID typeId) {
        RelationType type = requireRelationType(scopeId, typeId);
        if (!DeclaredValue.WITHDRAWN.equals(type.status)) {
            type.status = DeclaredValue.WITHDRAWN;
            vocabulary.flush();
            LOG.infof("relation type %s withdrawn in scope %s", typeId, scopeId);
        }
        return type;
    }

    /** A display name is present and is not whitespace. */
    private static void requireName(String name, String subject) {
        if (name == null || name.isBlank()) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                subject + " carries a display name: it is what a reader sees, and a "
                    + "declared value nobody can name is one nobody can pick",
                List.of(String.valueOf(name)));
        }
    }
}
