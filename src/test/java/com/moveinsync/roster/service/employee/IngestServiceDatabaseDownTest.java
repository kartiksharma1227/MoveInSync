package com.moveinsync.roster.service.employee;

import static com.moveinsync.roster.SyntheticRosterGenerator.event;
import static com.moveinsync.roster.SyntheticRosterGenerator.hr;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import com.moveinsync.roster.config.RosterMetrics;
import com.moveinsync.roster.repository.EmployeeRepository;
import com.moveinsync.roster.repository.OfficeRepository;
import com.moveinsync.roster.repository.OutboxRepository;
import com.moveinsync.roster.service.employee.IngestService.EventRequest;
import com.moveinsync.roster.service.outbox.OutboxWriter;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Per-record isolation must not turn "the database is down" into one slow failure per record (each waiting for a
 * connection timeout). Plain unit test with mocks: no Spring, no database.
 */
class IngestServiceDatabaseDownTest {

    @Test
    void unreachableDatabaseStopsTheBatchAtTheFirstRecord() {
        OfficeRepository offices = mock(OfficeRepository.class);
        when(offices.findCodes("ACME")).thenReturn(Set.of("BLR-01"));
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenThrow(new CannotCreateTransactionException("connection refused"));
        RosterMetrics metrics = new RosterMetrics(new SimpleMeterRegistry(), mock(OutboxRepository.class), 10);
        IngestService service = new IngestService(mock(EmployeeRepository.class), offices, mock(OutboxWriter.class),
                tx, metrics);
        List<EventRequest> batch = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            batch.add(event("CREATE", "E" + i, 1, Instant.parse("2026-10-01T09:00:00Z"), hr("BLR-01", "S0900")));
        }

        assertThatThrownBy(() -> service.ingest("ACME", batch)).isInstanceOf(CannotCreateTransactionException.class);
        verify(tx, times(1)).execute(any()); // gave up after the first record, not after 50 timeouts
    }
}
