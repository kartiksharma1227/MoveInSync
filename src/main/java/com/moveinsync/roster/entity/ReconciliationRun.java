package com.moveinsync.roster.entity;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.moveinsync.roster.core.SafetyBrake;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * One nightly file being reconciled. Status flow:
 * STAGING → APPLYING → COMPLETED, or STAGING → AWAITING_APPROVAL → (approve) APPLYING → COMPLETED / (reject) REJECTED.
 * Any unexpected error → FAILED.
 */
@Entity
@Table(name = "reconciliation_run")
public class ReconciliationRun extends BaseTenantEntity {

    public enum Status { STAGING, AWAITING_APPROVAL, APPLYING, COMPLETED, REJECTED, FAILED }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    /** When HR took the export. */
    @Column(nullable = false, updatable = false)
    private Instant snapshotAt;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "file_sha256", nullable = false, length = 64)
    private String fileSha256;

    /** The brake threshold in force for this run, kept for the audit trail. */
    @Column(nullable = false, precision = 4, scale = 3, updatable = false)
    private BigDecimal threshold;

    private int rowsTotal;
    private int rowsRejected;

    /** The first rejected rows: row number, employee ID, field and code. Never field values. */
    @JdbcTypeCode(SqlTypes.JSON)
    private String rejections;

    private Integer activeCount;
    private Integer missingCount;
    private int createdCount;
    private int updatedCount;
    private int deactivatedCount;
    /** Valid rows that couldn't be applied this time (e.g. lock timeout); re-uploading the file retries them. */
    private int failedCount;
    private String decidedBy;
    private Instant decidedAt;

    /** For JPA only. */
    protected ReconciliationRun() {
    }

    private ReconciliationRun(String tenantId, Instant snapshotAt, BigDecimal threshold) {
        super(tenantId);
        this.status = Status.STAGING;
        this.snapshotAt = snapshotAt;
        this.threshold = threshold;
        this.fileSha256 = "0".repeat(64); // known only after the whole file is read
    }

    public static ReconciliationRun start(String tenantId, Instant snapshotAt, double threshold) {
        return new ReconciliationRun(tenantId, snapshotAt, BigDecimal.valueOf(threshold));
    }

    public void staged(String sha256, int total, int rejected, String rejectionsJson) {
        fileSha256 = sha256;
        rowsTotal = total;
        rowsRejected = rejected;
        rejections = rejectionsJson;
    }

    /** Records the brake's counts, then either holds the run for an operator or lets it apply. */
    public void braked(SafetyBrake.Decision decision) {
        activeCount = (int) decision.activeCount();
        missingCount = (int) decision.missingCount();
        status = decision.held() ? Status.AWAITING_APPROVAL : Status.APPLYING;
    }

    public void approve(String operator, Instant at) {
        requireAwaitingApproval();
        status = Status.APPLYING;
        decidedBy = operator;
        decidedAt = at;
    }

    public void reject(String operator, Instant at) {
        requireAwaitingApproval();
        status = Status.REJECTED;
        decidedBy = operator;
        decidedAt = at;
    }

    public void complete(int created, int updated, int deactivated, int failed) {
        createdCount = created;
        updatedCount = updated;
        deactivatedCount = deactivated;
        failedCount = failed;
        status = Status.COMPLETED;
    }

    public void fail() {
        status = Status.FAILED;
    }

    /** Still staging or applying: the states a crashed process would leave a run in. */
    public boolean isInProgress() {
        return status == Status.STAGING || status == Status.APPLYING;
    }

    private void requireAwaitingApproval() {
        if (status != Status.AWAITING_APPROVAL) {
            throw new IllegalStateException("Run is " + status + ", not awaiting approval");
        }
    }

    public Status getStatus() {
        return status;
    }

    public Instant getSnapshotAt() {
        return snapshotAt;
    }

    public String getFileSha256() {
        return fileSha256;
    }

    public BigDecimal getThreshold() {
        return threshold;
    }

    public int getRowsTotal() {
        return rowsTotal;
    }

    public int getRowsRejected() {
        return rowsRejected;
    }

    public String getRejections() {
        return rejections;
    }

    public Integer getActiveCount() {
        return activeCount;
    }

    public Integer getMissingCount() {
        return missingCount;
    }

    public int getCreatedCount() {
        return createdCount;
    }

    public int getUpdatedCount() {
        return updatedCount;
    }

    public int getDeactivatedCount() {
        return deactivatedCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
