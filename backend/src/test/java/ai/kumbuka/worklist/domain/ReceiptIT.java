/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.Db;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A receipt is 32 random bytes, base64url without padding, and {@code
 * release} compares the SHA-256 of what is presented with the stored hash
 * (C10.5).
 *
 * <p>The expectation is written here from the rule: the form of the text, the
 * length of what it decodes to, and the digest computed in this test, not by
 * the service's own hash.
 *
 * <p>The data survives the change. A claim the previous image granted carries
 * a uuid in plaintext and no hash; V23 fills the hash from the plaintext, and
 * the uuid releases afterwards. The case plants that row as the previous
 * image wrote it, under the runtime role, and runs V23's own statement, read
 * from the migration file, under the migrator — the same statement Flyway ran
 * on every database. A row the previous image writes after a rollback, past
 * V23, carries no hash either; it releases on the hash of its plaintext.
 *
 * <p>RED STATE, observed with {@code release} comparing the stored hash alone
 * (no hash of the plaintext where the hash is absent): the case on the row
 * written after a rollback failed with {@code CLAIM_RECEIPT_UNKNOWN}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ReceiptIT {

    private static final Duration LEASE = Duration.ofMinutes(30);
    private static final String HOLDER = "a-holder";

    @Inject ItemService items;
    @Inject ClaimService claims;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;

    private UUID scope;
    private String openStatus;

    @BeforeEach
    void aScopeOfItsOwn() {
        scope = UUID.randomUUID();
        selectors.declare(scope, Selector.ITEM);
        openStatus = vocabulary.declareStatus(scope, "open", 1,
            true, false, false, false).name;
    }

    @Test
    void a_receipt_is_thirty_two_random_bytes_in_base64url_without_padding() {
        String first = receiptOf(claims.claim(scope, item(), HOLDER, LEASE));
        String second = receiptOf(claims.claim(scope, item(), HOLDER, LEASE));

        for (String receipt : new String[] {first, second}) {
            assertThat(receipt).as("base64url, no padding: 43 characters for 32 bytes")
                .matches("[A-Za-z0-9_-]{43}");
            assertThat(Base64.getUrlDecoder().decode(receipt)).hasSize(32);
        }
        assertThat(second).as("each claim its own").isNotEqualTo(first);
    }

    @Test
    void a_new_receipt_is_stored_as_its_sha256_and_releases() throws Exception {
        UUID item = item();
        String receipt = receiptOf(claims.claim(scope, item, HOLDER, LEASE));

        assertThat(storedHash(item))
            .as("the SHA-256 of the receipt's text, hex")
            .isEqualTo(sha256(receipt));

        claims.release(scope, item, HOLDER, receipt);
        assertThat(refusalOf(() -> claims.release(scope, item, HOLDER, receipt)))
            .as("released: the lease is over, a second release finds none")
            .isEqualTo(WorklistException.Reason.CLAIM_ABSENT);
    }

    @Test
    void a_wrong_receipt_is_refused() {
        UUID item = item();
        String receipt = receiptOf(claims.claim(scope, item, HOLDER, LEASE));

        assertThat(refusalOf(() -> claims.release(scope, item, HOLDER, receipt + "x")))
            .isEqualTo(WorklistException.Reason.CLAIM_RECEIPT_UNKNOWN);
        assertThat(refusalOf(() -> claims.release(scope, item, HOLDER, sha256(receipt))))
            .as("the stored hash is no receipt: presenting it is presenting a wrong one")
            .isEqualTo(WorklistException.Reason.CLAIM_RECEIPT_UNKNOWN);
    }

    @Test
    void a_uuid_receipt_granted_before_the_migration_releases_after_it() throws Exception {
        UUID item = item();
        String receipt = UUID.randomUUID().toString();
        plantClaimAsThePreviousImage(item, receipt);
        assertThat(storedHash(item)).as("the previous image writes no hash").isNull();

        runTheBackfillOfV23();

        assertThat(storedHash(item))
            .as("V23 filled the hash from the plaintext, over its UTF-8 bytes")
            .isEqualTo(sha256(receipt));
        claims.release(scope, item, HOLDER, receipt);
        assertThat(refusalOf(() -> claims.release(scope, item, HOLDER, receipt)))
            .isEqualTo(WorklistException.Reason.CLAIM_ABSENT);
    }

    @Test
    void a_receipt_the_previous_image_granted_after_a_rollback_releases() throws Exception {
        UUID item = item();
        String receipt = UUID.randomUUID().toString();
        plantClaimAsThePreviousImage(item, receipt);

        assertThat(refusalOf(() -> claims.release(scope, item, HOLDER, "not-" + receipt)))
            .isEqualTo(WorklistException.Reason.CLAIM_RECEIPT_UNKNOWN);
        claims.release(scope, item, HOLDER, receipt);
        assertThat(refusalOf(() -> claims.release(scope, item, HOLDER, receipt)))
            .isEqualTo(WorklistException.Reason.CLAIM_ABSENT);
    }

    // -----------------------------------------------------------------------

    private UUID item() {
        Map<String, Object> created = items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "an item to claim",
            Field.STATUS.canonicalName(), openStatus));
        return UUID.fromString(String.valueOf(created.get(Field.ID.canonicalName())));
    }

    /** A live claim with a plaintext receipt and no hash, the row the previous image writes. */
    private void plantClaimAsThePreviousImage(UUID item, String receipt) throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, PlanningFixture.boundTenant());
            try (var st = c.prepareStatement("""
                    INSERT INTO worklist.claim
                        (tenant_id, scope_id, item_number, receipt, actor, expires_at)
                    VALUES (?, ?, (SELECT number FROM worklist.item WHERE id = ?),
                            ?, ?, now() + interval '1 hour')
                    """)) {
                st.setObject(1, PlanningFixture.boundTenant());
                st.setObject(2, scope);
                st.setObject(3, item);
                st.setString(4, receipt);
                st.setString(5, HOLDER);
                st.executeUpdate();
            }
            c.commit();
        }
    }

    /** The UPDATE of V23, as the file holds it, run under the migrator for the bound tenant. */
    private static void runTheBackfillOfV23() throws Exception {
        String migration = Files.readString(migration("V23__claim_receipt_hash.sql"));
        int start = migration.indexOf("UPDATE worklist.claim");
        assertThat(start).as("V23 carries the backfill").isNotNegative();
        String backfill = migration.substring(start, migration.indexOf(';', start));
        try (Connection c = Db.asMigrator()) {
            Db.bindTenant(c, PlanningFixture.boundTenant());
            try (var st = c.createStatement()) {
                st.executeUpdate(backfill);
            }
            c.commit();
        }
    }

    private static Path migration(String name) {
        Path direct = Paths.get("src", "main", "resources", "db", "migration", name);
        return Files.isRegularFile(direct) ? direct : Paths.get("backend").resolve(direct);
    }

    private String storedHash(UUID item) throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, PlanningFixture.boundTenant());
            try (var st = c.prepareStatement("""
                    SELECT c.receipt_hash FROM worklist.claim c
                    JOIN worklist.item i ON i.tenant_id = c.tenant_id
                     AND i.scope_id = c.scope_id AND i.number = c.item_number
                    WHERE i.id = ?
                    """)) {
                st.setObject(1, item);
                try (ResultSet rows = st.executeQuery()) {
                    assertThat(rows.next()).as("the claim row is there").isTrue();
                    return rows.getString(1);
                }
            }
        }
    }

    private static String receiptOf(Map<String, Object> answer) {
        return String.valueOf(answer.get(ClaimService.F_RECEIPT));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static WorklistException.Reason refusalOf(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).as("the call is refused").isInstanceOf(WorklistException.class);
        return ((WorklistException) thrown).reason();
    }
}
