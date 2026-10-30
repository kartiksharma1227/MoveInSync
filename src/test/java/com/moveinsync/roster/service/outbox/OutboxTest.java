package com.moveinsync.roster.service.outbox;

import static com.moveinsync.roster.SyntheticRosterGenerator.event;
import static com.moveinsync.roster.SyntheticRosterGenerator.hr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.moveinsync.roster.IntegrationTest;
import com.moveinsync.roster.TestPublisherConfig.SimulatedCrash;
import com.moveinsync.roster.config.CurrentUser;
import com.moveinsync.roster.config.RosterMetrics;
import com.moveinsync.roster.repository.OutboxRepository;
import com.moveinsync.roster.service.employee.EmployeeService;
import com.moveinsync.roster.service.employee.EmployeeService.TransportRequest;
import com.moveinsync.roster.service.employee.IngestService;

/** The outbox guarantees: one event per applied change, crash-safe delivery, safe side effects. */
class OutboxTest extends IntegrationTest {

    static final Instant T = Instant.parse("2026-10-01T09:00:00Z");

    @Autowired
    IngestService ingestService;

    @Autowired
    EmployeeService employeeService;

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxRepository outboxRepository;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    RosterMetrics metrics;

    /** Other tests leave events behind (the relay is off in tests), so start each test with an empty queue. */
    @BeforeEach
    void drainLeftoverEvents() {
        drain(relay);
        publisher.reset();
    }

    // ---- One event per applied change, never for a no-op ----

    @Test
    void everyAppliedChangeWritesExactlyOneEventAndNoOpsWriteNone() {
        String id = uniqueId("OB");
        CurrentUser admin = new CurrentUser("admin", "ACME", Set.of("TRANSPORT_ADMIN"));

        apply(event("CREATE", id, 1, T, hr("BLR-01", "S0900")));
        apply(event("CREATE", id, 1, T, hr("BLR-01", "S0900")));                 // replay: duplicate
        apply(event("UPDATE", id, 2, T.plusSeconds(1), hr("HYD-01", "S0900")));
        apply(event("UPDATE", id, 1, T, hr("BLR-02", "S0900")));                 // stale
        employeeService.updateTransport(admin, id, new TransportRequest("Gate 4", null));
        apply(event("DEACTIVATE", id, 3, T.plusSeconds(2), null));

        assertThat(eventTypes(id)).containsExactly(
                "EMPLOYEE_CREATED", "EMPLOYEE_UPDATED", "EMPLOYEE_TRANSPORT_UPDATED", "EMPLOYEE_DEACTIVATED");
        for (String payload : payloads(id)) {
            assertThat(payload).doesNotContain("Test Person", "test.person@acme.test", "+919811112222");
        }
    }

    // ---- H8: deactivation side effects ----

    @Test
    void deactivationCancelsOnlyFutureBookingsAndFreesTheirSeats() {
        String id = createEmployee();
        long futureTrip = trip("1 day", 1);
        long pastTrip = trip("-1 day", 1);
        book(id, futureTrip);
        book(id, pastTrip);

        apply(event("DEACTIVATE", id, 2, T.plusSeconds(1), null));
        drain(relay);

        assertThat(bookingStatus(id, futureTrip)).isEqualTo("CANCELLED");
        assertThat(seatsBooked(futureTrip)).isZero();
        assertThat(bookingStatus(id, pastTrip)).isEqualTo("CONFIRMED"); // history is not rewritten
        assertThat(seatsBooked(pastTrip)).isEqualTo(1);
    }

    // ---- H7: crash between the database write and the publish ----

    @Test
    void crashBetweenCommitAndPublish_eventIsDeliveredAfterRestart() {
        String id = createEmployee();
        long futureTrip = trip("1 day", 1);
        book(id, futureTrip);
        drain(relay); // publish the CREATE, so the only pending event is the deactivation below

        // 1. The change commits: employee INACTIVE + outbox row, in one transaction.
        apply(event("DEACTIVATE", id, 2, T.plusSeconds(1), null));

        // 2. The process "dies" in the middle of publishing. No cleanup code runs.
        publisher.crashNext();
        assertThatThrownBy(relay::publishPending).isInstanceOf(SimulatedCrash.class);

        // 3. The state a real crash leaves behind: change durable, event waiting, side effect not yet done.
        assertThat(employeeStatus(id)).isEqualTo("INACTIVE");
        assertThat(unpublishedEvents(id)).isEqualTo(1);
        assertThat(bookingStatus(id, futureTrip)).isEqualTo("CONFIRMED");

        // 4. "Restart": a brand-new relay with no memory of the old one recovers purely from the database.
        OutboxRelay restarted = new OutboxRelay(outboxRepository, publisher, tx, metrics, true, 500, 10, 7);
        drain(restarted);

        assertThat(unpublishedEvents(id)).isZero();
        assertThat(bookingStatus(id, futureTrip)).isEqualTo("CANCELLED");
        assertThat(seatsBooked(futureTrip)).isZero();
        assertThat(restarted.publishPending()).isZero(); // nothing left, nothing published twice
    }

    // ---- Failure injection: broker briefly down, poison events ----

    @Test
    void failedPublishIsRetriedAndDoesNotBlockOtherEvents() {
        String first = uniqueId("BO"), second = uniqueId("BO");
        apply(event("CREATE", first, 1, T, hr("BLR-01", "S0900")));
        apply(event("CREATE", second, 1, T, hr("BLR-01", "S0900")));

        publisher.failNext(1); // the first (oldest) event fails

        assertThat(relay.publishPending()).isEqualTo(1); // the second still went out
        Map<String, Object> failed = outboxRow(first);
        assertThat(failed.get("published_at")).isNull();
        assertThat(failed.get("attempts")).isEqualTo(1);
        assertThat(failed.get("last_error")).isEqualTo("IllegalStateException"); // class name only, no message

        assertThat(relay.publishPending()).isEqualTo(1); // next poll: the retry succeeds
        assertThat(unpublishedEvents(first)).isZero();
    }

    @Test
    void poisonEventIsParkedAfterMaxAttemptsWhileOthersFlow() {
        String poison = uniqueId("PO"), healthy = uniqueId("HE");
        publisher.failAlwaysFor(poison);
        apply(event("CREATE", poison, 1, T, hr("BLR-01", "S0900")));
        apply(event("CREATE", healthy, 1, T, hr("BLR-01", "S0900")));

        for (int i = 0; i < 12; i++) {
            relay.publishPending();
        }

        assertThat(outboxRow(poison).get("attempts")).isEqualTo(10); // stopped at the limit, not 12
        assertThat(unpublishedEvents(poison)).isEqualTo(1);           // parked, still there for an engineer
        assertThat(unpublishedEvents(healthy)).isZero();
    }

    // ---- At-least-once delivery is harmless ----

    @Test
    void deliveringTheSameEventTwiceDoesNotFreeSeatsTwice() {
        String id = createEmployee();
        long futureTrip = trip("1 day", 2);
        book(id, futureTrip);
        apply(event("DEACTIVATE", id, 2, T.plusSeconds(1), null));
        drain(relay);
        assertThat(seatsBooked(futureTrip)).isEqualTo(1);

        // Simulate a crash after publishing but before marking it published: the event goes out again.
        jdbc.update("UPDATE outbox_event SET published_at = NULL WHERE aggregate_id = ? AND event_type = 'EMPLOYEE_DEACTIVATED'", id);
        drain(relay);

        assertThat(seatsBooked(futureTrip)).isEqualTo(1); // not freed a second time
        assertThat(bookingStatus(id, futureTrip)).isEqualTo("CANCELLED");
    }

    @Test
    void lateDeactivationEventDoesNotCancelARehiresNewBookings() {
        String id = createEmployee();
        apply(event("DEACTIVATE", id, 2, T.plusSeconds(1), null)); // event written, not yet published
        apply(event("CREATE", id, 3, T.plusSeconds(2), hr("BLR-01", "S0900"))); // re-hired
        long newTrip = trip("2 days", 1);
        book(id, newTrip);

        drain(relay);

        assertThat(bookingStatus(id, newTrip)).isEqualTo("CONFIRMED");
    }

    // ---- Several relays (app instances) never publish the same event twice ----

    @Test
    void fourRelaysDrainingTogetherDeliverEachEventExactlyOnce() throws Exception {
        String prefix = uniqueId("SL");
        for (int i = 0; i < 50; i++) {
            apply(event("CREATE", prefix + "-" + i, 1, T, hr("BLR-01", "S0900")));
        }
        List<UUID> ours = jdbc.queryForList(
                "SELECT event_id FROM outbox_event WHERE aggregate_id LIKE ?", UUID.class, prefix + "-%");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> runs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            OutboxRelay instance = new OutboxRelay(outboxRepository, publisher, tx, metrics, true, 5, 10, 7); // small batches
            runs.add(pool.submit(() -> {
                start.await();
                drain(instance);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> run : runs) {
            run.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        drain(relay); // anything a thread gave up on because it was locked by another

        assertThat(ours).hasSize(50);
        assertThat(publisher.delivered()).containsAll(ours);
        assertThat(publisher.delivered().stream().filter(ours::contains).count()).isEqualTo(50); // no duplicates
    }

    // ---- helpers ----

    private void apply(com.moveinsync.roster.service.employee.IngestService.EventRequest event) {
        ingestService.ingest("ACME", List.of(event));
    }

    private String createEmployee() {
        String id = uniqueId("EM");
        apply(event("CREATE", id, 1, T, hr("BLR-01", "S0900")));
        return id;
    }

    private static void drain(OutboxRelay relay) {
        while (relay.publishPending() > 0) {
            // keep going until nothing more can be published
        }
    }

    private long trip(String fromNow, int seatsBooked) {
        return jdbc.queryForObject("INSERT INTO trip (tenant_id, pickup_at, capacity, seats_booked) "
                + "VALUES ('ACME', now() + ?::interval, 4, ?) RETURNING id", Long.class, fromNow, seatsBooked);
    }

    private void book(String employeeId, long tripId) {
        jdbc.update("INSERT INTO booking (tenant_id, employee_id, trip_id, status) VALUES ('ACME', ?, ?, 'CONFIRMED')",
                employeeId, tripId);
    }

    private String bookingStatus(String employeeId, long tripId) {
        return jdbc.queryForObject("SELECT status FROM booking WHERE employee_id = ? AND trip_id = ?",
                String.class, employeeId, tripId);
    }

    private int seatsBooked(long tripId) {
        return jdbc.queryForObject("SELECT seats_booked FROM trip WHERE id = ?", Integer.class, tripId);
    }

    private String employeeStatus(String employeeId) {
        return jdbc.queryForObject("SELECT status FROM employee WHERE tenant_id = 'ACME' AND employee_id = ?",
                String.class, employeeId);
    }

    private List<String> eventTypes(String employeeId) {
        return jdbc.queryForList("SELECT event_type FROM outbox_event WHERE aggregate_id = ? ORDER BY id",
                String.class, employeeId);
    }

    private List<String> payloads(String employeeId) {
        return jdbc.queryForList("SELECT payload::text FROM outbox_event WHERE aggregate_id = ?", String.class, employeeId);
    }

    private int unpublishedEvents(String employeeId) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND published_at IS NULL",
                Integer.class, employeeId);
    }

    private Map<String, Object> outboxRow(String employeeId) {
        return jdbc.queryForMap("SELECT published_at, attempts, last_error FROM outbox_event WHERE aggregate_id = ?",
                employeeId);
    }
}
