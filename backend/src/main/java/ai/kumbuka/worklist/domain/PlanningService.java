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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Map;
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

    @Inject PlanningRepository planning;

    /**
     * The scope's settings row, or a typed refusal naming what is missing.
     *
     * <p>Not created here. A settings row invented on first use would carry
     * cardinality limits this service chose, and V4 leaves those four columns
     * without defaults precisely so that no layer can quietly pick them: "a
     * number written at this line would be a platform constant wearing a
     * setting's clothes". A scope is opened before it plans, exactly as a
     * selector is declared before an address is allocated.
     */
    protected ScopeSetting requireSetting(UUID scopeId) {
        ScopeSetting setting = planning.settingOf(scopeId);
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
     * A caller's value as a whole number, or a refusal naming the field.
     *
     * <p>Rendered and re-read rather than cast, for the same reason the item
     * domain renders its identities: a caller echoing a read answer back may
     * hold the value as an {@code Integer}, a {@code Long} or the string it
     * became in transit, and refusing one of those would make the round trip
     * depend on which route the value took.
     */
    protected static Integer whole(Field field, Object raw) {
        String rendered = ItemFields.text(field, raw == null ? null : String.valueOf(raw));
        if (rendered == null) {
            return null;
        }
        try {
            return Integer.valueOf(rendered);
        } catch (NumberFormatException notANumber) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                List.of(field.path()));
        }
    }

    /**
     * A value the caller must have supplied, refused by name when absent.
     *
     * <p>Used where the column is not null and there is nothing for this
     * service to fall back on. Inventing a value here would be the service
     * deciding something the caller is the only one who knows.
     */
    protected static String required(Field field, Object raw) {
        String value = ItemFields.text(field, raw);
        if (value == null) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                List.of(field.path()));
        }
        return value;
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
     * <p>Null is passed through: nullability is the field's own question and
     * is checked separately. The refusal here says "too long", not "missing".
     */
    protected static String capped(Field field, String value, int max) {
        if (value == null || value.length() <= max) {
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
     * The warning a cardinality threshold produces, or null for none.
     *
     * <p>Both numbers come from the scope's row and neither is written into
     * this file. A warning admits the write and says so; the limit beside it
     * refuses. The warning exists so that the limit is met deliberately
     * rather than discovered at the moment it refuses.
     */
    protected static Notice warningAt(int reached, int warnAt, Notice notice) {
        // A fixed notice: the numbers are the scope's settings and travel in
        // the read of those settings, never in a statement of the platform
        // (DEC-0043 R18).
        return reached < warnAt ? null : notice;
    }

    /**
     * The hard limit, as a refusal that names both numbers.
     *
     * <p>Called before the write, so nothing is stored and then undone. The
     * limit is the scope's and the refusal says so, because a caller told
     * only "too many" has no way to tell a platform ceiling from a setting
     * they may change.
     */
    protected static void refuseBeyond(int reached, int limit) {
        if (reached <= limit) {
            return;
        }
        throw new WorklistException(
            WorklistException.Reason.CARDINALITY_EXCEEDED,
            List.of());
    }

    /** The warnings of one write, as the answer carries them. Empty is the normal case. */
    protected static List<Notice> warnings(Notice... produced) {
        return Arrays.stream(produced).filter(Objects::nonNull).toList();
    }
}
