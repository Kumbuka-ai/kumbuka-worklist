/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.mcp;

import ai.kumbuka.worklist.adapter.payload.CapabilitiesPayload;
import ai.kumbuka.worklist.adapter.payload.EmptyPayload;
import ai.kumbuka.worklist.adapter.payload.InitializePayload;
import ai.kumbuka.worklist.adapter.payload.ObjectPayload;
import ai.kumbuka.worklist.adapter.payload.Payloads;
import ai.kumbuka.worklist.adapter.payload.RefusalPayload;
import ai.kumbuka.worklist.adapter.payload.RpcErrorBodyPayload;
import ai.kumbuka.worklist.adapter.payload.RpcErrorDataPayload;
import ai.kumbuka.worklist.adapter.payload.RpcErrorPayload;
import ai.kumbuka.worklist.adapter.payload.RpcResultPayload;
import ai.kumbuka.worklist.adapter.payload.ServerInfoPayload;
import ai.kumbuka.worklist.adapter.payload.TextContentPayload;
import ai.kumbuka.worklist.adapter.payload.ToolListPayload;
import ai.kumbuka.worklist.adapter.payload.ToolResultPayload;
import ai.kumbuka.worklist.domain.QueryFilter;
import ai.kumbuka.worklist.domain.QuerySpec;
import ai.kumbuka.worklist.domain.WorklistException;
import ai.kumbuka.worklist.surface.AddressParser;
import ai.kumbuka.worklist.surface.CallerActor;
import ai.kumbuka.worklist.surface.ProtocolMessages;
import ai.kumbuka.worklist.surface.SurfaceException;
import ai.kumbuka.worklist.surface.UnexpectedFailures;
import ai.kumbuka.worklist.surface.VerbInput;
import ai.kumbuka.worklist.surface.VerbName;
import ai.kumbuka.worklist.surface.VerbSurface;
import ai.kumbuka.worklist.tenancy.TenantBound;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.qute.i18n.MessageBundles;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 *
 * <h2>The protocol</h2>
 *
 * JSON-RPC 2.0 at {@code POST /mcp}, protocol {@code 2025-06-18}, the methods
 * {@code initialize}, {@code ping}, {@code tools/list} and {@code tools/call}
 * (C4.1). A tool is named {@code worklist_<verb>}; the bare verb is still taken
 * on {@code tools/call} until the breaking step (C4.2). A tool name that calls
 * no verb is a fault of the protocol, -32602 with the declared tool names under
 * {@code error.data.accepts}; an unknown method is -32601 (C4.3).
 *
 * <p>Present only where the build says so: {@code kumbuka.local-mcp.enabled},
 * on unless set to {@code false} (C4.6).
 */
@IfBuildProperty(name = "kumbuka.local-mcp.enabled", stringValue = "true", enableIfMissing = true)
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

    /** The claim family's shared duration argument. */
    private static final String ARG_DURATION = "duration_seconds";

    /** The idempotency key of a create (C10.4). */
    private static final String ARG_KEY = "idempotency_key";

    /** The receipt {@code release} presents. */
    private static final String ARG_RECEIPT = "receipt";

    /** The graph verbs' target and type arguments. */
    private static final String ARG_TO_ITEM = "to_item";
    private static final String ARG_TYPE = "type";

    /** The query's filter and paging, as arguments rather than address parts. */
    private static final String ARG_FILTER = QueryFilter.FILTER_ARGUMENT;
    private static final String ARG_PAGE_SIZE = QuerySpec.PAGE_SIZE_ARGUMENT;
    private static final String ARG_AFTER = QuerySpec.AFTER_ARGUMENT;
    private static final String ARG_SORT = QuerySpec.SORT_ARGUMENT;
    private static final String ARG_DIRECTION = QuerySpec.DIRECTION_ARGUMENT;

    /** JSON-RPC's own codes. Protocol faults only — a refused verb is not one. */
    private static final int METHOD_NOT_FOUND = -32601;

    private static final int INVALID_PARAMS = -32602;

    private static final Logger LOG = Logger.getLogger(McpAdapter.class);

    @Inject VerbSurface verbs;
    @Inject CallerActor caller;
    @Inject ObjectMapper json;

    /** The service's version, from the build: the POM's version, never a literal (C4.5). */
    @Inject
    @ConfigProperty(name = "quarkus.application.version")
    String version;

    @POST
    public Response rpc(@Nullable Map<String, Object> request) {
        if (request == null || !JSONRPC.equals(request.get(KEY_JSONRPC))) {
            return error(null, INVALID_PARAMS, protocol().envelopeRequired(),
                null);
        }

        Object id = request.get(KEY_ID);
        String method = string(request, "method").orElse("");

        // A notification carries no id and takes no answer. Answering one is a
        // protocol error on our side, not a courtesy.
        if (id == null) {
            return Response.accepted().build();
        }

        return switch (method) {
            case "initialize" -> result(id, initialize());
            case "ping" -> result(id, new EmptyPayload());
            case "tools/list" -> result(id, new ToolListPayload(McpTools.declared()));
            case "tools/call" -> call(id, arguments(request, "params"));
            // The method that arrived is the caller's and never comes back
            // (DEC-0043 R19); the answer names the methods this server speaks.
            default -> error(id, METHOD_NOT_FOUND, protocol().methodUnknown(),
                null);
        };
    }

    // ======================================================================
    // The four methods
    // ======================================================================

    private InitializePayload initialize() {
        return new InitializePayload(PROTOCOL_VERSION,
            new CapabilitiesPayload(new EmptyPayload()),
            new ServerInfoPayload("kumbuka-worklist", version));
    }

    /**
     * One tool call. A name that calls no verb is a fault of the protocol and
     * answers as one (C4.3); every other outcome is the call's, made and
     * answered, refused or not.
     */
    private Response call(Object id, Map<String, Object> params) {
        Optional<String> verb = string(params, KEY_NAME).flatMap(McpTools::verbOf);
        if (verb.isEmpty()) {
            // The tool name that arrived is the caller's and never comes back
            // (DEC-0043 R19); the answer names the tools this server declares.
            return error(id, INVALID_PARAMS, protocol().toolUnknown(),
                new RpcErrorDataPayload(McpTools.names()));
        }
        return result(id, call(verb.get(), present(arguments(params, "arguments"))));
    }

    /**
     * Runs one tool call.
     *
     * <p>A refused verb comes back as {@code isError} on a successful JSON-RPC
     * response, never as a JSON-RPC error. The distinction is the protocol's and
     * it is worth keeping: a JSON-RPC error says the call could not be made, and
     * every refusal in this service is a call that was made and answered.
     *
     * <p>The route stands once the tool name has named a verb, so every
     * refusal from here on carries {@code attempted}, the tool name (C6.2,
     * C8.7) -- whichever spelling of it the call arrived under, since the
     * declared one is what this surface names.
     */
    private ToolResultPayload call(String verb, Map<String, Object> arguments) {
        String attempted = VerbName.declared(verb).toolName();
        try {
            return content(invoke(verb, arguments), false);
        } catch (SurfaceException e) {
            LOG.debugf("surface refusal: %s", e.reason().name());
            return content(RefusalPayload.of(e, attempted), true);
        } catch (WorklistException e) {
            // The same envelope the REST path answers with, built by the same
            // method — which is what makes "one envelope, whichever hop" a
            // property of the construction and not a claim about it.
            LOG.debugf("domain refusal: %s", e.reason().name());
            return content(RefusalPayload.of(e, attempted), true);
        } catch (RuntimeException e) {
            // DEC-0043 R13: a defect answers as one, with a reference the log
            // line shares, and nothing the exception said.
            return content(RefusalPayload.unexpected(UnexpectedFailures.reference(verb, e),
                attempted), true);
        }
    }

    /**
     * The arguments that arrived with a value.
     *
     * <p>DEC-0043 R17: JSON {@code null} on an argument means the argument is
     * absent. Dropping the null-valued names here, once, before any verb reads
     * them, makes "null and absent answer alike" a property of the
     * construction: an optional argument then takes its default and a
     * mandatory one is refused as {@code ARGUMENT_MISSING}, on every tool.
     */
    private static Map<String, Object> present(Map<String, Object> arguments) {
        Map<String, Object> present = new LinkedHashMap<>();
        arguments.forEach((name, value) -> {
            if (value != null) {
                present.put(name, value);
            }
        });
        return present;
    }

    /**
     * Refuses a name the tool does not declare, at the top level of its
     * arguments (DEC-0043 R4). The answer names the arguments the tool
     * declares and never the name that arrived (R19); nothing is accepted and
     * discarded. A form fault, decided before any scope is resolved (C1.3).
     */
    private static void requireDeclared(List<String> declared, Map<String, Object> in) {
        if (!declared.containsAll(in.keySet())) {
            throw SurfaceException.unknown(declared);
        }
    }

    // ======================================================================
    // The verbs
    // ======================================================================

    /** @param verb a verb this service carries or refuses by name ({@link McpTools#verbOf}) */
    private Object invoke(String verb, Map<String, Object> in) {
        McpTools.argumentsOf(verb).ifPresent(declared -> requireDeclared(declared, in));
        return switch (verb) {
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

            // Built by SPRINT_171.2, addressed at the item view.
            case "claim" -> claim(in);
            case "release" -> release(in);
            case "claim_next" -> claimNext(in);
            case "relate" -> relate(in);
            case "unrelate" -> unrelate(in);
            case "validate" -> validate(in);

            // Not in tools/list, and still answered by name.
            case "send", "append", "digest", "abandon", "block", "resume", "consume" ->
                refused(in, verb, false);

            default -> throw new IllegalStateException(
                "a verb McpTools names has no arm here");
        };
    }

    private Object create(Map<String, Object> in) {
        String scope = required(in, ARG_SCOPE);
        return dressed(scope, verbs.create(caller.subject(), scope,
            required(in, ARG_SELECTOR), fields(in).orElse(null), text(in, ARG_KEY).orElse(null)));
    }

    private Object read(Map<String, Object> in) {
        Address at = address(in);
        String member = at.member();
        return dressed(at.scope(), member != null
            ? verbs.readMembership(caller.subject(), at.scope(), at.view(), at.head(),
                member)
            : verbs.read(caller.subject(), at.scope(), at.view(), at.head()));
    }

    private Object update(Map<String, Object> in) {
        Address at = address(in);
        String token = required(in, ARG_TOKEN);
        String member = at.member();

        return dressed(at.scope(), member != null
            ? verbs.updateMembership(caller.subject(), at.scope(), at.view(), at.head(),
                member, token, fields(in).orElse(null))
            : verbs.update(caller.subject(), at.scope(), at.view(), at.head(), token,
                fields(in).orElse(null)));
    }

    private Object query(Map<String, Object> in) {
        String scope = required(in, ARG_SCOPE);
        String selector = required(in, ARG_SELECTOR);

        // The filter and the paging arrive as arguments, not as address
        // parts: an address without an id part is reserved. They are handed on
        // raw and read by the verb surface, the same reading the REST query
        // string gets — an instanceof here that fell through to "no filter" is
        // exactly how a filter sent as a JSON string used to answer the whole
        // set. An argument that is none of them is handed on too and refused by
        // name. Every sort the view declares is offered here as on REST (C4.7).
        QuerySpec spec = new QuerySpec(in.get(ARG_FILTER), in.get(ARG_PAGE_SIZE),
            in.get(ARG_AFTER), in.get(ARG_SORT), in.get(ARG_DIRECTION), List.of());
        return Payloads.of(scope,
            verbs.query(caller.subject(), scope, selector, spec));
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
        Object produced = in.get("produced");
        return dressed(at.scope(), verbs.close(caller.subject(), at.scope(), at.view(),
            at.head(), required(in, ARG_TOKEN),
            produced == null ? null : String.valueOf(produced)));
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

    private Object claim(Map<String, Object> in) {
        Address at = address(in);
        return dressed(at.scope(), verbs.claim(caller.subject(), at.scope(), at.view(),
            at.head(), new VerbInput.Lease(requireLong(in).orElse(null))));
    }
    // NOTE: the actor is derived from `subject` inside the surface and
    // handed to the domain; both adapters pass the same value along the same
    // path.

    private Object release(Map<String, Object> in) {
        Address at = address(in);
        return dressed(at.scope(), verbs.release(caller.subject(), at.scope(), at.view(),
            at.head(), new VerbInput.Release(required(in, ARG_RECEIPT))));
    }

    private Object claimNext(Map<String, Object> in) {
        String scope = required(in, ARG_SCOPE);
        return dressed(scope, verbs.claimNext(caller.subject(), scope,
            required(in, ARG_SELECTOR),
            new VerbInput.Lease(requireLong(in).orElse(null))));
    }

    private Object relate(Map<String, Object> in) {
        Address at = address(in);
        return dressed(at.scope(), verbs.relate(caller.subject(), at.scope(), at.view(),
            at.head(), required(in, ARG_TOKEN),
            new VerbInput.Edge(required(in, ARG_TO_ITEM), required(in, ARG_TYPE))));
    }

    private Object unrelate(Map<String, Object> in) {
        Address at = address(in);
        return dressed(at.scope(), verbs.unrelate(caller.subject(), at.scope(), at.view(),
            at.head(), required(in, ARG_TOKEN),
            new VerbInput.Edge(required(in, ARG_TO_ITEM), required(in, ARG_TYPE))));
    }

    private Object validate(Map<String, Object> in) {
        String scope = required(in, ARG_SCOPE);
        return dressed(scope, verbs.validate(caller.subject(), scope,
            required(in, ARG_SELECTOR)));
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
        Optional<AddressParser.Parts> at = optional(in, ARG_ADDRESS).map(AddressParser::uri);

        String scope = at.map(AddressParser.Parts::scope).orElseGet(() -> required(in, ARG_SCOPE));
        String view = at.map(AddressParser.Parts::view).orElseGet(() -> required(in, ARG_SELECTOR));
        String id = at.map(AddressParser.Parts::id).orElse(null);

        if (unbuilt) {
            verbs.unbuilt(caller.subject(), scope, view, id, verb);
        } else {
            verbs.uncarried(caller.subject(), scope, view, id, verb);
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
     *
     * @param member the membership's item segment; absent where the address
     *               names an object rather than a membership
     */
    private record Address(String scope, String view, String head, @Nullable String member) {
    }

    private Address address(Map<String, Object> in) {
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
     * <p>Absent and not an empty map, so that the surface can tell "no
     * arguments arrived" from "an empty object arrived" — the first is a call
     * that forgot something and the second is a caller asking for nothing to
     * change, which the domain answers by writing nothing at all.
     */
    @SuppressWarnings("unchecked")
    private Optional<VerbInput.Fields> fields(Map<String, Object> in) {
        Object raw = in.get(ARG_FIELDS);
        if (raw == null) {
            return Optional.empty();
        }
        if (!(raw instanceof Map)) {
            // A form fault: decided before the scope (C1.3).
            throw SurfaceException.invalid(ARG_FIELDS);
        }
        return Optional.of(new VerbInput.Fields((Map<String, Object>) raw));
    }

    private static ObjectPayload dressed(String scope, VerbSurface.Result result) {
        return Payloads.of(scope, result);
    }

    private String required(Map<String, Object> in, String name) {
        String value = optional(in, name).orElse(null);
        if (value == null) {
            visibleFirst(in, name);
            throw SurfaceException.missing(name);
        }
        if (value.isBlank()) {
            // A form fault: decided before the scope (C1.3).
            throw SurfaceException.invalid(name);
        }
        return value;
    }

    /**
     * Resolves the scope the call names, before a missing argument of the
     * call is refused: a stranger is answered NOT_FOUND for a call that left
     * an argument out, as the verb surface answers it where the REST body
     * leaves one out. A fault of form is decided before the scope (C1.3) and
     * does not come here. Where the call names no scope -- the missing
     * argument is the address or the scope itself -- there is nothing to
     * resolve and the argument's own refusal stands.
     */
    private void visibleFirst(Map<String, Object> in, @Nullable String refused) {
        Object address = ARG_ADDRESS.equals(refused) ? null : in.get(ARG_ADDRESS);
        Object scope = ARG_SCOPE.equals(refused) ? null : in.get(ARG_SCOPE);
        if (address instanceof String text && !text.isBlank()) {
            scope = AddressParser.uri(text).scope();
        }
        if (scope instanceof String named && !named.isBlank()) {
            verbs.requireVisible(caller.subject(), named);
        }
    }

    /**
     * An optional text argument; absent where the call left it out. A value of
     * another JSON type is a form fault, decided before the scope (C1.3).
     */
    private static Optional<String> text(Map<String, Object> in, String name) {
        Object value = in.get(name);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof CharSequence text)) {
            throw SurfaceException.invalid(name);
        }
        return Optional.of(text.toString());
    }

    private static Optional<String> optional(Map<String, Object> in, String name) {
        return Optional.ofNullable(in.get(name)).map(Object::toString);
    }

    /**
     * The lease duration a claim or a draw carries, as an integral number.
     *
     * <p>Read here so the domain sees a {@code long} rather than a string. JSON
     * carries numbers as their own type and a caller sending {@code 3600} does
     * not expect the surface to reject their integer as unparseable. A missing
     * or non-numeric value is a typed refusal — the domain will refuse a
     * non-positive one for its own reason.
     */
    private Optional<Long> requireLong(Map<String, Object> in) {
        Object value = in.get(ARG_DURATION);
        if (value == null) {
            // Refused by the surface, behind the scope check.
            return Optional.empty();
        }
        if (value instanceof Number number) {
            return Optional.of(number.longValue());
        }
        if (value instanceof CharSequence text) {
            try {
                return Optional.of(Long.parseLong(text.toString().trim()));
            } catch (NumberFormatException notNumeric) {
                // fall through to the refusal below
            }
        }
        // A form fault: decided before the scope (C1.3).
        throw SurfaceException.invalid(ARG_DURATION);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> arguments(@Nullable Map<String, Object> envelope, String key) {
        Object value = envelope == null ? null : envelope.get(key);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static Optional<String> string(@Nullable Map<String, Object> envelope, String key) {
        return Optional.ofNullable(envelope == null ? null : envelope.get(key)).map(Object::toString);
    }

    // ======================================================================
    // The JSON-RPC envelope
    // ======================================================================

    /**
     * A tool result.
     *
     * <p>Both {@code content} and {@code structuredContent} carry the same answer,
     * because clients read one or the other and a surface that offered only the
     * structured half would be unreadable to half of them. The text is that
     * answer as JSON, written by the mapper that writes {@code structuredContent}
     * — not the payload's {@code toString()}, which no client can parse.
     */
    private ToolResultPayload content(Object payload, boolean isError) {
        return new ToolResultPayload(List.of(TextContentPayload.text(asJson(payload))), payload,
            isError);
    }

    /**
     * The payload as JSON. Every payload is a record this service built,
     * so a failure here is ours and not the caller's: it surfaces as a 500
     * rather than as a text that silently says something else.
     */
    private String asJson(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException("an MCP answer could not be written as JSON",
                unwritable);
        }
    }

    private static Response result(Object id, Object payload) {
        return Response.ok(new RpcResultPayload(JSONRPC, id, payload)).build();
    }

    /** The texts of the protocol's own errors. */
    private static ProtocolMessages protocol() {
        return MessageBundles.get(ProtocolMessages.class);
    }

    /** @param id the request's id; absent where the request carried none to answer under */
    private static Response error(@Nullable Object id, int code, String message,
                                  @Nullable RpcErrorDataPayload data) {
        return Response.ok(new RpcErrorPayload(JSONRPC, id,
            new RpcErrorBodyPayload(code, message, data))).build();
    }
}
