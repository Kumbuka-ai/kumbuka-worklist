/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.repository.VocabularyRepository;
import jakarta.enterprise.inject.Vetoed;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The rule of REA-0011 D18 at the declaration of {@code duplicate_of}, held by
 * {@link VocabularyRegistry} for every entry that declares through it, the
 * administration surface among them (REA-0011 S4, K6): the type takes a
 * target status that is declared, not withdrawn, {@code closed} and not
 * {@code successful}, and a declaration that breaks it writes nothing.
 *
 * <p>The repository is a stand-in holding the statuses of one scope and
 * recording what the registry inserts; the same cases run against the
 * database in {@code PropertyCoreIT}. The statuses and the expected refusals
 * are written here from D18.
 */
class DuplicateTargetDeclarationTest {

    private static final UUID SCOPE = UUID.fromString("0c0a11e5-0000-4000-8000-00000000d180");

    /** The repository, holding four statuses and recording every insert. */
    @Vetoed
    private static final class Statuses extends VocabularyRepository {
        final List<Object> inserted = new ArrayList<>();
        final Map<String, ItemStatus> byName = new LinkedHashMap<>();

        Statuses() {
            // name, closed, successful, withdrawn
            put("obsolete", 1L, true, false, false);
            put("done", 2L, true, true, false);
            put("planned", 3L, false, false, false);
            put("retired", 4L, true, false, true);
        }

        private void put(String name, long pk, boolean closed, boolean successful,
                         boolean withdrawn) {
            ItemStatus status = new ItemStatus();
            status.pk = pk;
            status.scopeId = SCOPE;
            status.name = name;
            status.closed = closed;
            status.successful = successful;
            status.status = withdrawn ? DeclaredValue.WITHDRAWN : DeclaredValue.DECLARED;
            byName.put(name, status);
        }

        @Override
        public Optional<ItemStatus> statusByName(@Nullable UUID scopeId, @Nullable String name) {
            return Optional.ofNullable(byName.get(name));
        }

        @Override
        public Optional<RelationType> relationTypeByName(@Nullable UUID scopeId,
                                                         @Nullable String name) {
            return Optional.empty();
        }

        @Override
        public <T extends DeclaredValue> T insert(T declaration) {
            inserted.add(declaration);
            return declaration;
        }
    }

    @Test
    void duplicate_of_is_refused_without_a_valid_target_and_writes_nothing() {
        Map<String, WorklistException.Reason> expected = new LinkedHashMap<>();
        expected.put("absent", WorklistException.Reason.ARGUMENT_MISSING);
        expected.put("never-declared", WorklistException.Reason.VALUE_UNDECLARED);
        expected.put("retired", WorklistException.Reason.VALUE_UNDECLARED);
        expected.put("planned", WorklistException.Reason.INVALID_VALUE);
        expected.put("done", WorklistException.Reason.INVALID_VALUE);

        expected.forEach((target, reason) -> {
            Statuses repository = new Statuses();
            VocabularyRegistry registry = registryOver(repository);
            Throwable thrown = catchThrowable(() -> registry.declareRelationType(SCOPE,
                RelationType.DUPLICATE_OF, false, 1, "absent".equals(target) ? null : target));

            assertThat(thrown).as("target %s", target).isInstanceOf(WorklistException.class);
            WorklistException refusal = (WorklistException) thrown;
            assertThat(refusal.reason()).as("target %s", target).isEqualTo(reason);
            assertThat(refusal.offenders()).as("target %s", target)
                .containsExactly("target_status");
            assertThat(repository.inserted).as("target %s writes nothing", target).isEmpty();
        });
    }

    @Test
    void duplicate_of_with_a_closed_unsuccessful_target_is_written_with_it() {
        Statuses repository = new Statuses();
        RelationType type = registryOver(repository).declareRelationType(SCOPE,
            RelationType.DUPLICATE_OF, false, 1, "obsolete");

        assertThat(type.targetStatusPk).as("the target is obsolete's surrogate").isEqualTo(1L);
        assertThat(repository.inserted).containsExactly(type);
    }

    @Test
    void another_type_takes_no_target_and_needs_none() {
        Statuses repository = new Statuses();
        Throwable thrown = catchThrowable(() -> registryOver(repository)
            .declareRelationType(SCOPE, "depends_on", true, 1, "obsolete"));
        assertThat(thrown).isInstanceOf(WorklistException.class);
        assertThat(((WorklistException) thrown).reason())
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(repository.inserted).isEmpty();

        RelationType plain = registryOver(repository)
            .declareRelationType(SCOPE, "depends_on", true, 1);
        assertThat(plain.targetStatusPk).isNull();
        assertThat(repository.inserted).containsExactly(plain);
    }

    private static VocabularyRegistry registryOver(VocabularyRepository repository) {
        VocabularyRegistry registry = new VocabularyRegistry();
        registry.vocabulary = repository;
        return registry;
    }
}
