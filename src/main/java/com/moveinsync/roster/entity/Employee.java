package com.moveinsync.roster.entity;

import java.time.Instant;
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.moveinsync.roster.core.ApplyOutcome;
import com.moveinsync.roster.core.RowHasher;
import com.moveinsync.roster.core.VersionGate;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One employee of one tenant. State changes only through the methods below, which enforce the rules:
 * HR changes are version-checked and can never touch transport-owned fields, and transport edits can never touch
 * HR-owned fields.
 */
@Entity
@Table(name = "employee")
public class Employee extends BaseTenantEntity {

    public enum Status { ACTIVE, INACTIVE }

    /** Fields owned by the HR system. Immutable: a change replaces the whole profile. */
    @Embeddable
    public record HrProfile(String name, String email, String phone, String managerId, String officeCode,
                            String shift) {
    }

    /** Fields owned by the transport admin. HR events cannot carry this type, so they cannot overwrite it. */
    @Embeddable
    public record TransportProfile(String pickupPoint, String specialAssistance) {
        public static final TransportProfile EMPTY = new TransportProfile(null, null);
    }

    @Column(nullable = false, updatable = false)
    private String employeeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private long hrVersion;

    @JdbcTypeCode(SqlTypes.CHAR) // the column is CHAR(64): a SHA-256 hex string always has exactly 64 characters
    @Column(nullable = false, length = 64)
    private String rowHash;

    @Column(nullable = false)
    private Instant lastHrChangeAt;

    @Embedded
    private HrProfile hr;

    @Embedded
    private TransportProfile transport;

    @Column(nullable = false)
    private boolean pickupNeedsReview;

    @Version
    private long optLock;

    /** For JPA only. */
    protected Employee() {
    }

    private Employee(String tenantId, String employeeId, HrProfile hr, long version, Instant at) {
        super(tenantId);
        this.employeeId = employeeId;
        this.status = Status.ACTIVE;
        this.hr = hr;
        this.rowHash = RowHasher.hash(hr);
        this.hrVersion = version;
        this.lastHrChangeAt = at;
    }

    public static Employee create(String tenantId, String employeeId, HrProfile hr, long version, Instant at) {
        return new Employee(tenantId, employeeId, hr, version, at);
    }

    /** Applies an HR change if its version is newer. Transport fields are never touched. */
    public ApplyOutcome applyHr(HrProfile newHr, long version, Instant at) {
        ApplyOutcome outcome = VersionGate.decide(hrVersion, version);
        if (outcome == ApplyOutcome.DUPLICATE && !RowHasher.hash(newHr).equals(rowHash)) {
            return ApplyOutcome.VERSION_CONFLICT;
        }
        if (outcome != ApplyOutcome.APPLIED) {
            return outcome;
        }
        // Conflict rule: a new office may make the transport-owned pickup point wrong. HR may not change it,
        // so we keep it and flag it for the transport admin.
        boolean officeChanged = !Objects.equals(hr.officeCode(), newHr.officeCode());
        if (officeChanged && transport().pickupPoint() != null) {
            pickupNeedsReview = true;
        }
        hr = newHr;
        rowHash = RowHasher.hash(newHr);
        hrVersion = version;
        lastHrChangeAt = at;
        status = Status.ACTIVE; // a newer HR change for a deactivated employee is a re-hire
        return ApplyOutcome.APPLIED;
    }

    /** Marks the employee as left if the version is newer. Transport data is kept in case of a re-hire. */
    public ApplyOutcome deactivate(long version, Instant at) {
        ApplyOutcome outcome = VersionGate.decide(hrVersion, version);
        if (outcome != ApplyOutcome.APPLIED) {
            return outcome;
        }
        status = Status.INACTIVE;
        hrVersion = version;
        lastHrChangeAt = at;
        return ApplyOutcome.APPLIED;
    }

    /** Transport-admin edit. HR fields and the HR version are never touched. */
    public void updateTransport(TransportProfile newTransport) {
        transport = newTransport;
        pickupNeedsReview = false;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public Status getStatus() {
        return status;
    }

    public long getHrVersion() {
        return hrVersion;
    }

    public String getRowHash() {
        return rowHash;
    }

    public Instant getLastHrChangeAt() {
        return lastHrChangeAt;
    }

    public HrProfile hr() {
        return hr;
    }

    /** Hibernate loads an embeddable whose columns are all null as null, so return an empty profile instead. */
    public TransportProfile transport() {
        return transport == null ? TransportProfile.EMPTY : transport;
    }

    public boolean isPickupNeedsReview() {
        return pickupNeedsReview;
    }

    /** No personal data here: this string may end up in logs. */
    @Override
    public String toString() {
        return "Employee[tenant=" + getTenantId() + ", employeeId=" + employeeId + ", status=" + status
                + ", hrVersion=" + hrVersion + "]";
    }
}
