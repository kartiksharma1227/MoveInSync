package com.moveinsync.roster.service.outbox;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.moveinsync.roster.entity.Employee;
import com.moveinsync.roster.entity.OutboxEvent;
import com.moveinsync.roster.repository.OutboxRepository;

import tools.jackson.databind.ObjectMapper;

/** Records a change event in the outbox table, as part of the transaction that made the change. */
@Component
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final ObjectMapper json;

    public OutboxWriter(OutboxRepository outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    /**
     * MANDATORY: fails fast if called outside a transaction, because an event saved separately from its change is
     * exactly what the outbox pattern exists to prevent.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(OutboxEvent.Type type, Employee employee) {
        // Thin event: IDs and codes only. Consumers that need contact details ask the API.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("employeeId", employee.getEmployeeId());
        payload.put("hrVersion", employee.getHrVersion());
        payload.put("status", employee.getStatus().name());
        payload.put("officeCode", employee.hr().officeCode());
        payload.put("shift", employee.hr().shift());
        payload.put("pickupNeedsReview", employee.isPickupNeedsReview());
        outbox.save(OutboxEvent.of(employee.getTenantId(), employee.getEmployeeId(), type,
                json.writeValueAsString(payload)));
    }
}
