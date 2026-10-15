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

    /**
     * The closed set of effect classes, named exactly as the router's verb
     * catalogue names them, so that the router reads them without a mapping
     * (ADR-0039). Only the classes a worklist verb takes are listed; a class
     * outside the router's set is a load error there.
     */
    public enum EffectClass {
        ITEM_READ,
        COLLECTION_READ,
        CREATE,
        FIELD_WRITE,
        GRAPH_ADD_OR_REMOVE,
        ITEM_TRANSITION,
        COLLECTION_COMPOUND
    }

    /** The shape of a verb's answer, named as the router names it. */
    public enum Answer {
        /** One object: {@code address} and {@code fields}. */
        OBJECT,
        /** One object at 201, with {@code Location}. */
        OBJECT_CREATED,
        /** A page of objects and {@code next_cursor} while more follow. */
        OBJECT_LISTING
    }

    /**
     * One verb: its name, the text that describes it, its effect class, the
     * shape of its answer, the reasons a refusal of it may name, and its
     * arguments in order.
     */
    public record Verb(VerbName name, Function<ToolDescriptions, String> description,
                       EffectClass effectClass, Answer answer, List<RefusalCode> reasons,
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
    // The reasons a refusal of a verb may name
    // ======================================================================

    /** What any call can meet before its own work begins. */
    private static final List<RefusalCode> COMMON = List.of(
        RefusalCode.ADDRESS_MALFORMED, RefusalCode.NOT_FOUND, RefusalCode.SESSION_NOT_BOUND,
        RefusalCode.SCOPE_KIND_UNSUPPORTED, RefusalCode.ARGUMENT_MISSING,
        RefusalCode.ARGUMENT_UNKNOWN, RefusalCode.ARGUMENT_INVALID,
        RefusalCode.PAYLOAD_MALFORMED, RefusalCode.UNEXPECTED_FAILURE);

    /** What a writing call can add: the scope's state, the caller's right, the view. */
    private static final List<RefusalCode> WRITE = List.of(
        RefusalCode.SCOPE_LOCKED, RefusalCode.SCOPE_READ_ONLY,
        RefusalCode.SELECTOR_UNDECLARED, RefusalCode.SELECTOR_WITHDRAWN);

    /** What a write that presents a conflict token can add. */
    private static final List<RefusalCode> TOKENED = List.of(
        RefusalCode.CONFLICT_TOKEN_MISSING, RefusalCode.CONFLICT, RefusalCode.ADDRESS_READ_ONLY);

    /** What a write of fields can add. */
    private static final List<RefusalCode> FIELD_WRITE = List.of(
        RefusalCode.SELECTOR_DECLARED_NOT_WRITTEN, RefusalCode.FIELD_RETRACTED,
        RefusalCode.FIELD_NOT_SETTABLE, RefusalCode.INVALID_VALUE, RefusalCode.VALUE_UNDECLARED,
        RefusalCode.WORKSTREAM_WITHDRAWN, RefusalCode.ITEM_WORKSTREAM_MISSING);

    // ======================================================================
    // The verbs
    // ======================================================================

    private static final List<Verb> VERBS = List.of(
        verb("create", ToolDescriptions::create, EffectClass.CREATE, Answer.OBJECT_CREATED,
            reasons(WRITE, FIELD_WRITE, codes(RefusalCode.SETTING_ABSENT,
                RefusalCode.CARDINALITY_EXCEEDED)),
            SCOPE, selector(ArgumentDescriptions::selectorCreate), FIELDS),
        verb("read", ToolDescriptions::read, EffectClass.ITEM_READ, Answer.OBJECT,
            reasons(codes(RefusalCode.SELECTOR_UNDECLARED, RefusalCode.ITERATION_ABSENT)),
            ADDRESS),
        verb("update", ToolDescriptions::update, EffectClass.FIELD_WRITE, Answer.OBJECT,
            reasons(WRITE, TOKENED, FIELD_WRITE, codes(RefusalCode.ITERATION_CLOSED)),
            ADDRESS, TOKEN, FIELDS),
        verb("query", ToolDescriptions::query, EffectClass.COLLECTION_READ, Answer.OBJECT_LISTING,
            reasons(codes(RefusalCode.SELECTOR_UNDECLARED, RefusalCode.SELECTOR_WITHDRAWN,
                RefusalCode.PREDICATE_UNKNOWN, RefusalCode.VALUE_UNDECLARED,
                RefusalCode.PAGE_SIZE_REJECTED, RefusalCode.CURSOR_MALFORMED,
                RefusalCode.CURSOR_FOREIGN, RefusalCode.SORT_UNDECLARED)),
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
        verb("accept", ToolDescriptions::accept, EffectClass.ITEM_TRANSITION, Answer.OBJECT,
            reasons(WRITE, TOKENED, codes(RefusalCode.VERB_UNCARRIED,
                RefusalCode.IDENTIFIER_UNDECIDED)),
            ADDRESS, TOKEN),
        verb("withdraw", ToolDescriptions::withdraw, EffectClass.ITEM_TRANSITION, Answer.OBJECT,
            reasons(WRITE, TOKENED, codes(RefusalCode.VERB_UNCARRIED, RefusalCode.INVALID_VALUE,
                RefusalCode.VALUE_UNDECLARED)),
            ADDRESS, TOKEN,
            required("status", ArgumentType.STRING, ArgumentDescriptions::status)),
        verb("close", ToolDescriptions::close, EffectClass.ITEM_TRANSITION, Answer.OBJECT,
            reasons(WRITE, TOKENED, codes(RefusalCode.VERB_UNCARRIED, RefusalCode.ITERATION_CLOSED,
                RefusalCode.ITERATION_INCOMPLETE, RefusalCode.ITERATION_PRODUCED_MISSING)),
            ADDRESS, TOKEN,
            optional("produced", ArgumentType.STRING, null, ArgumentDescriptions::produced)),
        verb("advance", ToolDescriptions::advance, EffectClass.COLLECTION_COMPOUND, Answer.OBJECT,
            reasons(WRITE, codes(RefusalCode.CONFLICT_TOKEN_MISSING, RefusalCode.CONFLICT,
                RefusalCode.VERB_UNCARRIED, RefusalCode.ITERATION_ABSENT,
                RefusalCode.SETTING_ABSENT)),
            SCOPE, selector(ArgumentDescriptions::selectorIteration),
            required("conflict_token", ArgumentType.STRING,
                ArgumentDescriptions::conflictTokenSetting)),
        verb("plan", ToolDescriptions::plan, EffectClass.GRAPH_ADD_OR_REMOVE, Answer.OBJECT_CREATED,
            reasons(WRITE, TOKENED, codes(RefusalCode.MEMBERSHIP_PRESENT,
                RefusalCode.ITERATION_CLOSED, RefusalCode.ITEM_UNPLANNABLE,
                RefusalCode.SETTING_ABSENT, RefusalCode.CARDINALITY_EXCEEDED)),
            MEMBERSHIP, TOKEN),
        verb("unplan", ToolDescriptions::unplan, EffectClass.GRAPH_ADD_OR_REMOVE, Answer.OBJECT,
            reasons(WRITE, TOKENED, codes(RefusalCode.ITERATION_CLOSED)),
            MEMBERSHIP, TOKEN),
        verb("claim", ToolDescriptions::claim, EffectClass.ITEM_TRANSITION, Answer.OBJECT,
            reasons(WRITE, codes(RefusalCode.VERB_UNCARRIED, RefusalCode.ADDRESS_READ_ONLY,
                RefusalCode.INVALID_VALUE, RefusalCode.CLAIM_HELD)),
            ADDRESS, DURATION),
        verb("release", ToolDescriptions::release, EffectClass.ITEM_TRANSITION, Answer.OBJECT,
            reasons(WRITE, codes(RefusalCode.VERB_UNCARRIED, RefusalCode.ADDRESS_READ_ONLY,
                RefusalCode.CLAIM_ABSENT, RefusalCode.CLAIM_RECEIPT_UNKNOWN)),
            ADDRESS,
            required("receipt", ArgumentType.STRING, ArgumentDescriptions::receipt)),
        verb("claim_next", ToolDescriptions::claimNext, EffectClass.COLLECTION_COMPOUND,
            Answer.OBJECT,
            reasons(WRITE, codes(RefusalCode.VERB_UNCARRIED, RefusalCode.DRAW_EMPTY,
                RefusalCode.CLAIM_HELD)),
            SCOPE, selector(ArgumentDescriptions::selectorItem), DURATION),
        verb("relate", ToolDescriptions::relate, EffectClass.ITEM_TRANSITION, Answer.OBJECT,
            reasons(WRITE, TOKENED, codes(RefusalCode.VERB_UNCARRIED, RefusalCode.INVALID_VALUE,
                RefusalCode.VALUE_UNDECLARED)),
            ADDRESS, TOKEN, TO_ITEM, TYPE),
        verb("unrelate", ToolDescriptions::unrelate, EffectClass.ITEM_TRANSITION, Answer.OBJECT,
            reasons(WRITE, TOKENED, codes(RefusalCode.VERB_UNCARRIED, RefusalCode.INVALID_VALUE,
                RefusalCode.VALUE_UNDECLARED)),
            ADDRESS, TOKEN, TO_ITEM, TYPE),
        verb("validate", ToolDescriptions::validate, EffectClass.COLLECTION_COMPOUND,
            Answer.OBJECT,
            reasons(codes(RefusalCode.SELECTOR_UNDECLARED, RefusalCode.SELECTOR_WITHDRAWN,
                RefusalCode.VERB_UNCARRIED)),
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
                             EffectClass effect, Answer answer, List<RefusalCode> reasons,
                             Argument... arguments) {
        return new Verb(VerbName.of(name), description, effect, answer, reasons,
            List.of(arguments));
    }

    /** The reasons every call can meet, followed by the groups given, each once. */
    @SafeVarargs
    private static List<RefusalCode> reasons(List<RefusalCode>... groups) {
        LinkedHashSet<RefusalCode> all = new LinkedHashSet<>(COMMON);
        for (List<RefusalCode> group : groups) {
            all.addAll(group);
        }
        return List.copyOf(all);
    }

    private static List<RefusalCode> codes(RefusalCode... codes) {
        return List.of(codes);
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
