/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The token that proves a claim, minted by the service and never accepted from
 * a caller.
 *
 * <h2>Why the caller does not get to supply one</h2>
 *
 * The obvious design lets an executor name itself and be recognised by that
 * name. It fails in a way that is easy to miss and hard to debug: several
 * executor instances on one machine reach the service through one channel, and
 * anything they could derive a name from — hostname, working directory, start
 * time — collides. The service would then see one holder where there are two,
 * hand the claim to whichever asked last, and both would work.
 *
 * <p>A minted receipt cannot collide, because it is minted AFTER the award and
 * only one award happens. That is the whole argument, and it is why a
 * caller-supplied holder identifier is refused rather than merely discouraged.
 *
 * <h2>What is stored</h2>
 *
 * A hash (C10.5). The receipt is a bearer token in the literal sense, so the only copy
 * of the token itself is the one handed to the winner; every backup, dump and
 * replica of the database carries hashes and no working credentials.
 */
public final class Receipt {

    /** 256 bits. A receipt is guessed or it is not; there is no partial credit. */
    private static final int BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Receipt() {
    }

    /**
     * A fresh receipt. Opaque on purpose: it encodes nothing about the
     * item, the holder or the time, so it cannot be reconstructed from
     * anything an observer already knows.
     */
    public static String mint() {
        byte[] material = new byte[BYTES];
        RANDOM.nextBytes(material);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    /** The stored form. */
    public static String hash(String receipt) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                sha256.digest(receipt.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the platform", impossible);
        }
    }

    /**
     * Whether a presented receipt matches a stored hash.
     *
     * <p>Constant-time comparison. The alternative leaks how much of a guess
     * was right through timing, which turns guessing a 256-bit token into
     * guessing it one byte at a time.
     */
    public static boolean matches(String presented, String storedHash) {
        if (presented == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
            hash(presented).getBytes(StandardCharsets.UTF_8),
            storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
