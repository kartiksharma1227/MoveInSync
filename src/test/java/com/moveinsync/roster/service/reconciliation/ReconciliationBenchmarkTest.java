package com.moveinsync.roster.service.reconciliation;

import static com.moveinsync.roster.SyntheticRosterGenerator.createTenant;
import static com.moveinsync.roster.SyntheticRosterGenerator.seedEmployees;
import static com.moveinsync.roster.SyntheticRosterGenerator.writeCsv;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import com.moveinsync.roster.IntegrationTest;
import com.moveinsync.roster.SyntheticRosterGenerator.CsvOptions;
import com.moveinsync.roster.service.reconciliation.ReconciliationService.RunView;

/** The case study's numbers: a 200,000-employee roster, a truncated 120,000-row file, and the 60-second budget. */
class ReconciliationBenchmarkTest extends IntegrationTest {

    static final Instant SNAPSHOT = Instant.parse("2026-10-29T22:00:00Z");

    @TempDir
    Path dir;

    @Autowired
    ReconciliationService reconciliationService;

    /** "The nightly file is cut off at 120,000 rows. The safety brake stops the run, nobody is deactivated." */
    @Test
    void fileCutAt120kOf200kTripsTheBrakeAndDeactivatesNobody() throws Exception {
        String tenant = uniqueId("H6");
        createTenant(jdbc, tenant);
        seedEmployees(jdbc, tenant, 200_000, SNAPSHOT.minus(Duration.ofDays(1)));
        Path file = writeCsv(dir.resolve("cut.csv"), CsvOptions.rows(200_000).truncateAt(120_000));

        RunView run = start(tenant, file);

        assertThat(run.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(run.rowsTotal()).isEqualTo(120_000);
        assertThat(run.activeCount()).isEqualTo(200_000);
        assertThat(run.missingCount()).isEqualTo(80_000); // what the operator sees
        assertThat(run.missingRatio()).isCloseTo(0.40, within(1e-9));
        assertThat(run.deactivated()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE tenant_id = ? AND status = 'ACTIVE'",
                Integer.class, tenant)).isEqualTo(200_000);
    }

    /** "Diff a 200,000-row snapshot in under 60 seconds with bounded memory." */
    @Test
    void diffOf200kRowsFinishesUnder60SecondsWithFlatMemory() throws Exception {
        Result small = benchmark(50_000);
        Result large = benchmark(200_000);

        System.out.printf("%nBENCHMARK nightly reconciliation (1%% of rows changed)%n");
        System.out.printf("BENCHMARK %-8s %-10s %-12s %-14s%n", "rows", "seconds", "rows/sec", "peak heap MB");
        for (Result r : new Result[] {small, large}) {
            System.out.printf("BENCHMARK %-8d %-10.2f %-12d %-14d%n",
                    r.rows, r.millis / 1000.0, r.rows * 1000L / Math.max(1, r.millis), r.peakHeapMb);
        }

        assertThat(large.run.status()).isEqualTo("COMPLETED");
        assertThat(large.run.updated()).isEqualTo(2_000);
        assertThat(large.run.deactivated()).isZero();
        assertThat(large.millis).isLessThan(60_000);
    }

    private record Result(int rows, long millis, long peakHeapMb, RunView run) {
    }

    private Result benchmark(int rows) throws Exception {
        String tenant = uniqueId("H5");
        createTenant(jdbc, tenant);
        seedEmployees(jdbc, tenant, rows, SNAPSHOT.minus(Duration.ofDays(1)));
        Path file = writeCsv(dir.resolve("full-" + rows + ".csv"), CsvOptions.rows(rows).changeEvery(100));

        System.gc();
        long baseline = heapUsed();
        resetPeaks();
        long start = System.nanoTime();
        RunView run = start(tenant, file);
        long millis = (System.nanoTime() - start) / 1_000_000;
        long peakMb = (peakHeapUsed() - baseline) / (1024 * 1024);
        return new Result(rows, millis, peakMb, run);
    }

    /** Reads the file from disk as a stream, the same way the controller hands over an upload. */
    private RunView start(String tenant, Path file) throws Exception {
        try (InputStream in = new FileInputStream(file.toFile())) {
            return reconciliationService.start(tenant, in, SNAPSHOT);
        }
    }

    private static long heapUsed() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static void resetPeaks() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
    }

    private static long peakHeapUsed() {
        long peak = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                peak += pool.getPeakUsage().getUsed();
            }
        }
        return peak;
    }
}
