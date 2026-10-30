-- Rows that were valid but couldn't be applied in this run (e.g. a lock timeout). The run still completes;
-- re-uploading the same file retries just these rows.
ALTER TABLE reconciliation_run ADD COLUMN failed_count INT NOT NULL DEFAULT 0;
