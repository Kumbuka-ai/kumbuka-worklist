-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V32: the four cardinality columns of `worklist.scope_setting` get defaults.
--
-- The first release of REA-0011 step S8, the expand half of an
-- expand/contract pair. Planning is bounded from this release by two
-- platform constants (REA-0011 D9): at most one hundred open iterations per
-- scope, at most one thousand memberships per iteration, configurable
-- nowhere. The service no longer reads or writes the four columns V4 created
-- NOT NULL without a default, and the act that opens a scope asks for no
-- number. A row it writes names none of the four, so each needs a default
-- for the insert to stand.
--
-- The defaults are the constants of D9: `max_planned_iterations` and
-- `warn_planned_iterations` 100, `max_memberships_per_iteration` and
-- `warn_memberships_per_iteration` 1000.
--
-- WHAT THIS DOES NOT DO
--
-- It drops nothing, renames nothing and narrows no constraint:
-- `ck_scope_setting_cardinality` of V4 stands as it is, and every default
-- satisfies it. It changes no existing row: SET DEFAULT applies to rows
-- inserted afterwards and rewrites none. The removal of the four columns is
-- the second release of S8 and follows once this one has run in production.
--
-- N-1 COMPATIBLE AS HELD BY THE AUTHOR
--
-- The image before this one, wave-1 at
-- 8097df974c8edc380253603fb05d405e299c0ae2, maps the four columns on
-- `ScopeSetting` as NOT NULL integers, reads them to bound planning and
-- writes them when it opens a scope. Against the schema this migration
-- leaves it finds a value in every row: an existing row keeps the numbers it
-- was written with, and a row written by this release carries the defaults
-- below, so the previous image reads at a new row the bounds of D9. Its own
-- inserts name all four columns, so a default never replaces a number it
-- writes. Nothing it reads or writes is removed or narrowed.
-- ===========================================================================

ALTER TABLE worklist.scope_setting
    ALTER COLUMN max_planned_iterations         SET DEFAULT 100,
    ALTER COLUMN warn_planned_iterations        SET DEFAULT 100,
    ALTER COLUMN max_memberships_per_iteration  SET DEFAULT 1000,
    ALTER COLUMN warn_memberships_per_iteration SET DEFAULT 1000;
