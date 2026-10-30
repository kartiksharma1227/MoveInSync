package com.moveinsync.roster.config;

import static com.moveinsync.roster.SyntheticRosterGenerator.createTenant;
import static com.moveinsync.roster.SyntheticRosterGenerator.event;
import static com.moveinsync.roster.SyntheticRosterGenerator.hr;
import static com.moveinsync.roster.SyntheticRosterGenerator.seedEmployees;
import static com.moveinsync.roster.SyntheticRosterGenerator.writeCsv;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.moveinsync.roster.IntegrationTest;
import com.moveinsync.roster.SyntheticRosterGenerator.CsvOptions;
import com.moveinsync.roster.service.employee.EmployeeService;
import com.moveinsync.roster.service.employee.EmployeeService.TransportRequest;
import com.moveinsync.roster.service.employee.IngestService;
import com.moveinsync.roster.service.employee.IngestService.EventRequest;
import com.moveinsync.roster.service.employee.IngestService.HrRequest;
import com.moveinsync.roster.service.outbox.OutboxRelay;
import com.moveinsync.roster.service.reconciliation.ReconciliationService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** The on-call metrics move when they should, the endpoint is protected, and logs never contain personal data. */
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityTest extends IntegrationTest {

    static final Instant T = Instant.parse("2026-10-01T09:00:00Z");

    @TempDir
    Path dir;

    @Autowired
    MeterRegistry registry;

    @Autowired
    IngestService ingestService;

    @Autowired
    EmployeeService employeeService;

    @Autowired
    OutboxRelay relay;

    @Autowired
    ReconciliationService reconciliationService;

    String tenant;

    @BeforeEach
    void freshTenantAndEmptyQueue() {
        tenant = uniqueId("M");
        createTenant(jdbc, tenant);
        drain();
    }

    @Test
    void everyIngestOutcomeIsCountedPerTenant() {
        HrRequest badPhone = new HrRequest("Test Person", "t@acme.test", "12345", null, "BLR-01", "S0900");
        ingestService.ingest(tenant, List.of(
                event("CREATE", "A", 1, T, hr("BLR-01", "S0900")),
                event("CREATE", "A", 1, T, hr("BLR-01", "S0900")),     // duplicate
                event("CREATE", "B", 2, T, hr("BLR-01", "S0900")),
                event("UPDATE", "B", 1, T, hr("HYD-01", "S0900")),     // stale
                event("CREATE", "C", 1, T, badPhone)));                // rejected

        assertThat(events("applied")).isEqualTo(2);
        assertThat(events("duplicate")).isEqualTo(1);
        assertThat(events("stale")).isEqualTo(1);
        assertThat(events("rejected")).isEqualTo(1);
        assertThat(registry.get("roster.ingest.batch").timer().count()).isPositive();
    }

    @Test
    void queueDepthAndLagFollowTheOutbox() {
        ingestService.ingest(tenant, List.of(
                event("CREATE", "Q1", 1, T, hr("BLR-01", "S0900")),
                event("CREATE", "Q2", 1, T, hr("BLR-01", "S0900"))));

        long pendingInDb = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE published_at IS NULL AND attempts < 10", Long.class);
        assertThat(gauge("roster.outbox.pending")).isEqualTo(pendingInDb).isGreaterThanOrEqualTo(2);
        assertThat(gauge("roster.outbox.lag.seconds")).isGreaterThanOrEqualTo(0);

        drain();

        assertThat(gauge("roster.outbox.pending")).isZero();
        assertThat(gauge("roster.outbox.lag.seconds")).isZero();
    }

    @Test
    void failedPublishIsCountedAsARetry() {
        double before = counter("roster.outbox.publish.failures", "type", "EMPLOYEE_CREATED");
        ingestService.ingest(tenant, List.of(event("CREATE", "F1", 1, T, hr("BLR-01", "S0900"))));
        publisher.failNext(1);

        relay.publishPending();

        assertThat(counter("roster.outbox.publish.failures", "type", "EMPLOYEE_CREATED")).isEqualTo(before + 1);
    }

    @Test
    void heldNightlyRunCountsAsABrakeTrip() throws Exception {
        seedEmployees(jdbc, tenant, 10, T);
        Path file = writeCsv(dir.resolve("cut.csv"), CsvOptions.rows(6)); // 40% missing
        try (InputStream in = new FileInputStream(file.toFile())) {
            reconciliationService.start(tenant, in, T.plusSeconds(86_400));
        }

        assertThat(counter("roster.recon.brake.tripped", "tenant", tenant)).isEqualTo(1);
        assertThat(counter("roster.recon.runs", "tenant", tenant, "status", "held")).isEqualTo(1);
    }

    @Test
    void prometheusEndpointIsForOperatorsOnly() throws Exception {
        ingestService.ingest(tenant, List.of(event("CREATE", "PR1", 1, T, hr("BLR-01", "S0900")))); // so counters exist
        String scrape = mockMvc.perform(get("/actuator/prometheus").header("Authorization", token("prom", "ACME", "OPERATOR")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(scrape).contains("roster_outbox_pending", "roster_outbox_lag_seconds", "roster_events_total");

        mockMvc.perform(get("/actuator/prometheus").header("Authorization", token("admin", "ACME", "TRANSPORT_ADMIN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    }

    /** H11: run every flow that handles personal data and check none of it reaches the logs. */
    @Test
    void noPersonalDataAppearsInTheLogs(CapturedOutput output) throws Exception {
        String name = "Zelda Quixotic", email = "zelda.quixotic@acme.test", phone = "+919900112233";
        String badPhone = "99-00-PII-7788", pickup = "Flat 9 Quixotic Lane";
        ingestService.ingest(tenant, List.of(
                new EventRequest("e1", "CREATE", "P1", 1L, T, new HrRequest(name, email, phone, null, "BLR-01", "S0900")),
                new EventRequest("e2", "CREATE", "P2", 1L, T, new HrRequest(name, email, badPhone, null, "BLR-01", "S0900"))));
        employeeService.updateTransport(new CurrentUser("admin", tenant, Set.of("TRANSPORT_ADMIN")), "P1",
                new TransportRequest(pickup, null));
        ingestService.ingest(tenant, List.of(new EventRequest("e3", "DEACTIVATE", "P1", 2L, T, null)));
        drain();
        seedEmployees(jdbc, tenant, 5, T);
        try (InputStream in = new FileInputStream(writeCsv(dir.resolve("n.csv"), CsvOptions.rows(5).badEvery(2)).toFile())) {
            reconciliationService.start(tenant, in, T.plusSeconds(86_400));
        }

        assertThat(output.getAll())
                .contains("Ingested batch tenant=" + tenant) // the flows really logged something
                .doesNotContain(name, email, phone, badPhone, pickup, "BAD-PHONE", "@bench.test");
    }

    // ---- helpers ----

    private void drain() {
        while (relay.publishPending() > 0) {
            // keep publishing until the queue is empty
        }
    }

    private double events(String outcome) {
        return counter("roster.events", "tenant", tenant, "outcome", outcome);
    }

    private double counter(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }
}
