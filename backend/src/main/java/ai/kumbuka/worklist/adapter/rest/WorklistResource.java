/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.rest;

import ai.kumbuka.worklist.adapter.payload.ArgumentsPayload;
import ai.kumbuka.worklist.adapter.payload.Payloads;
import ai.kumbuka.worklist.domain.QueryFilter;
import ai.kumbuka.worklist.domain.QuerySpec;
import ai.kumbuka.worklist.surface.CallerActor;
import ai.kumbuka.worklist.surface.RefusalCode;
import ai.kumbuka.worklist.surface.SurfaceDeclaration;
import ai.kumbuka.worklist.surface.SurfaceException;
import ai.kumbuka.worklist.surface.VerbInput;
import ai.kumbuka.worklist.surface.VerbSurface;
import ai.kumbuka.worklist.tenancy.TenantBound;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The REST exposition of the verb surface.
 *
 * <p>The verbs the scheme carries on their outward forms; the seven it does not
 * carry, each answering by name; and a writing verb on a truncated address
 * answering 405 with {@code Allow}. Nothing else. A conformance probe checks both
 * halves of that — coverage and closure — against a specification this class
 * cannot edit.
 *
 * <h2>This is a front door, not an inner leg</h2>
 *
 * The community edition has no facade and addresses the services directly, so
 * this surface is the published contract of a copyleft-licensed product from its
 * first day. That is the reason for the strictness about form and closure: a
 * route added here casually is a route somebody depends on.
 *
 * <h2>The path is the address space, and the colon is forced</h2>
 *
 * The scheme is not part of the path — it is the routing decision one layer out —
 * so what this service sees is {@code {scope}/{view}/{id}}, with the id running
 * to a second segment where it names a membership. A transition is a custom
 * method in colon notation because the id part may be multi-segment and a
 * trailing verb segment could not be told apart from a further id segment.
 *
 * <p>The colon is split off in {@link CustomMethod} rather than routed by the
 * framework, because the framework cannot: a path template takes the whole
 * segment it appears in, so one {@code @Path("{id}:verb")} route per verb would
 * collapse into one. That measurement is recorded on {@code CustomMethod}. The
 * outward form is unchanged and it is the outward form that is probed.
 *
 * <h2>Expression, not offering</h2>
 *
 * REST conventions are followed in how a verb is expressed and not in what is
 * offered: what the verb set lacks is not offered even where the convention
 * expects it. There is no DELETE on an object — the one DELETE below removes a
 * membership, which is {@code unplan} and is a verb — and a transition addressed
 * at a collection answers 405 rather than being routed somewhere plausible.
 *
 * <p>The acts themselves are in {@link VerbSurface}, which the MCP exposition
 * calls too. This class holds their HTTP expression and nothing else, so an
 * omission there is an omission for both, and an addition here would be an
 * addition with no act behind it.
 */
@Path("/api/{scope}")
@Authenticated
@TenantBound
@Produces(MediaType.APPLICATION_JSON)
public class WorklistResource {

    /** The query parameters of {@code query} besides the filter. */
    private static final java.util.Set<String> PAGING_ARGUMENTS = java.util.Set.of(
        QuerySpec.PAGE_SIZE_ARGUMENT, QuerySpec.AFTER_ARGUMENT, QuerySpec.SORT_ARGUMENT,
        QuerySpec.DIRECTION_ARGUMENT);

    /**
     * Names the surface the caller came through, where that is not this one: a
     * router publishing a call as an assistant tool says so here. The name and
     * its one value are kumbuka-dispatch's, so a router sends one header to
     * both services. Any other value, or the header twice, is refused before
     * the request reaches a route ({@link SurfaceHeaderFilter}).
     */
    static final String SURFACE = "Kumbuka-Surface";

    /**
     * The header the idempotency key of a create travels in on this surface
     * (C10.4); the MCP surface carries it as the argument {@code
     * idempotency_key}.
     */
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /** The one value {@link #SURFACE} takes: the assistant surface. */
    static final String ASSISTANT = "assistant";

    @Inject VerbSurface verbs;
    @Inject CallerActor caller;
    @Inject ObjectMapper json;
    @Inject CallContext context;
    @Context HttpHeaders headers;

    // ======================================================================
    // The collection binding: create, query, and the collection transitions
    // ======================================================================

    /**
     * POST at collection depth.
     *
     * <p>The body arrives as text and is deserialised only once the verb is
     * known. That order is deliberate and it is what makes the 405 reachable: a
     * typed body parameter is deserialised before the method is entered, so a
     * transition wrongly addressed at a collection would answer 415 about its
     * content type instead of 405 about its address — a refusal about the wrong
     * thing entirely.
     *
     * <p>{@code create} takes the query parameter {@code submit}, which
     * submits a new item in the same act (REA-0011 D12): {@code true} or
     * {@code false}, any other value a fault of form decided before the scope.
     * A call that names the assistant surface creates and submits in two acts
     * (TAR-0002 B12), so there the parameter is refused as an argument the
     * tool does not declare, as the MCP adapter refuses it.
     */
    @POST
    @Path("{selector}")
    public Response collectionPost(@PathParam("scope") String scope,
                                   @PathParam("selector") String segment,
                                   @HeaderParam("If-Match") @Nullable String ifMatch,
                                   @HeaderParam(IDEMPOTENCY_KEY) @Nullable String key,
                                   @QueryParam(SurfaceDeclaration.SUBMIT_ARGUMENT)
                                   @Nullable String submit,
                                   @Nullable String body) {
        var split = CustomMethod.split(segment, CustomMethod.Depth.COLLECTION);
        if (split.isEmpty()) {
            // No colon: a plain collection address, and create is the one
            // writing verb whose set semantics is declared as exactly one.
            calling(CREATE);
            boolean submitting = submitting(submit);
            return created(scope, verbs.create(caller.subject(), scope, segment,
                ArgumentsPayload.object(json, body).map(a -> Payloads.fields(a.values())).orElse(null),
                key, submitting));
        }

        CustomMethod.Split at = split.get();
        if (!at.isKnown()) {
            // Every other verb acts at item depth. Depth is declared per verb and
            // undeclared means complete address only, so a range capability never
            // comes into existence by omission.
            throw new SurfaceException(RefusalCode.WRITE_ON_TRUNCATED_ADDRESS,
                CustomMethod.allow(CustomMethod.Depth.COLLECTION));
        }

        calling(at.known().verb());
        return switch (at.known()) {
            case ADVANCE -> {
                arguments(body);
                yield ok(scope, verbs.advance(caller.subject(), scope, at.address(),
                    unquote(ifMatch).orElse(null)));
            }
            case VALIDATE -> {
                arguments(body);
                yield ok(scope, verbs.validate(caller.subject(), scope, at.address()));
            }
            case DIGEST -> uncarried(scope, at.address(), null, at.verb());
            default -> throw new IllegalStateException(
                "'" + at.verb() + "' is declared at collection depth and has no arm here");
        };
    }

    /**
     * GET at collection depth: {@code query}.
     *
     * <p>Reading on a collection, so GET on the collection URI — the form
     * follows from the target and the effect class rather than from the
     * verb's name.
     *
     * <p><strong>The filter and the paging travel as query parameters, not as
     * path segments.</strong> An address without an id part is reserved, so a
     * segment for a filter would either collapse into the selector or reserve
     * a form no address can grow into. The query string is the transport
     * layer for arguments a caller narrows a read with, and its parameters do
     * not become part of the address the answer is at.
     *
     * <p>A filter is written as {@code ?filter.<name>=<value>}, the page size as
     * {@code ?page_size=}, the continuation as {@code ?after=}, the order as
     * {@code ?sort=} and {@code ?direction=}. Every parameter is handed to
     * {@link QuerySpec} as it arrived — text, or a list when it was repeated —
     * and read by the verb surface, by the same parse the MCP adapter's
     * arguments get; a parameter that is none of these is refused here as an
     * argument the call does not declare, a fault of form decided before the
     * scope (C1.3), as the MCP adapter decides it. The JAX-RS
     * conversion of {@code page_size} to an integer is deliberately not used:
     * it answers an unreadable value with a bare 404 before any refusal of ours.
     *
     * <p>Both surfaces offer every sort a view declares (C4.7), so
     * {@code Kumbuka-Surface: assistant}, which {@link SurfaceHeaderFilter}
     * checks on every call, changes nothing in the answer of this call.
     */
    @GET
    @Path("{selector}")
    public Response collectionGet(@PathParam("scope") String scope,
                                  @PathParam("selector") String selector,
                                  jakarta.ws.rs.core.UriInfo info) {
        calling("query");
        Map<String, Object> filter = new LinkedHashMap<>();
        Map<String, Object> paging = new LinkedHashMap<>();
        java.util.List<String> untaken = new java.util.ArrayList<>();
        for (Map.Entry<String, java.util.List<String>> entry
                : info.getQueryParameters().entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue().size() == 1 ? entry.getValue().get(0) : entry.getValue();
            String filterPrefix = QueryFilter.FILTER_ARGUMENT + ".";
            if (name.startsWith(filterPrefix) && name.length() > filterPrefix.length()) {
                filter.put(name.substring(filterPrefix.length()), value);
            } else if (PAGING_ARGUMENTS.contains(name)) {
                paging.put(name, value);
            } else {
                untaken.add(name);
            }
        }
        if (!untaken.isEmpty()) {
            // A fault of form: decided before the scope (C1.3), as the MCP
            // adapter decides it, and answered with the arguments query declares.
            throw SurfaceException.unknown(QuerySpec.ARGUMENTS);
        }
        QuerySpec spec = new QuerySpec(filter, paging.get(QuerySpec.PAGE_SIZE_ARGUMENT),
            paging.get(QuerySpec.AFTER_ARGUMENT), paging.get(QuerySpec.SORT_ARGUMENT),
            paging.get(QuerySpec.DIRECTION_ARGUMENT), untaken);
        return Response.ok(Payloads.of(scope,
            verbs.query(caller.subject(), scope, selector, spec), assistant())).build();
    }

    // ======================================================================
    // The item binding: read, update, and every item-depth custom method
    // ======================================================================

    /**
     * GET at item depth: {@code read}, with the option of an item's
     * fulfilment ledger as the query parameter {@code ?fulfilment=}, read by
     * the verb surface as the MCP argument is.
     */
    @GET
    @Path("{selector}/{id}")
    public Response read(@PathParam("scope") String scope,
                         @PathParam("selector") String selector,
                         @PathParam("id") String id,
                         @QueryParam(SurfaceDeclaration.FULFILMENT_ARGUMENT)
                         @Nullable String fulfilment) {
        calling(READ);
        return ok(scope, verbs.read(caller.subject(), scope, selector, id, fulfilment));
    }

    /** The field write. PATCH with {@code If-Match}; a stale token is 412. */
    @PATCH
    @Path("{selector}/{id}")
    public Response update(@PathParam("scope") String scope,
                           @PathParam("selector") String selector,
                           @PathParam("id") String id,
                           @HeaderParam("If-Match") @Nullable String ifMatch,
                           @Nullable String body) {
        calling(UPDATE);
        context.at(new CallContext.Target(scope, selector, id, null));
        return ok(scope, verbs.update(caller.subject(), scope, selector, id,
            unquote(ifMatch).orElse(null), ArgumentsPayload.object(json, body).map(a -> Payloads.fields(a.values())).orElse(null)));
    }

    /**
     * POST at item depth: every custom method, in colon notation.
     *
     * <p>A segment with no colon is a plain item address, and POST is not
     * something an item offers — 405 with {@code Allow}, for the same reason the
     * collection refuses a transition: what the verb set lacks is not offered,
     * even where the convention expects it.
     *
     * <p>A colon naming no verb of this depth is 405 as well and not 404. The
     * address resolved; what did not exist is the verb, and a 404 would send the
     * caller looking for the object.
     */
    @POST
    @Path("{selector}/{id}")
    public Response itemPost(@PathParam("scope") String scope,
                             @PathParam("selector") String selector,
                             @PathParam("id") String segment,
                             @HeaderParam("If-Match") @Nullable String ifMatch,
                             @Nullable String body) {
        CustomMethod.Split at = CustomMethod.split(segment, CustomMethod.Depth.ITEM)
            .filter(CustomMethod.Split::isKnown)
            .orElseThrow(() -> new SurfaceException(
                RefusalCode.WRITE_ON_TRUNCATED_ADDRESS,
                CustomMethod.allow(CustomMethod.Depth.ITEM)));

        calling(at.known().verb());
        context.at(new CallContext.Target(scope, selector, at.address(), null));
        return dispatch(at, scope, selector, unquote(ifMatch).orElse(null), body);
    }

    /**
     * One custom method onto one act.
     *
     * <p>A {@code switch} over the enum with no default: a verb added to the
     * table without a case here is a compile error, which is the only way the
     * table and the routing cannot drift apart.
     */
    private Response dispatch(CustomMethod.Split at, String scope, String selector,
                              @Nullable String token, @Nullable String body) {
        String subject = caller.subject();
        String id = at.address();

        return switch (at.known()) {
            case CLOSE -> {
                ArgumentsPayload given = arguments(body, PRODUCED, REASON, STATUS);
                yield ok(scope, verbs.close(subject, scope, selector, id, token,
                    new VerbInput.Closing(given.text(PRODUCED).orElse(null),
                        given.text(REASON).orElse(null), given.text(STATUS).orElse(null))));
            }

            case SUBMIT -> {
                arguments(body);
                yield ok(scope, verbs.submit(subject, scope, selector, id, token));
            }
            case DISCARD -> {
                arguments(body);
                yield ok(scope, verbs.discard(subject, scope, selector, id, token));
            }
            case START -> ok(scope, verbs.start(subject, scope, selector, id, token,
                arguments(body, STATUS).text(STATUS).orElse(null)));
            case RETURN -> {
                ArgumentsPayload given = arguments(body, REASON, STATUS);
                yield ok(scope, verbs.return_(subject, scope, selector, id, token,
                    given.text(REASON).orElse(null), given.text(STATUS).orElse(null)));
            }

            case RELATE -> ok(scope, verbs.relate(subject, scope, selector, id, token,
                edge(arguments(body, TO_ITEM, TYPE)).orElse(null)));
            case UNRELATE -> ok(scope, verbs.unrelate(subject, scope, selector, id, token,
                edge(arguments(body, TO_ITEM, TYPE)).orElse(null)));

            case TAG_ATTACH -> ok(scope, verbs.tagAttach(subject, scope, selector, id,
                arguments(body, TAG).text(TAG).orElse(null)));
            case TAG_DETACH -> ok(scope, verbs.tagDetach(subject, scope, selector, id,
                arguments(body, TAG).text(TAG).orElse(null)));

            case BOOK -> ok(scope, verbs.book(subject, scope, selector, id,
                booking(arguments(body, INSTANCE, ENTRIES)).orElse(null)));

            case SEND, APPEND, ABANDON, BLOCK, RESUME, CONSUME ->
                uncarried(scope, selector, id, at.verb());

            case VALIDATE, ADVANCE, DIGEST -> throw new IllegalStateException(
                "'" + at.verb() + "' acts at collection depth and cannot arrive here");
        };
    }

    // ======================================================================
    // The membership binding: a second id segment, and no view of its own
    // ======================================================================

    @GET
    @Path("{selector}/{id}/{member}")
    public Response readMembership(@PathParam("scope") String scope,
                                   @PathParam("selector") String selector,
                                   @PathParam("id") String id,
                                   @PathParam("member") String member) {
        calling(READ);
        return ok(scope, verbs.readMembership(caller.subject(), scope, selector, id, member));
    }

    /**
     * {@code plan}: POST at the membership's own address.
     *
     * <p>POST rather than PUT even though the address is fully determined by the
     * caller: the act is not "make this address hold this content", it is "add
     * this item to this iteration, at the end of its order" — and the position it
     * lands in is the service's to decide. A PUT would promise that a repeat
     * lands in the same state, and a second plan is a typed refusal instead.
     */
    @POST
    @Path("{selector}/{id}/{member}")
    public Response plan(@PathParam("scope") String scope,
                         @PathParam("selector") String selector,
                         @PathParam("id") String id,
                         @PathParam("member") String member,
                         @HeaderParam("If-Match") @Nullable String ifMatch) {
        calling("plan");
        context.at(new CallContext.Target(scope, selector, id, member));
        return created(scope, verbs.plan(caller.subject(), scope, selector, id, member,
            unquote(ifMatch).orElse(null)));
    }

    /**
     * {@code unplan}: DELETE at the membership's own address.
     *
     * <p>The one DELETE this surface offers, and it is not a delete of an object:
     * a membership is a relation with a position, and removing it is the verb
     * {@code unplan}. The one object this scheme deletes is a draft, through
     * the transition {@code discard}; an item closed keeps its address
     * forever. So the method is available here precisely because what it
     * removes is not an object.
     */
    @DELETE
    @Path("{selector}/{id}/{member}")
    public Response unplan(@PathParam("scope") String scope,
                           @PathParam("selector") String selector,
                           @PathParam("id") String id,
                           @PathParam("member") String member,
                           @HeaderParam("If-Match") @Nullable String ifMatch) {
        calling("unplan");
        context.at(new CallContext.Target(scope, selector, id, member));
        return ok(scope, verbs.unplan(caller.subject(), scope, selector, id, member,
            unquote(ifMatch).orElse(null)));
    }

    @PATCH
    @Path("{selector}/{id}/{member}")
    public Response updateMembership(@PathParam("scope") String scope,
                                     @PathParam("selector") String selector,
                                     @PathParam("id") String id,
                                     @PathParam("member") String member,
                                     @HeaderParam("If-Match") @Nullable String ifMatch,
                                     @Nullable String body) {
        calling(UPDATE);
        context.at(new CallContext.Target(scope, selector, id, member));
        return ok(scope, verbs.updateMembership(caller.subject(), scope, selector, id, member,
            unquote(ifMatch).orElse(null), ArgumentsPayload.object(json, body).map(a -> Payloads.fields(a.values())).orElse(null)));
    }

    // ======================================================================
    // The call the request became
    // ======================================================================

    private static final String CREATE = "create";
    private static final String READ = "read";
    private static final String UPDATE = "update";

    /**
     * Records the verb the route named, before anything about the call is
     * read: from here on every refusal carries it as {@code attempted}, in the
     * names of the surface the caller came through (C6.2).
     */
    private void calling(String verb) {
        context.call(verb, assistant());
    }

    /**
     * The query parameter {@code submit} of a create, read for its form: its
     * absence and {@code false} keep the new item a draft, {@code true}
     * submits it in the same act, and any other value is refused naming it.
     * On the assistant surface the parameter is refused whatever it says,
     * with the arguments the tool declares.
     *
     * @param raw the parameter as it arrived; absent where the call sent none
     */
    private boolean submitting(@Nullable String raw) {
        if (raw == null) {
            return false;
        }
        if (assistant()) {
            throw SurfaceException.unknown(SurfaceDeclaration.verb(CREATE)
                .map(verb -> verb.assistantArguments().stream()
                    .map(argument -> argument.name().name()).toList())
                .orElseThrow(() -> new IllegalStateException("create is a declared verb")));
        }
        return switch (raw) {
            case "true" -> true;
            case "false" -> false;
            default -> throw SurfaceException.invalid(SurfaceDeclaration.SUBMIT_ARGUMENT);
        };
    }

    /**
     * Whether the request names the assistant surface, so that the calls an
     * answer names under {@code next} carry their tool names (C6.2, C14.4).
     */
    private boolean assistant() {
        return ASSISTANT.equals(headers.getHeaderString(SURFACE));
    }

    // ======================================================================
    // The verbs that do not act
    // ======================================================================

    /**
     * Both of these always throw, and they exist so the compiler is not told a
     * lie about a value that cannot be produced — a {@code return null} on those
     * lines would be a value sitting where a later edit could make it real.
     *
     * <p>The reasons travel from the surface and not from here. Which class a
     * verb is in is written once, in {@link CustomMethod}, and the sentence
     * explaining it is written once, below: two adapters explaining the same
     * refusal in their own words is how two callers come to believe two different
     * things about one surface.
     */
    private Response uncarried(String scope, String view, @Nullable String id, String verb) {
        verbs.uncarried(caller.subject(), scope, view, id, verb);
        throw unreachable(verb);
    }

    // ======================================================================
    // Dressing a result in HTTP
    // ======================================================================

    private Response ok(String scope, VerbSurface.Result result) {
        return tagged(Response.ok(Payloads.of(scope, result, assistant())), result);
    }

    /**
     * 201 with {@code Location}, built from the address rather than echoed from
     * the request.
     *
     * <p>The canonical form is generated by the surface and what arrived is never
     * passed through, so a tolerated trailing slash does not survive into a
     * header other clients will treat as an identity.
     */
    private Response created(String scope, VerbSurface.Result result) {
        return tagged(Response
            .created(UriBuilder.fromResource(WorklistResource.class)
                .path("{selector}/{id}")
                .build(new Object[] {
                    scope, result.address().view(), result.address().id()
                }, false))
            .entity(Payloads.of(scope, result, assistant())), result);
    }

    /**
     * The conflict token as an entity tag.
     *
     * <p>It is also in the field map, because that is where the domain reads it
     * from and what makes read-modify-write work. Putting it in the header as
     * well is not a second truth: it is the same value in the place HTTP looks
     * for it, so that {@code If-Match} is usable by a client that knows nothing
     * about this scheme's field names.
     */
    private static Response tagged(Response.ResponseBuilder response,
                                   VerbSurface.Result result) {
        result.conflictToken().ifPresent(token -> response.tag(new EntityTag(token)));
        return response.build();
    }

    // ======================================================================
    // The arguments of a body
    // ======================================================================

    private static final String PRODUCED = "produced";
    private static final String REASON = SurfaceDeclaration.REASON_ARGUMENT;
    private static final String STATUS = SurfaceDeclaration.STATUS_ARGUMENT;
    private static final String TO_ITEM = "to_item";
    private static final String TYPE = "type";
    private static final String TAG = "tag";
    private static final String INSTANCE = SurfaceDeclaration.BOOK_INSTANCE;
    private static final String ENTRIES = SurfaceDeclaration.BOOK_ENTRIES;

    /**
     * The arguments of a body under the names the verb declares, checked for
     * form in {@link ArgumentsPayload} before any scope is resolved (C1.2,
     * C1.3).
     */
    private ArgumentsPayload arguments(@Nullable String body, String... declared) {
        return ArgumentsPayload.declared(json, body, declared);
    }

    /**
     * A booking's arguments, checked for their JSON type here; absent where
     * the body carried none, which the surface refuses by the names of both.
     */
    private static Optional<VerbInput.Booking> booking(ArgumentsPayload arguments) {
        return arguments.isEmpty() ? Optional.empty()
            : Optional.of(new VerbInput.Booking(arguments.text(INSTANCE).orElse(null),
                arguments.list(ENTRIES).orElse(null)));
    }

    private static Optional<VerbInput.Edge> edge(ArgumentsPayload arguments) {
        return arguments.isEmpty() ? Optional.empty()
            : Optional.of(new VerbInput.Edge(arguments.text(TO_ITEM).orElse(null),
                arguments.text(TYPE).orElse(null)));
    }

    /**
     * Tolerates the quoted form an HTTP entity tag arrives in.
     *
     * <p>Done here and not in the surface: the quoting is the transport's, and a
     * surface that had to know about it would be a surface that knows about HTTP.
     */
    private static Optional<String> unquote(@Nullable String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.trim();
        value = value.startsWith("W/") ? value.substring(2) : value;
        return Optional.of(value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
            ? value.substring(1, value.length() - 1)
            : value);
    }

    private static IllegalStateException unreachable(String verb) {
        return new IllegalStateException(
            "'" + verb + "' does not act and its refusal did not throw");
    }
}
