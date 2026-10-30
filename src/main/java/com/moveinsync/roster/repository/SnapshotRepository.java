package com.moveinsync.roster.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The staging table for nightly files, and the set-based queries that compare it with the employee table.
 * The database does the comparing, so the application never holds the whole file or the whole table in memory.
 */
@Repository
public class SnapshotRepository {

    /** One staged file row. An invalid row is kept only so its employee doesn't look missing. */
    public record StagedRow(String employeeId, long version, String rowHash, String payloadJson, boolean valid) {
    }

    /** A file row that differs from what we store. */
    public record ChangedRow(String employeeId, long version, String payloadJson, boolean isNew) {
    }

    private final JdbcTemplate jdbc;

    public SnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** If the file lists an employee twice, the row with the higher version wins. */
    public void insertBatch(long runId, List<StagedRow> rows) {
        jdbc.batchUpdate("""
                INSERT INTO snapshot_row (run_id, employee_id, version, row_hash, payload, valid)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (run_id, employee_id) DO UPDATE
                SET version = EXCLUDED.version, row_hash = EXCLUDED.row_hash,
                    payload = EXCLUDED.payload, valid = EXCLUDED.valid
                WHERE EXCLUDED.version > snapshot_row.version
                """, rows, rows.size(), (ps, row) -> {
                    ps.setLong(1, runId);
                    ps.setString(2, row.employeeId());
                    ps.setLong(3, row.version());
                    ps.setString(4, row.rowHash());
                    ps.setString(5, row.payloadJson());
                    ps.setBoolean(6, row.valid());
                });
    }

    /** Fresh statistics so the planner picks good join plans for a table that just grew by 200k rows. */
    public void analyze() {
        jdbc.execute("ANALYZE snapshot_row");
    }

    public long countActive(String tenantId) {
        return jdbc.queryForObject("SELECT count(*) FROM employee WHERE tenant_id = ? AND status = 'ACTIVE'",
                Long.class, tenantId);
    }

    /**
     * Active employees the file doesn't list. Anyone changed after the export was taken is excluded: they may have
     * joined after the snapshot, so their absence from it means nothing.
     */
    public long countMissing(String tenantId, long runId, Instant snapshotAt) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM employee e
                WHERE e.tenant_id = ? AND e.status = 'ACTIVE' AND e.last_hr_change_at < ?
                  AND NOT EXISTS (SELECT 1 FROM snapshot_row s WHERE s.run_id = ? AND s.employee_id = e.employee_id)
                """, Long.class, tenantId, Timestamp.from(snapshotAt), runId);
    }

    /**
     * The next page of valid file rows that are new, differ from what we store (by hash), or belong to an inactive
     * employee. Paged by employee ID ("keyset"), so memory stays at one page whatever the file size.
     */
    public List<ChangedRow> changedPage(long runId, String tenantId, String afterEmployeeId, int limit) {
        return jdbc.query("""
                SELECT s.employee_id, s.version, s.payload::text AS payload, (e.id IS NULL) AS is_new
                FROM snapshot_row s
                LEFT JOIN employee e ON e.tenant_id = ? AND e.employee_id = s.employee_id
                WHERE s.run_id = ? AND s.valid AND s.employee_id > ?
                  AND (e.id IS NULL OR e.row_hash <> s.row_hash OR e.status = 'INACTIVE')
                ORDER BY s.employee_id
                LIMIT ?
                """, (rs, i) -> new ChangedRow(rs.getString("employee_id"), rs.getLong("version"),
                        rs.getString("payload"), rs.getBoolean("is_new")),
                tenantId, runId, afterEmployeeId, limit);
    }

    /** The next page of missing employee IDs (same rule as {@link #countMissing}). */
    public List<String> missingPage(String tenantId, long runId, Instant snapshotAt, String afterEmployeeId, int limit) {
        return jdbc.queryForList("""
                SELECT e.employee_id FROM employee e
                WHERE e.tenant_id = ? AND e.status = 'ACTIVE' AND e.last_hr_change_at < ? AND e.employee_id > ?
                  AND NOT EXISTS (SELECT 1 FROM snapshot_row s WHERE s.run_id = ? AND s.employee_id = e.employee_id)
                ORDER BY e.employee_id
                LIMIT ?
                """, String.class, tenantId, Timestamp.from(snapshotAt), afterEmployeeId, runId, limit);
    }

    public void deleteRun(long runId) {
        jdbc.update("DELETE FROM snapshot_row WHERE run_id = ?", runId);
    }

    // ---- The complete per-row error report (kept after the run, unlike staged rows) ----

    /** One rejected file row: its number, its ID if it had a valid one, and the error codes as JSON. */
    public record RejectionRow(long row, String employeeId, String errorsJson) {
    }

    public void insertRejections(long runId, List<RejectionRow> rows) {
        jdbc.batchUpdate("INSERT INTO reconciliation_rejection (run_id, row_number, employee_id, errors) "
                + "VALUES (?, ?, ?, ?::jsonb)", rows, rows.size(), (ps, row) -> {
                    ps.setLong(1, runId);
                    ps.setLong(2, row.row());
                    ps.setString(3, row.employeeId());
                    ps.setString(4, row.errorsJson());
                });
    }

    /** The next page of rejections after a row number ("keyset" paging, so deep pages are as fast as the first). */
    public List<RejectionRow> rejectionsPage(long runId, long afterRow, int limit) {
        return jdbc.query("""
                SELECT row_number, employee_id, errors::text AS errors FROM reconciliation_rejection
                WHERE run_id = ? AND row_number > ?
                ORDER BY row_number
                LIMIT ?
                """, (rs, i) -> new RejectionRow(rs.getLong("row_number"), rs.getString("employee_id"),
                        rs.getString("errors")),
                runId, afterRow, limit);
    }
}
