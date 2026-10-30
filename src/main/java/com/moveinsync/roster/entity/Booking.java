package com.moveinsync.roster.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** An employee's seat on a cab trip. */
@Entity
@Table(name = "booking")
public class Booking extends BaseTenantEntity {

    public enum Status { CONFIRMED, CANCELLED }

    @Column(nullable = false, updatable = false)
    private String employeeId;

    @Column(nullable = false, updatable = false)
    private Long tripId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    /** For JPA only. */
    protected Booking() {
    }

    public void cancel() {
        status = Status.CANCELLED;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public Long getTripId() {
        return tripId;
    }

    public Status getStatus() {
        return status;
    }
}
