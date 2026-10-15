-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V21: an index for the creation order of the four views.
--
-- A query pages by keyset in the database, in one of two standard sorts: by
-- number, and by created_at with the number as second key. The number order
-- is already served by the unique constraint every view carries on
-- (tenant_id, scope_id, number). The creation order had no index on any of
-- the four tables, although item paged by exactly (created_at, number) before
-- this release; a page in that order read the whole scope and sorted it.
--
-- One index per view on (tenant_id, scope_id, created_at, number). Both
-- directions of the sort use it, an index being readable backwards.
--
-- Additive and compatible with the previous image: indexes only, no column,
-- no constraint, nothing the previous image reads or writes differently.
-- ===========================================================================

CREATE INDEX idx_item_created_order ON worklist.item
    (tenant_id, scope_id, created_at, number);
CREATE INDEX idx_iteration_created_order ON worklist.iteration
    (tenant_id, scope_id, created_at, number);
CREATE INDEX idx_milestone_created_order ON worklist.milestone
    (tenant_id, scope_id, created_at, number);
CREATE INDEX idx_workstream_created_order ON worklist.workstream
    (tenant_id, scope_id, created_at, number);
