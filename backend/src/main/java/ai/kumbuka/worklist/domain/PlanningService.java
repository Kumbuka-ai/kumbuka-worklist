package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.repository.PlanningRepository;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
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
                "scope " + scopeId + " has no settings row, so its cardinality limits "
                    + "and its allocation counters do not exist yet. They carry no "
                    + "defaults on purpose — a limit this service invented would be a "
                    + "platform constant wearing a setting's clothes. Open the scope "
                    + "first",
                List.of(String.valueOf(scopeId)));
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
                refused.add(field.canonicalName());
            }
        }

        if (!refused.isEmpty()) {
            throw new WorklistException(
                WorklistException.Reason.FIELD_NOT_SETTABLE,
                "these fields of a " + addressed.description() + " are the service's and "
                    + "may not be changed: " + refused + ". Sending them back unaltered "
                    + "is fine — that is what a read answer carries — but the values "
                    + "given differ from the ones held. Settable here is "
                    + Field.settableNames(addressed),
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
                field.canonicalName() + " is a whole number, and " + rendered
                    + " was given",
                List.of(field.canonicalName()));
        }
    }

    /**
     * A value the caller must have supplied, refused by name when absent.
     *
     * <p>Used where the column is not null and there is nothing for this
     * service to fall back on. Inventing a value here would be the service
     * deciding something the caller is the only one who knows.
     */
    protected static String required(Field field, Object raw, String why) {
        String value = ItemFields.text(field, raw);
        if (value == null) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE, why,
                List.of(field.canonicalName()));
        }
        return value;
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
            field.canonicalName() + " is one of " + admitted + " — the platform's own "
                + "planning vocabulary, not a value the scope declares — and " + value
                + " was given",
            List.of(field.canonicalName()));
    }

    /**
     * The warning a cardinality threshold produces, or null for none.
     *
     * <p>Both numbers come from the scope's row and neither is written into
     * this file. A warning admits the write and says so; the limit beside it
     * refuses. The warning exists so that the limit is met deliberately
     * rather than discovered at the moment it refuses.
     */
    protected static String warningAt(int reached, int warnAt, int limit, String subject) {
        if (reached < warnAt) {
            return null;
        }
        return subject + " now stands at " + reached + ", at or past the warning "
            + "threshold of " + warnAt + " this scope set for itself. The hard limit is "
            + limit + ", and it refuses rather than warns";
    }

    /**
     * The hard limit, as a refusal that names both numbers.
     *
     * <p>Called before the write, so nothing is stored and then undone. The
     * limit is the scope's and the refusal says so, because a caller told
     * only "too many" has no way to tell a platform ceiling from a setting
     * they may change.
     */
    protected static void refuseBeyond(int reached, int limit, String subject) {
        if (reached <= limit) {
            return;
        }
        throw new WorklistException(
            WorklistException.Reason.CARDINALITY_EXCEEDED,
            subject + " would stand at " + reached + ", beyond the limit of " + limit
                + " this scope set for itself. The limit is a setting and not a platform "
                + "constant: raise it on the scope if the work genuinely belongs here",
            List.of(String.valueOf(limit)));
    }

    /** The warnings of one write, as the answer carries them. Empty is the normal case. */
    protected static List<String> warnings(String... produced) {
        List<String> collected = new ArrayList<>();
        for (String warning : produced) {
            if (warning != null) {
                collected.add(warning);
            }
        }
        return List.copyOf(collected);
    }
}
