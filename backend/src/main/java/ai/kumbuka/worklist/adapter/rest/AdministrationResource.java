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
import ai.kumbuka.worklist.domain.ScopeUsage;
import ai.kumbuka.worklist.surface.AddressParser;
import ai.kumbuka.worklist.surface.AdministrationSurface;
import ai.kumbuka.worklist.surface.CallerActor;
import ai.kumbuka.worklist.surface.RefusalCode;
import ai.kumbuka.worklist.surface.SurfaceException;
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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The REST exposition of the administration surface (C1.6, C3.5): where a
 * scope's vocabulary is kept, under {@code /api/{scope}/admin/...}.
 *
 * <p>Resources, never verbs (ADR-0040): each kind of declared value is a
 * collection that is read with GET, declared into with POST and withdrawn
 * from with DELETE on the value's own path, where the registry carries a
 * withdrawal; a workstream and the settings are changed with PATCH under
 * {@code If-Match}. Every route is a row of {@code administration-surface.tsv},
 * and none is a verb of the work surface: no tool of {@code tools/list} and no
 * call of {@code GET /api/declaration} names one (C4.8, C5.7).
 *
 * <h2>Why the literal segment sits after the scope</h2>
 *
 * {@code /api/{scope}/admin} leaves every scope name free: a literal segment
 * beats the template {@code {selector}} of {@link WorklistResource}, and the
 * selectors are the four fixed views, of which {@code admin} is none and never
 * becomes one (C3.5, {@code AddressParser#ADMINISTRATION}). The other form,
 * {@code /api/admin/{scope}}, would reserve {@code admin} as a scope name and
 * break a scope already called so.
 *
 * <h2>The form of a call is decided here</h2>
 *
 * A body is read and checked for form -- a JSON object, the names the call
 * takes, each value of its JSON type, the mandatory ones present -- before
 * the surface resolves any scope (C1.2, C1.3). What the values mean is the
 * surface's and the registry's.
 */
@Path("/api/{scope}/admin")
@Authenticated
@TenantBound
@Produces(MediaType.APPLICATION_JSON)
public class AdministrationResource {

    /** A number in a path: at least one, no leading zeroes, as an address writes it. */
    private static final Pattern NUMBER_FORM = Pattern.compile("[1-9]\\d{0,18}");

    private static final String NAME = "name";
    private static final String RANK = "rank";
    private static final String ACTIONABLE = "actionable";
    private static final String IN_PROGRESS = "in_progress";
    private static final String CLOSED = "closed";
    private static final String SUCCESSFUL = "successful";
    private static final String REVIEWED = "reviewed";
    private static final String APPROVED = "approved";
    private static final String VERIFIED = "verified";
    private static final String KEY = "key";
    private static final String TYPE = "type";
    private static final String SORTABLE = "sortable";
    private static final String BLOCKS = "blocks";
    private static final String TARGET_STATUS = "target_status";
    private static final String TOKEN = "token";
    private static final String DESCRIPTION = "description";
    private static final String DEFAULT_COLUMNS = "default_columns";

    @Inject AdministrationSurface administration;
    @Inject CallerActor caller;
    @Inject ObjectMapper json;
    @Inject CallContext context;

    // ======================================================================
    // Statuses
    // ======================================================================

    @GET
    @Path(AdministrationSurface.STATUSES_PATH)
    public Response statuses(@PathParam("scope") String scope) {
        calling("GET", AdministrationSurface.STATUSES_PATH);
        return listing(scope, administration.statuses(caller.subject(), scope));
    }

    /**
     * Declares a status: {@code name} and {@code rank} mandatory, and the
     * seven predicates, each {@code false} where the call leaves it out.
     */
    @POST
    @Path(AdministrationSurface.STATUSES_PATH)
    public Response declareStatus(@PathParam("scope") String scope, @Nullable String body) {
        calling("POST", AdministrationSurface.STATUSES_PATH);
        ArgumentsPayload given = arguments(body, NAME, RANK, ACTIONABLE, IN_PROGRESS, CLOSED,
            SUCCESSFUL, REVIEWED, APPROVED, VERIFIED);
        AdministrationSurface.StatusDeclaration declared =
            new AdministrationSurface.StatusDeclaration(text(given, NAME), rankOf(given),
                flag(given, ACTIONABLE), flag(given, IN_PROGRESS), flag(given, CLOSED),
                flag(given, SUCCESSFUL), flag(given, REVIEWED), flag(given, APPROVED),
                flag(given, VERIFIED));
        return ok(scope, administration.declareStatus(caller.subject(), scope, declared));
    }

    @DELETE
    @Path(AdministrationSurface.STATUSES_PATH + "/{name}")
    public Response withdrawStatus(@PathParam("scope") String scope,
                                   @PathParam("name") String name) {
        calling("DELETE", AdministrationSurface.STATUSES_PATH);
        return ok(scope, administration.withdrawStatus(caller.subject(), scope, name));
    }

    // ======================================================================
    // What the scope uses
    // ======================================================================

    @GET
    @Path(AdministrationSurface.USAGES_PATH)
    public Response usages(@PathParam("scope") String scope) {
        calling("GET", AdministrationSurface.USAGES_PATH);
        return listing(scope, administration.usages(caller.subject(), scope));
    }

    /** Declares a usage by its {@code name}, one of the five the platform knows. */
    @POST
    @Path(AdministrationSurface.USAGES_PATH)
    public Response declareUsage(@PathParam("scope") String scope, @Nullable String body) {
        calling("POST", AdministrationSurface.USAGES_PATH);
        String name = text(arguments(body, NAME), NAME);
        ScopeUsage.Property property = ScopeUsage.Property.byToken(name)
            .orElseThrow(() -> SurfaceException.invalid(NAME));
        return ok(scope, administration.declareUsage(caller.subject(), scope, property));
    }

    // ======================================================================
    // Attributes
    // ======================================================================

    @GET
    @Path(AdministrationSurface.ATTRIBUTES_PATH)
    public Response attributes(@PathParam("scope") String scope) {
        calling("GET", AdministrationSurface.ATTRIBUTES_PATH);
        return listing(scope, administration.attributes(caller.subject(), scope));
    }

    /**
     * Declares an attribute: {@code key}, {@code name}, {@code type} and
     * {@code rank} mandatory, {@code sortable} {@code false} where left out.
     */
    @POST
    @Path(AdministrationSurface.ATTRIBUTES_PATH)
    public Response declareAttribute(@PathParam("scope") String scope, @Nullable String body) {
        calling("POST", AdministrationSurface.ATTRIBUTES_PATH);
        ArgumentsPayload given = arguments(body, KEY, NAME, TYPE, RANK, SORTABLE);
        AdministrationSurface.AttributeDeclaration declared =
            new AdministrationSurface.AttributeDeclaration(text(given, KEY), text(given, NAME),
                text(given, TYPE), rankOf(given), flag(given, SORTABLE));
        return ok(scope, administration.declareAttribute(caller.subject(), scope, declared));
    }

    @DELETE
    @Path(AdministrationSurface.ATTRIBUTES_PATH + "/{key}")
    public Response withdrawAttribute(@PathParam("scope") String scope,
                                      @PathParam("key") String key) {
        calling("DELETE", AdministrationSurface.ATTRIBUTES_PATH);
        return ok(scope, administration.withdrawAttribute(caller.subject(), scope, key));
    }

    /** Declares one option of the attribute: {@code name} and {@code rank} mandatory. */
    @POST
    @Path(AdministrationSurface.ATTRIBUTES_PATH + "/{key}/options")
    public Response declareOption(@PathParam("scope") String scope,
                                  @PathParam("key") String key, @Nullable String body) {
        calling("POST", AdministrationSurface.ATTRIBUTES_PATH);
        ArgumentsPayload given = arguments(body, NAME, RANK);
        return ok(scope, administration.declareOption(caller.subject(), scope, key,
            text(given, NAME), rankOf(given)));
    }

    // ======================================================================
    // Relation types
    // ======================================================================

    @GET
    @Path(AdministrationSurface.RELATION_TYPES_PATH)
    public Response relationTypes(@PathParam("scope") String scope) {
        calling("GET", AdministrationSurface.RELATION_TYPES_PATH);
        return listing(scope, administration.relationTypes(caller.subject(), scope));
    }

    /**
     * Declares a relation type: {@code name} and {@code rank} mandatory,
     * {@code blocks} {@code false} where left out, and {@code target_status}
     * for {@code duplicate_of}, which takes one and is the only type that does.
     */
    @POST
    @Path(AdministrationSurface.RELATION_TYPES_PATH)
    public Response declareRelationType(@PathParam("scope") String scope,
                                        @Nullable String body) {
        calling("POST", AdministrationSurface.RELATION_TYPES_PATH);
        ArgumentsPayload given = arguments(body, NAME, BLOCKS, RANK, TARGET_STATUS);
        AdministrationSurface.RelationDeclaration declared =
            new AdministrationSurface.RelationDeclaration(text(given, NAME),
                flag(given, BLOCKS), rankOf(given), given.text(TARGET_STATUS).orElse(null));
        return ok(scope, administration.declareRelationType(caller.subject(), scope, declared));
    }

    @DELETE
    @Path(AdministrationSurface.RELATION_TYPES_PATH + "/{name}")
    public Response withdrawRelationType(@PathParam("scope") String scope,
                                         @PathParam("name") String name) {
        calling("DELETE", AdministrationSurface.RELATION_TYPES_PATH);
        return ok(scope, administration.withdrawRelationType(caller.subject(), scope, name));
    }

    // ======================================================================
    // Workstreams
    // ======================================================================

    /** Declares a workstream: {@code token} and {@code description} mandatory. */
    @POST
    @Path(AdministrationSurface.WORKSTREAMS_PATH)
    public Response declareWorkstream(@PathParam("scope") String scope, @Nullable String body) {
        calling("POST", AdministrationSurface.WORKSTREAMS_PATH);
        ArgumentsPayload given = arguments(body, TOKEN, DESCRIPTION);
        return ok(scope, administration.declareWorkstream(caller.subject(), scope,
            text(given, TOKEN), text(given, DESCRIPTION)));
    }

    /**
     * Changes a workstream's {@code description}, renames its {@code token},
     * or both, under {@code If-Match}; at least one of the two.
     */
    @PATCH
    @Path(AdministrationSurface.WORKSTREAMS_PATH + "/{number}")
    public Response changeWorkstream(@PathParam("scope") String scope,
                                     @PathParam("number") String number,
                                     @HeaderParam("If-Match") @Nullable String ifMatch,
                                     @Nullable String body) {
        calling("PATCH", AdministrationSurface.WORKSTREAMS_PATH);
        long at = numberOf(number);
        ArgumentsPayload given = arguments(body, TOKEN, DESCRIPTION);
        if (given.isEmpty()) {
            throw SurfaceException.missing(TOKEN, DESCRIPTION);
        }
        return ok(scope, administration.changeWorkstream(caller.subject(), scope, at,
            unquote(ifMatch).orElse(null), given.text(TOKEN).orElse(null),
            given.text(DESCRIPTION).orElse(null)));
    }

    @DELETE
    @Path(AdministrationSurface.WORKSTREAMS_PATH + "/{number}")
    public Response withdrawWorkstream(@PathParam("scope") String scope,
                                       @PathParam("number") String number,
                                       @HeaderParam("If-Match") @Nullable String ifMatch) {
        calling("DELETE", AdministrationSurface.WORKSTREAMS_PATH);
        long at = numberOf(number);
        return ok(scope, administration.withdrawWorkstream(caller.subject(), scope, at,
            unquote(ifMatch).orElse(null)));
    }

    // ======================================================================
    // The settings
    // ======================================================================

    @GET
    @Path(AdministrationSurface.SETTINGS_PATH)
    public Response settings(@PathParam("scope") String scope) {
        calling("GET", AdministrationSurface.SETTINGS_PATH);
        return ok(scope, administration.settings(caller.subject(), scope));
    }

    /**
     * Opens the scope in this service: its settings, with the
     * {@code default_columns} the call names, and its four views.
     */
    @POST
    @Path(AdministrationSurface.SETTINGS_PATH)
    public Response openSettings(@PathParam("scope") String scope, @Nullable String body) {
        calling("POST", AdministrationSurface.SETTINGS_PATH);
        return ok(scope, administration.openSettings(caller.subject(), scope,
            arguments(body, DEFAULT_COLUMNS).values()));
    }

    /** Writes the {@code default_columns} of the settings, under {@code If-Match}. */
    @PATCH
    @Path(AdministrationSurface.SETTINGS_PATH)
    public Response changeSettings(@PathParam("scope") String scope,
                                   @HeaderParam("If-Match") @Nullable String ifMatch,
                                   @Nullable String body) {
        calling("PATCH", AdministrationSurface.SETTINGS_PATH);
        ArgumentsPayload given = arguments(body, DEFAULT_COLUMNS);
        given.list(DEFAULT_COLUMNS).orElseThrow(() -> SurfaceException.missing(DEFAULT_COLUMNS));
        return ok(scope, administration.changeSettings(caller.subject(), scope,
            unquote(ifMatch).orElse(null), given.values()));
    }

    // ======================================================================
    // The call, its form, and its answer
    // ======================================================================

    /**
     * Records the call the route named before anything about it is read, so
     * that every refusal carries it as {@code attempted} (C6.2).
     */
    private void calling(String method, String collection) {
        context.administering(method + " " + AddressParser.ADMINISTRATION + "/" + collection);
    }

    private ArgumentsPayload arguments(@Nullable String body, String... declared) {
        return ArgumentsPayload.declared(json, body, declared);
    }

    /** A mandatory text argument, refused by its name when absent or blank. */
    private static String text(ArgumentsPayload given, String name) {
        String value = given.text(name).orElseThrow(() -> SurfaceException.missing(name));
        if (value.isBlank()) {
            throw SurfaceException.invalid(name);
        }
        return value;
    }

    /** The mandatory {@code rank}: a whole number within the range of the column. */
    private static int rankOf(ArgumentsPayload given) {
        long rank = given.integer(RANK).orElseThrow(() -> SurfaceException.missing(RANK));
        if (rank < Integer.MIN_VALUE || rank > Integer.MAX_VALUE) {
            throw SurfaceException.invalid(RANK);
        }
        return (int) rank;
    }

    /** An optional predicate or property, {@code false} where the call leaves it out. */
    private static boolean flag(ArgumentsPayload given, String name) {
        return given.flag(name).orElse(false);
    }

    /** A workstream's number in the path, refused as a malformed address otherwise. */
    private static long numberOf(String raw) {
        if (!NUMBER_FORM.matcher(raw).matches()) {
            throw new SurfaceException(RefusalCode.ADDRESS_MALFORMED);
        }
        return Long.parseLong(raw);
    }

    private static Response ok(String scope, AdministrationSurface.Entry entry) {
        Response.ResponseBuilder response = Response.ok(Payloads.of(scope, entry));
        entry.conflictToken().ifPresent(token -> response.tag(new EntityTag(token)));
        return response.build();
    }

    private static Response listing(String scope, List<AdministrationSurface.Entry> entries) {
        return Response.ok(Payloads.of(scope, entries)).build();
    }

    /** Tolerates the quoted form an HTTP entity tag arrives in, as the verb routes do. */
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
}
