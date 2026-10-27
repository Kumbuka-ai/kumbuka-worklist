/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Two writers present the same conflict token: exactly one writes, and the
 * other is refused with {@code CONFLICT} and the current token (C10.2).
 *
 * <p>The comparison of the presented token and the write happen under the
 * row lock, refreshed under it (C11.1). Each case makes the two writers
 * overlap for real: the first holds its write in an open transaction while
 * the second is started, and the database is asked whether the second waits
 * on the first ({@link LockWaits}) before the first commits. Without the lock
 * the second reads the committed row, finds its token current, and writes
 * too — it waits only on the row lock of the first one's UPDATE, and once
 * that commits it writes over it.
 *
 * <p>RED STATE, observed against worklist {@code b20d27f}, where the
 * aggregate is read with a bare {@code em.find} and compared in memory: in
 * both cases the second writer wrote as well, and each case failed on the
 * refusal it expected, with "the second writer must be refused, not write: Expecting actual not to be null".
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ConcurrentWriteIT {

    private UUID scope;
    private String openStatus;

    @Inject ItemService items;
    @Inject IterationService iterations;
    @Inject ScopeSettingService settings;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject EntityManager em;

    @BeforeEach
    void aScopeOfItsOwn() {
        scope = UUID.randomUUID();
        selectors.declare(scope, Selector.ITEM);
        openStatus = vocabulary.declareStatus(scope, "open", 1,
            true, false, false, false).name;
        settings.create(scope, Map.of(
            "max_planned_iterations", 10,
            "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10,
            "warn_memberships_per_iteration", 9));
    }

    @Test
    void two_writers_with_one_token_on_an_item_exactly_one_writes() throws Exception {
        Map<String, Object> created = items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "contested",
            Field.STATUS.canonicalName(), openStatus));
        UUID id = idOf(created);
        String token = tokenOf(created);

        contest(title -> items.update(scope, id, Map.of(
            Field.TITLE.canonicalName(), title,
            Field.CONFLICT_TOKEN.canonicalName(), token)));

        assertThat(items.read(scope, id).get(Field.TITLE.canonicalName()))
            .as("the first writer's change stands").isEqualTo("first");
    }

    @Test
    void two_writers_with_one_token_on_an_iteration_exactly_one_writes() throws Exception {
        Map<String, Object> created = iterations.create(scope, Map.of(
            Field.TITLE.canonicalName(), "contested",
            Field.MOTTO.canonicalName(), "a motto",
            Field.DESCRIPTION.canonicalName(), "a description"));
        UUID id = idOf(created);
        String token = tokenOf(created);

        contest(title -> iterations.update(scope, id, Map.of(
            Field.TITLE.canonicalName(), title,
            Field.CONFLICT_TOKEN.canonicalName(), token)));

        assertThat(iterations.read(scope, id).get(Field.TITLE.canonicalName()))
            .as("the first writer's change stands").isEqualTo("first");
    }

    // -----------------------------------------------------------------------

    /**
     * Runs {@code write} as the first writer, with title {@code first}, in an
     * open transaction; starts it as the second, with title {@code second}, on
     * a thread of its own; checks that the second waits on the first; commits
     * the first; and requires the second to be refused as stale.
     */
    private void contest(Function<String, Map<String, Object>> write) throws Exception {
        ExecutorService second = Executors.newSingleThreadExecutor();
        Future<Map<String, Object>> secondWrite;
        try {
            QuarkusTransaction.begin();
            try {
                write.apply("first");
                int holder = LockWaits.sessionOf(em);

                secondWrite = second.submit(() -> onItsOwnThread(() -> write.apply("second")));

                assertThat(LockWaits.waitsOn(holder, secondWrite))
                    .as("the second writer met the first one's lock; otherwise this case "
                        + "shows nothing about the two overlapping")
                    .isTrue();
            } finally {
                QuarkusTransaction.commit();
            }

            Throwable refused = catchThrowable(() -> secondWrite.get(30, TimeUnit.SECONDS));
            assertThat(refused).as("the second writer must be refused, not write")
                .isInstanceOf(ExecutionException.class);
            assertThat(refused.getCause()).isInstanceOf(WorklistException.class);
            assertThat(((WorklistException) refused.getCause()).reason())
                .isEqualTo(WorklistException.Reason.CONFLICT);
        } finally {
            second.shutdownNow();
        }
    }

    /** Runs one call with a request context of its own, on the calling thread. */
    private static <T> T onItsOwnThread(Callable<T> call) throws Exception {
        var request = Arc.container().requestContext();
        request.activate();
        try {
            return call.call();
        } finally {
            request.terminate();
        }
    }

    private static UUID idOf(Map<String, Object> fields) {
        return UUID.fromString(String.valueOf(fields.get(Field.ID.canonicalName())));
    }

    private static String tokenOf(Map<String, Object> fields) {
        return String.valueOf(fields.get(Field.CONFLICT_TOKEN.canonicalName()));
    }
}
