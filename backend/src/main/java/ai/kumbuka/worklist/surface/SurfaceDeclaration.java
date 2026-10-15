/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.Field;
import ai.kumbuka.worklist.domain.QueryFilter;
import ai.kumbuka.worklist.domain.QuerySpec;
import ai.kumbuka.worklist.domain.SortKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The one declaration of the verbs this service carries: their names, their
 * arguments, the value sets those arguments take, and which text describes
 * each of them.
 *
 * <p>Everything a caller is told about a verb is read from here. The tool list
 * of the MCP adapter is generated from it, and the placeholder types a text may
 * carry -- {@link ArgumentName}, {@link ArgumentPath}, {@link VerbName},
 * {@link Bound} -- come into being here and nowhere else: a name that arrived
 * with a call can only become one of them by being one this declaration holds.
 *
 * <p>The texts are named, never written, here. A description is a reference to
 * a method of a message bundle; the words live in
 * {@code src/main/resources/messages/}.
 */
public final class SurfaceDeclaration {

    private SurfaceDeclaration() {
    }

    // ======================================================================
    // The shapes
    // ======================================================================

    /** The JSON type of an argument. */
    public enum ArgumentType {
        STRING("string"),
        INTEGER("integer"),
        OBJECT("object");

        private final String json;

        ArgumentType(String json) {
            this.json = json;
        }

        /** The JSON Schema name of the type. */
        public String json() {
            return json;
        }
    }

    /** One value of a fixed set an argument takes, with the text that describes it. */
    public record Value(String name, Function<ValueDescriptions, String> description) {
    }

    /**
     * One argument of a verb.
     *
     * @param defaultValue the value an optional argument takes when it is left
     *                     out or sent as null (DEC-0043 R17), as the caller
     *                     would write it; {@code null} for a mandatory one and
     *                     for an optional one whose absence means "unset"
     * @param values       the fixed set the argument takes, empty where it is free
     */
    public record Argument(ArgumentName name, ArgumentType type, boolean required,
                           String defaultValue, List<Value> values,
                           Function<ArgumentDescriptions, String> description) {
    }

    /** One verb: its name, the text that describes it, and its arguments in order. */
    public record Verb(VerbName name, Function<ToolDescriptions, String> description,
                       List<Argument> arguments) {

        /** The names of the arguments, in order. */
        public List<String> argumentNames() {
            return arguments.stream().map(a -> a.name().name()).toList();
        }
    }

    // ======================================================================
    // The bounds a text may name
    // ======================================================================

    /** The largest page a query answers. */
    public static final Bound PAGE_SIZE_MAX = Bound.of(QuerySpec.MAX_PAGE_SIZE);

    /** The page a query answers when page_size is left out. */
    public static final Bound PAGE_SIZE_DEFAULT = Bound.of(QuerySpec.DEFAULT_PAGE_SIZE);

    /** The longest title of an object, in characters. */
    public static final Bound TITLE_MAX = Bound.of(200);

    // ======================================================================
    // The arguments, once each
    // ======================================================================

    private static final Map<String, ArgumentName> NAMES = new LinkedHashMap<>();

    private static ArgumentName name(String name) {
        return NAMES.computeIfAbsent(name, ArgumentName::of);
    }

    private static Argument required(String name, ArgumentType type,
                                     Function<ArgumentDescriptions, String> description) {
        return new Argument(name(name), type, true, null, List.of(), description);
    }

    private static Argument optional(String name, ArgumentType type, String defaultValue,
                                     Function<ArgumentDescriptions, String> description) {
        return new Argument(name(name), type, false, defaultValue, List.of(), description);
    }

    private static final Argument SCOPE =
        required("scope", ArgumentType.STRING, ArgumentDescriptions::scope);
    private static final Argument ADDRESS =
        required("address", ArgumentType.STRING, ArgumentDescriptions::address);
    private static final Argument MEMBERSHIP =
        required("address", ArgumentType.STRING, ArgumentDescriptions::addressMembership);
    private static final Argument TOKEN =
        required("conflict_token", ArgumentType.STRING, ArgumentDescriptions::conflictToken);
    private static final Argument FIELDS =
        required(Field.FIELDS_ARGUMENT, ArgumentType.OBJECT, d -> d.fields(TITLE_MAX));
    private static final Argument DURATION =
        required("duration_seconds", ArgumentType.INTEGER, ArgumentDescriptions::durationSeconds);
    private static final Argument TO_ITEM =
        required("to_item", ArgumentType.STRING, ArgumentDescriptions::toItem);
    private static final Argument TYPE =
        required("type", ArgumentType.STRING, ArgumentDescriptions::type);

    private static Argument selector(Function<ArgumentDescriptions, String> description) {
        return required("selector", ArgumentType.STRING, description);
    }

    // ======================================================================
    // The verbs
    // ======================================================================

    private static final List<Verb> VERBS = List.of(
        verb("create", ToolDescriptions::create,
            SCOPE, selector(ArgumentDescriptions::selectorCreate), FIELDS),
        verb("read", ToolDescriptions::read, ADDRESS),
        verb("update", ToolDescriptions::update, ADDRESS, TOKEN, FIELDS),
        verb("query", ToolDescriptions::query,
            SCOPE, selector(ArgumentDescriptions::selectorQuery),
            optional(QueryFilter.FILTER_ARGUMENT, ArgumentType.OBJECT, null,
                ArgumentDescriptions::filter),
            optional(QuerySpec.PAGE_SIZE_ARGUMENT, ArgumentType.INTEGER,
                String.valueOf(QuerySpec.DEFAULT_PAGE_SIZE),
                d -> d.pageSize(PAGE_SIZE_MAX, PAGE_SIZE_DEFAULT)),
            optional(QuerySpec.AFTER_ARGUMENT, ArgumentType.STRING, null,
                ArgumentDescriptions::after),
            new Argument(name(QuerySpec.SORT_ARGUMENT), ArgumentType.STRING, false,
                QuerySpec.DEFAULT_SORT.wireName(),
                List.of(new Value(SortKey.ID.wireName(), ValueDescriptions::sortId),
                    new Value(SortKey.CREATED_AT.wireName(), ValueDescriptions::sortCreatedAt)),
                ArgumentDescriptions::sort),
            new Argument(name(QuerySpec.DIRECTION_ARGUMENT), ArgumentType.STRING, false,
                QuerySpec.DEFAULT_DIRECTION.wireName(),
                List.of(new Value(SortKey.Direction.ASC.wireName(), ValueDescriptions::directionAsc),
                    new Value(SortKey.Direction.DESC.wireName(),
                        ValueDescriptions::directionDesc)),
                ArgumentDescriptions::direction)),
        verb("accept", ToolDescriptions::accept, ADDRESS, TOKEN),
        verb("withdraw", ToolDescriptions::withdraw, ADDRESS, TOKEN,
            required("status", ArgumentType.STRING, ArgumentDescriptions::status)),
        verb("close", ToolDescriptions::close, ADDRESS, TOKEN,
            optional("produced", ArgumentType.STRING, null, ArgumentDescriptions::produced)),
        verb("advance", ToolDescriptions::advance,
            SCOPE, selector(ArgumentDescriptions::selectorIteration),
            required("conflict_token", ArgumentType.STRING,
                ArgumentDescriptions::conflictTokenSetting)),
        verb("plan", ToolDescriptions::plan, MEMBERSHIP, TOKEN),
        verb("unplan", ToolDescriptions::unplan, MEMBERSHIP, TOKEN),
        verb("claim", ToolDescriptions::claim, ADDRESS, DURATION),
        verb("release", ToolDescriptions::release, ADDRESS,
            required("receipt", ArgumentType.STRING, ArgumentDescriptions::receipt)),
        verb("claim_next", ToolDescriptions::claimNext,
            SCOPE, selector(ArgumentDescriptions::selectorItem), DURATION),
        verb("relate", ToolDescriptions::relate, ADDRESS, TOKEN, TO_ITEM, TYPE),
        verb("unrelate", ToolDescriptions::unrelate, ADDRESS, TOKEN, TO_ITEM, TYPE),
        verb("validate", ToolDescriptions::validate,
            SCOPE, selector(ArgumentDescriptions::selectorItem)));

    /**
     * The verbs of the worklist scheme this service refuses by name rather
     * than carries: a caller who names one is told it lies outside this
     * service, and that sentence names the verb.
     */
    private static final List<String> REFUSED_BY_NAME = List.of(
        "send", "append", "digest", "abandon", "block", "resume", "consume");

    /** The argument a protocol call names its tool under. */
    public static final String TOOL_ARGUMENT = "name";

    private static Verb verb(String name, Function<ToolDescriptions, String> description,
                             Argument... arguments) {
        return new Verb(VerbName.of(name), description, List.of(arguments));
    }

    private static final Map<String, VerbName> VERB_NAMES = verbNames();
    private static final Map<String, ArgumentPath> PATHS = paths();

    private static Map<String, VerbName> verbNames() {
        Map<String, VerbName> names = new LinkedHashMap<>();
        VERBS.forEach(v -> names.put(v.name().name(), v.name()));
        REFUSED_BY_NAME.forEach(n -> names.put(n, VerbName.of(n)));
        return Map.copyOf(names);
    }

    /**
     * Every path a refusal can concern: each declared argument, each member of
     * {@code fields} an object of this service carries, each filter a view
     * declares, and the tool argument of the protocol.
     */
    private static Map<String, ArgumentPath> paths() {
        Set<String> spelled = new LinkedHashSet<>(NAMES.keySet());
        spelled.add(TOOL_ARGUMENT);
        for (Field field : Field.values()) {
            spelled.add(field.path());
        }
        for (QueryFilter filter : QueryFilter.values()) {
            spelled.add(filter.offenderName());
        }
        Map<String, ArgumentPath> paths = new LinkedHashMap<>();
        spelled.forEach(p -> paths.put(p, ArgumentPath.of(p)));
        return Map.copyOf(paths);
    }

    // ======================================================================
    // Reading the declaration
    // ======================================================================

    /** The carried verbs, in the order the tool list gives them. */
    public static List<Verb> verbs() {
        return VERBS;
    }

    /** The carried verb of this name. */
    public static Optional<Verb> verb(String name) {
        return VERBS.stream().filter(v -> v.name().name().equals(name)).findFirst();
    }

    /** The names of the carried verbs, as placeholders. */
    public static List<VerbName> carried() {
        return VERBS.stream().map(Verb::name).toList();
    }

    /** The declared argument name of this spelling. */
    static Optional<ArgumentName> argument(String name) {
        return Optional.ofNullable(NAMES.get(name));
    }

    /** The declared path of this spelling. */
    static Optional<ArgumentPath> path(String path) {
        return Optional.ofNullable(PATHS.get(path));
    }

    /** The declared verb name of this spelling, carried or refused by name. */
    static Optional<VerbName> verbName(String name) {
        return Optional.ofNullable(VERB_NAMES.get(name));
    }

    /**
     * The declared names among these spellings -- argument paths, and the verb
     * names a refusal of an unknown tool answers with -- in their order.
     *
     * <p>A spelling the declaration lacks is left out, because it can only be
     * something a call sent: the detail of a refusal is made of the platform's
     * vocabulary alone (DEC-0043 R18). That a spelling was left out is a
     * defect of the throw site and is reported by the caller of this method.
     */
    public static List<String> declaredNames(List<String> spellings) {
        return spellings.stream()
            .filter(s -> PATHS.containsKey(s) || VERB_NAMES.containsKey(s))
            .toList();
    }

    /**
     * The declared paths among these spellings, as placeholders, in their order;
     * a spelling the declaration lacks is left out as in {@link #declaredNames}.
     */
    public static List<ArgumentPath> declaredPaths(List<String> spellings) {
        List<ArgumentPath> declared = new ArrayList<>();
        for (String spelling : spellings) {
            path(spelling).ifPresent(declared::add);
        }
        return List.copyOf(declared);
    }
}
