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

    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
