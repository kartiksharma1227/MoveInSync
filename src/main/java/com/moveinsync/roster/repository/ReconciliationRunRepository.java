package com.moveinsync.roster.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.moveinsync.roster.entity.ReconciliationRun;

import jakarta.persistence.LockModeType;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, Long> {

    Optional<ReconciliationRun> findByIdAndTenantId(Long id, String tenantId);

    /** Runs still marked as in progress that haven't made progress since the cutoff. */
    List<ReconciliationRun> findByStatusInAndUpdatedAtBefore(Collection<ReconciliationRun.Status> statuses, Instant cutoff);

    /** Heartbeat: a run that is still working keeps its updated_at fresh, so it's never mistaken for a stuck one. */
    @Modifying
    @Query("UPDATE ReconciliationRun r SET r.updatedAt = :now WHERE r.id = :id")
    int touch(@Param("id") Long id, @Param("now") Instant now);

    /** Locks the run so two operators deciding at the same moment are handled one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ReconciliationRun r WHERE r.id = :id AND r.tenantId = :tenantId")
    Optional<ReconciliationRun> findForUpdate(@Param("id") Long id, @Param("tenantId") String tenantId);
}
