package com.moveinsync.roster;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.moveinsync.roster.entity.OutboxEvent;
import com.moveinsync.roster.service.outbox.EventPublisher;
import com.moveinsync.roster.service.outbox.InProcessEventPublisher;

/** Replaces the publisher in tests with one that records deliveries and can inject failures. */
@TestConfiguration(proxyBeanMethods = false)
public class TestPublisherConfig {

    @Bean
    @Primary
    RecordingPublisher recordingPublisher(InProcessEventPublisher real) {
        return new RecordingPublisher(real);
    }

    /** Stands in for the process dying. An Error, not an Exception, so the relay's error handling never sees it. */
    public static class SimulatedCrash extends Error {
        public SimulatedCrash() {
            super("simulated process crash during publish");
        }
    }

    public static class RecordingPublisher implements EventPublisher {

        private final EventPublisher real;
        private final List<UUID> delivered = new CopyOnWriteArrayList<>();
        private final AtomicInteger failuresLeft = new AtomicInteger();
        private final AtomicBoolean crashNext = new AtomicBoolean();
        private final Set<String> poisonedEmployees = ConcurrentHashMap.newKeySet();

        RecordingPublisher(EventPublisher real) {
            this.real = real;
        }

        @Override
        public void publish(OutboxEvent event) {
            if (crashNext.compareAndSet(true, false)) {
                throw new SimulatedCrash();
            }
            if (poisonedEmployees.contains(event.getAggregateId())) {
                throw new IllegalStateException("simulated permanent failure");
            }
            if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("simulated broker outage");
            }
            real.publish(event);
            delivered.add(event.getEventId());
        }

        /** The next n publishes fail with an exception, like a broker that is briefly down. */
        public void failNext(int n) {
            failuresLeft.set(n);
        }

        /** The next publish kills the "process" mid-publish. */
        public void crashNext() {
            crashNext.set(true);
        }

        /** Every event about this employee fails, forever: a poison event. */
        public void failAlwaysFor(String employeeId) {
            poisonedEmployees.add(employeeId);
        }

        public List<UUID> delivered() {
            return delivered;
        }

        public void reset() {
            delivered.clear();
            failuresLeft.set(0);
            crashNext.set(false);
            poisonedEmployees.clear();
        }
    }
}
