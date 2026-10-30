-- The complete per-row error report of a nightly file. Kept after the run finishes (unlike staged rows) so HR can
-- fix every bad row; removed together with its run. Holds row numbers, IDs and error codes only, never field values.
CREATE TABLE reconciliation_rejection (
    run_id      BIGINT      NOT NULL REFERENCES reconciliation_run (id) ON DELETE CASCADE,
    row_number  BIGINT      NOT NULL,
    employee_id VARCHAR(64),                   -- null when the ID column didn't hold a valid ID
    errors      JSONB       NOT NULL,          -- [{field, code}]
    PRIMARY KEY (run_id, row_number)
);
