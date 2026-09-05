package ai.kumbuka.worklist.adapter.mcp;

import ai.kumbuka.worklist.adapter.payload.Payloads;
import ai.kumbuka.worklist.domain.WorklistException;
import ai.kumbuka.worklist.surface.AddressParser;
import ai.kumbuka.worklist.surface.CallerActor;
import ai.kumbuka.worklist.surface.SurfaceException;
import ai.kumbuka.worklist.surface.VerbInput;
import ai.kumbuka.worklist.surface.VerbSurface;
import ai.kumbuka.worklist.tenancy.TenantBound;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The MCP exposition of the verb surface: a projection that omits and adds
 * nothing.
 *
 * <p>It calls {@link VerbSurface} and reimplements no verb, so the two
 * expositions cannot drift on what an act does or in which order it checks. What
 * differs is only expression — JSON-RPC over one endpoint here, method and path
 * there.
 *
 * <h2>Why the address arrives complete, and the REST path does not</h2>
 *
 * MCP is JSON-RPC over a single endpoint: tool name and arguments necessarily
 * travel in the body, and there is no request line an address could travel in. So
 * the address arrives whole, scheme included, and this adapter validates it. The
 * REST adapter constructs the address from the path instead. The two are
 * asymmetric by nature rather than by accident, and neither is a round trip of
 * the other.
 *
 * <h2>The thirteen verbs that do not act are not simply missing</h2>
 *
 * They are absent from {@code tools/list}, which is what "MCP omits" means. But a
 * call naming one of them answers the same typed refusal the REST surface
 * answers, rather than "unknown tool" — and the two classes are answered apart:
 * a verb the scheme does not carry is a category error, and one it carries
 * unbuilt says so. The difference matters: unknown tool says the caller mistyped,
 * a category error says the act does not exist in this scheme, and neither of
 * those is "not yet".
 */
@Path("/mcp")
@Authenticated
@TenantBound
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class McpAdapter {

    /** The revision of the MCP protocol this adapter speaks. */
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private static final String JSONRPC = "2.0";

    /** The envelope's version field, by name. */
    private static final String KEY_JSONRPC = "jsonrpc";
    private static final String KEY_ID = "id";
    private static final String KEY_NAME = "name";

    /** The arguments a verb takes when it acts on no existing object. */
    private static final String ARG_SCOPE = "scope";
    private static final String ARG_SELECTOR = "selector";
    private static final String ARG_ADDRESS = "address";
    private static final String ARG_TOKEN = "conflict_token";
    private static final String ARG_FIELDS = "fields";

    /** JSON-RPC's own codes. Protocol faults only — a refused verb is not one. */
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;

    /**
     * The sentences the two classes of non-acting verb are refused with.
     *
     * <p>Written once and handed to the surface, which owns the refusal. Two
     * adapters explaining the same refusal in their own words is how two callers
     * come to believe two different things about one surface.
     */
    private static final String UNCARRIED_WHY =
        "The capability declaration gives this scheme the object lifecycle, the planning "
            + "verbs, the claim family, the graph verbs and validate. The commitment gate "
            + "it carries is accept and not send: an item is never frozen, so there is "
            + "nothing for an author to commit outward and nothing for an addendum to hang "
            + "off.";

    private static final String UNBUILT_WHY =
        "It is declared, it is specified, and no code answers it yet. That is a different "
            + "sentence from 'this scheme does not have it', which is why it does not get "
            + "the same answer: waiting for this one is reasonable.";

    @Inject VerbSurface verbs;
    @Inject CallerActor caller;

    @POST
    public Response rpc(Map<String, Object> request) {
        if (request == null || !JSONRPC.equals(request.get(KEY_JSONRPC))) {
            return error(null, INVALID_PARAMS, "a JSON-RPC 2.0 envelope is required");
        }

        Object id = request.get(KEY_ID);
        String method = string(request, "method");

        // A notification carries no id and takes no answer. Answering one is a
        // protocol error on our side, not a courtesy.
        if (id == null) {
            return Response.accepted().build();
        }

        return switch (method == null ? "" : method) {
            case "initialize" -> result(id, initialize());
            case "tools/list" -> result(id, Map.of("tools", tools()));
            case "tools/call" -> result(id, call(arguments(request, "params")));
            default -> error(id, METHOD_NOT_FOUND,
                "'" + method + "' is not a method of this server. It speaks initialize, "
                    + "tools/list and tools/call.");
        };
    }

    // ======================================================================
    // The three methods
    // ======================================================================

    private static Map<String, Object> initialize() {
        return Map.of(
            "protocolVersion", PROTOCOL_VERSION,
            "capabilities", Map.of("tools", Map.of()),
            "serverInfo", Map.of("name", "kumbuka-worklist", "version", "0.1.0"));
    }

    /** The declared tools, in the shape MCP asks for them. */
    private static List<Map<String, Object>> tools() {
        return McpTools.declared().stream()
            .map(t -> Map.<String, Object>of(
                KEY_NAME, t.name(),
                "description", t.description(),
                "inputSchema", t.inputSchema()))
            .toList();
    }

    /**
     * Runs one tool call.
     *
     * <p>A refused verb comes back as {@code isError} on a successful JSON-RPC
     * response, never as a JSON-RPC error. The distinction is the protocol's and
     * it is worth keeping: a JSON-RPC error says the call could not be made, and
     * every refusal in this service is a call that was made and answered.
     */
    private Map<String, Object> call(Map<String, Object> params) {
        String tool = string(params, KEY_NAME);
        Map<String, Object> arguments = arguments(params, "arguments");

        try {
            return content(invoke(tool, arguments), false);
        } catch (SurfaceException e) {
            return content(Payloads.Refusal.of(e.reason().name(), e.getMessage()), true);
        } catch (WorklistException e) {
            return content(new Payloads.Refusal(e.reason().name(), e.getMessage(),
                e.offenders()), true);
        }
    }

    // ======================================================================
    // The verbs
    // ======================================================================

    private Object invoke(String tool, Map<String, Object> in) {
        return switch (tool == null ? "" : tool) {
            case "create" -> create(in);
            case "read" -> read(in);
            case "update" -> update(in);
            case "query" -> query(in);
            case "accept" -> accept(in);
            case "withdraw" -> withdraw(in);
            case "close" -> close(in);
            case "advance" -> advance(in);
            case "plan" -> plan(in);
            case "unplan" -> unplan(in);

            // Not in tools/list, and still answered by name.
            case "send", "append", "digest", "abandon", "block", "resume", "consume" ->
                refused(in, tool, false);
            case "claim", "claim_next", "release", "relate", "unrelate", "validate" ->
                refused(in, tool, true);

            default -> throw new SurfaceException(SurfaceException.Reason.PAYLOAD_MALFORMED,
                "'" + tool + "' is not a tool of this server. Its tools are the verbs of "
                    + "the worklist scheme, and tools/list names them.");
        };
    }

    private Object create(Map<String, Object> in) {
        String scope = required(in, ARG_SCOPE);
        return dressed(scope, verbs.create(caller.subject(), scope,
            required(in, ARG_SELECTOR), fields(in)));
    }

    private Object read(Map<String, Object> in) {
        Address at = address(in);
        return dressed(at.scope(), at.isMembership()
            ? verbs.readMembership(caller.subject(), at.scope(), at.view(), at.head(),
                at.member())
            : verbs.read(caller.subject(), at.scope(), at.view(), at.head()));
    }

    private Object update(Map<String, Object> in) {
        Address at = address(in);
        String token = required(in, ARG_TOKEN);

        return dressed(at.scope(), at.isMembership()
            ? verbs.updateMembership(caller.subject(), at.scope(), at.view(), at.head(),
                at.member(), token, fields(in))
            : verbs.update(caller.subject(), at.scope(), at.view(), at.head(), token,
                fields(in)));
    }

    private Object query(Map<String, Object> in) {
        String scope = required(in, ARG_SCOPE);
        return Payloads.of(scope,
            verbs.query(caller.subject(), scope, required(in, ARG_SELECTOR)));
    }

    private Object accept(Map<String, Object> in) {
        Address at = address(in);
        return dressed(at.scope(), verbs.accept(caller.subject(), at.scope(), at.view(),
            at.head(), required(in, ARG_TOKEN)));
    }

    private Object withdraw(Map<String, Object> in) {
        Address at = address(in);
        return dressed(at.scope(), verbs.withdraw(caller.subject(), at.scope(), at.view(),
            at.head(), required(in, ARG_TOKEN),
            new VerbInput.Withdrawal(required(in, "status"))));
    }

    private Object close(Map<String, Object> in) {
        Address at = address(in);
        return dressed(at.scope(), verbs.close(caller.subject(), at.scope(), at.view(),
            at.head(), required(in, ARG_TOKEN)));
    }

    private Object advance(Map<String, Object> in) {
        String scope = required(in, ARG_SCOPE);
        return dressed(scope, verbs.advance(caller.subject(), scope,
            required(in, ARG_SELECTOR), required(in, ARG_TOKEN)));
    }

    private Object plan(Map<String, Object> in) {
        Address at = address(in);
        return dressed(at.scope(), verbs.plan(caller.subject(), at.scope(), at.view(),
            at.head(), at.member(), required(in, ARG_TOKEN)));
    }

    private Object unplan(Map<String, Object> in) {
        Address at = address(in);
        return dressed(at.scope(), verbs.unplan(caller.subject(), at.scope(), at.view(),
            at.head(), at.member(), required(in, ARG_TOKEN)));
    }

    /**
     * The thirteen that do not act.
     *
     * <p>The address is optional here, and that is deliberate: a caller reaching
     * for a verb this scheme does not have has often not worked out what it would
     * be addressed at either, and demanding an address first would answer a
     * question about the address when the answer they need is about the verb.
     * What is not optional is the scope, because the refusal sits behind scope
     * visibility.
     */
    private Object refused(Map<String, Object> in, String verb, boolean unbuilt) {
        String raw = optional(in, ARG_ADDRESS);
        AddressParser.Parts at = raw == null ? null : AddressParser.uri(raw);

        String scope = at == null ? required(in, ARG_SCOPE) : at.scope();
        String view = at == null ? required(in, ARG_SELECTOR) : at.view();
        String id = at == null ? null : at.id();

        if (unbuilt) {
            verbs.unbuilt(caller.subject(), scope, view, id, verb, UNBUILT_WHY);
        } else {
            verbs.uncarried(caller.subject(), scope, view, id, verb, UNCARRIED_WHY);
        }
        throw new IllegalStateException("a verb that does not act returned instead of refusing");
    }

    // ======================================================================
    // Arguments
    // ======================================================================

    /**
     * An address taken apart, with the membership's second segment separated.
     *
     * <p>The split happens here rather than in the surface because the surface
     * receives the parts the REST path already carries separately. Doing it the
     * other way — a surface that took one string — would make the REST adapter
     * join what it had, which is a round trip through a form neither side needs.
     */
    private record Address(String scope, String view, String head, String member) {

        boolean isMembership() {
            return member != null;
        }
    }

    private static Address address(Map<String, Object> in) {
        AddressParser.Parts parts = AddressParser.uri(required(in, ARG_ADDRESS));
        int at = parts.id().indexOf('/');
        return at < 0
            ? new Address(parts.scope(), parts.view(), parts.id(), null)
            : new Address(parts.scope(), parts.view(), parts.id().substring(0, at),
                parts.id().substring(at + 1));
    }

    /**
     * The field map of a write.
     *
     * <p>Absent is null and not an empty map, so that the surface can tell "no
     * arguments arrived" from "an empty object arrived" — the first is a call
     * that forgot something and the second is a caller asking for nothing to
     * change, which the domain answers by writing nothing at all.
     */
    @SuppressWarnings("unchecked")
    private static VerbInput.Fields fields(Map<String, Object> in) {
        Object raw = in.get(ARG_FIELDS);
        return raw instanceof Map ? new VerbInput.Fields((Map<String, Object>) raw) : null;
    }

    private static Payloads.ObjectResponse dressed(String scope, VerbSurface.Result result) {
        return Payloads.of(scope, result);
    }

    private static String required(Map<String, Object> in, String name) {
        String value = optional(in, name);
        if (value == null || value.isBlank()) {
            throw new SurfaceException(SurfaceException.Reason.PAYLOAD_MALFORMED,
                "the argument '" + name + "' is required and did not arrive.");
        }
        return value;
    }

    private static String optional(Map<String, Object> in, String name) {
        Object value = in.get(name);
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> arguments(Map<String, Object> envelope, String key) {
        Object value = envelope == null ? null : envelope.get(key);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static String string(Map<String, Object> envelope, String key) {
        Object value = envelope == null ? null : envelope.get(key);
        return value == null ? null : value.toString();
    }

    // ======================================================================
    // The JSON-RPC envelope
    // ======================================================================

    /**
     * A tool result.
     *
     * <p>Both {@code content} and {@code structuredContent} carry the same answer,
     * because clients read one or the other and a surface that offered only the
     * structured half would be unreadable to half of them.
     */
    private static Map<String, Object> content(Object payload, boolean isError) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", String.valueOf(payload))));
        result.put("structuredContent", payload);
        result.put("isError", isError);
        return result;
    }

    private static Response result(Object id, Object payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(KEY_JSONRPC, JSONRPC);
        envelope.put(KEY_ID, id);
        envelope.put("result", payload);
        return Response.ok(envelope).build();
    }

    private static Response error(Object id, int code, String message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(KEY_JSONRPC, JSONRPC);
        envelope.put(KEY_ID, id);
        envelope.put("error", Map.of("code", code, "message", message));
        return Response.ok(envelope).build();
    }
}
