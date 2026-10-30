package com.moveinsync.roster.service.employee;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.moveinsync.roster.config.CurrentUser;
import com.moveinsync.roster.entity.Employee;
import com.moveinsync.roster.entity.Employee.HrProfile;
import com.moveinsync.roster.entity.Employee.TransportProfile;
import com.moveinsync.roster.entity.OutboxEvent;
import com.moveinsync.roster.repository.EmployeeRepository;
import com.moveinsync.roster.service.outbox.OutboxWriter;

import jakarta.validation.constraints.Size;

/** Reading an employee, and the transport admin's edits to transport-owned fields. */
@Service
public class EmployeeService {

    public record EmployeeView(String employeeId, String status, long hrVersion, String name, String email,
                               String phone, String managerId, String officeCode, String shift, String pickupPoint,
                               String specialAssistance, boolean pickupNeedsReview) {

        /** @param withContactDetails false hides name, email and phone from callers who do not need them */
        static EmployeeView from(Employee e, boolean withContactDetails) {
            HrProfile hr = e.hr();
            TransportProfile transport = e.transport();
            return new EmployeeView(e.getEmployeeId(), e.getStatus().name(), e.getHrVersion(),
                    withContactDetails ? hr.name() : null,
                    withContactDetails ? hr.email() : null,
                    withContactDetails ? hr.phone() : null,
                    hr.managerId(), hr.officeCode(), hr.shift(),
                    transport.pickupPoint(), transport.specialAssistance(), e.isPickupNeedsReview());
        }
    }

    public record TransportRequest(@Size(max = 200) String pickupPoint, @Size(max = 200) String specialAssistance) {
    }

    private final EmployeeRepository employees;
    private final OutboxWriter outboxWriter;

    public EmployeeService(EmployeeRepository employees, OutboxWriter outboxWriter) {
        this.employees = employees;
        this.outboxWriter = outboxWriter;
    }

    @Transactional(readOnly = true)
    public EmployeeView get(CurrentUser user, String employeeId) {
        boolean isStaff = user.hasRole("TRANSPORT_ADMIN") || user.hasRole("OPERATOR");
        boolean isSelf = employeeId.equals(user.userId());
        if (!isStaff && !isSelf) {
            throw notFound(); // an employee may only see their own record; don't reveal whether others exist
        }
        Employee employee = find(user.tenantId(), employeeId);
        // Operators only run sync jobs, so they don't need contact details (data minimisation).
        return EmployeeView.from(employee, user.hasRole("TRANSPORT_ADMIN") || isSelf);
    }

    @Transactional
    public EmployeeView updateTransport(CurrentUser user, String employeeId, TransportRequest request) {
        Employee employee = find(user.tenantId(), employeeId);
        employee.updateTransport(new TransportProfile(request.pickupPoint(), request.specialAssistance()));
        outboxWriter.record(OutboxEvent.Type.EMPLOYEE_TRANSPORT_UPDATED, employee);
        // Flush now so a concurrent-edit conflict (@Version) surfaces here as an exception, not after we return.
        employees.saveAndFlush(employee);
        return EmployeeView.from(employee, true);
    }

    /** Looks only inside the caller's tenant, so another tenant's employee is simply "not found". */
    private Employee find(String tenantId, String employeeId) {
        return employees.findByTenantIdAndEmployeeId(tenantId, employeeId).orElseThrow(this::notFound);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "EMPLOYEE_NOT_FOUND");
    }
}
