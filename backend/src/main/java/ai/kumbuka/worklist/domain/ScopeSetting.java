/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * One row per scope, and everything the scope decides.
 *
 * <p>A configuration object with a conflict token of its own rather than an
 * aggregate root with children: nothing hangs off it, and it is still a row
 * two callers can hold a view of at once.
 *
 * <h2>What the settings hold, and what they no longer hold</h2>
 *
 * The pointer to the current iteration and the default column set, and
 * nothing that bounds planning (TAR-0002 B11). Planning is bounded by the
 * platform constants of REA-0011 D9, held in {@link PlanningService} and
 * configurable nowhere.
 *
 * <p>The four cardinality columns V4 created ({@code max_planned_iterations},
 * {@code warn_planned_iterations}, {@code max_memberships_per_iteration},
 * {@code warn_memberships_per_iteration}) still stand in the table and are
 * deliberately not mapped: the previous image reads them, and V32 gives them
 * defaults equal to the constants, so a row this entity writes without them
 * reads to the previous image with the bounds of D9. They are removed in a
 * later release.
 *
 * <p>The row is written by the act that opens the scope in this service,
 * with the scope's views, and asks the caller for nothing.
 *
 * <h2>The current iteration is a pointer and lives here</h2>
 *
 * Rather than a boolean on the iteration, which would allow two current ones
 * and would then need a partial unique index to forbid what a single nullable
 * pointer cannot express in the first place.
 *
 * <h2>The allocation is per selector and there is no second position</h2>
 *
 * Each view — item, iteration, milestone, workstream — draws from its own
 * {@link NumberSpace} row, and that row is the one position. There used to
 * be an {@code allocation_mode} column on this row with a second,
 * scope-wide, expressible position beside it; the two differed only in
 * which counter the allocator read. It was removed on the class reading
 * of the selector: the views are different classes of thing,
 * and {@code .../item/1}, {@code .../iteration/1} and {@code .../milestone/1}
 * are three different addresses already, so a bare number that had to
 * disambiguate itself across them was solving a problem the address form
 * does not have.
 *
 * <p>Advancing a mark does not rotate this row's token. Creating an
 * iteration is a write on the ITERATION aggregate; it advances the mark as
 * an allocator side effect against the iteration selector's
 * {@link NumberSpace}, exactly as {@code accept} advances the item
 * selector's {@link NumberSpace} while rotating only the item's token.
 * Rotating the setting's token there would move a caller's token with no
 * write of their own in between — the sprint-169 defect, reproduced by a
 * service that had learnt from it.
 */
@Entity
@Table(name = "scope_setting", schema = "worklist")
@AttributeOverride(name = "scopeId",
    column = @Column(name = "scope_id", nullable = false, insertable = false, updatable = false))
public class ScopeSetting extends AggregateRoot {

    /**
     * The row's identity: its scope. The table's primary key is
     * {@code (tenant_id, scope_id)} — one row per scope — and the tenant is
     * the session's, so the scope alone names the row without drawing the
     * tenancy axis into the entity's key.
     *
     * <p>The same column as the inherited {@link #scopeId}, mapped twice and
     * written once, here; the inherited mapping is overridden read-only so
     * every other class keeps reading {@code scopeId} as on every other table.
     */
    @Id
    @Column(name = "scope_id", nullable = false, updatable = false)
    public UUID scopeKey;

    /**
     * The iteration being worked; absent where none is. A pointer, unambiguous
     * by construction.
     */
    @Column(name = "current_iteration_number")
    public @Nullable Long currentIterationNumber;

    /**
     * The declared default column set of a reader's first view.
     *
     * <p>Declared and not compiled into the console: otherwise the
     * predecessor's disease reappears one level up, with the vocabulary free
     * and the choice of what a reader sees code again. Nothing in this sprint
     * writes it; the column is mapped so that a read of the settings answers
     * with what the row holds.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "default_columns", nullable = false, columnDefinition = "text[]")
    public String[] defaultColumns = new String[0];

    // Each axis's counter is the `number_space` row of its selector — one
    // mechanism, one position. The two high-water marks that briefly lived
    // here (added while the axes were not selectors, removed once they
    // became ones) are gone from the schema too.

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Override
    protected String subject() {
        return Addressed.SETTING.description();
    }

    @Override
    protected void touch(Instant at) {
        updatedAt = at;
    }

    @Override
    protected Optional<Instant> changeTime() {
        return Optional.ofNullable(updatedAt);
    }

    @Override
    protected void created(Instant at) {
        createdAt = at;
    }
}
