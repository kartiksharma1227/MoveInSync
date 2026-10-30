package com.moveinsync.roster.service.reconciliation;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.moveinsync.roster.config.CurrentUser;
import com.moveinsync.roster.config.RosterMetrics;
import com.moveinsync.roster.core.ApplyOutcome;
import com.moveinsync.roster.core.RosterEvent;
import com.moveinsync.roster.core.SafetyBrake;
import com.moveinsync.roster.entity.Employee;
import com.moveinsync.roster.entity.Employee.HrProfile;
import com.moveinsync.roster.entity.OutboxEvent;
import com.moveinsync.roster.entity.ReconciliationRun;
import com.moveinsync.roster.repository.EmployeeRepository;
import com.moveinsync.roster.repository.ReconciliationRunRepository;
import com.moveinsync.roster.repository.SnapshotRepository;
import com.moveinsync.roster.repository.SnapshotRepository.ChangedRow;
import com.moveinsync.roster.service.employee.IngestService;
import com.moveinsync.roster.service.outbox.OutboxWriter;
import com.moveinsync.roster.service.reconciliation.SnapshotStager.InvalidFileException;
import com.moveinsync.roster.service.reconciliation.SnapshotStager.StageResult;

import tools.jackson.databind.ObjectMapper;

/**
 * The nightly reconciliation: stage the file, check the safety brake, then apply the differences.
 * Every step is either a single set-based query or works through the file one page at a time.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final int PAGE_SIZE = 1000;
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final int MAX_REJECTION_PAGE = 1000;

    public record RunView(long id, String status, Instant snapshotAt, String fileSha256, int rowsTotal,
                          int rowsRejected, Integer activeCount, Integer missingCount, Double missingRatio,
                          BigDecimal threshold, int created, int updated, int deactivated, int failed,
                          String decidedBy, Instant decidedAt, Object rejections) {
    }

    public record RejectionItem(long row, String employeeId, Object errors) {
    }

    public record RejectionPage(List<RejectionItem> items, Long nextAfterRow) {
    }

    private final ReconciliationRunRepository runs;
    private final SnapshotRepository snapshots;
    private final SnapshotStager stager;
    private final IngestService ingestService;
    private final EmployeeRepository employees;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final RosterMetrics metrics;
    private final double brakeThreshold;
    private final long stuckAfterMinutes;

    public ReconciliationService(ReconciliationRunRepository runs, SnapshotRepository snapshots, SnapshotStager stager,
                                 IngestService ingestService, EmployeeRepository employees, OutboxWriter outboxWriter,
                                 TransactionTemplate tx, ObjectMapper json, RosterMetrics metrics,
                                 @Value("${roster.brake.threshold}") double brakeThreshold,
                                 @Value("${roster.recon.stuck-after-minutes}") long stuckAfterMinutes) {
        this.runs = runs;
        this.snapshots = snapshots;
        this.stager = stager;
        this.ingestService = ingestService;
        this.employees = employees;
        this.outboxWriter = outboxWriter;
        this.tx = tx;
        this.json = json;
        this.metrics = metrics;
        this.brakeThreshold = brakeThreshold;
        this.stuckAfterMinutes = stuckAfterMinutes;
    }

    public RunView start(String tenantId, InputStream file, Instant snapshotAt) {
        return start(tenantId, file, snapshotAt, brakeThreshold);
    }

    /** Same as {@link #start(String, InputStream, Instant)} with an explicit brake threshold. */
    public RunView start(String tenantId, InputStream file, Instant snapshotAt, double threshold) {
        // An export can't be taken in the future. A future snapshotAt would make every employee look "missing"
        // (their last change is before it), so refuse it up front. 5 minutes allows for clock skew between systems.
        if (snapshotAt.isAfter(Instant.now().plus(MAX_CLOCK_SKEW))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_SNAPSHOT_TIME");
        }
        SafetyBrake brake = new SafetyBrake(threshold);
        long runId = createRun(tenantId, snapshotAt, threshold);
        try {
            long t0 = System.nanoTime();
            StageResult staged = stager.stage(tenantId, runId, file);
            long t1 = System.nanoTime();

            long active = snapshots.countActive(tenantId);
            long missing = snapshots.countMissing(tenantId, runId, snapshotAt);
            SafetyBrake.Decision decision = brake.evaluate(active, missing);
            ReconciliationRun run = update(runId, r -> {
                r.staged(staged.sha256(), staged.rowsTotal(), staged.rowsRejected(), toJson(staged.rejections()));
                r.braked(decision);
            });
            long t2 = System.nanoTime();
            log.info("Reconciliation staged runId={} tenant={} rows={} rejected={} stageMs={} brakeMs={}",
                    runId, tenantId, staged.rowsTotal(), staged.rowsRejected(), ms(t1 - t0), ms(t2 - t1));
            metrics.reconRowsRejected(tenantId, staged.rowsRejected());

            if (decision.held()) {
                // The alert an operator acts on. Counts and IDs only.
                log.error("SAFETY BRAKE: reconciliation held runId={} tenant={} active={} missing={} missingRatio={} threshold={}",
                        runId, tenantId, active, missing, String.format("%.3f", decision.missingRatio()), threshold);
                metrics.brakeTripped(tenantId);
                metrics.reconRun(tenantId, "held");
                return view(run);
            }
            RunView completed = view(applyAndComplete(run));
            metrics.reconDuration(Duration.ofNanos(System.nanoTime() - t0));
            return completed;
        } catch (InvalidFileException e) {
            markFailed(runId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (RuntimeException e) {
            markFailed(runId);
            throw e;
        }
    }

    public RunView approve(CurrentUser operator, long runId) {
        ReconciliationRun run = decide(operator, runId, true);
        try {
            return view(applyAndComplete(run));
        } catch (RuntimeException e) {
            markFailed(runId);
            throw e;
        }
    }

    public RunView reject(CurrentUser operator, long runId) {
        ReconciliationRun run = decide(operator, runId, false);
        snapshots.deleteRun(runId);
        metrics.reconRun(run.getTenantId(), "rejected");
        log.info("Reconciliation rejected runId={} tenant={} by={}", runId, run.getTenantId(), operator.userId());
        return view(run);
    }

    public RunView get(CurrentUser user, long runId) {
        return view(runs.findByIdAndTenantId(runId, user.tenantId()).orElseThrow(this::notFound));
    }

    /**
     * The complete per-row error report of a run, one page at a time.
     * @param afterRow return rows after this row number (0 for the first page)
     * @return the page, plus where the next page starts (null when there are no more)
     */
    public RejectionPage rejections(CurrentUser user, long runId, long afterRow, int limit) {
        runs.findByIdAndTenantId(runId, user.tenantId()).orElseThrow(this::notFound); // tenant check
        int pageSize = Math.max(1, Math.min(limit, MAX_REJECTION_PAGE));
        List<RejectionItem> items = snapshots.rejectionsPage(runId, afterRow, pageSize).stream()
                .map(r -> new RejectionItem(r.row(), r.employeeId(), json.readTree(r.errorsJson())))
                .toList();
        Long next = items.size() == pageSize ? items.get(items.size() - 1).row() : null;
        return new RejectionPage(items, next);
    }

    /** Applies the differences page by page, then deactivates the missing. Safe to repeat: every step is idempotent. */
    private ReconciliationRun applyAndComplete(ReconciliationRun run) {
        long start = System.nanoTime();
        String tenantId = run.getTenantId();
        long runId = run.getId();
        Instant snapshotAt = run.getSnapshotAt();
        int created = 0;
        int updated = 0;
        int deactivated = 0;
        int failed = 0;

        String after = "";
        List<ChangedRow> page;
        while (!(page = snapshots.changedPage(runId, tenantId, after, PAGE_SIZE)).isEmpty()) {
            for (ChangedRow row : page) {
                // Same path as a real-time event: version gate, row lock, outbox. A newer API change wins (stale).
                HrProfile hr = json.readValue(row.payloadJson(), HrProfile.class);
                Optional<ApplyOutcome> outcome;
                try {
                    outcome = ingestService.apply(tenantId,
                            new RosterEvent.Snapshot(row.employeeId(), row.version(), snapshotAt, hr));
                } catch (CannotCreateTransactionException | DataAccessResourceFailureException e) {
                    throw e; // database unreachable: stop the run (it's marked FAILED) rather than fail every row slowly
                } catch (RuntimeException e) {
                    // One row failing (e.g. its employee locked past the lock timeout) must not fail the whole run.
                    // Re-uploading the same file later applies just this row.
                    rowFailed(tenantId, runId, row.employeeId(), e);
                    failed++;
                    continue;
                }
                if (outcome.orElse(null) == ApplyOutcome.APPLIED) {
                    if (row.isNew()) {
                        created++;
                    } else {
                        updated++;
                    }
                }
            }
            after = page.get(page.size() - 1).employeeId();
            heartbeat(runId);
        }

        after = "";
        List<String> missing;
        while (!(missing = snapshots.missingPage(tenantId, runId, snapshotAt, after, PAGE_SIZE)).isEmpty()) {
            for (String employeeId : missing) {
                try {
                    if (deactivateMissing(tenantId, employeeId, snapshotAt)) {
                        deactivated++;
                    }
                } catch (CannotCreateTransactionException | DataAccessResourceFailureException e) {
                    throw e;
                } catch (RuntimeException e) {
                    rowFailed(tenantId, runId, employeeId, e);
                    failed++;
                }
            }
            after = missing.get(missing.size() - 1);
            heartbeat(runId);
        }

        int c = created, u = updated, d = deactivated, f = failed;
        ReconciliationRun done = update(runId, r -> r.complete(c, u, d, f));
        snapshots.deleteRun(runId);
        metrics.reconRun(tenantId, "completed");
        log.info("Reconciliation completed runId={} tenant={} created={} updated={} deactivated={} failed={} applyMs={}",
                runId, tenantId, c, u, d, f, ms(System.nanoTime() - start));
        return done;
    }

    private void rowFailed(String tenantId, long runId, String employeeId, RuntimeException e) {
        String code = e instanceof PessimisticLockingFailureException ? "LOCK_TIMEOUT" : "PROCESSING_FAILED";
        log.warn("Reconciliation row failed runId={} tenant={} employeeId={} code={} type={}",
                runId, tenantId, employeeId, code, e.getClass().getSimpleName());
    }

    /**
     * Recovery after a crash. A process that dies mid-run leaves it in STAGING or APPLYING, and the
     * one-open-run-per-tenant rule would then block that tenant's next upload forever. Runs that made no progress
     * for a while are marked FAILED and their staged rows removed, which unblocks the tenant. Re-uploading the file
     * is safe because every apply step is idempotent.
     * @return how many runs were recovered
     */
    @Scheduled(fixedDelayString = "${roster.recon.stuck-check-interval-ms}")
    public int failStuckRuns() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(stuckAfterMinutes));
        int recovered = 0;
        for (ReconciliationRun candidate : runs.findByStatusInAndUpdatedAtBefore(
                List.of(ReconciliationRun.Status.STAGING, ReconciliationRun.Status.APPLYING), cutoff)) {
            Boolean failed = tx.execute(status -> {
                // Re-check under the lock: the run may have finished or made progress since we listed it.
                ReconciliationRun run = runs.findForUpdate(candidate.getId(), candidate.getTenantId()).orElseThrow();
                if (!run.isInProgress() || !run.getUpdatedAt().isBefore(cutoff)) {
                    return false;
                }
                run.fail();
                return true;
            });
            if (Boolean.TRUE.equals(failed)) {
                snapshots.deleteRun(candidate.getId());
                metrics.reconRun(candidate.getTenantId(), "failed");
                recovered++;
                log.error("Reconciliation run stuck with no progress for {} min, marked FAILED runId={} tenant={}",
                        stuckAfterMinutes, candidate.getId(), candidate.getTenantId());
            }
        }
        metrics.stuckRunsRecovered(recovered);
        return recovered;
    }

    private void heartbeat(long runId) {
        tx.execute(status -> runs.touch(runId, Instant.now()));
    }

    /** One missing employee, in its own transaction: re-check under the row lock, deactivate, record the event. */
    private boolean deactivateMissing(String tenantId, String employeeId, Instant snapshotAt) {
        Boolean done = tx.execute(status -> {
            Optional<Employee> found = employees.findForUpdate(tenantId, employeeId);
            if (found.isEmpty() || !found.get().getLastHrChangeAt().isBefore(snapshotAt)) {
                return false; // changed since we listed it, e.g. by a real-time event
            }
            if (found.get().markMissingFromSnapshot(snapshotAt) != ApplyOutcome.APPLIED) {
                return false;
            }
            outboxWriter.record(OutboxEvent.Type.EMPLOYEE_DEACTIVATED, found.get());
            return true;
        });
        return Boolean.TRUE.equals(done);
    }

    private long createRun(String tenantId, Instant snapshotAt, double threshold) {
        try {
            return tx.execute(status -> runs.saveAndFlush(ReconciliationRun.start(tenantId, snapshotAt, threshold)).getId());
        } catch (DataIntegrityViolationException e) {
            // The partial unique index allows one staging/applying run per tenant.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "RUN_IN_PROGRESS");
        }
    }

    private ReconciliationRun decide(CurrentUser operator, long runId, boolean approve) {
        try {
            return tx.execute(status -> {
                ReconciliationRun run = runs.findForUpdate(runId, operator.tenantId()).orElseThrow(this::notFound);
                if (approve) {
                    run.approve(operator.userId(), Instant.now());
                } else {
                    run.reject(operator.userId(), Instant.now());
                }
                runs.flush();
                return run;
            });
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "RUN_NOT_AWAITING_APPROVAL");
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "RUN_IN_PROGRESS");
        }
    }

    private ReconciliationRun update(long runId, Consumer<ReconciliationRun> change) {
        return tx.execute(status -> {
            ReconciliationRun run = runs.findById(runId).orElseThrow();
            change.accept(run);
            return run;
        });
    }

    private void markFailed(long runId) {
        ReconciliationRun run = update(runId, ReconciliationRun::fail);
        snapshots.deleteRun(runId);
        metrics.reconRun(run.getTenantId(), "failed");
    }

    private RunView view(ReconciliationRun r) {
        Double ratio = r.getActiveCount() == null || r.getActiveCount() == 0
                ? null : (double) r.getMissingCount() / r.getActiveCount();
        Object rejections = r.getRejections() == null ? List.of() : json.readTree(r.getRejections());
        return new RunView(r.getId(), r.getStatus().name(), r.getSnapshotAt(), r.getFileSha256(), r.getRowsTotal(),
                r.getRowsRejected(), r.getActiveCount(), r.getMissingCount(), ratio, r.getThreshold(),
                r.getCreatedCount(), r.getUpdatedCount(), r.getDeactivatedCount(), r.getFailedCount(), r.getDecidedBy(),
                r.getDecidedAt(), rejections);
    }

    private String toJson(Object value) {
        return json.writeValueAsString(value);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND");
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }
}
