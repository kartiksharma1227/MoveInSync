package com.moveinsync.roster;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoint for a failure that real code paths can't trigger on demand. It lives under src/test, so
 * component scanning registers it only when tests run.
 */
@RestController
public class TestEndpoints {

    public static final String LEAKY_PHONE = "+919876543210";
    public static final String LEAKY_EMAIL = "priya.secret@acme.test";

    /** An unexpected bug whose exception message happens to contain personal data. */
    @GetMapping("/test/boom")
    public String boom() {
        throw new IllegalStateException("lookup failed for " + LEAKY_PHONE + " / " + LEAKY_EMAIL);
    }
}
