package com.moveinsync.roster.service.outbox;

import com.moveinsync.roster.entity.OutboxEvent;

/**
 * Delivers one change event to whoever consumes it. Today an in-process implementation; in production a Kafka or
 * Redis Streams implementation would replace it without changing the outbox, the relay or any caller.
 * Throwing means "not delivered": the relay records the failure and retries later.
 */
public interface EventPublisher {

    void publish(OutboxEvent event);
}
