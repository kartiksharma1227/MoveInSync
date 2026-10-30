package com.moveinsync.roster.service.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.moveinsync.roster.entity.OutboxEvent;

/** Delivers events to consumers inside this application. Only deactivation has a side effect today. */
@Component
public class InProcessEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InProcessEventPublisher.class);

    private final BookingCancellationHandler bookingCancellation;

    public InProcessEventPublisher(BookingCancellationHandler bookingCancellation) {
        this.bookingCancellation = bookingCancellation;
    }

    @Override
    public void publish(OutboxEvent event) {
        switch (event.getType()) {
            case EMPLOYEE_DEACTIVATED -> bookingCancellation.cancelFutureBookings(event.getTenantId(), event.getAggregateId());
            case EMPLOYEE_CREATED, EMPLOYEE_UPDATED, EMPLOYEE_TRANSPORT_UPDATED -> {
                // No in-process consumer yet; an external broker would fan these out to other services.
            }
        }
        log.info("Published {} eventId={} tenant={} employeeId={}",
                event.getType(), event.getEventId(), event.getTenantId(), event.getAggregateId());
    }
}
