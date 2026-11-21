/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.repository.PlanningRepository;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What the four planning verbs sets are made of.
 *
 * <p>Abstract, and it carries no verb of its own — {@link MilestoneService},
 * {@link IterationService}, {@link MembershipService} and
 * {@link ScopeSettingService} do. What is here is the mechanics all four
 * need, held once so that four copies cannot slowly stop agreeing about what
 * a no-op write is.
 *
 * <p>The concrete services are four rather than one because the platform
 * carries ONE verb vocabulary and it is the ADDRESS that says which object is
 * meant. {@code update} on an iteration and {@code update} on a membership
 * are the same word aimed at two different things, and in Java two methods of
 * one name that take the same arguments are one method.
 *
 * <h2>Everything below is protected, and that is load-bearing</h2>
 *
 * {@code VerbVocabularyGuardTest} reads the PUBLIC methods of a service and
 * holds them against the verb set. A helper that slipped out as public would
 * be a service-private verb the guard reports — which is the guard doing its
 * job — so the helpers are protected and the guard additionally asserts that
 * this class has no public method at all.
 *
 * <h2>The no-op rule, and why the comparison is against the projection</h2>
 *
 * A write that changes nothing writes nothing: no timestamp, no rotated
 * token, no statement. That is the item domain's measured rule and it holds
 * here unchanged. The comparison is against what a caller READ — the
 * projection — rather than against the entity, because comparing a token a
 * caller sent with a uuid the row holds would report a change every time.
 */
public abstract class PlanningService {

    /**
     * The platform constant on open iterations: at most this many per scope,
     * closed iterations not counted (REA-0011 D9). Not configurable, and not a
     * setting of the scope; it surfaces only as the refusal
     * {@code CARDINALITY_EXCEEDED}.
     */
    static final int OPEN_ITERATIONS_PER_SCOPE = 100;

    /**
     * The platform constant on memberships: at most this many per iteration,
     * a dropped membership not counted (REA-0011 D9). Not configurable either,
     * and surfacing the same way.
     */
    static final int MEMBERSHIPS_PER_ITERATION = 1000;

    @Inject PlanningRepository planning;
    @Inject Clock clock;

    /**
     * The scope's settings row, or a typed refusal naming what is missing.
     *
     * <p>Not created here: a read never writes. The row is written by the act
     * that opens the scope in this service ({@link ScopeSettingService#create}
     * or the bootstrap), together with the scope's views, so a scope that has
     * its views has its settings.
     */
    protected ScopeSetting requireSetting(UUID scopeId) {
        return requirePresent(planning.settingOf(scopeId).orElse(null));
    }

    /**
     * The scope's settings row, locked and read as it stands under the lock,
     * or the same refusal. Every write of the row loads it here, so a token
     * compared against it is compared atomically with the write (C10.2,
     * C11.1).
     */
    protected ScopeSetting requireLockedSetting(UUID scopeId) {
        return requirePresent(planning.lockSettingOf(scopeId).orElse(null));
    }

    /** @param setting the scope's row; absent where the scope was never opened */
    private static ScopeSetting requirePresent(@Nullable ScopeSetting setting) {
        if (setting == null) {
            throw new WorklistException(
                WorklistException.Reason.SETTING_ABSENT,
                List.of());
        }
        return setting;
    }

    /**
     * Refuse a read-only field that carries a DIFFERENT value, and accept one
     * that carries the value it already has.
     *
     * <p>The second half is what makes the round trip usable: a caller sending
     * a read answer back is sending {@code id}, {@code created_at} and the
     * rest along with it, and refusing all of those would mean the canonical
     * naming had bought a loud trap instead of a silent one.
     *
     * <p>Two fields are exempt. {@link Field#CONFLICT_TOKEN} was already
     * checked, by a stricter test than this one. {@link Field#WARNINGS} is a
     * statement about the write that produced it rather than about the state
     * of the row, so sending yesterday's warning back means nothing and must
     * not be a refusal.
     */
    protected static void refuseUnsettableChanges(Addressed addressed,
            Map<String, Object> current, Map<Field, Object> given) {
        List<String> refused = new ArrayList<>();
        for (Map.Entry<Field, Object> entry : given.entrySet()) {
            Field field = entry.getKey();
            if (field.settableOn(addressed) || exemptFromEchoCheck(field)) {
                continue;
            }
            if (!ItemFields.unchangedAsText(current.get(field.canonicalName()),
                    entry.getValue())) {
                refused.add(field.path());
            }
        }

        if (!refused.isEmpty()) {
            throw new WorklistException(
                WorklistException.Reason.FIELD_NOT_SETTABLE,
                refused);
        }
    }

    private static boolean exemptFromEchoCheck(Field field) {
        return field == Field.CONFLICT_TOKEN || field == Field.WARNINGS;
    }

    /** The subset a caller may actually set on the addressed object. */
    protected static Map<Field, Object> settableOnly(Addressed addressed,
            Map<Field, Object> given) {
        Map<Field, Object> settable = new EnumMap<>(Field.class);
        given.forEach((field, value) -> {
            if (field.settableOn(addressed)) {
                settable.put(field, value);
            }
        });
        return settable;
    }

    /**
     * A caller's value as a whole number, empty where the caller gave none, or
     * a refusal naming the field.
     *
     * <p>Rendered and re-read rather than cast, for the same reason the item
     * domain renders its identities: a caller echoing a read answer back may
     * hold the value as an {@code Integer}, a {@code Long} or the string it
     * became in transit, and refusing one of those would make the round trip
     * depend on which route the value took.
     */
    protected static Optional<Integer> whole(Field field, @Nullable Object raw) {
        return ItemFields.text(field, raw == null ? null : String.valueOf(raw))
            .map(rendered -> {
                try {
                    return Integer.valueOf(rendered);
                } catch (NumberFormatException notANumber) {
                    throw new WorklistException(
                        WorklistException.Reason.INVALID_VALUE,
                        List.of(field.path()));
                }
            });
    }

    /**
     * A value the caller must have supplied, refused by name when absent.
     *
     * <p>Used where the column is not null and there is nothing for this
     * service to fall back on. Inventing a value here would be the service
     * deciding something the caller is the only one who knows.
     */
    protected static String required(Field field, @Nullable Object raw) {
        return ItemFields.text(field, raw).orElseThrow(() -> new WorklistException(
            WorklistException.Reason.INVALID_VALUE,
            List.of(field.path())));
    }

    /**
     * A text value whose length is inside the field's cap, or a typed refusal
     * that NAMES the field, the cap and the length that was given.
     *
     * <p>The store carries the same cap as a check constraint (V7 for item
     * and milestone, V16 for iteration): a value over the cap is refused
     * whichever path a write took. What this check does is catch the value
     * BEFORE flush, so the refusal reaches the caller as a typed one — a
     * constraint violation would arrive at JTA commit, outside the typed
     * refusal model, and reach the caller as an internal error.
     *
     * <p>An absent value never reaches this check: nullability is the field's
     * own question and is checked separately. The refusal here says "too
     * long", not "missing".
     */
    protected static String capped(Field field, String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        throw new WorklistException(
            WorklistException.Reason.INVALID_VALUE,
            List.of(field.path()));
    }

    /**
     * The value a declared vocabulary admits, or a refusal listing what it
     * does admit.
     *
     * <p>These are the platform's own small fixed sets — a milestone's three
     * statuses, a membership's four — and not a scope's declaration. The
     * distinction is the concept's: a scope describes its own work in its own
     * words, while the planning mechanism is the platform's and is the same
     * everywhere.
     */
    protected static String oneOf(Field field, String value, List<String> admitted) {
        if (admitted.contains(value)) {
            return value;
        }
        throw new WorklistException(
            WorklistException.Reason.INVALID_VALUE,
            List.of(field.path()));
    }

    /**
     * A platform constant of planning, as a refusal with a code of its own
     * (REA-0011 D8).
     *
     * <p>Called before the write, so nothing is stored and then undone. There
     * is no warning below the constant: it surfaces only as this refusal.
     */
    protected static void refuseBeyond(int reached, int limit) {
        if (within(reached, limit)) {
            return;
        }
        throw new WorklistException(
            WorklistException.Reason.CARDINALITY_EXCEEDED,
            List.of());
    }

    /**
     * Whether a count the write would reach stays within a platform constant:
     * the decision {@link #refuseBeyond} refuses on, and the one the open
     * calls of an iteration name {@code plan} on.
     */
    protected static boolean within(int reached, int limit) {
        return reached <= limit;
    }

    /**
     * The memberships of an iteration that count against
     * {@link #MEMBERSHIPS_PER_ITERATION}: every one but a dropped one. A
     * dropped membership is what {@code unplan} leaves behind, since no row is
     * deleted, and the item it held is no longer in the iteration.
     */
    protected static int countedMemberships(List<IterationMembership> held) {
        return (int) held.stream()
            .filter(membership -> !IterationMembership.DROPPED.equals(membership.status))
            .count();
    }
}
