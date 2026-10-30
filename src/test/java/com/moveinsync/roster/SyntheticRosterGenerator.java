package com.moveinsync.roster;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.springframework.jdbc.core.JdbcTemplate;

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

    // ---- Nightly-file data. The SQL seed and the CSV writer produce the same employee i, so a clean file matches. ----

    /** Options for a generated nightly file. */
    public record CsvOptions(int rows, int truncateAt, int changeEvery, int badEvery, int extraNew) {
        public static CsvOptions rows(int rows) {
            return new CsvOptions(rows, 0, 0, 0, 0);
        }

        /** Stop writing after this many rows, like an export that was cut off. */
        public CsvOptions truncateAt(int n) {
            return new CsvOptions(rows, n, changeEvery, badEvery, extraNew);
        }

        /** Every n-th employee gets version 2 and a new shift. */
        public CsvOptions changeEvery(int n) {
            return new CsvOptions(rows, truncateAt, n, badEvery, extraNew);
        }

        /** Every n-th row has an invalid phone number. */
        public CsvOptions badEvery(int n) {
            return new CsvOptions(rows, truncateAt, changeEvery, n, extraNew);
        }

        /** Adds n new joiners after the existing employees. */
        public CsvOptions extraNew(int n) {
            return new CsvOptions(rows, truncateAt, changeEvery, badEvery, n);
        }
    }

    public static String benchId(int i) {
        return String.format("B%07d", i);
    }

    /** A tenant with the three standard offices. */
    public static void createTenant(JdbcTemplate jdbc, String tenantId) {
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?, ?)", tenantId, "Test tenant");
        for (String office : ACME_OFFICES) {
            jdbc.update("INSERT INTO office (tenant_id, code, name) VALUES (?, ?, ?)", tenantId, office, office);
        }
    }

    /**
     * Creates employees 1..n directly in SQL (200k in a few seconds), all ACTIVE at version 1, with the row hash
     * computed by Postgres in the same canonical form as RowHasher.
     */
    public static void seedEmployees(JdbcTemplate jdbc, String tenantId, int n, Instant lastChangedAt) {
        jdbc.update("""
                INSERT INTO employee (tenant_id, employee_id, status, hr_version, row_hash, last_hr_change_at,
                                      name, email, phone, manager_id, office_code, shift)
                SELECT ?, id, 'ACTIVE', 1,
                       encode(sha256(convert_to(concat_ws('|', name, email, phone, '', office, shift), 'UTF8')), 'hex'),
                       ?, name, email, phone, NULL, office, shift
                FROM (SELECT 'B' || lpad(i::text, 7, '0') AS id,
                             'Employee ' || i AS name,
                             'e' || i || '@bench.test' AS email,
                             '+9198' || lpad(i::text, 8, '0') AS phone,
                             (ARRAY['BLR-01', 'BLR-02', 'HYD-01'])[i % 3 + 1] AS office,
                             (ARRAY['S0900', 'S1400', 'S2200'])[i % 3 + 1] AS shift
                      FROM generate_series(1, ?) AS i) AS t
                """, tenantId, Timestamp.from(lastChangedAt), n);
    }

    /** Streams a nightly CSV to disk one line at a time, so even a 200k-row file never sits in memory. */
    public static Path writeCsv(Path file, CsvOptions options) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file)) {
            out.write("employee_id,version,name,email,phone,manager_id,office_code,shift\n");
            for (int i = 1; i <= options.rows(); i++) {
                if (options.truncateAt() > 0 && i > options.truncateAt()) {
                    break;
                }
                boolean changed = options.changeEvery() > 0 && i % options.changeEvery() == 0;
                boolean bad = options.badEvery() > 0 && i % options.badEvery() == 0;
                writeRow(out, i, changed ? 2 : 1, SHIFTS[(changed ? i + 1 : i) % 3], bad);
            }
            // New joiners get IDs far above any seeded employee.
            for (int k = 1; k <= options.extraNew(); k++) {
                writeRow(out, NEW_JOINER_BASE + k, 1, SHIFTS[k % 3], false);
            }
        }
        return file;
    }

    public static final int NEW_JOINER_BASE = 1_000_000;

    private static void writeRow(BufferedWriter out, int i, int version, String shift, boolean badPhone)
            throws IOException {
        String phone = badPhone ? "BAD-PHONE" : String.format("+9198%08d", i);
        out.write(benchId(i) + "," + version + ",Employee " + i + ",e" + i + "@bench.test,"
                + phone + ",," + ACME_OFFICES[i % 3] + "," + shift + "\n");
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
