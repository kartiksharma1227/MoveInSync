package com.moveinsync.roster.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.moveinsync.roster.entity.Booking;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /** An employee's confirmed bookings on trips that haven't happened yet, locked so they're cancelled only once. */
    @Query(value = """
            SELECT b.* FROM booking b
            JOIN trip t ON t.id = b.trip_id
            WHERE b.tenant_id = :tenantId AND b.employee_id = :employeeId
              AND b.status = 'CONFIRMED' AND t.pickup_at > now()
            FOR UPDATE OF b
            """, nativeQuery = true)
    List<Booking> lockFutureConfirmed(@Param("tenantId") String tenantId, @Param("employeeId") String employeeId);

    /** One atomic statement, so two cancellations on the same trip at once can't lose an update. */
    @Modifying
    @Query(value = "UPDATE trip SET seats_booked = seats_booked - 1 WHERE id = :tripId", nativeQuery = true)
    int freeSeat(@Param("tripId") Long tripId);
}
