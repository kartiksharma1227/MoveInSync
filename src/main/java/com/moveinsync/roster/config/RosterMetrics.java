package com.moveinsync.roster.config;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.moveinsync.roster.entity.OutboxEvent;
import com.moveinsync.roster.repository.OutboxRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * The numbers an on-call engineer needs at 3 AM, in one place: ingest outcomes and rejections, outbox queue depth,
 * lag and retries, and how the nightly run went. Exposed at /actuator/prometheus.
 * Tags hold only low-cardinality values (tenant, outcome, type, status), never employee IDs.
 */
@Component
public class RosterMetrics {

    private final MeterRegistry registry;
    private final Timer ingestBatch;
    private final Timer reconDuration;

    public RosterMetrics(MeterRegistry registry, OutboxRepository outbox,
                         @Value("${roster.outbox.max-attempts}") int maxAttempts) {
        this.registry = registry;
        this.ingestBatch = Timer.builder("roster.ingest.batch")
                .description("Time to process one batch of HR events")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.reconDuration = Timer.builder("roster.recon.duration")
                .description("Time to stage, check and apply one nightly file")
                .register(registry);

        // Gauges are read from the database each time Prometheus scrapes, so they're always current.
        Gauge.builder("roster.outbox.pending", () -> outbox.countPending(maxAttempts))
                .description("Queue depth: change events waiting to be published")
                .register(registry);
        Gauge.builder("roster.outbox.parked", () -> outbox.countParked(maxAttempts))
                .description("Events that failed too often and need a human")
                .register(registry);
        Gauge.builder("roster.outbox.lag.seconds", () -> {
                    Double age = outbox.oldestPendingAgeSeconds(maxAttempts);
                    return age == null ? 0 : age;
                })
                .description("Age of the oldest event still waiting to be published")
                .register(registry);
    }

    /** @param outcome applied, duplicate, stale, version_conflict or rejected */
    public void events(String tenantId, String outcome, int count) {
        if (count > 0) {
            registry.counter("roster.events", "tenant", tenantId, "outcome", outcome).increment(count);
        }
    }

    public <T> T timeIngestBatch(Supplier<T> work) {
        return ingestBatch.record(work);
    }

    public void published(OutboxEvent.Type type) {
        registry.counter("roster.outbox.published", "type", type.name()).increment();
    }

    /** Every failure is followed by a retry on the next poll, so this is also the retry count. */
    public void publishFailed(OutboxEvent.Type type) {
        registry.counter("roster.outbox.publish.failures", "type", type.name()).increment();
    }

    /** @param status completed, held, rejected or failed */
    public void reconRun(String tenantId, String status) {
        registry.counter("roster.recon.runs", "tenant", tenantId, "status", status).increment();
    }

    public void brakeTripped(String tenantId) {
        registry.counter("roster.recon.brake.tripped", "tenant", tenantId).increment();
    }

    public void reconRowsRejected(String tenantId, int count) {
        if (count > 0) {
            registry.counter("roster.recon.rows.rejected", "tenant", tenantId).increment(count);
        }
    }

    public void reconDuration(Duration duration) {
        reconDuration.record(duration);
    }

    public void stuckRunsRecovered(int count) {
        if (count > 0) {
            registry.counter("roster.recon.stuck.recovered").increment(count);
        }
    }
}
