/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.AddressRegistry;
import ai.kumbuka.worklist.domain.AttributeDefinition;
import ai.kumbuka.worklist.domain.AttributeOption;
import ai.kumbuka.worklist.domain.Field;
import ai.kumbuka.worklist.domain.ItemStatus;
import ai.kumbuka.worklist.domain.RelationType;
import ai.kumbuka.worklist.domain.ScopeSettingService;
import ai.kumbuka.worklist.domain.ScopeUsage;
import ai.kumbuka.worklist.domain.VocabularyFields;
import ai.kumbuka.worklist.domain.VocabularyRegistry;
import ai.kumbuka.worklist.domain.WorklistException;
import ai.kumbuka.worklist.domain.Workstream;
import ai.kumbuka.worklist.domain.WorkstreamService;
import ai.kumbuka.worklist.platform.ScopeDirectory;
import ai.kumbuka.worklist.platform.ScopeDirectory.Access;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The administration surface: where a scope's vocabulary is kept (TAR-0002
 * B13, REA-0011 S4) -- its statuses with their phase and predicates, what it
 * uses of the optional predicates and properties, its attribute definitions
 * with their options (the type of an item among them), its relation types,
 * its workstreams and its default column set -- and the read of that model.
 *
 * <p>The second entry class beside {@link VerbSurface} (C1.6). A request
 * travels the REST adapter, this class, the domain service, the repository
 * (C1.1), and the transaction opens on the public methods here (C1.4). It
 * calls the same domain services the verbs reach -- {@link VocabularyRegistry},
 * {@link WorkstreamService}, {@link ScopeSettingService} -- and adds no write
 * logic of its own: a rule a declared value must satisfy lives in the domain
 * service, so the rows written here are the rows the registry writes for any
 * other caller (REA-0011 S4, rollback).
 *
 * <p><strong>It is not part of the work surface.</strong> No verb names its
 * calls, no tool of {@code tools/list} reaches it (C4.8) and the declaration
 * of {@code GET /api/declaration} does not carry it (C5.7); its calls are
 * declared in {@code administration-surface.tsv} beside the verb
 * specification. An agent working a scope cannot change the scope's model
 * through the surface it works on (B13, D5).
 *
 * <h2>The check order</h2>
 *
 * The one of C1.3: the form of a call is the adapter's and is decided before
 * this class is entered; here the scope name's grammar, then whether the
 * caller sees the scope, then what the scope holds. A scope the caller cannot
 * see answers {@code NOT_FOUND} as on the work surface, whatever else is wrong.
 *
 * <h2>The right a call asks for</h2>
 *
 * A read of the model asks for the read right, so that a caller of the work
 * surface reads the declared statuses, types and relation types of its scope.
 * Every call that writes resolves the scope with {@link Access#ADMINISTER}: it
 * is refused for a locked scope with {@code SCOPE_LOCKED} and for a subject
 * without the write right with {@code ROLE_INSUFFICIENT}. That write right
 * stands in for the administration permission until it exists (REA-0011
 * sections 4 and 6); the check moves to that permission with S15 and the wave
 * of TAR-0006, in {@link ScopeDirectory}.
 *
 * <h2>What is deliberately not here</h2>
 *
 * A change of a declared status, and a rename of a relation type: the
 * registry carries neither, and a declared value that items use is never
 * retyped or given another meaning ({@code selector-declared-before-use});
 * withdrawing it is the way. Re-declaring a name the scope holds answers the
 * declaration that stands, unchanged, as the registry does for every caller.
 */
@ApplicationScoped
@TenantBound
public class AdministrationSurface {

    /*
     * Every line a call of this surface leaves is written here, through
     * PlatformLog, by the technical address of what it wrote and the scope id,
     * never by a name, a key, a token or a description (DEC-0051, C13.3). The
     * domain logs nothing.
     */

    /**
     * The names of the arguments the calls of this surface take, which a
     * refusal may name under {@code offenders} and {@code accepts}. Written
     * here once; the expectation the conformance probe holds them against is
     * {@code administration-surface.tsv}.
     */
    public static final List<String> ARGUMENTS = List.of(
        "name", "rank", "actionable", "in_progress", "closed", "successful", "reviewed",
        "approved", "verified", "key", "type", "sortable", "blocks", "target_status", "token",
        "description", "default_columns");

    @Inject VocabularyRegistry vocabulary;
    @Inject WorkstreamService workstreams;
    @Inject ScopeSettingService settings;
    @Inject AddressRegistry addresses;
    @Inject ScopeDirectory scopes;

    // ======================================================================
    // Statuses
    // ======================================================================

    /** Every status the scope declared, withdrawn ones included, by rank then name. */
    @Transactional
    public List<Entry> statuses(String subject, String rawScope) {
        Scope in = entry(subject, rawScope, Access.READ);
        PlatformLog.vocabularyRead(in.log());
        return vocabulary.statuses(in.id()).stream()
            .map(AdministrationSurface::statusEntry)
            .toList();
    }

    /**
     * Declares a status with its rank and predicates. The registry refuses
     * predicates that contradict each other before it writes anything
     * (TAR-0002 B3); the answer names the phase the status is bound to.
     */
    @Transactional
    public Entry declareStatus(String subject, String rawScope, StatusDeclaration declared) {
        Scope in = entry(subject, rawScope, Access.ADMINISTER);
        ItemStatus status = vocabulary.declareStatus(in.id(), declared.name(), declared.rank(),
            declared.actionable(), declared.inProgress(), declared.closed(),
            declared.successful(), declared.reviewed(), declared.approved(),
            declared.verified());
        PlatformLog.statusDeclared(PlatformLog.TechnicalAddress.of(status), in.log());
        return statusEntry(status);
    }

    /**
     * Withdraws a status by its name: nothing new takes it, and every item
     * that carries it keeps reading it.
     */
    @Transactional
    public Entry withdrawStatus(String subject, String rawScope, String name) {
        Scope in = entry(subject, rawScope, Access.ADMINISTER);
        ItemStatus named = vocabulary.statusByName(in.id(), name)
            .orElseThrow(() -> undeclared(Field.STATUS.path()));
        ItemStatus status = vocabulary.withdrawStatus(in.id(), named.pk);
        PlatformLog.statusWithdrawn(PlatformLog.TechnicalAddress.of(status), in.log());
        return statusEntry(status);
    }

    // ======================================================================
    // What the scope uses
    // ======================================================================

    /** The optional predicates and properties the scope declared it uses. */
    @Transactional
    public List<Entry> usages(String subject, String rawScope) {
        Scope in = entry(subject, rawScope, Access.READ);
        PlatformLog.vocabularyRead(in.log());
        return vocabulary.usages(in.id()).stream()
            .map(AdministrationSurface::usageEntry)
            .toList();
    }

    /**
     * Declares that the scope uses an optional predicate or property, by its
     * name: {@code reviewed}, {@code approved}, {@code verified},
     * {@code priority} or {@code severity}.
     */
    @Transactional
    public Entry declareUsage(String subject, String rawScope, ScopeUsage.Property property) {
        Scope in = entry(subject, rawScope, Access.ADMINISTER);
        vocabulary.declareUsage(in.id(), property);
        PlatformLog.usageDeclared(in.log());
        return usageEntry(property);
    }

    // ======================================================================
    // Attributes
    // ======================================================================

    /** Every attribute the scope declared, with its options, by rank then key. */
    @Transactional
    public List<Entry> attributes(String subject, String rawScope) {
        Scope in = entry(subject, rawScope, Access.READ);
        PlatformLog.vocabularyRead(in.log());
        return vocabulary.attributes(in.id()).stream()
            .map(definition -> attributeEntry(in, definition))
            .toList();
    }

    /**
     * Declares an attribute by its key, its display name and one of the eight
     * types; {@code type} is the attribute whose options are the types of an
     * item.
     */
    @Transactional
    public Entry declareAttribute(String subject, String rawScope,
                                  AttributeDeclaration declared) {
        Scope in = entry(subject, rawScope, Access.ADMINISTER);
        AttributeDefinition definition = vocabulary.declareAttribute(in.id(), declared.key(),
            declared.name(), declared.type(), declared.rank(), declared.sortable());
        PlatformLog.attributeDeclared(PlatformLog.TechnicalAddress.of(definition), in.log());
        return attributeEntry(in, definition);
    }

    /** Declares one option of a {@code choice} or {@code multi_choice} attribute. */
    @Transactional
    public Entry declareOption(String subject, String rawScope, String key, String name,
                               int rank) {
        Scope in = entry(subject, rawScope, Access.ADMINISTER);
        AttributeOption option = vocabulary.declareOption(in.id(), key, name, rank);
        PlatformLog.optionDeclared(PlatformLog.TechnicalAddress.of(option), in.log());
        return attributeEntry(in, vocabulary.requireAttribute(in.id(), key));
    }

    /** Withdraws an attribute by its key; its key stays taken and items keep their values. */
    @Transactional
    public Entry withdrawAttribute(String subject, String rawScope, String key) {
        Scope in = entry(subject, rawScope, Access.ADMINISTER);
        AttributeDefinition definition = vocabulary.withdrawAttribute(in.id(), key);
        PlatformLog.attributeWithdrawn(PlatformLog.TechnicalAddress.of(definition), in.log());
        return attributeEntry(in, definition);
    }

    // ======================================================================
    // Relation types
    // ======================================================================

    /** Every relation type the scope declared, by rank then name. */
    @Transactional
    public List<Entry> relationTypes(String subject, String rawScope) {
        Scope in = entry(subject, rawScope, Access.READ);
        PlatformLog.vocabularyRead(in.log());
        return vocabulary.relationTypes(in.id()).stream()
            .map(this::relationEntry)
            .toList();
    }

    /**
     * Declares a relation type, whether it blocks, and for {@code duplicate_of}
     * its target status (REA-0011 D18), which the registry holds to its rule.
     */
    @Transactional
    public Entry declareRelationType(String subject, String rawScope,
                                     RelationDeclaration declared) {
        Scope in = entry(subject, rawScope, Access.ADMINISTER);
        RelationType type = vocabulary.declareRelationType(in.id(), declared.name(),
            declared.blocks(), declared.rank(), declared.targetStatus());
        PlatformLog.relationTypeDeclared(PlatformLog.TechnicalAddress.of(type), in.log());
        return relationEntry(type);
    }

    /** Withdraws a relation type by its name; the edges that carry it stay readable. */
    @Transactional
    public Entry withdrawRelationType(String subject, String rawScope, String name) {
        Scope in = entry(subject, rawScope, Access.ADMINISTER);
        RelationType named = vocabulary.relationTypeByName(in.id(), name)
            .orElseThrow(() -> undeclared(Field.RELATIONS.path()));
        RelationType type = vocabulary.withdrawRelationType(in.id(), named.pk);
        PlatformLog.relationTypeWithdrawn(PlatformLog.TechnicalAddress.of(type), in.log());
        return relationEntry(type);
    }

    // ======================================================================
    // Workstreams (D5: read and query are verbs; the rest is here)
    // ======================================================================

    /** Declares a workstream by its token and description. */
    @Transactional
    public Entry declareWorkstream(String subject, String rawScope, String token,
                                   String description) {
        Scope in = entry(subject, rawScope, Access.ADMINISTER);
        Workstream workstream = workstreams.declare(in.id(), token, description);
        PlatformLog.workstreamDeclared(PlatformLog.TechnicalAddress.of(workstream), in.log());
        return workstreamEntry(in, workstream);
    }

    /**
     * Changes a workstream's description, renames its token, or both, under
     * the conflict token of its last read. Each goes through
     * {@link WorkstreamService}, which refuses a rename while an item or a
     * milestone points at the workstream; both in one call run in one
     * transaction, the description first.
     *
     * @param token       the new token; absent where the call renames nothing
     * @param description the new description; absent where the call changes none
     */
    @Transactional
    public Entry changeWorkstream(String subject, String rawScope, long number,
                                  @Nullable String conflictToken, @Nullable String token,
                                  @Nullable String description) {
        Scope in = entry(subject, rawScope, Access.ADMINISTER);
        String presented = requireToken(conflictToken);
        UUID id = addresses.workstreamAt(in.id(), number);
        Workstream workstream = workstreams.require(in.id(), id);
        if (description != null) {
            workstream = workstreams.update(in.id(), id, description, presented);
            presented = workstream.conflictToken().orElse(presented);
        }
        if (token != null) {
            workstream = workstreams.rename(in.id(), id, token, presented);
        }
        PlatformLog.workstreamChanged(PlatformLog.TechnicalAddress.of(workstream), in.log());
        return workstreamEntry(in, workstream);
    }

    /** Withdraws a workstream under the conflict token of its last read. */
    @Transactional
    public Entry withdrawWorkstream(String subject, String rawScope, long number,
                                    @Nullable String conflictToken) {
        Scope in = entry(subject, rawScope, Access.ADMINISTER);
        String presented = requireToken(conflictToken);
        UUID id = addresses.workstreamAt(in.id(), number);
        Workstream workstream = workstreams.withdraw(in.id(), id, presented);
        PlatformLog.workstreamWithdrawn(PlatformLog.TechnicalAddress.of(workstream), in.log());
        return workstreamEntry(in, workstream);
    }

    // ======================================================================
    // The settings, the default column set among them
    // ======================================================================

    /** The scope's settings: the current iteration pointer and the default column set. */
    @Transactional
    public Entry settings(String subject, String rawScope) {
        Scope in = entry(subject, rawScope, Access.READ);
        PlatformLog.vocabularyRead(in.log());
        return settingsEntry(settings.read(in.id()));
    }

    /**
     * Opens the scope in this service: writes its settings row, with the
     * default column set where the call names one, and the scope's four
     * views. Once per scope.
     */
    @Transactional
    public Entry openSettings(String subject, String rawScope, Map<String, Object> arguments) {
        Scope in = entry(subject, rawScope, Access.ADMINISTER);
        Map<String, Object> opened = settings.create(in.id(), arguments);
        PlatformLog.settingsOpened(in.log());
        return settingsEntry(opened);
    }

    /**
     * Changes the settings -- the default column set is what a caller sets --
     * under the conflict token of their last read.
     */
    @Transactional
    public Entry changeSettings(String subject, String rawScope, @Nullable String conflictToken,
                                Map<String, Object> arguments) {
        Scope in = entry(subject, rawScope, Access.ADMINISTER);
        Map<String, Object> given = new LinkedHashMap<>(arguments);
        given.put(Field.CONFLICT_TOKEN.canonicalName(), requireToken(conflictToken));
        Map<String, Object> changed = settings.update(in.id(), given);
        PlatformLog.settingsChanged(in.log());
        return settingsEntry(changed);
    }

    // ======================================================================
    // Stage 1 and stage 2
    // ======================================================================

    /**
     * The scope name's grammar, then whether this subject sees the scope and
     * may make this call in it (C1.3).
     *
     * @param access {@link Access#READ} for a read of the model,
     *               {@link Access#ADMINISTER} for every call that writes
     */
    private Scope entry(String subject, String rawScope, Access access) {
        String slug = AddressParser.scope(rawScope);
        ScopeDirectory.ScopeAccess resolved = scopes.resolve(subject, slug, access);
        return new Scope(resolved.scopeId());
    }

    private static String requireToken(@Nullable String presented) {
        if (presented == null || presented.isBlank()) {
            throw new SurfaceException(RefusalCode.CONFLICT_TOKEN_MISSING);
        }
        return presented;
    }

    private static WorklistException undeclared(String offender) {
        return new WorklistException(WorklistException.Reason.VALUE_UNDECLARED,
            List.of(offender));
    }

    // ======================================================================
    // Answers
    // ======================================================================

    private static Entry statusEntry(ItemStatus status) {
        return new Entry(STATUSES + "/" + status.name, VocabularyFields.of(status));
    }

    private static Entry usageEntry(ScopeUsage.Property property) {
        return new Entry(USAGES + "/" + property.token(), VocabularyFields.of(property));
    }

    private Entry attributeEntry(Scope in, AttributeDefinition definition) {
        List<AttributeOption> options = AttributeDefinition.ENUMERATED.contains(definition.type)
            ? vocabulary.options(in.id(), definition.key) : List.of();
        return new Entry(ATTRIBUTES + "/" + definition.key,
            VocabularyFields.of(definition, options));
    }

    private Entry relationEntry(RelationType type) {
        ItemStatus target = type.targetStatusPk == null ? null
            : vocabulary.statusByPk(type.targetStatusPk).orElse(null);
        return new Entry(RELATION_TYPES + "/" + type.name, VocabularyFields.of(type, target));
    }

    private Entry workstreamEntry(Scope in, Workstream workstream) {
        return new Entry(WORKSTREAMS + "/" + workstream.number,
            workstreams.read(in.id(), workstream.id));
    }

    private static Entry settingsEntry(Map<String, Object> fields) {
        return new Entry(SETTINGS, fields);
    }

    /** The collections of this surface, as their paths under {@code admin} name them. */
    public static final String STATUSES = "statuses";
    public static final String USAGES = "usages";
    public static final String ATTRIBUTES = "attributes";
    public static final String RELATION_TYPES = "relation-types";
    public static final String WORKSTREAMS = "workstreams";
    public static final String SETTINGS = "settings";

    /**
     * One declared value, or the settings, as this surface answers them.
     *
     * @param path   where the value stands under {@code /api/{scope}/admin/}
     * @param fields the domain's projection of it
     */
    public record Entry(String path, Map<String, Object> fields) {

        /** The conflict token the value carries, where it carries one: a workstream, the settings. */
        public Optional<String> conflictToken() {
            return Optional.ofNullable(fields.get(Field.CONFLICT_TOKEN.canonicalName()))
                .map(String::valueOf);
        }
    }

    /** A status as a call declares it: its name, its rank and its seven predicates. */
    public record StatusDeclaration(String name, int rank, boolean actionable,
                                    boolean inProgress, boolean closed, boolean successful,
                                    boolean reviewed, boolean approved, boolean verified) {
    }

    /** An attribute as a call declares it. */
    public record AttributeDeclaration(String key, String name, String type, int rank,
                                       boolean sortable) {
    }

    /**
     * A relation type as a call declares it.
     *
     * @param targetStatus the name of the status {@code duplicate_of} closes
     *                     into; absent for every other type
     */
    public record RelationDeclaration(String name, boolean blocks, int rank,
                                      @Nullable String targetStatus) {
    }

    /** The scope a call resolved to. */
    private record Scope(UUID id) {

        PlatformLog.ScopeId log() {
            return PlatformLog.ScopeId.of(id);
        }
    }
}
