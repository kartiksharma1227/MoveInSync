package com.moveinsync.roster.controller.reconciliation;

import static com.moveinsync.roster.SyntheticRosterGenerator.NEW_JOINER_BASE;
import static com.moveinsync.roster.SyntheticRosterGenerator.benchId;
import static com.moveinsync.roster.SyntheticRosterGenerator.createTenant;
import static com.moveinsync.roster.SyntheticRosterGenerator.event;
import static com.moveinsync.roster.SyntheticRosterGenerator.hr;
import static com.moveinsync.roster.SyntheticRosterGenerator.seedEmployees;
import static com.moveinsync.roster.SyntheticRosterGenerator.writeCsv;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.moveinsync.roster.IntegrationTest;
import com.moveinsync.roster.SyntheticRosterGenerator.CsvOptions;
import com.moveinsync.roster.service.employee.IngestService;
import com.moveinsync.roster.service.outbox.OutboxRelay;
import com.moveinsync.roster.service.reconciliation.ReconciliationService;
import com.moveinsync.roster.service.reconciliation.ReconciliationService.RunView;

/** Nightly reconciliation end to end, on small files. Every test uses its own tenant so counts are isolated. */
class ReconciliationTest extends IntegrationTest {

    static final Instant SNAPSHOT = Instant.parse("2026-10-29T22:00:00Z");
    static final Instant BEFORE_SNAPSHOT = SNAPSHOT.minus(Duration.ofDays(1));

    @TempDir
    Path dir;

    @Autowired
    ReconciliationService reconciliationService;

    @Autowired
    IngestService ingestService;

    @Autowired
    OutboxRelay relay;

    String tenant;
    String hr;
    String operator;

    @BeforeEach
    void tenantWithTenEmployees() throws Exception {
        tenant = uniqueId("R");
        createTenant(jdbc, tenant);
        seedEmployees(jdbc, tenant, 10, BEFORE_SNAPSHOT);
        hr = token("hr", tenant, "HR_SYSTEM");
        operator = token("ops", tenant, "OPERATOR");
    }

    @Test
    void appliesChangedNewAndMissingEmployees() throws Exception {
        long trip = jdbc.queryForObject("INSERT INTO trip (tenant_id, pickup_at, capacity, seats_booked) "
                + "VALUES (?, now() + interval '1 day', 4, 1) RETURNING id", Long.class, tenant);
        jdbc.update("INSERT INTO booking (tenant_id, employee_id, trip_id, status) VALUES (?, ?, ?, 'CONFIRMED')",
                tenant, benchId(9), trip);

        // Rows 1..8 (9 and 10 missing = 20%), rows 4 and 8 changed, plus 2 new joiners.
        Path file = writeCsv(dir.resolve("night.csv"), CsvOptions.rows(8).changeEvery(4).extraNew(2));

        upload(file)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.activeCount").value(10))
                .andExpect(jsonPath("$.missingCount").value(2))
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.updated").value(2))
                .andExpect(jsonPath("$.deactivated").value(2));

        assertThat(employeeStatus(benchId(9))).isEqualTo("INACTIVE");
        assertThat(shift(benchId(4))).isEqualTo("S2200"); // seeded S1400 (4 % 3 = 1), changed to shift (4 + 1) % 3
        assertThat(employeeStatus(benchId(NEW_JOINER_BASE + 1))).isEqualTo("ACTIVE");
        // Downstream effect: the missing employee's future booking is cancelled once the event is published.
        while (relay.publishPending() > 0) {
            // drain
        }
        assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE trip_id = ?", String.class, trip))
                .isEqualTo("CANCELLED");
    }

    @Test
    void employeeWhoJoinedAfterTheExportIsNotDeactivated() throws Exception {
        String lateJoiner = "LATE-1";
        ingestService.ingest(tenant, List.of(event("CREATE", lateJoiner, 1, SNAPSHOT.plusSeconds(3600), hr("BLR-01", "S0900"))));

        upload(writeCsv(dir.resolve("night.csv"), CsvOptions.rows(10)))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.missingCount").value(0))
                .andExpect(jsonPath("$.deactivated").value(0));

        assertThat(employeeStatus(lateJoiner)).isEqualTo("ACTIVE");
    }

    @Test
    void fileCannotRevertANewerRealTimeChange() throws Exception {
        ingestService.ingest(tenant, List.of(event("UPDATE", benchId(3), 3, BEFORE_SNAPSHOT.plusSeconds(60), hr("HYD-01", "S2200"))));

        upload(writeCsv(dir.resolve("night.csv"), CsvOptions.rows(10))) // row 3 still says version 1
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.updated").value(0));

        assertThat(shift(benchId(3))).isEqualTo("S2200");
    }

    @Test
    void rejectedRowIsReportedButItsEmployeeIsNotTreatedAsMissing() throws Exception {
        String body = upload(writeCsv(dir.resolve("night.csv"), CsvOptions.rows(10).badEvery(5)))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.rowsRejected").value(2))
                .andExpect(jsonPath("$.rejections[0].row").value(5))
                .andExpect(jsonPath("$.rejections[0].employeeId").value(benchId(5)))
                .andExpect(jsonPath("$.rejections[0].errors[0].code").value("INVALID_FORMAT"))
                .andExpect(jsonPath("$.missingCount").value(0))
                .andExpect(jsonPath("$.deactivated").value(0))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("BAD-PHONE");
        assertThat(employeeStatus(benchId(5))).isEqualTo("ACTIVE");
    }

    @Test
    void rowWithPersonalDataInTheIdColumnIsRejectedWithoutEchoingIt() throws Exception {
        Path file = writeCsv(dir.resolve("night.csv"), CsvOptions.rows(10));
        Files.writeString(file, "Priya Sharma +919811111111,1,Priya,p@acme.test,+919811111111,,BLR-01,S0900\n",
                StandardOpenOption.APPEND);

        String body = upload(file)
                .andExpect(jsonPath("$.rowsRejected").value(1))
                .andExpect(jsonPath("$.rejections[0].row").value(11))
                .andExpect(jsonPath("$.rejections[0].employeeId").doesNotExist())
                .andExpect(jsonPath("$.rejections[0].errors[0].code").value("INVALID_FORMAT"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Priya", "+919811111111");
    }

    @Test
    void brakeHoldsTheRunUntilAnOperatorApproves() throws Exception {
        String body = upload(writeCsv(dir.resolve("night.csv"), CsvOptions.rows(6))) // 4 of 10 missing = 40%
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.missingCount").value(4))
                .andExpect(jsonPath("$.missingRatio").value(0.4))
                .andExpect(jsonPath("$.deactivated").value(0))
                .andReturn().getResponse().getContentAsString();
        long runId = ((Number) JsonPath.read(body, "$.id")).longValue();
        assertThat(activeCount()).isEqualTo(10); // nobody deactivated while held

        mockMvc.perform(post("/api/v1/reconciliations/{id}/approve", runId).header("Authorization", operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.deactivated").value(4))
                .andExpect(jsonPath("$.decidedBy").value("ops"));
        assertThat(activeCount()).isEqualTo(6);

        mockMvc.perform(post("/api/v1/reconciliations/{id}/approve", runId).header("Authorization", operator))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectedRunChangesNothingAndCleansUp() throws Exception {
        String body = upload(writeCsv(dir.resolve("night.csv"), CsvOptions.rows(6)))
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"))
                .andReturn().getResponse().getContentAsString();
        long runId = ((Number) JsonPath.read(body, "$.id")).longValue();

        mockMvc.perform(post("/api/v1/reconciliations/{id}/reject", runId).header("Authorization", operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(activeCount()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM snapshot_row WHERE run_id = ?", Integer.class, runId))
                .isZero();
    }

    @Test
    void thresholdIsConfigurableAndRecordedOnEachRun() throws Exception {
        Path file = writeCsv(dir.resolve("night.csv"), CsvOptions.rows(6)); // 40% missing

        RunView lenient;
        try (InputStream in = new FileInputStream(file.toFile())) {
            lenient = reconciliationService.start(tenant, in, SNAPSHOT, 0.50);
        }

        assertThat(lenient.status()).isEqualTo("COMPLETED");
        assertThat(lenient.threshold()).isEqualByComparingTo("0.5");
        assertThat(lenient.deactivated()).isEqualTo(4);
    }

    @Test
    void sameFileTwiceChangesNothingTheSecondTime() throws Exception {
        Path file = writeCsv(dir.resolve("night.csv"), CsvOptions.rows(10).changeEvery(5));

        upload(file).andExpect(jsonPath("$.updated").value(2));
        upload(file)
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.deactivated").value(0));
    }

    @Test
    void employeeMissingOneNightIsRestoredWhenTheyReappear() throws Exception {
        upload(writeCsv(dir.resolve("night1.csv"), CsvOptions.rows(9))) // employee 10 missing (10%)
                .andExpect(jsonPath("$.deactivated").value(1));
        assertThat(employeeStatus(benchId(10))).isEqualTo("INACTIVE");

        upload(writeCsv(dir.resolve("night2.csv"), CsvOptions.rows(10))) // back, unchanged
                .andExpect(jsonPath("$.updated").value(1));
        assertThat(employeeStatus(benchId(10))).isEqualTo("ACTIVE");
    }

    /** What a crash mid-staging leaves behind: a run stuck in STAGING with half its rows, blocking the tenant. */
    @Test
    void runLeftBehindByACrashIsRecoveredAndUnblocksTheTenant() throws Exception {
        long stuckRunId = jdbc.queryForObject("""
                INSERT INTO reconciliation_run (tenant_id, status, snapshot_at, file_sha256, threshold, updated_at)
                VALUES (?, 'STAGING', ?, repeat('0', 64), 0.3, now() - interval '2 hours') RETURNING id
                """, Long.class, tenant, Timestamp.from(SNAPSHOT));
        jdbc.update("INSERT INTO snapshot_row (run_id, employee_id, version, row_hash, payload) "
                + "VALUES (?, ?, 1, repeat('0', 64), '{}')", stuckRunId, benchId(1));
        Path file = writeCsv(dir.resolve("night.csv"), CsvOptions.rows(10));

        upload(file).andExpect(status().isConflict()); // blocked by the dead run

        assertThat(reconciliationService.failStuckRuns()).isPositive();

        assertThat(jdbc.queryForObject("SELECT status FROM reconciliation_run WHERE id = ?", String.class, stuckRunId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM snapshot_row WHERE run_id = ?", Integer.class, stuckRunId))
                .isZero();
        upload(file).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED")); // unblocked
    }

    @Test
    void runThatIsStillMakingProgressIsLeftAlone() {
        long activeRunId = jdbc.queryForObject("""
                INSERT INTO reconciliation_run (tenant_id, status, snapshot_at, file_sha256, threshold, updated_at)
                VALUES (?, 'APPLYING', ?, repeat('0', 64), 0.3, now()) RETURNING id
                """, Long.class, tenant, Timestamp.from(SNAPSHOT));

        reconciliationService.failStuckRuns();

        assertThat(jdbc.queryForObject("SELECT status FROM reconciliation_run WHERE id = ?", String.class, activeRunId))
                .isEqualTo("APPLYING");
    }

    @Test
    void snapshotTakenInTheFutureIsRefusedAndNoRunIsCreated() throws Exception {
        Path file = writeCsv(dir.resolve("night.csv"), CsvOptions.rows(10));

        mockMvc.perform(multipart("/api/v1/reconciliations")
                        .file(csvPart(file))
                        .param("snapshotAt", Instant.now().plus(Duration.ofHours(1)).toString())
                        .header("Authorization", hr))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SNAPSHOT_TIME"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_run WHERE tenant_id = ?", Integer.class,
                tenant)).isZero();
        assertThat(activeCount()).isEqualTo(10);
    }

    @Test
    void completeRejectionReportIsPagedAndContainsNoValues() throws Exception {
        Path file = writeCsv(dir.resolve("bad.csv"), CsvOptions.rows(250).badEvery(1)); // every row has a bad phone
        String body = upload(file)
                .andExpect(jsonPath("$.rowsRejected").value(250))
                .andExpect(jsonPath("$.rejections.length()").value(100)) // inline preview stays at 100
                .andReturn().getResponse().getContentAsString();
        long runId = ((Number) JsonPath.read(body, "$.id")).longValue();

        List<Integer> rows = new ArrayList<>();
        Long after = 0L;
        int pages = 0;
        while (after != null) {
            String page = mockMvc.perform(get("/api/v1/reconciliations/{id}/rejections", runId)
                            .param("afterRow", after.toString()).param("limit", "100")
                            .header("Authorization", hr))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(page).doesNotContain("BAD-PHONE", "@bench.test", "Employee ");
            List<Integer> pageRows = JsonPath.read(page, "$.items[*].row");
            rows.addAll(pageRows);
            Number next = JsonPath.read(page, "$.nextAfterRow");
            after = next == null ? null : next.longValue();
            pages++;
        }

        assertThat(rows).hasSize(250).doesNotHaveDuplicates().isSorted(); // all 250, each once, in file order
        assertThat(rows.get(0)).isEqualTo(1);
        assertThat(rows.get(249)).isEqualTo(250);
        assertThat(pages).isEqualTo(3);
        mockMvc.perform(get("/api/v1/reconciliations/{id}/rejections", runId).header("Authorization", operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].employeeId").value(benchId(1)))
                .andExpect(jsonPath("$.items[0].errors[0].field").value("phone"))
                .andExpect(jsonPath("$.items[0].errors[0].code").value("INVALID_FORMAT"));
        mockMvc.perform(get("/api/v1/reconciliations/{id}/rejections", runId)
                        .header("Authorization", token("ops", "GLOBEX", "OPERATOR")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/reconciliations/{id}/rejections", runId)
                        .header("Authorization", token("admin", tenant, "TRANSPORT_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void wrongHeaderIsABadRequest() throws Exception {
        Path file = dir.resolve("bad.csv");
        Files.writeString(file, "id,name\n1,someone\n");

        upload(file).andExpect(status().isBadRequest());
    }

    @Test
    void onlyHrUploadsAndOperatorsSeeOnlyTheirOwnTenantsRuns() throws Exception {
        Path file = writeCsv(dir.resolve("night.csv"), CsvOptions.rows(10));
        mockMvc.perform(multipart("/api/v1/reconciliations").file(csvPart(file)).param("snapshotAt", SNAPSHOT.toString())
                        .header("Authorization", token("admin", tenant, "TRANSPORT_ADMIN")))
                .andExpect(status().isForbidden());

        String body = upload(file).andReturn().getResponse().getContentAsString();
        long runId = ((Number) JsonPath.read(body, "$.id")).longValue();

        mockMvc.perform(get("/api/v1/reconciliations/{id}", runId).header("Authorization", operator))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/reconciliations/{id}", runId)
                        .header("Authorization", token("ops", "GLOBEX", "OPERATOR")))
                .andExpect(status().isNotFound());
    }

    // ---- helpers ----

    private ResultActions upload(Path file) throws Exception {
        return mockMvc.perform(multipart("/api/v1/reconciliations")
                .file(csvPart(file))
                .param("snapshotAt", SNAPSHOT.toString())
                .header("Authorization", hr));
    }

    private static MockMultipartFile csvPart(Path file) throws Exception {
        return new MockMultipartFile("file", "roster.csv", "text/csv", Files.readAllBytes(file));
    }

    private String employeeStatus(String employeeId) {
        return jdbc.queryForObject("SELECT status FROM employee WHERE tenant_id = ? AND employee_id = ?",
                String.class, tenant, employeeId);
    }

    private String shift(String employeeId) {
        return jdbc.queryForObject("SELECT shift FROM employee WHERE tenant_id = ? AND employee_id = ?",
                String.class, tenant, employeeId);
    }

    private int activeCount() {
        return jdbc.queryForObject("SELECT count(*) FROM employee WHERE tenant_id = ? AND status = 'ACTIVE'",
                Integer.class, tenant);
    }
}
