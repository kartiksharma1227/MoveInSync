-- Every table carries tenant_id: one client's data must never be readable by another.

CREATE TABLE tenant (
    id   VARCHAR(32)  PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

CREATE TABLE office (
    tenant_id VARCHAR(32)  NOT NULL REFERENCES tenant (id),
    code      VARCHAR(32)  NOT NULL,
    name      VARCHAR(100) NOT NULL,
    PRIMARY KEY (tenant_id, code)
);

CREATE TABLE employee (
    id                  BIGSERIAL    PRIMARY KEY,
    tenant_id           VARCHAR(32)  NOT NULL REFERENCES tenant (id),
    employee_id         VARCHAR(64)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,          -- ACTIVE | INACTIVE
    -- Version of the last applied HR change; this column is the idempotency state.
    hr_version          BIGINT       NOT NULL,
    -- SHA-256 of the HR-owned fields; lets the nightly diff compare one value per employee.
    row_hash            CHAR(64)     NOT NULL,
    -- Source-system time of the last applied HR change; protects people who joined after a nightly snapshot.
    last_hr_change_at   TIMESTAMPTZ  NOT NULL,

    -- Owned by HR
    name                VARCHAR(200) NOT NULL,
    email               VARCHAR(200) NOT NULL,
    phone               VARCHAR(20)  NOT NULL,
    manager_id          VARCHAR(64),
    office_code         VARCHAR(32)  NOT NULL,
    shift               VARCHAR(32)  NOT NULL,

    -- Owned by the transport admin; HR changes never touch these
    pickup_point        VARCHAR(200),
    special_assistance  VARCHAR(200),
    pickup_needs_review BOOLEAN      NOT NULL DEFAULT FALSE,

    opt_lock            BIGINT       NOT NULL DEFAULT 0, -- optimistic lock for transport-admin edits
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_employee UNIQUE (tenant_id, employee_id),
    CONSTRAINT fk_employee_office FOREIGN KEY (tenant_id, office_code) REFERENCES office (tenant_id, code)
);

CREATE INDEX ix_employee_tenant_status ON employee (tenant_id, status);

-- A cab trip with a fixed number of seats. Cancelling a booking gives its seat back.
CREATE TABLE trip (
    id           BIGSERIAL   PRIMARY KEY,
    tenant_id    VARCHAR(32) NOT NULL REFERENCES tenant (id),
    pickup_at    TIMESTAMPTZ NOT NULL,
    capacity     INT         NOT NULL,
    seats_booked INT         NOT NULL DEFAULT 0,
    CONSTRAINT ck_trip_seats CHECK (seats_booked BETWEEN 0 AND capacity)
);

CREATE TABLE booking (
    id          BIGSERIAL   PRIMARY KEY,
    tenant_id   VARCHAR(32) NOT NULL REFERENCES tenant (id),
    employee_id VARCHAR(64) NOT NULL,
    trip_id     BIGINT      NOT NULL REFERENCES trip (id),
    status      VARCHAR(16) NOT NULL,                   -- CONFIRMED | CANCELLED
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_booking_employee ON booking (tenant_id, employee_id, status);

-- Transactional outbox: written in the same transaction as the change it describes.
CREATE TABLE outbox_event (
    id           BIGSERIAL    PRIMARY KEY,
    event_id     UUID         NOT NULL UNIQUE,          -- consumers dedupe on this
    tenant_id    VARCHAR(32)  NOT NULL,
    aggregate_id VARCHAR(64)  NOT NULL,                 -- employee_id
    event_type   VARCHAR(64)  NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts     INT          NOT NULL DEFAULT 0,
    last_error   VARCHAR(200)
);

-- The relay only ever scans unpublished rows, so index just those.
CREATE INDEX ix_outbox_unpublished ON outbox_event (id) WHERE published_at IS NULL;

CREATE TABLE reconciliation_run (
    id                BIGSERIAL     PRIMARY KEY,
    tenant_id         VARCHAR(32)   NOT NULL REFERENCES tenant (id),
    status            VARCHAR(24)   NOT NULL,           -- STAGING | AWAITING_APPROVAL | APPLYING | COMPLETED | REJECTED | FAILED
    snapshot_at       TIMESTAMPTZ   NOT NULL,           -- when HR took the export
    file_sha256       CHAR(64)      NOT NULL,
    threshold         NUMERIC(4, 3) NOT NULL,           -- brake threshold in force for this run
    rows_total        INT           NOT NULL DEFAULT 0,
    rows_rejected     INT           NOT NULL DEFAULT 0,
    rejections        JSONB,                            -- first N per-row errors: row number, employee_id, field, code
    active_count      INT,
    missing_count     INT,
    created_count     INT           NOT NULL DEFAULT 0,
    updated_count     INT           NOT NULL DEFAULT 0,
    deactivated_count INT           NOT NULL DEFAULT 0,
    decided_by        VARCHAR(64),
    decided_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Staging area for one nightly file, so the diff never holds the whole file in memory.
CREATE TABLE snapshot_row (
    run_id      BIGINT      NOT NULL REFERENCES reconciliation_run (id) ON DELETE CASCADE,
    employee_id VARCHAR(64) NOT NULL,
    version     BIGINT      NOT NULL,
    row_hash    CHAR(64)    NOT NULL,
    payload     JSONB       NOT NULL,
    PRIMARY KEY (run_id, employee_id)
);
