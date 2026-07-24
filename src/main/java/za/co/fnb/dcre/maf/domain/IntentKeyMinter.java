package za.co.fnb.dcre.maf.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;

/**
 * Mints the DCRE affordability-enquiry idempotency key (R-08). The key is a
 * deterministic SHA-256 digest over the FULL business identity of the enquiry,
 * which is exactly the spine entry it scores: {@code (arrival_id, sequence)}
 * plus the {@code mandate_ref} for self-documentation. Deterministic minting is
 * the crash-safety linchpin: a resume re-mints the IDENTICAL key for the same
 * record, so the write-ahead {@code man_affordability_enquiry} row (intent_key
 * UNIQUE) is first-write-wins and the bureau is asked at most once
 * (full-identity idempotency key, engineering.md).
 */
public final class IntentKeyMinter {

    private static final String PREFIX = "MAF-";

    private IntentKeyMinter() {
    }

    public static String mint(final UUID arrivalId, final int sequence, final String mandateRef) {
        final String identity = "%s|%d|%s".formatted(arrivalId, sequence, mandateRef);
        final byte[] digest = sha256(identity.getBytes(StandardCharsets.UTF_8));
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 40);
    }

    private static byte[] sha256(final byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
