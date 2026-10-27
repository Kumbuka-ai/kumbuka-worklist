-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V23: every foreign key names its target table.
--
-- A foreign key is named `fk_<table>_<target>` (C18.3), so a refusal of the
-- database names both tables. V19 named five keys against that: two shorten
-- the target (`definition` for attribute_definition, `type` for
-- relation_type, `status` for item_status) and two do not name it at all
-- (`from`, `to`). V4 gave their predecessors the same names without the
-- suffix; those keys went with the columns they held when V20 dropped the
-- uuid references, so the five of V19 are the ones left to rename.
--
-- Additive and compatible with the previous image: a rename of a constraint
-- changes no column, no rule and nothing a statement of the previous image
-- names; no code of that image refers to a constraint by its name.
-- ===========================================================================

ALTER TABLE worklist.item
    RENAME CONSTRAINT fk_item_status_pk TO fk_item_item_status;
ALTER TABLE worklist.attribute_option
    RENAME CONSTRAINT fk_attribute_option_definition_pk TO fk_attribute_option_attribute_definition;
ALTER TABLE worklist.item_relation
    RENAME CONSTRAINT fk_item_relation_from_number TO fk_item_relation_item_from;
ALTER TABLE worklist.item_relation
    RENAME CONSTRAINT fk_item_relation_to_number TO fk_item_relation_item_to;
ALTER TABLE worklist.item_relation
    RENAME CONSTRAINT fk_item_relation_type_pk TO fk_item_relation_relation_type;
