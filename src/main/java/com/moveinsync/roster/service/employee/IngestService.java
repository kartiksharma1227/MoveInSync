package com.moveinsync.roster.service.employee;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.moveinsync.roster.core.ApplyOutcome;
import com.moveinsync.roster.core.RecordValidator;
import com.moveinsync.roster.core.RecordValidator.FieldError;
import com.moveinsync.roster.core.RosterEvent;
import com.moveinsync.roster.entity.Employee;
import com.moveinsync.roster.entity.Employee.HrProfile;
import com.moveinsync.roster.entity.OutboxEvent;
import com.moveinsync.roster.repository.EmployeeRepository;
import com.moveinsync.roster.repository.OfficeRepository;
import com.moveinsync.roster.service.outbox.OutboxWriter;

/**
 * Applies a batch of HR events. Every record is validated on its own and applied in its own transaction,
 * so one bad or failing record never affects the others.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    /** One event as the HR system sends it (raw JSON shape, not yet validated). */
    public record EventRequest(String eventId, String type, String employeeId, Long version, Instant occurredAt,
                               HrRequest hr) {
    }

    public record HrRequest(String name, String email, String phone, String managerId, String officeCode,
                            String shift) {

        HrProfile toProfile() {
            return new HrProfile(name, email, phone, managerId, officeCode, shift);
        }
    }

    /** A problem with one record. Holds IDs and error codes only, never field values. */
    public record RecordError(int index, String eventId, String employeeId, List<FieldError> errors) {
    }

    public record BatchResult(int received, int applied, int duplicate, int stale, int versionConflict, int rejected,
                              List<RecordError> errors) {
    }

    private final EmployeeRepository employees;
    private final OfficeRepository offices;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate tx;

    public IngestService(EmployeeRepository employees, OfficeRepository offices, OutboxWriter outboxWriter,
                         TransactionTemplate tx) {
        this.employees = employees;
        this.offices = offices;
        this.outboxWriter = outboxWriter;
        this.tx = tx;
    }

    public BatchResult ingest(String tenantId, List<EventRequest> requests) {
        RecordValidator validator = new RecordValidator(offices.findCodes(tenantId)::contains);
        Map<ApplyOutcome, Integer> counts = new EnumMap<>(ApplyOutcome.class);
        List<RecordError> errors = new ArrayList<>();
        int rejected = 0;

        for (int i = 0; i < requests.size(); i++) {
            EventRequest request = requests.get(i);
            List<FieldError> problems = validate(request, validator);
            if (!problems.isEmpty()) {
                errors.add(new RecordError(i, eventIdOf(request), employeeIdOf(request), problems));
                rejected++;
                continue;
            }
            Optional<ApplyOutcome> outcome = apply(tenantId, toEvent(request));
            if (outcome.isEmpty()) {
                errors.add(new RecordError(i, request.eventId(), request.employeeId(),
                        List.of(new FieldError("employeeId", "UNKNOWN_EMPLOYEE"))));
                rejected++;
                continue;
            }
            counts.merge(outcome.get(), 1, Integer::sum);
            if (outcome.get() == ApplyOutcome.VERSION_CONFLICT) {
                errors.add(new RecordError(i, request.eventId(), request.employeeId(),
                        List.of(new FieldError("version", "VERSION_CONFLICT"))));
            }
        }

        BatchResult result = new BatchResult(requests.size(),
                counts.getOrDefault(ApplyOutcome.APPLIED, 0),
                counts.getOrDefault(ApplyOutcome.DUPLICATE, 0),
                counts.getOrDefault(ApplyOutcome.STALE, 0),
                counts.getOrDefault(ApplyOutcome.VERSION_CONFLICT, 0),
                rejected, errors);
        log.info("Ingested batch tenant={} received={} applied={} duplicate={} stale={} versionConflict={} rejected={}",
                tenantId, result.received(), result.applied(), result.duplicate(), result.stale(),
                result.versionConflict(), result.rejected());
        return result;
    }

    private List<FieldError> validate(EventRequest request, RecordValidator validator) {
        if (request == null) {
            return List.of(new FieldError("record", "REQUIRED"));
        }
        List<FieldError> errors = new ArrayList<>();
        boolean carriesHr = "CREATE".equals(request.type()) || "UPDATE".equals(request.type());
        if (!carriesHr && !"DEACTIVATE".equals(request.type())) {
            errors.add(new FieldError("type", "INVALID_TYPE"));
        }
        if (request.occurredAt() == null) {
            errors.add(new FieldError("occurredAt", "REQUIRED"));
        }
        if (carriesHr && request.hr() == null) {
            errors.add(new FieldError("hr", "REQUIRED"));
        }
        long version = request.version() == null ? 0 : request.version();
        HrProfile hr = carriesHr && request.hr() != null ? request.hr().toProfile() : null;
        errors.addAll(validator.validate(request.employeeId(), version, hr));
        return errors;
    }

    private RosterEvent toEvent(EventRequest r) {
        return switch (r.type()) {
            case "CREATE" -> new RosterEvent.Create(r.employeeId(), r.version(), r.occurredAt(), r.hr().toProfile());
            case "UPDATE" -> new RosterEvent.Update(r.employeeId(), r.version(), r.occurredAt(), r.hr().toProfile());
            default -> new RosterEvent.Deactivate(r.employeeId(), r.version(), r.occurredAt());
        };
    }

    /**
     * Applies one event in its own transaction. Also used by the nightly reconciliation, so both channels share
     * exactly the same rules. We use TransactionTemplate rather than @Transactional because calling a
     * @Transactional method on 'this' bypasses Spring's proxy, so no transaction would start.
     * @return the outcome, or empty if the employee is unknown and this event may not create them
     */
    public Optional<ApplyOutcome> apply(String tenantId, RosterEvent event) {
        try {
            return tx.execute(status -> applyOne(tenantId, event));
        } catch (DataIntegrityViolationException e) {
            // Two CREATEs for a new employee raced and the other one inserted first (unique key on tenant + employee).
            // The row exists now, so a second attempt takes the normal locked, version-checked path.
            log.info("Concurrent create, retrying tenant={} employeeId={}", tenantId, event.employeeId());
            return tx.execute(status -> applyOne(tenantId, event));
        }
    }

    /**
     * Applies one event and, if it changed anything, records the change event in the same transaction.
     * @return the outcome, or empty if the employee is unknown and this event may not create them
     */
    private Optional<ApplyOutcome> applyOne(String tenantId, RosterEvent event) {
        Optional<Employee> existing = employees.findForUpdate(tenantId, event.employeeId());
        if (existing.isPresent()) {
            // Changes are saved at commit by JPA dirty checking; no explicit save needed.
            ApplyOutcome outcome = event.applyTo(existing.get());
            if (outcome == ApplyOutcome.APPLIED) {
                OutboxEvent.Type type = event instanceof RosterEvent.Deactivate
                        ? OutboxEvent.Type.EMPLOYEE_DEACTIVATED
                        : OutboxEvent.Type.EMPLOYEE_UPDATED;
                outboxWriter.record(type, existing.get());
            }
            return Optional.of(outcome);
        }
        Optional<Employee> created = event.newEmployee(tenantId);
        if (created.isEmpty()) {
            return Optional.empty();
        }
        employees.saveAndFlush(created.get());
        outboxWriter.record(OutboxEvent.Type.EMPLOYEE_CREATED, created.get());
        return Optional.of(ApplyOutcome.APPLIED);
    }

    private static String eventIdOf(EventRequest request) {
        return request == null ? null : request.eventId();
    }

    private static String employeeIdOf(EventRequest request) {
        return request == null ? null : request.employeeId();
    }
}
