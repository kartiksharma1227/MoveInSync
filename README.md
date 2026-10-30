# HR Roster Sync

Keeps MoveInSync's copy of each corporate client's employee roster correct, even though the client's HR system sends
updates **twice**, **out of order**, with **bad data**, and sometimes as a **truncated nightly file**. Every applied
change is published reliably (transactional outbox), and an employee who leaves has their future cabs cancelled.

Spring Boot 4.1 · Java 21 · PostgreSQL 16 · Flyway · Spring Security (JWT) · Micrometer/Prometheus · Testcontainers.
**Design note with diagrams and the failure-mode table: [DESIGN.md](DESIGN.md).**

---

## Quick start

```bash
docker compose up --build          # app on :8080 + Postgres, seeded with demo tenants ACME and GLOBEX
```

```bash
# 1. Get a dev token (the dev profile issues them; in production an identity provider would)
TOKEN=$(curl -s localhost:8080/dev/token -H 'Content-Type: application/json' \
  -d '{"sub":"acme-hr","tenant":"ACME","roles":["HR_SYSTEM"]}' | sed -E 's/.*"token":"([^"]+)".*/\1/')

# 2. Push a batch of HR events (valid, invalid, duplicate and out-of-order records)
curl -s localhost:8080/api/v1/events -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' --data @samples/events-batch.json

# 3. Upload a nightly file
curl -s localhost:8080/api/v1/reconciliations -H "Authorization: Bearer $TOKEN" \
  -F file=@samples/nightly-full.csv -F snapshotAt=2026-10-30T22:00:00Z
```

| Script | What it shows |
|---|---|
| `scripts/demo.sh` | Every main flow end to end: per-record report, replay, out-of-order, tenant isolation, field ownership, nightly run, safety brake + operator decision, metrics, error format |
| `scripts/crash-demo.sh` | **Live failure:** the app is killed (`kill -9`) after a change commits but before its event is published, and after a restart the event is still delivered and bookings are cancelled |

**Tests:** `./mvnw test` (Docker must be running; Testcontainers starts Postgres). 111 tests, including the 200k
benchmark and the load test, finish in about 1.5 minutes.

---

## API

All endpoints need `Authorization: Bearer <JWT>`. The tenant is taken **only from the token**, never from the request.

| Endpoint | Role | Purpose |
|---|---|---|
| `POST /api/v1/events` | `HR_SYSTEM` | Batch of ≤ 1,000 `CREATE` / `UPDATE` / `DEACTIVATE` events → per-record report |
| `GET /api/v1/employees/{id}` | `TRANSPORT_ADMIN`, `OPERATOR`, `EMPLOYEE` (self) | Read an employee (operators don't see contact details) |
| `PUT /api/v1/employees/{id}/transport` | `TRANSPORT_ADMIN` | Pickup point and special assistance |
| `POST /api/v1/reconciliations` (multipart `file`, `snapshotAt`) | `HR_SYSTEM` | Nightly full file → `COMPLETED`, or `AWAITING_APPROVAL` if the safety brake trips (`snapshotAt` more than 5 min in the future → 400 `INVALID_SNAPSHOT_TIME`) |
| `GET /api/v1/reconciliations/{id}` | `OPERATOR` | Run status and counts (incl. how many employees the file appears to be missing), plus a preview of the first 100 rejected rows |
| `GET /api/v1/reconciliations/{id}/rejections?afterRow=0&limit=500` | `OPERATOR`, `HR_SYSTEM` | The **complete** per-row error report (row, employeeId, field, code), paged by row number (max 1,000 per page); `nextAfterRow` is null on the last page |
| `POST /api/v1/reconciliations/{id}/approve` · `/reject` | `OPERATOR` | Decide a held run |
| `GET /actuator/health` | none | Liveness |
| `GET /actuator/prometheus` | `OPERATOR` | Metrics |
| `POST /dev/token` | none (dev profile only) | Issue a test token |

**Event:** `{eventId, type, employeeId, version, occurredAt, hr: {name, email, phone, managerId, officeCode, shift}}`
**Nightly CSV:** `employee_id,version,name,email,phone,manager_id,office_code,shift`
**Every error:** `{status, title, code, detail, instance, correlationId}`, e.g. `code: "EMPLOYEE_NOT_FOUND"`. Input values
are never echoed, and `instance` is the route pattern (`/api/v1/employees/{employeeId}`), never the raw path.
**Per-record results** (`POST /events`): `rejected` = the record is invalid (`REQUIRED`, `INVALID_FORMAT`, `TOO_LONG`,
`UNKNOWN_OFFICE`, `UNKNOWN_EMPLOYEE`, `INVALID_TYPE`, `INVALID_VERSION`); `failed` = valid but couldn't be applied right
now, **retryable** (`LOCK_TIMEOUT`, `PROCESSING_FAILED`); `versionConflict` is reported too. If the database itself is
unreachable, the batch stops with **503 `DATABASE_UNAVAILABLE`**, and resending it is safe.

---

## Idempotency key and version comparison

**Key:** `(tenantId, employeeId, version)`, where `version` is the source system's per-employee version number.

**Stored state:** one number per employee, `employee.hr_version` (the version of the last applied HR change). There is
no "processed events" table: any event at or below the stored version has already been seen or superseded, so this
single column *is* the deduplication state. That's O(1) per employee instead of a table growing with every event.

Every incoming change (API event or nightly row) is compared with the stored version (`VersionGate`, used by `Employee`):

| Incoming vs stored | Outcome | Effect |
|---|---|---|
| `incoming > stored` | `APPLIED` | Change applied, `hr_version` = incoming, one outbox event written |
| `incoming == stored`, same data (same row hash) | `DUPLICATE` | No-op: a replay |
| `incoming == stored`, different data | `VERSION_CONFLICT` | No-op, but **reported**: the source reused a version number |
| `incoming < stored` | `STALE` | Ignored: a newer change is already applied (e.g. v6 arriving after v7) |

- A replay writes **nothing**: no row update (not even `updated_at`), no outbox event. That's proven by
  `IngestApiTest.replayingTheSameFeedTwiceChangesNothing`.
- Nightly-file rows go through the same comparison, so a file can never revert a newer real-time change.
- Concurrent changes for one employee are serialised by a row lock (`SELECT … FOR UPDATE`) before comparing.
- A source that only has timestamps can use epoch milliseconds as the version. Anything that only increases per employee works.

---

## Field ownership and conflict rule

| Owner | Fields | Changed only by |
|---|---|---|
| **HR system** | name, email, phone, manager, office, shift | `POST /events` and the nightly file |
| **Transport admin** | pickup point, special-assistance needs | `PUT /employees/{id}/transport` |

**Conflict rule:**
1. Every field has exactly one owner, and only that owner's channel can change it. This is enforced **by types**: an HR change
   carries an `HrProfile` and can only call `employee.applyHr(...)`, while a transport edit carries a `TransportProfile`
   and calls `employee.updateTransport(...)`. There is no code path from one to the other's fields.
2. Transport fields sent inside an HR payload are **ignored**, not applied and not an error.
3. When HR changes an employee's **office**, the transport-owned pickup point is **kept** (HR may not change it) but
   flagged `pickupNeedsReview = true`, since it may belong to the old office. The transport admin's next edit clears the flag.
4. HR **deactivation wins**: the employee becomes inactive and future bookings are cancelled. Transport data is **kept**, so
   a re-hire gets it back.
5. Two transport admins editing at once → optimistic lock (`@Version`) → the second gets **409 `CONCURRENT_MODIFICATION`**
   and reloads.
6. The nightly file follows the same rules. Deactivating someone because a file *left them out* does **not** consume an HR
   version, so any real HR change that arrives later still applies (and reactivates them).

---

## Hard rules and the tests that prove them

| # | Rule | Proven by |
|---|---|---|
| H1 | Invalid record rejected with a per-record error, batch continues | `IngestApiTest.badRecordsAreRejectedOneByOneAndTheGoodOnesStillApply`, `RecordValidatorTest`, `ReconciliationTest.rejectedRowIsReportedButItsEmployeeIsNotTreatedAsMissing`, `…completeRejectionReportIsPagedAndContainsNoValues` |
| H2 | Per-employee version order: stale ignored (v7 before v6) | `EmployeeTest.outOfOrder_v7BeforeV6_staleV6IsIgnoredAndV7Wins`, `IngestApiTest.outOfOrder_v7BeforeV6_staleV6IsIgnored` |
| H3 | Replaying the same feed twice changes nothing | `IngestApiTest.replayingTheSameFeedTwiceChangesNothing` (even `updated_at` is unchanged, and no new outbox events), `ReconciliationTest.sameFileTwiceChangesNothingTheSecondTime` |
| H4 | HR never overwrites transport-owned fields | `EmployeeTest.hrUpdateNeverTouchesTransportOwnedFields`, `IngestApiTest.hrOfficeChangeKeepsTheAdminsPickupPointAndFlagsIt`, `…transportFieldsSentInsideAnHrEventAreIgnored`, `…employeeCannotEditTransportFieldsEvenTheirOwn` |
| H5 | 200k-row diff < 60 s, bounded memory | `ReconciliationBenchmarkTest.diffOf200kRowsFinishesUnder60SecondsWithFlatMemory` |
| H6 | File cut at 120k of 200k → brake holds, nobody deactivated, missing count shown, configurable threshold | `ReconciliationBenchmarkTest.fileCutAt120kOf200kTripsTheBrakeAndDeactivatesNobody`, `ReconciliationTest.brakeHoldsTheRunUntilAnOperatorApproves`, `…thresholdIsConfigurableAndRecordedOnEachRun`, `…snapshotTakenInTheFutureIsRefusedAndNoRunIsCreated` |
| H7 | Crash between DB write and event publish → event still delivered | `OutboxTest.crashBetweenCommitAndPublish_eventIsDeliveredAfterRestart` + `scripts/crash-demo.sh` |
| H8 | Leaver's future bookings cancelled, seats freed | `OutboxTest.deactivationCancelsOnlyFutureBookingsAndFreesTheirSeats` |
| H9 | Tenant isolation | `IngestApiTest.anotherTenantCannotSeeTheEmployee`, `ReconciliationTest.onlyHrUploadsAndOperatorsSeeOnlyTheirOwnTenantsRuns` |
| H10 | Concurrency | `IngestConcurrencyTest` (19 threads race versions of one employee, 8 threads race the same CREATE), `OutboxTest.fourRelaysDrainingTogetherDeliverEachEventExactlyOnce` |
| H11 | No personal data in logs or error responses | `ObservabilityTest.noPersonalDataAppearsInTheLogs`, `ErrorHandlingTest.unexpectedErrorIsAGeneric500AndItsMessageNeverReachesTheResponseOrTheLogs`, `…errorBodyNamesTheRouteNotTheRawPath`, `IngestApiTest.personalDataInAnIdFieldIsRejectedAndNeverEchoed` |
| — | Failure injection | `OutboxTest.failedPublishIsRetriedAndDoesNotBlockOtherEvents`, `…poisonEventIsParkedAfterMaxAttemptsWhileOthersFlow`, `ReconciliationTest.runLeftBehindByACrashIsRecoveredAndUnblocksTheTenant` |
| — | Timeouts and partial failures | `FailureIsolationTest.lockHeldTooLongFailsOnlyThatRecordAndNothingHangs`, `…unexpectedDatabaseErrorFailsOnlyThatRecord`, `…lockedEmployeeFailsOnlyThatRowOfTheNightlyRun`, `IngestServiceDatabaseDownTest` |

---

## Benchmarks

**Machine:** Apple M2 (8 cores), 8 GB RAM, macOS, Docker 29.7, Postgres 16 in a container, Java 22. Numbers vary
between runs, so ranges are from several runs.

**Nightly reconciliation** (`ReconciliationBenchmarkTest`), with 1% of rows changed:

| Rows | Total time | Rows/s | Breakdown (200k) | Peak heap growth |
|---|---|---|---|---|
| 50,000 | 2.5 – 4.9 s | 10k – 20k | | 59 – 110 MB |
| **200,000** | **7.8 – 10.7 s** (budget 60 s) | 19k – 26k | stage ≈ 5 s · brake counts ≈ 0.3 s · diff + apply 2,000 changes ≈ 2.4 – 5.4 s | 83 – 110 MB |

4× the rows did **not** mean 4× the memory: every stage holds at most one 1,000-row batch or page. (Peak heap includes
uncollected garbage, so it's an upper bound.) **Truncated file:** 120,000 of 200,000 rows → held, `missingCount = 80,000`,
ratio 0.400, 0 deactivated.

**Real-time API load test** (`IngestLoadTest`): 8 concurrent clients × 250 requests × 10 events = 20,000 events,
through the full stack in-process (no network hop):

| Requests/s | Events/s | p50 | p95 | p99 | max |
|---|---|---|---|---|---|
| 205 – 377 | 2,050 – 3,770 | 19.5 – 37.6 ms | 30.6 – 56.7 ms | 43.5 – 73.5 ms | 65 – 121 ms |

Each event is its own transaction (row lock + update + outbox insert + commit), so commit latency on the Dockerised
Postgres dominates.

---

## Complexity and where it stops scaling

| Step | Time | Space (app) |
|---|---|---|
| Apply one event | O(1): one indexed row lock + update + outbox insert | O(1) |
| Batch of n events | O(n) transactions | O(n) for the report |
| Stage nightly file of N rows | O(N) parse, hash, batch insert | **O(batch)** = 1,000 rows |
| Brake counts, diff | O(N) hash/anti-joins in Postgres | O(1) |
| Apply C changed + M missing | O(C + M) transactions, paged by keyset | **O(page)** = 1,000 rows |

**Limits, in the order you'd hit them:**
1. **Single Postgres write throughput:** every change is a committed transaction. Next steps: chunked transactions, a
   partitioned queue in front (Kafka keyed by employee), then **sharding by tenant** (tenants never share data).
2. **First-ever load of a huge tenant:** 200k new employees = 200k transactions (minutes). Fix: parallel apply
   (employees are independent) or a dedicated bulk-load path.
3. **Synchronous upload:** beyond a few million rows, HTTP or load-balancer timeouts. Fix: return 202 + run ID and process in
   the background (the run is already persisted with its status).

---

## Trade-offs

**Consistency over latency for every change.** Each event is applied synchronously under a row lock (`SELECT … FOR UPDATE`)
with a version check, so the caller learns `applied` / `stale` / `duplicate` / `rejected` immediately and per-employee order
is guaranteed across any number of instances. The cost is a database round trip per event and a ceiling at Postgres write
throughput. A queue-first design would absorb bursts better (availability) but answer only "accepted".

**Outbox: atomic, at-least-once, slightly delayed.** The change and its event commit together, so they can never disagree.
Delivery is at-least-once with up to ~500 ms of polling lag, so consumers must be idempotent (booking cancellation is).
Exactly-once delivery across a crash isn't achievable without consumer cooperation, so we chose idempotent consumers instead
of distributed transactions.

**Safety over freshness in the nightly run.** If ≥ 30% of active employees are missing, the *whole* run waits for a human,
even though some of its updates might be fine. A day's delay on the nightly safety net is cheap compared with 80,000 people
without a cab, and the real-time API keeps flowing meanwhile.

**Thin events.** Events carry IDs and codes, never contact details, so no personal data sits in the outbox or a broker.
Consumers that need details call the API.

**Strict creation.** Only `CREATE` can create an employee, so a typo'd ID in an `UPDATE` can't invent a person. If an
`UPDATE` overtakes its `CREATE`, it's rejected, and HR's retry or the next nightly file heals it.

**Deliberately left out:** a real message broker (`EventPublisher` is an interface, so Kafka or Redis Streams is one class),
event sourcing, per-field versioning (events are full snapshots), an identity provider (tokens are verified for real but issued
by a dev endpoint), async uploads, Merkle-style hash buckets (the SQL diff already meets the target), encryption of personal
data at rest, and distributed tracing.

---

## Timeouts and partial failures

| What could hang or fail | Bound / behaviour |
|---|---|
| Waiting for another transaction's row lock | Postgres `lock_timeout` **5 s** (`roster.db.lock-timeout`) → that record fails with `LOCK_TIMEOUT`, the rest continue |
| Any single SQL statement | `statement_timeout` **60 s** (`roster.db.statement-timeout`) |
| Waiting for a pooled connection | Hikari `connection-timeout` **10 s** |
| Database unreachable | Batch stops at once with **503 `DATABASE_UNAVAILABLE`** instead of failing every record slowly; resend is safe |
| One record hits an unexpected error | That record → `PROCESSING_FAILED`, the batch continues (`failed` count) |
| One nightly row fails | Run still `COMPLETED`, with a `failed` count; re-uploading the same file retries just those rows |
| Event publishing | In-process, so no network wait. A Kafka publisher would bound it with `delivery.timeout.ms`, and failures are retried by the relay anyway |

The timeouts are set once per pooled connection (`connection-init-sql`), so every query is bounded without per-query code.

---

## Caching

| Cached | Where | Eviction | What can be stale, for how long |
|---|---|---|---|
| Each tenant's office codes (read by every batch and file) | Caffeine, in memory (`CacheConfig`) | 5 min after write; max 1,000 tenants (least recently used) | A **newly added office** may be rejected as `UNKNOWN_OFFICE` for **≤ 5 minutes**. Nothing wrong is ever written: the worst case is a rejected record that HR retries. Offices are only added today. An office-admin feature would evict the tenant's entry on every change (`@CacheEvict`) |

Nothing else is cached: employee state must be read under its row lock to be correct. Hit and miss rates are exported as
`cache_gets_total{cache="officeCodes"}`. With several instances, each has its own copy with the same 5-minute bound.

---

## Monitoring

`/actuator/prometheus` (OPERATOR token). What on-call needs at 3 AM:

| Need | Metric | Suggested alert |
|---|---|---|
| Queue depth | `roster_outbox_pending` | |
| Lag | `roster_outbox_lag_seconds` | > 60 s for 5 min → page |
| Retries | `roster_outbox_publish_failures_total{type}` | rising steadily → investigate |
| Stuck events | `roster_outbox_parked` | > 0 → ticket |
| Rejections / error rate | `roster_events_total{tenant,outcome}`, `http_server_requests_seconds_count{status}` | rejected > 5% per tenant → ticket; 5xx > 1% → page |
| Ingest latency | `roster_ingest_batch_seconds{quantile}` | p99 > 1 s → investigate |
| Nightly health | `roster_recon_runs_total{status}`, `roster_recon_brake_tripped_total` | any brake trip → page (a run awaits approval) |

**Alert rules:** [`monitoring/alerts.yml`](monitoring/alerts.yml) holds these as ready-to-load Prometheus rules (brake tripped,
nightly run failed, outbox lag, parked events, rejection rate, 5xx rate), each with a severity (`page` / `ticket`) and a
description telling on-call what to do. They're validated with
`docker run --rm -v $PWD/monitoring:/m --entrypoint promtool prom/prometheus:v3.5.0 check rules /m/alerts.yml`.
This is how "alert an operator" reaches a human: the brake trip increments a counter, and the rule pages on-call.

Logs are JSON (Elastic Common Schema) in Docker and readable text locally. Every line carries the request's
`correlationId`, which is also returned in the `X-Correlation-Id` header and in every error body. Logs contain IDs only.

---

## Assumptions

- **Events are full snapshots** of the HR-owned fields, not deltas. That's why a stale event can simply be ignored (the case
  study's v7/v6 example only works this way).
- **Ownership:** HR owns name, email, phone, manager, office and shift. The transport admin owns pickup point and special
  assistance. Phone and shift weren't assigned in the brief, and HR owns them because they arrive in the HR feed.
- **Versions** are integers that increase per employee in the source system. `(tenant, employeeId, version)` is the
  idempotency key.
- **`snapshotAt`** is when HR took the export. Employees changed after it are never treated as missing.
- An HR change of office **keeps** the transport admin's pickup point and flags it for review. Deactivation keeps transport
  data for a possible re-hire, and cancelled bookings are not restored on re-hire.
- An employee deactivated only because a nightly file left them out is reactivated if a later file lists them again unchanged.
- **IDs** (`employeeId`, `eventId`) match `[A-Za-z0-9._-]{1,64}`. Anything else (spaces, `+`, `@`) is rejected as
  `INVALID_FORMAT` and never echoed back, because a value in the wrong field may be personal data.

---

## Project layout

```
src/main/java/com/moveinsync/roster/
  controller/   employee/RosterController · reconciliation/ReconciliationController · auth/DevTokenController
  service/      employee/{IngestService, EmployeeService}
                outbox/{OutboxWriter, OutboxRelay, EventPublisher, InProcessEventPublisher, BookingCancellationHandler}
                reconciliation/{ReconciliationService, SnapshotStager}
  core/         VersionGate · RecordValidator · RowHasher · SafetyBrake · RosterEvent · ApplyOutcome   ← plain Java, no Spring
  entity/       BaseTenantEntity · Employee · OutboxEvent · Booking · ReconciliationRun
  repository/   Employee · Outbox · Booking · ReconciliationRun · Snapshot · Office
  config/       SecurityConfig · CurrentUser · ApiExceptionHandler · CorrelationIdFilter · RosterMetrics · CacheConfig
src/main/resources/db/migration   Flyway schema (db/seed = demo data, dev profile only)
samples/      example event batch and nightly files      scripts/   demo.sh · crash-demo.sh
```
