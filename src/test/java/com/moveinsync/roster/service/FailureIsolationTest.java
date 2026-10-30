package com.moveinsync.roster.service;

import static com.moveinsync.roster.SyntheticRosterGenerator.benchId;
import static com.moveinsync.roster.SyntheticRosterGenerator.createTenant;
import static com.moveinsync.roster.SyntheticRosterGenerator.event;
import static com.moveinsync.roster.SyntheticRosterGenerator.hr;
import static com.moveinsync.roster.SyntheticRosterGenerator.seedEmployees;
import static com.moveinsync.roster.SyntheticRosterGenerator.writeCsv;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.moveinsync.roster.IntegrationTest;
import com.moveinsync.roster.SyntheticRosterGenerator.CsvOptions;
import com.moveinsync.roster.repository.OfficeRepository;
import com.moveinsync.roster.service.employee.IngestService;
import com.moveinsync.roster.service.employee.IngestService.BatchResult;
import com.moveinsync.roster.service.reconciliation.ReconciliationService;
import com.moveinsync.roster.service.reconciliation.ReconciliationService.RunView;

/**
 * Failure injection: a stuck transaction holding a row lock, and an unexpected database error. In both cases only the
 * affected record fails (with a retryable code), nothing hangs, and the rest of the batch or run still goes through.
 * The test context uses a 2 s lock timeout (production default: 5 s).
 */
class FailureIsolationTest extends IntegrationTest {

    static final Instant T = Instant.parse("2026-10-01T09:00:00Z");
    static final Instant SNAPSHOT = Instant.parse("2026-10-29T22:00:00Z");

    @TempDir
    Path dir;

    @Autowired
    IngestService ingestService;

    @Autowired
    ReconciliationService reconciliationService;

    @Autowired
    OfficeRepository offices;

    @Autowired
    TransactionTemplate tx;

    ExecutorService lockHolder = Executors.newSingleThreadExecutor();
    String tenant;

    @BeforeEach
    void freshTenant() {
        tenant = uniqueId("FI");
        createTenant(jdbc, tenant);
    }

    @AfterEach
    void stopLockHolder() {
        lockHolder.shutdownNow();
    }

    @Test
    void lockHeldTooLongFailsOnlyThatRecordAndNothingHangs() throws Exception {
        ingestService.ingest(tenant, List.of(event("CREATE", "A", 1, T, hr("BLR-01", "S0900"))));
        List<IngestService.EventRequest> batch = List.of(
                event("UPDATE", "A", 2, T.plusSeconds(60), hr("HYD-01", "S0900")), // A's row is locked by someone else
                event("CREATE", "B", 1, T, hr("BLR-01", "S0900")));

        BatchResult result;
        long millis;
        try (AutoCloseable stuckTransaction = lockEmployee("A")) {
            long start = System.nanoTime();
            result = ingestService.ingest(tenant, batch);
            millis = (System.nanoTime() - start) / 1_000_000;
        }

        assertThat(result.applied()).isEqualTo(1);                    // B went through
        assertThat(result.failed()).isEqualTo(1);                     // only A failed
        assertThat(result.errors().get(0).index()).isZero();
        assertThat(result.errors().get(0).errors().get(0).code()).isEqualTo("LOCK_TIMEOUT");
        assertThat(millis).isBetween(1_500L, 10_000L);                // gave up after ~2 s instead of hanging

        BatchResult retry = ingestService.ingest(tenant, batch);      // lock released: the same batch is safe to resend
        assertThat(retry.applied()).isEqualTo(1);                     // A now applied
        assertThat(retry.duplicate()).isEqualTo(1);                   // B already done
        assertThat(retry.failed()).isZero();
    }

    @Test
    void unexpectedDatabaseErrorFailsOnlyThatRecord() {
        offices.findCodes(tenant); // offices now cached
        // The office disappears behind the cache's back: validation still passes, but the database foreign key refuses.
        jdbc.update("DELETE FROM office WHERE tenant_id = ? AND code = 'HYD-01'", tenant);

        BatchResult result = ingestService.ingest(tenant, List.of(
                event("CREATE", "X", 1, T, hr("HYD-01", "S0900")),
                event("CREATE", "Y", 1, T, hr("BLR-01", "S0900"))));

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).employeeId()).isEqualTo("X");
        assertThat(result.errors().get(0).errors().get(0).code()).isEqualTo("PROCESSING_FAILED");
        assertThat(result.applied()).isEqualTo(1); // Y still applied
    }

    @Test
    void lockedEmployeeFailsOnlyThatRowOfTheNightlyRun() throws Exception {
        seedEmployees(jdbc, tenant, 10, SNAPSHOT.minus(Duration.ofDays(1)));
        Path file = writeCsv(dir.resolve("night.csv"), CsvOptions.rows(10).changeEvery(5)); // rows 5 and 10 changed

        RunView run;
        try (AutoCloseable stuckTransaction = lockEmployee(benchId(5))) {
            run = start(file);
        }

        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.updated()).isEqualTo(1); // employee 10
        assertThat(run.failed()).isEqualTo(1);  // employee 5, locked

        RunView rerun = start(file);            // re-uploading the same file retries just the failed row
        assertThat(rerun.updated()).isEqualTo(1);
        assertThat(rerun.failed()).isZero();
    }

    /** Holds SELECT … FOR UPDATE on one employee in another thread, like a slow or stuck transaction, until closed. */
    private AutoCloseable lockEmployee(String employeeId) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = lockHolder.submit(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM employee WHERE tenant_id = ? AND employee_id = ? FOR UPDATE",
                    tenant, employeeId);
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        return () -> {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        };
    }

    private RunView start(Path file) throws Exception {
        try (InputStream in = new FileInputStream(file.toFile())) {
            return reconciliationService.start(tenant, in, SNAPSHOT);
        }
    }
}
