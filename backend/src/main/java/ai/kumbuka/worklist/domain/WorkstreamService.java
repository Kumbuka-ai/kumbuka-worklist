package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.repository.WorkstreamRepository;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The workstream axis — the fourth view. The service that declares
 * workstreams, holds their descriptions, refuses renames while pointers
 * stand, and refuses everything on the default beyond changing its
 * description.
 *
 * <h2>The verbs</h2>
 *
 * {@code declare} a workstream, {@code read} one, {@code update} its
 * description, {@code rename} its token (while nothing points at it),
 * {@code withdraw} it, {@code query} the scope's axis. Every verb is the
 * platform's own word for the act; the ADDRESS says a workstream is meant.
 *
 * <h2>Governance is enforced here, in one place</h2>
 *
 * The rename predicate is a two-table read of {@code item} and
 * {@code milestone}, and a database trigger doing it would push the
 * enforcement across a table boundary the trigger does not own. So it
 * lives here, guarded by the tests that exercise both directions —
 * renaming while nothing points at the workstream, and refused once
 * anything does.
 *
 * <p>The default's protection keys on the flag {@link Workstream#isDefault}
 * and not on the token: the token is renamable up to the first pointer
 * and would be an unreliable identity carrier.
 */
@ApplicationScoped
@TenantBound
public class WorkstreamService {

    /** An address, a scope id, a token, a transition. Never a description, never an actor. */
    private static final Logger LOG = Logger.getLogger(WorkstreamService.class);

    @Inject WorkstreamRepository workstreams;
    @Inject SelectorRegistry selectors;

    // ------------------------------------------------------------------
    // Reading.
    // ------------------------------------------------------------------

    /** One workstream, as the canonical field map. */
    @Transactional
    public Map<String, Object> read(UUID scopeId, UUID workstreamId) {
        return project(require(scopeId, workstreamId));
    }

    /** The scope's whole axis, by number. */
    @Transactional
    public List<Map<String, Object>> query(UUID scopeId) {
        return workstreams.inScope(scopeId).stream()
            .map(WorkstreamService::project)
            .toList();
    }

    /**
     * The default workstream of the scope. Created lazily on first read if
     * missing — the default IS a property of every scope by the ratified
     * design, and its concrete row is where that property is expressed.
     *
     * <p>Lazy creation is not a licence for other workstreams to appear on
     * first use: the frame refuses that flatly for CALLER-declared ones,
     * because a service that mints a workstream on first mention makes a
     * typo indistinguishable from an intention. The default is not
     * caller-mentioned. It is the intake landing every scope carries by
     * construction, and its identity comes from its {@code is_default}
     * flag rather than from any name a caller might name.
     *
     * <p>The lazy path also ensures the workstream selector and the
     * workstream selector's number-space row exist for the scope, so a
     * scope opened outside the bootstrap path (a test fixture) has the
     * same shape as one opened through it.
     */
    @Transactional
    public Workstream requireDefault(UUID scopeId) {
        Workstream defaultWs = workstreams.findDefault(scopeId);
        if (defaultWs != null) {
            return defaultWs;
        }
        // Ensure the workstream selector exists in this scope. `declare`
        // is idempotent — it returns the existing selector row and opens
        // its number-space row if that is missing too.
        Selector workstreamSelector = selectors.declare(scopeId, Selector.WORKSTREAM);
        long number = selectors.allocate(scopeId, workstreamSelector);

        Workstream defaultRow = new Workstream();
        defaultRow.scopeId     = scopeId;
        defaultRow.number      = number;
        defaultRow.token       = Workstream.DEFAULT_TOKEN;
        defaultRow.description = "The default workstream. Everything that names no other "
            + "lands here; its identity is fixed and only the description is settable.";
        defaultRow.status      = Workstream.DECLARED;
        defaultRow.isDefault   = true;
        workstreams.insert(defaultRow);
        workstreams.refresh(defaultRow);

        // V12 (2026-09-09): the milestone counter is scope-wide again
        // (TAR-0002 section 4, REQ-0148 obsolete), so no per-workstream
        // milestone-counter row is opened here. The scope-wide milestone
        // number_space row is planted by bootstrap or by the first
        // `selectors.declare(scope, MILESTONE)` call.

        LOG.infof("default workstream created lazily in scope %s (number %d)",
            scopeId, number);
        return defaultRow;
    }

    // ------------------------------------------------------------------
    // Writing.
    // ------------------------------------------------------------------

    /**
     * Declare a new workstream in a scope.
     *
     * <p>Refuses a token that is malformed, an empty description, and any
     * token already declared in the scope.
     *
     * <p>V12 (2026-09-09): the milestone number space is scope-wide again
     * (TAR-0002 section 4, REQ-0148 obsolete), so no per-workstream
     * milestone counter is opened for a new workstream.
     */
    @Transactional
    public Workstream declare(UUID scopeId, String token, String description) {
        if (token == null || !Selector.TOKEN_PATTERN.matcher(token).matches()) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "a workstream token is a leading lower-case letter followed by lower-case "
                    + "alphanumerics and interior hyphens. Upper case is refused rather "
                    + "than folded, because folding would make two strings resolve to one "
                    + "workstream. Refused: " + token,
                List.of(String.valueOf(token)));
        }
        String cleaned = requireNonBlank(description,
            "a workstream carries a mandatory description. A workstream without one is a "
                + "violation and not a report");
        if (workstreams.findByToken(scopeId, token) != null) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "workstream token " + token + " is already declared in scope " + scopeId
                    + ". A withdrawn workstream keeps its token, so redeclaring under a "
                    + "known name is refused rather than folded",
                List.of(token));
        }

        Selector workstreamSelector = selectors.require(scopeId, Selector.WORKSTREAM);
        long number = selectors.allocate(scopeId, workstreamSelector);

        Workstream workstream = new Workstream();
        workstream.scopeId     = scopeId;
        workstream.number      = number;
        workstream.token       = token;
        workstream.description = cleaned;
        workstream.status      = Workstream.DECLARED;
        workstream.isDefault   = false;
        workstreams.insert(workstream);
        workstreams.refresh(workstream);

        // V12 (2026-09-09): no per-workstream milestone counter is opened;
        // the counter is scope-wide (see class comment above).

        LOG.infof("workstream %s (number %d) declared in scope %s",
            token, number, scopeId);
        return workstream;
    }

    /**
     * Change a workstream's description. Applies to the default too: the
     * default's identity is fixed, its description is not.
     */
    @Transactional
    public Workstream update(UUID scopeId, UUID workstreamId, String description,
            String conflictToken) {
        Workstream workstream = require(scopeId, workstreamId);
        workstream.requireCurrentToken(conflictToken);
        String cleaned = requireNonBlank(description,
            "a workstream description is mandatory; it may not be cleared");
        if (cleaned.equals(workstream.description)) {
            return workstream;
        }
        workstream.description = cleaned;
        workstream.stamp();
        workstreams.flush();
        LOG.infof("workstream %s description updated in scope %s", workstream.token, scopeId);
        return workstream;
    }

    /**
     * Rename the workstream's token.
     *
     * <p>Allowed only while nothing points at the workstream: neither an
     * item nor a milestone. The default's token is fixed regardless — its
     * identity is what things point at from outside the store.
     */
    @Transactional
    public Workstream rename(UUID scopeId, UUID workstreamId, String newToken,
            String conflictToken) {
        Workstream workstream = require(scopeId, workstreamId);
        workstream.requireCurrentToken(conflictToken);
        refuseIfDefault(workstream, "renamed",
            "The default's token is fixed — its identity is what things point at from "
                + "outside the store, and renaming it would break every such pointer at once");
        if (newToken == null || !Selector.TOKEN_PATTERN.matcher(newToken).matches()) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "a workstream token is a leading lower-case letter followed by lower-case "
                    + "alphanumerics and interior hyphens. Refused: " + newToken,
                List.of(String.valueOf(newToken)));
        }
        if (newToken.equals(workstream.token)) {
            return workstream;
        }
        if (workstreams.findByToken(scopeId, newToken) != null) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "workstream token " + newToken + " is already in use in scope " + scopeId,
                List.of(newToken));
        }
        if (workstreams.hasReferences(scopeId, workstreamId)) {
            throw new WorklistException(
                WorklistException.Reason.WORKSTREAM_HAS_REFERENCES,
                "workstream " + workstream.token + " in scope " + scopeId
                    + " is renamable only while nothing points at it. An item or a "
                    + "milestone does; declare a new workstream and move the pointers "
                    + "if the name has to change",
                List.of(workstream.token));
        }
        String oldToken = workstream.token;
        workstream.token = newToken;
        workstream.stamp();
        workstreams.flush();
        LOG.infof("workstream renamed %s -> %s in scope %s", oldToken, newToken, scopeId);
        return workstream;
    }

    /**
     * Withdraw a workstream: nothing new is accepted under it, everything
     * already there keeps resolving.
     */
    @Transactional
    public Workstream withdraw(UUID scopeId, UUID workstreamId, String conflictToken) {
        Workstream workstream = require(scopeId, workstreamId);
        workstream.requireCurrentToken(conflictToken);
        refuseIfDefault(workstream, "withdrawn",
            "The default is the intake landing for every item and milestone that names no "
                + "other. Withdrawing it would refuse a create that carried no explicit "
                + "workstream, and the frame says the default takes those in");
        if (Workstream.WITHDRAWN.equals(workstream.status)) {
            return workstream;
        }
        workstream.status = Workstream.WITHDRAWN;
        workstream.stamp();
        workstreams.flush();
        LOG.infof("workstream %s withdrawn in scope %s", workstream.token, scopeId);
        return workstream;
    }

    // ------------------------------------------------------------------
    // Mechanisms shared with other services.
    // ------------------------------------------------------------------

    /**
     * Resolve a workstream by id in a scope, or a typed refusal.
     */
    @Transactional
    public Workstream require(UUID scopeId, UUID workstreamId) {
        Workstream workstream = workstreams.find(scopeId, workstreamId);
        if (workstream == null) {
            throw new WorklistException(
                WorklistException.Reason.WORKSTREAM_UNKNOWN,
                "no workstream " + workstreamId + " in scope " + scopeId,
                List.of(String.valueOf(workstreamId)));
        }
        return workstream;
    }

    /** True when the workstream may still accept new pointers. */
    public void refuseWithdrawn(Workstream workstream) {
        if (!Workstream.WITHDRAWN.equals(workstream.status)) {
            return;
        }
        throw new WorklistException(
            WorklistException.Reason.WORKSTREAM_WITHDRAWN,
            "workstream " + workstream.token + " is withdrawn. Nothing new is accepted "
                + "under it; what was already accepted keeps resolving",
            List.of(workstream.token));
    }

    private static void refuseIfDefault(Workstream workstream, String verb, String reason) {
        if (!workstream.isDefault) {
            return;
        }
        throw new WorklistException(
            WorklistException.Reason.WORKSTREAM_DEFAULT_LOCKED,
            "the default workstream of scope " + workstream.scopeId + " may not be " + verb
                + ". " + reason,
            List.of(workstream.token));
    }

    private static String requireNonBlank(String value, String reason) {
        if (value == null || value.isBlank()) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                reason + " — empty and whitespace-only are refused",
                List.of("description"));
        }
        return value.strip();
    }

    private static Map<String, Object> project(Workstream workstream) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", workstream.id);
        fields.put("scope", workstream.scopeId);
        fields.put("number", workstream.number);
        fields.put("token", workstream.token);
        fields.put("description", workstream.description);
        fields.put("status", workstream.status);
        fields.put("is_default", workstream.isDefault);
        fields.put("created_at", workstream.createdAt);
        fields.put("updated_at", workstream.updatedAt);
        fields.put("conflict_token", workstream.conflictToken);
        return fields;
    }
}
