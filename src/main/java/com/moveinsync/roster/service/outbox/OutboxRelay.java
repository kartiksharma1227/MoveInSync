package com.moveinsync.roster.service.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.moveinsync.roster.entity.OutboxEvent;
import com.moveinsync.roster.repository.OutboxRepository;

/**
 * Publishes saved change events in the background. It keeps no state in memory: everything it needs is in the
 * outbox table, so after a crash a restarted process simply carries on where the last one stopped.
 * Delivery is at-least-once: a crash after publishing but before marking the row means it is published again.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final int batchSize;
    private final int maxAttempts;
    private final int retentionDays;

    public OutboxRelay(OutboxRepository outbox, EventPublisher publisher, TransactionTemplate tx,
                       @Value("${roster.outbox.relay-enabled}") boolean enabled,
                       @Value("${roster.outbox.batch-size}") int batchSize,
                       @Value("${roster.outbox.max-attempts}") int maxAttempts,
                       @Value("${roster.outbox.retention-days}") int retentionDays) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.tx = tx;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${roster.outbox.poll-interval-ms}")
    public void poll() {
        if (enabled) {
            publishPending();
        }
    }

    /** Publishes one batch of pending events. @return how many were published */
    public int publishPending() {
        Integer published = tx.execute(status -> {
            List<OutboxEvent> batch = outbox.lockNextBatch(maxAttempts, batchSize);
            int count = 0;
            for (OutboxEvent event : batch) {
                try {
                    publisher.publish(event);
                    event.markPublished(Instant.now());
                    count++;
                } catch (Exception e) {
                    // One failing event must not block the others; it is retried on the next poll.
                    event.markFailed(e.getClass().getSimpleName());
                    log.warn("Publish failed eventId={} type={} attempts={} error={}",
                            event.getEventId(), event.getType(), event.getAttempts(), event.getLastError());
                    if (event.getAttempts() >= maxAttempts) {
                        log.error("Outbox event parked after {} attempts eventId={}", maxAttempts, event.getEventId());
                    }
                }
            }
            return count;
        });
        return published == null ? 0 : published;
    }

    /** Published events are kept for a few days for troubleshooting, then deleted so the table stays small. */
    @Scheduled(cron = "0 0 3 * * *")
    public void purgePublished() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        Integer deleted = tx.execute(status -> outbox.deletePublishedBefore(cutoff));
        log.info("Purged {} published outbox events older than {} days", deleted, retentionDays);
    }
}
