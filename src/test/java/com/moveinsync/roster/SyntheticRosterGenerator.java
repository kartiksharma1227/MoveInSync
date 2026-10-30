package com.moveinsync.roster;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.moveinsync.roster.service.employee.IngestService.EventRequest;
import com.moveinsync.roster.service.employee.IngestService.HrRequest;

/**
 * Deterministic fake roster data (fixed seed, so every run produces the same data). All people are fictional.
 * Phase 5 adds nightly CSV generation here.
 */
public final class SyntheticRosterGenerator {

    static final String[] ACME_OFFICES = {"BLR-01", "BLR-02", "HYD-01"};
    static final String[] SHIFTS = {"S0900", "S1400", "S2200"};

    private SyntheticRosterGenerator() {
    }

    /**
     * A feed for {@code employees} new people: a CREATE (v1) for each, then an UPDATE (v2) moving each one to a
     * different office and shift. Creates come first because only a CREATE may create an employee.
     */
    public static List<EventRequest> createThenUpdateFeed(String idPrefix, int employees) {
        Random random = new Random(42);
        Instant start = Instant.parse("2026-10-01T00:00:00Z");
        List<EventRequest> creates = new ArrayList<>();
        List<EventRequest> updates = new ArrayList<>();
        for (int i = 0; i < employees; i++) {
            String id = idPrefix + "-" + i;
            creates.add(event("CREATE", id, 1, start.plusSeconds(i), hr(i, random)));
            updates.add(event("UPDATE", id, 2, start.plusSeconds(100_000 + i), hr(i, random)));
        }
        creates.addAll(updates);
        return creates;
    }

    public static EventRequest event(String type, String employeeId, long version, Instant at, HrRequest hr) {
        return new EventRequest("evt-" + employeeId + "-v" + version, type, employeeId, version, at, hr);
    }

    public static HrRequest hr(String officeCode, String shift) {
        return new HrRequest("Test Person", "test.person@acme.test", "+919811112222", null, officeCode, shift);
    }

    private static HrRequest hr(int n, Random random) {
        return new HrRequest(
                "Employee " + n,
                "e" + n + "@acme.test",
                "+9198" + (10_000_000 + random.nextInt(89_999_999)),
                n == 0 ? null : "M-" + (n / 10),
                ACME_OFFICES[random.nextInt(ACME_OFFICES.length)],
                SHIFTS[random.nextInt(SHIFTS.length)]);
    }
}
