-- A file row that failed validation is still staged (valid = false) so its employee is not mistaken for "missing".
-- It is never applied.
ALTER TABLE snapshot_row ADD COLUMN valid BOOLEAN NOT NULL DEFAULT TRUE;

-- At most one nightly run per tenant may be staging or applying at a time.
CREATE UNIQUE INDEX ux_reconciliation_one_open_run ON reconciliation_run (tenant_id)
    WHERE status IN ('STAGING', 'APPLYING');
