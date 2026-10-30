package com.moveinsync.roster.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.moveinsync.roster.entity.Employee;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByTenantIdAndEmployeeId(String tenantId, String employeeId);
}
