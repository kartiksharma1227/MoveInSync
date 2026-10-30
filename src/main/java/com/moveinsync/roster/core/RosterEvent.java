package com.moveinsync.roster.core;

import java.time.Instant;
import java.util.Optional;

import com.moveinsync.roster.entity.Employee;
import com.moveinsync.roster.entity.Employee.HrProfile;

/**
 * A change pushed by the HR system. Each event type knows how it applies to an employee, so the service never
 * needs an if/else on the event type. Sealed: these three are the only kinds of event that exist.
 */
public sealed interface RosterEvent
        permits RosterEvent.Create, RosterEvent.Update, RosterEvent.Deactivate, RosterEvent.Snapshot {

    String employeeId();

    long version();

    /** When the change happened in the source system. */
    Instant occurredAt();

    /** Applies this event to an employee we already know. */
    ApplyOutcome applyTo(Employee employee);

    /** Builds the employee the first time we see them. Only a CREATE may do this. */
    Optional<Employee> newEmployee(String tenantId);

    record Create(String employeeId, long version, Instant occurredAt, HrProfile hr) implements RosterEvent {
        @Override
        public ApplyOutcome applyTo(Employee employee) {
            // CREATE for someone we know: a replay, an old event, or a re-hire. The version decides which.
            return employee.applyHr(hr, version, occurredAt);
        }

        @Override
        public Optional<Employee> newEmployee(String tenantId) {
            return Optional.of(Employee.create(tenantId, employeeId, hr, version, occurredAt));
        }
    }

    record Update(String employeeId, long version, Instant occurredAt, HrProfile hr) implements RosterEvent {
        @Override
        public ApplyOutcome applyTo(Employee employee) {
            return employee.applyHr(hr, version, occurredAt);
        }

        @Override
        public Optional<Employee> newEmployee(String tenantId) {
            // Strict: an update for an unknown ID is rejected, so a typo can never create a phantom employee.
            return Optional.empty();
        }
    }

    record Deactivate(String employeeId, long version, Instant occurredAt) implements RosterEvent {
        @Override
        public ApplyOutcome applyTo(Employee employee) {
            return employee.deactivate(version, occurredAt);
        }

        @Override
        public Optional<Employee> newEmployee(String tenantId) {
            return Optional.empty();
        }
    }

    /** One row of the nightly full export: the employee as HR saw them at snapshot time. */
    record Snapshot(String employeeId, long version, Instant occurredAt, HrProfile hr) implements RosterEvent {
        @Override
        public ApplyOutcome applyTo(Employee employee) {
            return employee.applySnapshot(hr, version, occurredAt);
        }

        @Override
        public Optional<Employee> newEmployee(String tenantId) {
            // The full export is authoritative, so a row for someone we don't know is a new joiner.
            return Optional.of(Employee.create(tenantId, employeeId, hr, version, occurredAt));
        }
    }
}
