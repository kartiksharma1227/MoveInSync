package com.moveinsync.roster.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.moveinsync.roster.entity.OutboxEvent;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * The next unpublished events, oldest first, locked until the transaction ends. SKIP LOCKED makes a second relay
     * (e.g. another app instance) skip rows the first one is working on instead of waiting or publishing them twice.
     * Events that already failed maxAttempts times are parked and left out.
     */
    @Query(value = """
            SELECT * FROM outbox_event
            WHERE published_at IS NULL AND attempts < :maxAttempts
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@Param("maxAttempts") int maxAttempts, @Param("limit") int limit);

    // ---- For monitoring. All three only touch unpublished rows, which the partial index keeps small. ----

    /** Queue depth: events still waiting to be published. */
    @Query(value = "SELECT count(*) FROM outbox_event WHERE published_at IS NULL AND attempts < :maxAttempts",
            nativeQuery = true)
    long countPending(@Param("maxAttempts") int maxAttempts);

    /** Poison events the relay gave up on, waiting for a human. */
    @Query(value = "SELECT count(*) FROM outbox_event WHERE published_at IS NULL AND attempts >= :maxAttempts",
            nativeQuery = true)
    long countParked(@Param("maxAttempts") int maxAttempts);

    /** Lag: how long the oldest waiting event has been waiting, in seconds (null if none). */
    @Query(value = """
            SELECT EXTRACT(EPOCH FROM (now() - min(created_at))) FROM outbox_event
            WHERE published_at IS NULL AND attempts < :maxAttempts
            """, nativeQuery = true)
    Double oldestPendingAgeSeconds(@Param("maxAttempts") int maxAttempts);

    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
