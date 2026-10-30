package com.moveinsync.roster.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A change event waiting to be published. It is saved in the same transaction as the change it describes, so the
 * change and its event are always both saved or both missing. The relay publishes it later.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent extends BaseTenantEntity {

    public enum Type { EMPLOYEE_CREATED, EMPLOYEE_UPDATED, EMPLOYEE_DEACTIVATED, EMPLOYEE_TRANSPORT_UPDATED }

    /** Consumers use this to recognise a repeated delivery. */
    @Column(nullable = false, updatable = false)
    private UUID eventId;

    /** The employee this event is about. */
    @Column(nullable = false, updatable = false)
    private String aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false)
    private Type type;

    /** IDs and codes only, never personal data. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String payload;

    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    private String lastError;

    /** For JPA only. */
    protected OutboxEvent() {
    }

    private OutboxEvent(String tenantId, String employeeId, Type type, String payload) {
        super(tenantId);
        this.eventId = UUID.randomUUID();
        this.aggregateId = employeeId;
        this.type = type;
        this.payload = payload;
    }

    public static OutboxEvent of(String tenantId, String employeeId, Type type, String payload) {
        return new OutboxEvent(tenantId, employeeId, type, payload);
    }

    public void markPublished(Instant at) {
        publishedAt = at;
    }

    /** @param errorType the exception class name only; its message could contain data */
    public void markFailed(String errorType) {
        attempts++;
        lastError = errorType;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public Type getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }
}
