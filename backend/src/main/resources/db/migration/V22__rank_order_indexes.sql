-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V22: an index for the rank order of the two views that carry a rank.
--
-- A query on the iteration or the milestone view may page by rank, with the
-- number as second key. V21 indexed the creation order of all four views and
-- left this one out, so a page in rank order read the whole scope and sorted
-- it, however small the page.
--
-- One index per view on (tenant_id, scope_id, rank, number). Both directions
-- of the sort use it, an index being readable backwards.
--
-- Additive and compatible with the previous image: indexes only, no column,
-- no constraint, nothing the previous image reads or writes differently.
-- ===========================================================================

CREATE INDEX idx_iteration_rank_order ON worklist.iteration
    (tenant_id, scope_id, rank, number);
CREATE INDEX idx_milestone_rank_order ON worklist.milestone
    (tenant_id, scope_id, rank, number);
