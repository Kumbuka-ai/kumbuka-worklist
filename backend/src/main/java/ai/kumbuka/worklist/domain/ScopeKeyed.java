/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.StringUuidConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;

import java.util.UUID;

/**
 * The tenancy pair for a row whose identity starts with its scope.
 *
 * <p>A link between objects — a relation, a membership, a tag assignment — is
 * identified by the numbers of the objects it joins, and a number names an
 * object only within its scope (ADR-0042). So {@code scope_id} is part of the
 * key here, where {@link TenantScoped} carries it as an ordinary column. The
 * tenant mapping is the one {@link TenantScoped} documents, column for column;
 * the two classes differ in the {@code @Id} on the scope and in nothing else.
 */
@MappedSuperclass
public abstract class ScopeKeyed {

    /** The tenancy axis, mapped exactly as {@link TenantScoped#tenantId}. */
    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    /** The scope, the first part of the row's identity. */
    @Id
    @Column(name = "scope_id", nullable = false)
    public UUID scopeId;
}
