package com.moveinsync.roster.service.outbox;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.moveinsync.roster.entity.Booking;
import com.moveinsync.roster.entity.Employee;
import com.moveinsync.roster.repository.BookingRepository;
import com.moveinsync.roster.repository.EmployeeRepository;

/**
 * Reacts to an employee leaving: cancels their future bookings and frees the seats. Safe to run twice for the same
 * event (the second run finds nothing left to cancel), because events can be delivered more than once.
 */
@Component
public class BookingCancellationHandler {

    private static final Logger log = LoggerFactory.getLogger(BookingCancellationHandler.class);

    private final BookingRepository bookings;
    private final EmployeeRepository employees;

    public BookingCancellationHandler(BookingRepository bookings, EmployeeRepository employees) {
        this.bookings = bookings;
        this.employees = employees;
    }

    /** Own transaction (REQUIRES_NEW), like a separate consumer service would have. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cancelFutureBookings(String tenantId, String employeeId) {
        Optional<Employee> employee = employees.findByTenantIdAndEmployeeId(tenantId, employeeId);
        if (employee.isPresent() && employee.get().getStatus() == Employee.Status.ACTIVE) {
            // Re-hired after this event was written: a late event must not cancel their new bookings.
            log.info("Skipped booking cancellation, employee is active again tenant={} employeeId={}", tenantId, employeeId);
            return 0;
        }
        List<Booking> future = bookings.lockFutureConfirmed(tenantId, employeeId);
        for (Booking booking : future) {
            booking.cancel();
            bookings.freeSeat(booking.getTripId());
        }
        log.info("Cancelled {} future bookings tenant={} employeeId={}", future.size(), tenantId, employeeId);
        return future.size();
    }
}
