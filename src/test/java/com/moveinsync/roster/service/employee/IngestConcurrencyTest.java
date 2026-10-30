package com.moveinsync.roster.service.employee;

import static com.moveinsync.roster.SyntheticRosterGenerator.event;
import static com.moveinsync.roster.SyntheticRosterGenerator.hr;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.moveinsync.roster.IntegrationTest;
import com.moveinsync.roster.service.employee.IngestService.BatchResult;

/** Many threads hit the same employee at once; the row lock and unique key must keep the result correct. */
class IngestConcurrencyTest extends IntegrationTest {

    static final Instant T = Instant.parse("2026-10-01T09:00:00Z");

    @Autowired
    IngestService ingestService;

    @Test
    void parallelVersionsOfOneEmployee_highestVersionWins() throws Exception {
        String id = uniqueId("CC");
        ingestService.ingest("ACME", List.of(event("CREATE", id, 1, T, hr("BLR-01", "S0900"))));

        List<Integer> versions = new ArrayList<>();
        for (int v = 2; v <= 20; v++) {
            versions.add(v);
        }
        Collections.shuffle(versions);
        List<Callable<BatchResult>> tasks = new ArrayList<>();
        for (int v : versions) {
            tasks.add(() -> ingestService.ingest("ACME",
                    List.of(event("UPDATE", id, v, T.plusSeconds(v), hr("BLR-01", "V" + v)))));
        }
        runAllAtOnce(tasks);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT count(*) OVER () AS rows, hr_version, shift FROM employee WHERE tenant_id = 'ACME' AND employee_id = ?", id);
        assertThat(row.get("rows")).isEqualTo(1L);
        assertThat(row.get("hr_version")).isEqualTo(20L);
        assertThat(row.get("shift")).isEqualTo("V20"); // the state of version 20, not of whichever thread ran last
    }

    @Test
    void simultaneousCreatesOfANewEmployee_exactlyOneApplies() throws Exception {
        String id = uniqueId("CR");
        List<Callable<BatchResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> ingestService.ingest("ACME", List.of(event("CREATE", id, 1, T, hr("BLR-01", "S0900")))));
        }

        List<BatchResult> results = runAllAtOnce(tasks);

        assertThat(results.stream().mapToInt(BatchResult::applied).sum()).isEqualTo(1);
        assertThat(results.stream().mapToInt(BatchResult::duplicate).sum()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE tenant_id = 'ACME' AND employee_id = ?",
                Integer.class, id)).isEqualTo(1);
    }

    /** Starts every task at the same instant to maximise contention, then waits for all of them. */
    private <T> List<T> runAllAtOnce(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS)); // rethrows any exception from the task
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
