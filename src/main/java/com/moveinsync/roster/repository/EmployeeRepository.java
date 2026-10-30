package com.moveinsync.roster.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.moveinsync.roster.entity.Employee;

import jakarta.persistence.LockModeType;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByTenantIdAndEmployeeId(String tenantId, String employeeId);

    /**
     * SELECT ... FOR UPDATE: locks this one employee's row until the transaction ends, so two changes for the same
     * employee are applied one after the other. Different employees never wait for each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Employee e WHERE e.tenantId = :tenantId AND e.employeeId = :employeeId")
    Optional<Employee> findForUpdate(@Param("tenantId") String tenantId, @Param("employeeId") String employeeId);
}
