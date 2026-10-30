package com.moveinsync.roster.service.reconciliation;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import com.moveinsync.roster.core.RecordValidator;
import com.moveinsync.roster.core.RecordValidator.FieldError;
import com.moveinsync.roster.core.RowHasher;
import com.moveinsync.roster.entity.Employee.HrProfile;
import com.moveinsync.roster.repository.OfficeRepository;
import com.moveinsync.roster.repository.SnapshotRepository;
import com.moveinsync.roster.repository.SnapshotRepository.RejectionRow;
import com.moveinsync.roster.repository.SnapshotRepository.StagedRow;

import tools.jackson.databind.ObjectMapper;

/**
 * Streams a nightly CSV into the staging table: read one row, validate it, hash it, and write rows in batches of
 * 1,000. Memory use is one batch, whether the file has 2,000 rows or 2,000,000.
 */
@Component
public class SnapshotStager {

    static final List<String> HEADER =
            List.of("employee_id", "version", "name", "email", "phone", "manager_id", "office_code", "shift");
    static final int BATCH_SIZE = 1000;
    static final int MAX_REPORTED_REJECTIONS = 100;

    /** A rejected file row. IDs and error codes only, never values. */
    public record Rejection(long row, String employeeId, List<FieldError> errors) {
    }

    /** @param rejections a preview: the first 100. The complete list is in reconciliation_rejection. */
    public record StageResult(String sha256, int rowsTotal, int rowsRejected, List<Rejection> rejections) {
    }

    /** The file as a whole is unusable (e.g. wrong header). */
    public static class InvalidFileException extends RuntimeException {
        public InvalidFileException(String code) {
            super(code);
        }
    }

    private final SnapshotRepository snapshots;
    private final OfficeRepository offices;
    private final ObjectMapper json;

    public SnapshotStager(SnapshotRepository snapshots, OfficeRepository offices, ObjectMapper json) {
        this.snapshots = snapshots;
        this.offices = offices;
        this.json = json;
    }

    public StageResult stage(String tenantId, long runId, InputStream file) {
        RecordValidator validator = new RecordValidator(offices.findCodes(tenantId)::contains);
        MessageDigest sha256 = newSha256();
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
        int total = 0;
        int rejected = 0;
        List<Rejection> preview = new ArrayList<>();           // first 100, returned inline for a quick look
        List<RejectionRow> rejectionBatch = new ArrayList<>(); // every rejection, written 1,000 at a time
        // Keyed by employee ID so a duplicate inside one batch keeps only the higher version.
        Map<String, StagedRow> batch = new LinkedHashMap<>();

        try (Reader reader = new InputStreamReader(new DigestInputStream(file, sha256), StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.builder().setReader(reader).setFormat(format).get()) {
            if (!HEADER.equals(parser.getHeaderNames())) {
                throw new InvalidFileException("INVALID_HEADER");
            }
            for (CSVRecord record : parser) {
                total++;
                String employeeId = record.get(0);
                long version = record.isConsistent() ? parseVersion(record.get("version")) : 0;
                HrProfile hr = record.isConsistent() ? toProfile(record) : null;
                List<FieldError> errors = hr == null
                        ? List.of(new FieldError("row", "MALFORMED_ROW"))
                        : validator.validate(employeeId, version, hr);

                StagedRow row;
                if (errors.isEmpty()) {
                    row = new StagedRow(employeeId, version, RowHasher.hash(hr), json.writeValueAsString(hr), true);
                } else {
                    rejected++;
                    Rejection rejection = new Rejection(record.getRecordNumber(), RecordValidator.safeId(employeeId), errors);
                    if (preview.size() < MAX_REPORTED_REJECTIONS) {
                        preview.add(rejection);
                    }
                    rejectionBatch.add(new RejectionRow(rejection.row(), rejection.employeeId(),
                            json.writeValueAsString(rejection.errors())));
                    if (rejectionBatch.size() >= BATCH_SIZE) {
                        flushRejections(runId, rejectionBatch);
                    }
                    // Still staged (but never applied) so this employee isn't mistaken for "missing".
                    row = RecordValidator.isValidEmployeeId(employeeId)
                            ? new StagedRow(employeeId, version, "", "{}", false) : null;
                }
                if (row != null) {
                    batch.merge(row.employeeId(), row, (a, b) -> b.version() > a.version() ? b : a);
                }
                if (batch.size() >= BATCH_SIZE) {
                    flush(runId, batch);
                }
            }
            flush(runId, batch);
            flushRejections(runId, rejectionBatch);
        } catch (IOException | UncheckedIOException e) {
            throw new InvalidFileException("UNREADABLE_FILE");
        }
        snapshots.analyze();
        return new StageResult(HexFormat.of().formatHex(sha256.digest()), total, rejected, preview);
    }

    private void flushRejections(long runId, List<RejectionRow> rows) {
        if (!rows.isEmpty()) {
            snapshots.insertRejections(runId, new ArrayList<>(rows));
            rows.clear();
        }
    }

    private void flush(long runId, Map<String, StagedRow> batch) {
        if (!batch.isEmpty()) {
            snapshots.insertBatch(runId, new ArrayList<>(batch.values()));
            batch.clear();
        }
    }

    private static HrProfile toProfile(CSVRecord r) {
        String managerId = r.get("manager_id");
        return new HrProfile(r.get("name"), r.get("email"), r.get("phone"),
                managerId.isBlank() ? null : managerId, r.get("office_code"), r.get("shift"));
    }

    /** Unparseable versions become 0, which the validator reports as INVALID_VERSION. */
    private static long parseVersion(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }
}
