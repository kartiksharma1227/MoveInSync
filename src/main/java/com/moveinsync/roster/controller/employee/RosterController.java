package com.moveinsync.roster.controller.employee;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.moveinsync.roster.config.CurrentUser;
import com.moveinsync.roster.service.employee.EmployeeService;
import com.moveinsync.roster.service.employee.EmployeeService.EmployeeView;
import com.moveinsync.roster.service.employee.EmployeeService.TransportRequest;
import com.moveinsync.roster.service.employee.IngestService;
import com.moveinsync.roster.service.employee.IngestService.BatchResult;
import com.moveinsync.roster.service.employee.IngestService.EventRequest;

import jakarta.validation.Valid;

/** HTTP only: roles, input binding and status codes. The rules live in the services and the domain. */
@RestController
@RequestMapping("/api/v1")
public class RosterController {

    private final IngestService ingestService;
    private final EmployeeService employeeService;
    private final int maxBatchSize;

    public RosterController(IngestService ingestService, EmployeeService employeeService,
                            @Value("${roster.ingest.max-batch-size}") int maxBatchSize) {
        this.ingestService = ingestService;
        this.employeeService = employeeService;
        this.maxBatchSize = maxBatchSize;
    }

    @PostMapping("/events")
    @PreAuthorize("hasRole('HR_SYSTEM')")
    public BatchResult ingest(@AuthenticationPrincipal Jwt jwt, @RequestBody List<EventRequest> events) {
        if (events.size() > maxBatchSize) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "BATCH_TOO_LARGE");
        }
        return ingestService.ingest(CurrentUser.from(jwt).tenantId(), events);
    }

    @GetMapping("/employees/{employeeId}")
    @PreAuthorize("hasAnyRole('TRANSPORT_ADMIN', 'OPERATOR', 'EMPLOYEE')")
    public EmployeeView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String employeeId) {
        return employeeService.get(CurrentUser.from(jwt), employeeId);
    }

    @PutMapping("/employees/{employeeId}/transport")
    @PreAuthorize("hasRole('TRANSPORT_ADMIN')")
    public EmployeeView updateTransport(@AuthenticationPrincipal Jwt jwt, @PathVariable String employeeId,
                                        @Valid @RequestBody TransportRequest request) {
        return employeeService.updateTransport(CurrentUser.from(jwt), employeeId, request);
    }

    /** Someone else changed this employee between our read and our write. The client should reload and retry. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> onConcurrentEdit() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION"));
    }
}
