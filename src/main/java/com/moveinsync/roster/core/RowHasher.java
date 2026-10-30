package com.moveinsync.roster.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.moveinsync.roster.entity.Employee.HrProfile;

/**
 * One fingerprint per employee over the HR-owned fields, so the nightly diff compares one value instead of six.
 * Transport fields are deliberately excluded: a pickup-point edit must never look like an HR change.
 * The canonical form must stay identical to the SQL in db/seed/V1_1__demo_data.sql.
 */
public final class RowHasher {

    private RowHasher() {
    }

    public static String hash(HrProfile hr) {
        String canonical = String.join("|",
                nullToEmpty(hr.name()),
                nullToEmpty(hr.email()),
                nullToEmpty(hr.phone()),
                nullToEmpty(hr.managerId()),
                nullToEmpty(hr.officeCode()),
                nullToEmpty(hr.shift()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
