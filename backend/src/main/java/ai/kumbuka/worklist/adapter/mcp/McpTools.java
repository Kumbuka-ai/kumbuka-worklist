package ai.kumbuka.worklist.adapter.mcp;

import ai.kumbuka.worklist.domain.Selector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools the MCP exposition declares: the ten verbs this service carries, and
 * nothing else.
 *
 * <p><strong>MCP omits and never adds.</strong> There is no tool here without a
 * verb behind it. What it omits is declared rather than inferred from a shorter
 * list: the seven verbs the scheme does not carry and the six it carries unbuilt
 * are absent from {@code tools/list} and are still <em>answered by name</em> when
 * called, with the same typed refusal the REST surface gives. An unknown-tool
 * reply would send a caller looking for a spelling; a category error tells them
 * the act does not exist here, and a 501-shaped one tells them it is coming.
 *
 * <p>The declaration is written out rather than derived from the REST routes. The
 * two expositions are conformance-probed against one specification and neither is
 * the source for the other — that is share-nothing applied to the surface. A list
 * generated from the routes would make the probe an assertion that one copy
 * equals itself.
 *
 * <h2>Two argument shapes, and the difference is not cosmetic</h2>
 *
 * A verb acting on an existing object takes a <strong>complete address</strong>
 * and nothing else identifies the target. A verb that does not act on an existing
 * object — {@code create}, {@code query}, and {@code advance}, which draws from a
 * set — takes scope and selector as separate arguments, because an address
 * without an id part is reserved and giving a three-part string the meaning "the
 * objects of this scope" is what the reservation forbids.
 */
public final class McpTools {

    private McpTools() {
    }

    /** One declared tool: its name, what it does, and what it takes. */
    public record Tool(String name, String description, Map<String, Object> inputSchema) {
    }

    /** The JSON Schema types this surface's arguments take. */
    private static final String STRING = "string";
    private static final String OBJECT = "object";

    /** The argument every verb acting on an existing object takes. */
    private static final String ARG_ADDRESS = "address";

    /** The two that address a collection rather than an object. */
    private static final String ARG_SCOPE = "scope";
    private static final String ARG_SELECTOR = "selector";

    private static final String ARG_TOKEN = "conflict_token";
    private static final String ARG_FIELDS = "fields";

    private static final String SCOPE_DOC = "The scope name, a DNS label.";

    private static final String SELECTOR_DOC =
        "The view: one of " + Selector.ITEM + ", " + Selector.ITERATION + ", "
            + Selector.MILESTONE + ". Lower case; it is not folded.";

    private static final String ADDRESS_DOC =
        "The complete address: worklist://<scope>/<view>/<number>. An iteration may also "
            + "be addressed as 'current', which resolves to the iteration the scope is "
            + "working and cannot be written through. A membership carries a second id "
            + "segment: worklist://<scope>/iteration/<iteration>/<item>.";

    private static final String TOKEN_DOC =
        "The conflict token from the last read of this object. A membership presents its "
            + "ITERATION's token, because the iteration is the aggregate and a reorder of "
            + "twelve memberships must not present twelve tokens.";

    private static final String FIELDS_DOC =
        "The object's fields, under the canonical names a read answers with. A read answer "
            + "may be sent back unchanged: a field that carries the value it already has "
            + "changes nothing, and one this object does not have is refused by name rather "
            + "than dropped.";

    /**
     * The ten, in the order of the object's life rather than alphabetically: what
     * brings it into being, what reads and changes it, what ends it, and what
     * plans it.
     */
    public static List<Tool> declared() {
        return List.of(
            new Tool("create",
                "Bring an object into being in one view. Its number is allocated inside "
                    + "the transaction that inserts it and is never supplied by the "
                    + "caller; the answer carries the address it got.",
                schema(
                    required(ARG_SCOPE, STRING, SCOPE_DOC),
                    required(ARG_SELECTOR, STRING, SELECTOR_DOC),
                    required(ARG_FIELDS, OBJECT, FIELDS_DOC))),

            new Tool("read",
                "One object by address. Writes nothing.",
                schema(required(ARG_ADDRESS, STRING, ADDRESS_DOC))),

            new Tool("update",
                "Change what is known about an object. A write that changes no value "
                    + "writes nothing at all — no timestamp, no rotated token — so the "
                    + "change trail keeps meaning what it says.",
                schema(
                    required(ARG_ADDRESS, STRING, ADDRESS_DOC),
                    required(ARG_TOKEN, STRING, TOKEN_DOC),
                    required(ARG_FIELDS, OBJECT, FIELDS_DOC))),

            new Tool("query",
                "The objects of one view, oldest first for items and in the axis's own "
                    + "order for the other two. The whole set comes back; there is no "
                    + "paging and no filter yet.",
                schema(
                    required(ARG_SCOPE, STRING, SCOPE_DOC),
                    required(ARG_SELECTOR, STRING, SELECTOR_DOC))),

            new Tool("accept",
                "The intake gate. It refuses today and says why: the address it used to "
                    + "allocate is now allocated with the object, and what a business "
                    + "identifier beside it should be is an open decision about this "
                    + "store. Called rather than hidden, so the gap is visible.",
                schema(
                    required(ARG_ADDRESS, STRING, ADDRESS_DOC),
                    required(ARG_TOKEN, STRING, TOKEN_DOC))),

            new Tool("withdraw",
                "Take an item back. It keeps its address forever and moves into a status "
                    + "the scope declared as closed. NOTHING HERE IS HARD DELETED: the "
                    + "predecessor's delete removed the row, this tombstones, and the two "
                    + "are not the same act — an address that was issued must never later "
                    + "resolve to nothing.",
                schema(
                    required(ARG_ADDRESS, STRING, ADDRESS_DOC),
                    required(ARG_TOKEN, STRING, TOKEN_DOC),
                    required("status", STRING,
                        "The closed status to withdraw into, by identity. Which values a "
                            + "scope closes with is its own declaration."))),

            new Tool("close",
                "Terminal, on an iteration or a milestone. An iteration refuses while it "
                    + "still holds live memberships and names them. Never addressed at an "
                    + "item: an item's terminality is a declared status reached through "
                    + "update.",
                schema(
                    required(ARG_ADDRESS, STRING, ADDRESS_DOC),
                    required(ARG_TOKEN, STRING, TOKEN_DOC))),

            new Tool("advance",
                "Promote the first planned iteration to current. Exactly one, in the "
                    + "scope's own order — which is why it takes no address. The token is "
                    + "the scope settings' one, because the pointer being moved lives "
                    + "there.",
                schema(
                    required(ARG_SCOPE, STRING, SCOPE_DOC),
                    required(ARG_SELECTOR, STRING,
                        "The view this acts on, which is " + Selector.ITERATION + "."),
                    required(ARG_TOKEN, STRING,
                        "The conflict token of the scope's settings row."))),

            new Tool("plan",
                "Add an item to an iteration, at the end of its order. Planning it twice "
                    + "is refused rather than treated as a move: displacing a position "
                    + "somebody is holding is how the predecessor lost one.",
                schema(
                    required(ARG_ADDRESS, STRING,
                        "The membership's address: worklist://<scope>/iteration/"
                            + "<iteration>/<item>."),
                    required(ARG_TOKEN, STRING, TOKEN_DOC))),

            new Tool("unplan",
                "Remove an item from an iteration.",
                schema(
                    required(ARG_ADDRESS, STRING,
                        "The membership's address: worklist://<scope>/iteration/"
                            + "<iteration>/<item>."),
                    required(ARG_TOKEN, STRING, TOKEN_DOC))));
    }

    // ----------------------------------------------------------------------
    // The schema shapes
    // ----------------------------------------------------------------------

    private record Field(String name, String type, String description, boolean required) {
    }

    private static Field required(String name, String type, String description) {
        return new Field(name, type, description, true);
    }

    /**
     * A JSON Schema object, with {@code additionalProperties} closed.
     *
     * <p>Closed rather than open, deliberately: an argument this surface does not
     * know is one a caller believes in. Accepting and ignoring it is how a client
     * comes to depend on a field the server never read — which is the same defect
     * the canonical field naming exists against, one layer out.
     */
    private static Map<String, Object> schema(Field... fields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> mandatory = new ArrayList<>();

        for (Field f : fields) {
            properties.put(f.name(), Map.of("type", f.type(), "description", f.description()));
            if (f.required()) {
                mandatory.add(f.name());
            }
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", OBJECT);
        schema.put("properties", properties);
        schema.put("required", List.copyOf(mandatory));
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }
}
