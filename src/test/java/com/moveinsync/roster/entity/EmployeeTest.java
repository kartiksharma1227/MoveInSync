package com.moveinsync.roster.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.moveinsync.roster.core.ApplyOutcome;
import com.moveinsync.roster.core.RosterEvent;
import com.moveinsync.roster.entity.Employee.HrProfile;
import com.moveinsync.roster.entity.Employee.Status;
import com.moveinsync.roster.entity.Employee.TransportProfile;

/** Domain rules on the Employee aggregate: plain Java, no Spring, no database. */
class EmployeeTest {

    static final Instant T1 = Instant.parse("2026-10-01T09:00:00Z");
    static final Instant T2 = Instant.parse("2026-10-02T09:00:00Z");
    static final Instant T3 = Instant.parse("2026-10-03T09:00:00Z");

    static HrProfile hr(String office, String shift) {
        return new HrProfile("Employee 1", "e1@acme.test", "+919800000001", "E9", office, shift);
    }

    static Employee employeeAtVersion5() {
        return Employee.create("ACME", "E1", hr("BLR-01", "S0900"), 5, T1);
    }

    @Test
    void newerVersionIsApplied() {
        Employee e = employeeAtVersion5();

        ApplyOutcome outcome = e.applyHr(hr("HYD-01", "S1400"), 6, T2);

        assertThat(outcome).isEqualTo(ApplyOutcome.APPLIED);
        assertThat(e.hr().officeCode()).isEqualTo("HYD-01");
        assertThat(e.getHrVersion()).isEqualTo(6);
        assertThat(e.getLastHrChangeAt()).isEqualTo(T2);
    }

    /** The case study's example: office transfer v7 arrives before shift change v6. */
    @Test
    void outOfOrder_v7BeforeV6_staleV6IsIgnoredAndV7Wins() {
        Employee e = employeeAtVersion5();
        HrProfile v7 = hr("HYD-01", "S2200"); // transfer; carries the full state, including v7's shift
        HrProfile v6 = hr("BLR-01", "S1400"); // older shift change

        ApplyOutcome first = e.applyHr(v7, 7, T3);
        ApplyOutcome second = e.applyHr(v6, 6, T2);

        assertThat(first).isEqualTo(ApplyOutcome.APPLIED);
        assertThat(second).isEqualTo(ApplyOutcome.STALE);
        assertThat(e.hr()).isEqualTo(v7);
        assertThat(e.getHrVersion()).isEqualTo(7);
    }

    @Test
    void sameEventTwiceIsADuplicateAndChangesNothing() {
        Employee e = employeeAtVersion5();
        e.applyHr(hr("HYD-01", "S1400"), 6, T2);
        String hashAfterFirst = e.getRowHash();

        ApplyOutcome replay = e.applyHr(hr("HYD-01", "S1400"), 6, T2);

        assertThat(replay).isEqualTo(ApplyOutcome.DUPLICATE);
        assertThat(e.getRowHash()).isEqualTo(hashAfterFirst);
        assertThat(e.getHrVersion()).isEqualTo(6);
    }

    @Test
    void sameVersionWithDifferentDataIsAConflictAndChangesNothing() {
        Employee e = employeeAtVersion5();

        ApplyOutcome outcome = e.applyHr(hr("HYD-01", "S1400"), 5, T2);

        assertThat(outcome).isEqualTo(ApplyOutcome.VERSION_CONFLICT);
        assertThat(e.hr().officeCode()).isEqualTo("BLR-01");
    }

    @Test
    void hrUpdateNeverTouchesTransportOwnedFields() {
        Employee e = employeeAtVersion5();
        TransportProfile transport = new TransportProfile("Gate 3, Tower A", "Wheelchair access");
        e.updateTransport(transport);

        e.applyHr(hr("BLR-01", "S1400"), 6, T2);

        assertThat(e.transport()).isEqualTo(transport);
    }

    @Test
    void officeChangeFlagsAnExistingPickupPointForReview() {
        Employee e = employeeAtVersion5();
        e.updateTransport(new TransportProfile("Gate 3, Tower A", null));

        e.applyHr(hr("HYD-01", "S0900"), 6, T2);

        assertThat(e.isPickupNeedsReview()).isTrue();
        assertThat(e.transport().pickupPoint()).isEqualTo("Gate 3, Tower A"); // kept, not wiped
    }

    @Test
    void officeChangeWithoutPickupPointIsNotFlagged() {
        Employee e = employeeAtVersion5();

        e.applyHr(hr("HYD-01", "S0900"), 6, T2);

        assertThat(e.isPickupNeedsReview()).isFalse();
    }

    @Test
    void transportEditClearsReviewFlagAndLeavesHrAlone() {
        Employee e = employeeAtVersion5();
        e.updateTransport(new TransportProfile("Gate 3", null));
        e.applyHr(hr("HYD-01", "S0900"), 6, T2);
        HrProfile hrBefore = e.hr();

        e.updateTransport(new TransportProfile("Hitec City Gate 1", null));

        assertThat(e.isPickupNeedsReview()).isFalse();
        assertThat(e.hr()).isEqualTo(hrBefore);
        assertThat(e.getHrVersion()).isEqualTo(6);
    }

    @Test
    void newerDeactivationMarksInactiveAndKeepsTransportData() {
        Employee e = employeeAtVersion5();
        e.updateTransport(new TransportProfile("Gate 3", "Wheelchair access"));

        ApplyOutcome outcome = e.deactivate(6, T2);

        assertThat(outcome).isEqualTo(ApplyOutcome.APPLIED);
        assertThat(e.getStatus()).isEqualTo(Status.INACTIVE);
        assertThat(e.transport().pickupPoint()).isEqualTo("Gate 3");
    }

    @Test
    void staleDeactivationIsIgnored() {
        Employee e = employeeAtVersion5();

        ApplyOutcome outcome = e.deactivate(4, T2);

        assertThat(outcome).isEqualTo(ApplyOutcome.STALE);
        assertThat(e.getStatus()).isEqualTo(Status.ACTIVE);
    }

    @Test
    void newerCreateAfterDeactivationIsARehire() {
        Employee e = employeeAtVersion5();
        e.deactivate(6, T2);

        ApplyOutcome outcome = new RosterEvent.Create("E1", 7, T3, hr("BLR-02", "S0900")).applyTo(e);

        assertThat(outcome).isEqualTo(ApplyOutcome.APPLIED);
        assertThat(e.getStatus()).isEqualTo(Status.ACTIVE);
    }

    @Test
    void onlyCreateMayCreateAnUnknownEmployee() {
        HrProfile profile = hr("BLR-01", "S0900");

        assertThat(new RosterEvent.Create("E2", 1, T1, profile).newEmployee("ACME")).isPresent();
        assertThat(new RosterEvent.Update("E2", 2, T1, profile).newEmployee("ACME")).isEmpty();
        assertThat(new RosterEvent.Deactivate("E2", 3, T1).newEmployee("ACME")).isEmpty();
    }

    @Test
    void toStringContainsNoPersonalData() {
        Employee e = employeeAtVersion5();

        String text = e.toString();

        assertThat(text).contains("E1").doesNotContain("Employee 1", "e1@acme.test", "+919800000001");
    }
}
