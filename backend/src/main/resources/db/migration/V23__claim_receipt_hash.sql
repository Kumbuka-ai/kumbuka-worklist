-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V23: a claim's receipt is stored as its SHA-256.
--
-- A receipt is a bearer token: whoever presents it may release the lease. A
-- column holding it in plaintext hands every backup, dump and replica a
-- working credential. From this release the service mints 32 random bytes,
-- base64url without padding, and compares the SHA-256 of what is presented
-- with `receipt_hash`, hex-encoded (C10.5).
--
-- The column is filled here for every claim the table holds, from the
-- plaintext, so a receipt handed out before this migration still releases
-- after it. The hash is taken over the UTF-8 bytes of the text, as the
-- service takes it.
--
-- Additive and compatible with the previous image: one nullable column and a
-- check that admits null; the previous image neither reads nor writes the
-- column and keeps comparing the plaintext column, which this release still
-- writes. The plaintext column falls with the contraction, one release later.
-- A row the previous image writes after a rollback carries no hash, and the
-- service then hashes its plaintext.
-- ===========================================================================

ALTER TABLE worklist.claim ADD COLUMN receipt_hash TEXT;

ALTER TABLE worklist.claim ADD CONSTRAINT ck_claim_receipt_hash
    CHECK (receipt_hash IS NULL OR receipt_hash ~ '^[0-9a-f]{64}$');

UPDATE worklist.claim
   SET receipt_hash = encode(sha256(convert_to(receipt, 'UTF8')), 'hex')
 WHERE receipt_hash IS NULL;
