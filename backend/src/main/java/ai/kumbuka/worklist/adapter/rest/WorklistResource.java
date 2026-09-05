package ai.kumbuka.worklist.adapter.rest;

import ai.kumbuka.worklist.adapter.payload.Payloads;
import ai.kumbuka.worklist.surface.CallerActor;
import ai.kumbuka.worklist.surface.SurfaceException;
import ai.kumbuka.worklist.surface.VerbSurface;
import ai.kumbuka.worklist.tenancy.TenantBound;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

import java.util.Map;

/**
 * The REST exposition of the verb surface.
 *
 * <p>Ten verbs on their outward forms; the seven the scheme does not carry and
 * the six it carries unbuilt, each answering by name; and a writing verb on a
 * truncated address answering 405 with {@code Allow}. Nothing else. A conformance
 * probe checks both halves of that — coverage and closure — against a
 * specification this class cannot edit.
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
 * segment it appears in, so seventeen {@code @Path("{id}:verb")} routes would
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

    /** What a collection URI offers, for the {@code Allow} of a 405. */
    private static final String COLLECTION_ALLOW = "GET, POST";

    /** What an item URI offers when the segment names no verb. */
    private static final String ITEM_ALLOW = "GET, PATCH, POST";

    /** The shape a JSON object body is read into. */
    private static final TypeReference<Map<String, Object>> OBJECT =
        new TypeReference<>() { };

    @Inject VerbSurface verbs;
    @Inject CallerActor caller;
    @Inject ObjectMapper json;

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
     */
    @POST
    @Path("{selector}")
    public Response collectionPost(@PathParam("scope") String scope,
                                   @PathParam("selector") String segment,
                                   @HeaderParam("If-Match") String ifMatch,
                                   String body) {
        var split = CustomMethod.split(segment, CustomMethod.Depth.COLLECTION);
        if (split.isEmpty()) {
            // No colon: a plain collection address, and create is the one
            // writing verb whose set semantics is declared as exactly one.
            return created(scope, verbs.create(caller.subject(), scope, segment,
                Payloads.fields(readObject(body))));
        }

        CustomMethod.Split at = split.get();
        if (!at.isKnown()) {
            // Every other verb acts at item depth. Depth is declared per verb and
            // undeclared means complete address only, so a range capability never
            // comes into existence by omission.
            throw new SurfaceException(SurfaceException.Reason.WRITE_ON_TRUNCATED_ADDRESS,
                "'" + at.verb() + "' does not act on a truncated address. The only "
                    + "declarable set semantics is exactly one, which create has and "
                    + "advance has and no other verb of this scheme does.",
                COLLECTION_ALLOW);
        }

        return switch (at.method()) {
            case ADVANCE -> ok(scope, verbs.advance(caller.subject(), scope, at.address(),
                unquote(ifMatch)));
            case CLAIM_NEXT -> unbuilt(scope, at.address(), null, at.verb());
            case DIGEST -> uncarried(scope, at.address(), null, at.verb());
            default -> throw new IllegalStateException(
                "'" + at.verb() + "' is declared at collection depth and has no arm here");
        };
    }

    /**
     * GET at collection depth: {@code query}.
     *
     * <p>Reading on a collection, so GET on the collection URI — the form follows
     * from the target and the effect class rather than from the verb's name.
     */
    @GET
    @Path("{selector}")
    public Response collectionGet(@PathParam("scope") String scope,
                                  @PathParam("selector") String selector) {
        return Response.ok(Payloads.of(scope,
            verbs.query(caller.subject(), scope, selector))).build();
    }

    // ======================================================================
    // The item binding: read, update, and every item-depth custom method
    // ======================================================================

    @GET
    @Path("{selector}/{id}")
    public Response read(@PathParam("scope") String scope,
                         @PathParam("selector") String selector,
                         @PathParam("id") String id) {
        return ok(scope, verbs.read(caller.subject(), scope, selector, id));
    }

    /** The field write. PATCH with {@code If-Match}; a stale token is 412. */
    @PATCH
    @Path("{selector}/{id}")
    public Response update(@PathParam("scope") String scope,
                           @PathParam("selector") String selector,
                           @PathParam("id") String id,
                           @HeaderParam("If-Match") String ifMatch,
                           Map<String, Object> body) {
        return ok(scope, verbs.update(caller.subject(), scope, selector, id,
            unquote(ifMatch), Payloads.fields(body)));
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
                             @HeaderParam("If-Match") String ifMatch,
                             String body) {
        CustomMethod.Split at = CustomMethod.split(segment, CustomMethod.Depth.ITEM)
            .filter(CustomMethod.Split::isKnown)
            .orElseThrow(() -> new SurfaceException(
                SurfaceException.Reason.WRITE_ON_TRUNCATED_ADDRESS,
                "'" + segment + "' names no verb of this scheme at item depth. A "
                    + "transition is written in colon notation on the item URI — "
                    + "'<number>:close' — because a verb as a trailing path segment could "
                    + "not be told apart from a further id segment.",
                ITEM_ALLOW));

        return dispatch(at, scope, selector, unquote(ifMatch), body);
    }

    /**
     * One custom method onto one act.
     *
     * <p>A {@code switch} over the enum with no default: a verb added to the
     * table without a case here is a compile error, which is the only way the
     * table and the routing cannot drift apart.
     */
    private Response dispatch(CustomMethod.Split at, String scope, String selector,
                              String token, String body) {
        String subject = caller.subject();
        String id = at.address();

        return switch (at.method()) {
            case ACCEPT -> ok(scope, verbs.accept(subject, scope, selector, id, token));
            case WITHDRAW -> ok(scope, verbs.withdraw(subject, scope, selector, id, token,
                Payloads.withdrawal(read(body, Payloads.WithdrawRequest.class))));
            case CLOSE -> ok(scope, verbs.close(subject, scope, selector, id, token));

            case CLAIM, RELEASE, RELATE, UNRELATE, VALIDATE ->
                unbuilt(scope, selector, id, at.verb());
            case SEND, APPEND, ABANDON, BLOCK, RESUME, CONSUME ->
                uncarried(scope, selector, id, at.verb());

            case ADVANCE, CLAIM_NEXT, DIGEST -> throw new IllegalStateException(
                "'" + at.verb() + "' acts at collection depth and cannot arrive here");
        };
    }

    // ======================================================================
    // The membership binding: a second id segment, and no fourth view
    // ======================================================================

    @GET
    @Path("{selector}/{id}/{member}")
    public Response readMembership(@PathParam("scope") String scope,
                                   @PathParam("selector") String selector,
                                   @PathParam("id") String id,
                                   @PathParam("member") String member) {
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
                         @HeaderParam("If-Match") String ifMatch) {
        return created(scope, verbs.plan(caller.subject(), scope, selector, id, member,
            unquote(ifMatch)));
    }

    /**
     * {@code unplan}: DELETE at the membership's own address.
     *
     * <p>The one DELETE this surface offers, and it is not a delete of an object:
     * a membership is a relation with a position, and removing it is the verb
     * {@code unplan}. Nothing in this scheme is hard-deleted — an item withdrawn
     * keeps its address forever — so the method is available here precisely
     * because what it removes is not an object.
     */
    @DELETE
    @Path("{selector}/{id}/{member}")
    public Response unplan(@PathParam("scope") String scope,
                           @PathParam("selector") String selector,
                           @PathParam("id") String id,
                           @PathParam("member") String member,
                           @HeaderParam("If-Match") String ifMatch) {
        return ok(scope, verbs.unplan(caller.subject(), scope, selector, id, member,
            unquote(ifMatch)));
    }

    @PATCH
    @Path("{selector}/{id}/{member}")
    public Response updateMembership(@PathParam("scope") String scope,
                                     @PathParam("selector") String selector,
                                     @PathParam("id") String id,
                                     @PathParam("member") String member,
                                     @HeaderParam("If-Match") String ifMatch,
                                     Map<String, Object> body) {
        return ok(scope, verbs.updateMembership(caller.subject(), scope, selector, id, member,
            unquote(ifMatch), Payloads.fields(body)));
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
    private Response uncarried(String scope, String view, String id, String verb) {
        verbs.uncarried(caller.subject(), scope, view, id, verb,
            "The capability declaration gives this scheme the object lifecycle, the "
                + "planning verbs, the claim family, the graph verbs and validate. The "
                + "commitment gate it carries is accept and not send: an item is never "
                + "frozen, so there is nothing for an author to commit outward and nothing "
                + "for an addendum to hang off.");
        throw unreachable(verb);
    }

    private Response unbuilt(String scope, String view, String id, String verb) {
        verbs.unbuilt(caller.subject(), scope, view, id, verb,
            "It is declared, it is specified, and no code answers it yet. That is a "
                + "different sentence from 'this scheme does not have it', which is why it "
                + "does not get the same status: waiting for this one is reasonable.");
        throw unreachable(verb);
    }

    // ======================================================================
    // Dressing a result in HTTP
    // ======================================================================

    private static Response ok(String scope, VerbSurface.Result result) {
        return tagged(Response.ok(Payloads.of(scope, result)), result);
    }

    /**
     * 201 with {@code Location}, built from the address rather than echoed from
     * the request.
     *
     * <p>The canonical form is generated by the surface and what arrived is never
     * passed through, so a tolerated trailing slash does not survive into a
     * header other clients will treat as an identity.
     */
    private static Response created(String scope, VerbSurface.Result result) {
        return tagged(Response
            .created(UriBuilder.fromResource(WorklistResource.class)
                .path("{selector}/{id}")
                .build(new Object[] {
                    scope, result.address().view(), result.address().id()
                }, false))
            .entity(Payloads.of(scope, result)), result);
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
        String token = result.conflictToken();
        if (token != null) {
            response.tag(new EntityTag(token));
        }
        return response.build();
    }

    // ======================================================================
    // The body
    // ======================================================================

    /**
     * Deserialises a JSON object body, after the verb has been decided.
     *
     * <p>An absent body is null rather than an error: some verbs take one and
     * some do not, and which is which is the verb's business rather than the
     * transport's. The verb refuses a missing body where it needs one, with a
     * message that says what was missing.
     */
    private Map<String, Object> readObject(String body) {
        return read(body, OBJECT);
    }

    private <T> T read(String body, Class<T> shape) {
        return read(body, json.getTypeFactory().constructType(shape));
    }

    private <T> T read(String body, TypeReference<T> shape) {
        return read(body, json.getTypeFactory().constructType(shape));
    }

    private <T> T read(String body, com.fasterxml.jackson.databind.JavaType shape) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return json.readValue(body, shape);
        } catch (JsonProcessingException e) {
            throw new SurfaceException(SurfaceException.Reason.PAYLOAD_MALFORMED,
                "the request body is not the JSON this verb takes: " + e.getOriginalMessage());
        }
    }

    /**
     * Tolerates the quoted form an HTTP entity tag arrives in.
     *
     * <p>Done here and not in the surface: the quoting is the transport's, and a
     * surface that had to know about it would be a surface that knows about HTTP.
     */
    private static String unquote(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        value = value.startsWith("W/") ? value.substring(2) : value;
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
            ? value.substring(1, value.length() - 1)
            : value;
    }

    private static IllegalStateException unreachable(String verb) {
        return new IllegalStateException(
            "'" + verb + "' does not act and its refusal did not throw");
    }
}
