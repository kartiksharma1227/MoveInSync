package com.moveinsync.roster.perf;

import static com.moveinsync.roster.SyntheticRosterGenerator.createTenant;
import static com.moveinsync.roster.SyntheticRosterGenerator.event;
import static com.moveinsync.roster.SyntheticRosterGenerator.hr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.moveinsync.roster.IntegrationTest;
import com.moveinsync.roster.service.employee.IngestService.EventRequest;

/**
 * Load test for the real-time API: 8 concurrent clients each send 250 batches of 10 new employees. Every request goes
 * through the full stack (correlation filter, JWT check, JSON, validation, row lock, outbox insert, Postgres), but
 * in-process, so there's no network hop. Reports throughput and latency percentiles.
 */
class IngestLoadTest extends IntegrationTest {

    static final int THREADS = 8;
    static final int REQUESTS_PER_THREAD = 250;
    static final int EVENTS_PER_REQUEST = 10;
    static final int WARM_UP_REQUESTS = 100;
    static final Instant T = Instant.parse("2026-10-01T09:00:00Z");

    @Test
    void ingestApiUnderConcurrentLoad() throws Exception {
        String tenant = uniqueId("L");
        createTenant(jdbc, tenant);
        String hrToken = token("load-hr", tenant, "HR_SYSTEM");

        // Request bodies are built up front, so the timings measure the server, not the test client.
        List<List<String>> bodiesPerThread = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            List<String> bodies = new ArrayList<>();
            for (int r = 0; r < REQUESTS_PER_THREAD; r++) {
                bodies.add(batch("T" + t + "-R" + r));
            }
            bodiesPerThread.add(bodies);
        }
        for (int w = 0; w < WARM_UP_REQUESTS; w++) { // let the JIT and connection pool warm up; not measured
            send(hrToken, batch("WARM-" + w));
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Long>>> results = new ArrayList<>();
        for (List<String> bodies : bodiesPerThread) {
            Callable<List<Long>> client = () -> {
                start.await();
                List<Long> latencies = new ArrayList<>();
                for (String body : bodies) {
                    long begin = System.nanoTime();
                    send(hrToken, body);
                    latencies.add(System.nanoTime() - begin);
                }
                return latencies;
            };
            results.add(pool.submit(client));
        }
        long wallStart = System.nanoTime();
        start.countDown();
        List<Long> all = new ArrayList<>();
        for (Future<List<Long>> result : results) {
            all.addAll(result.get(5, TimeUnit.MINUTES));
        }
        double wallSeconds = (System.nanoTime() - wallStart) / 1e9;
        pool.shutdown();

        Collections.sort(all);
        int requests = all.size();
        System.out.printf("%nLOADTEST POST /api/v1/events: %d clients x %d requests x %d events%n",
                THREADS, REQUESTS_PER_THREAD, EVENTS_PER_REQUEST);
        System.out.printf("LOADTEST requests=%d seconds=%.2f requests/s=%.0f events/s=%.0f%n",
                requests, wallSeconds, requests / wallSeconds, requests * EVENTS_PER_REQUEST / wallSeconds);
        System.out.printf("LOADTEST latency ms: p50=%.1f p95=%.1f p99=%.1f max=%.1f%n",
                ms(percentile(all, 0.50)), ms(percentile(all, 0.95)), ms(percentile(all, 0.99)), ms(all.get(requests - 1)));

        int created = jdbc.queryForObject("SELECT count(*) FROM employee WHERE tenant_id = ? AND employee_id LIKE 'T%'",
                Integer.class, tenant);
        assertThat(requests).isEqualTo(THREADS * REQUESTS_PER_THREAD);
        assertThat(created).isEqualTo(THREADS * REQUESTS_PER_THREAD * EVENTS_PER_REQUEST);
    }

    private String batch(String prefix) {
        List<EventRequest> events = new ArrayList<>();
        for (int e = 0; e < EVENTS_PER_REQUEST; e++) {
            events.add(event("CREATE", prefix + "-E" + e, 1, T, hr("BLR-01", "S0900")));
        }
        return json.writeValueAsString(events);
    }

    private void send(String token, String body) throws Exception {
        int status = mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);
    }

    private static long percentile(List<Long> sorted, double q) {
        int index = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    private static double ms(long nanos) {
        return nanos / 1e6;
    }
}
