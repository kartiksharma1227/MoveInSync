package com.moveinsync.roster.controller.reconciliation;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.moveinsync.roster.config.CurrentUser;
import com.moveinsync.roster.service.reconciliation.ReconciliationService;
import com.moveinsync.roster.service.reconciliation.ReconciliationService.RunView;

/** HR uploads the nightly file; operators review held runs and approve or reject them. */
@RestController
@RequestMapping("/api/v1/reconciliations")
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    /** Runs the whole reconciliation and returns its result (COMPLETED, or AWAITING_APPROVAL if the brake tripped). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('HR_SYSTEM')")
    public RunView upload(@AuthenticationPrincipal Jwt jwt, @RequestParam("file") MultipartFile file,
                          @RequestParam("snapshotAt") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant snapshotAt)
            throws IOException {
        try (InputStream in = file.getInputStream()) {
            return service.start(CurrentUser.from(jwt).tenantId(), in, snapshotAt);
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('OPERATOR')")
    public RunView get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.get(CurrentUser.from(jwt), id);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('OPERATOR')")
    public RunView approve(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.approve(CurrentUser.from(jwt), id);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('OPERATOR')")
    public RunView reject(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.reject(CurrentUser.from(jwt), id);
    }
}
