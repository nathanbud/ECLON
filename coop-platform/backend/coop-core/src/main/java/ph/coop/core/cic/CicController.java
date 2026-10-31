package ph.coop.core.cic;

import static ph.coop.core.web.ApiErrors.bad;
import static ph.coop.core.web.ApiErrors.notFound;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.db.Db;

/**
 * CIC submission (guide phase 11), first stage: a batch holds the header record (HD) the CIC utility asks for, and the status log of what was done for it. Generating the members' and
 * loans' data, the .txt and .zip files, encryption, the Excel copy, the transmittal letter and sending answer 501 until the CIC file layout is available: it is in neither the guide nor
 * the repository, and the fields are not guessed. Administrators and accountants only; every batch is audited.
 */
@RestController
@RequestMapping("/api/cic")
@PreAuthorize("hasAnyRole('ADMIN', 'ACCOUNTANT')")
public class CicController {

    /** Index = the code written in the header's Submission Type column (0 Standard, 1 Correction, 2 Full resubmission). To confirm against the CIC layout. */
    static final List<String> SUBMISSION_TYPES = List.of("Standard", "Correction", "Full resubmission");

    public record NewBatch(String submissionType, String cutOffDate, String providerCode, String version, String remarks) {}

    private final JdbcTemplate jdbc;
    private final Db db;

    CicController(JdbcTemplate jdbc, Db db) {
        this.jdbc = jdbc;
        this.db = db;
    }

    @GetMapping("/batches")
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT * FROM ecis_cic_batch ORDER BY id DESC LIMIT 200").stream().map(CicController::plain).toList();
    }

    /** One batch with its header row as the screen shows it and the status log per tab. */
    @GetMapping("/batches/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> b = one(id);
        Map<String, Object> out = new LinkedHashMap<>(b);
        out.put("header", header(b));
        Map<String, List<String>> logs = new LinkedHashMap<>();
        for (String kind : List.of("MEMBERS", "LOANS", "FILES")) logs.put(kind, List.of());
        for (Map<String, Object> r : jdbc.queryForList("SELECT kind, at, line FROM ecis_cic_log WHERE batch_id = ? ORDER BY id", id)) {
            logs.merge(String.valueOf(r.get("kind")), List.of(String.valueOf(r.get("line"))), (a, c) -> java.util.stream.Stream.concat(a.stream(), c.stream()).toList());
        }
        out.put("logs", logs);
        return out;
    }

    /** Generate Header Info: records the batch and its HD row. */
    @PostMapping("/batches")
    @Transactional
    public Map<String, Object> create(@RequestBody NewBatch in, Authentication auth) {
        int type = SUBMISSION_TYPES.indexOf(in.submissionType() == null ? "" : in.submissionType().trim());
        if (type < 0) throw bad("Choose the submission type: " + String.join(", ", SUBMISSION_TYPES) + ".");
        String provider = in.providerCode() == null ? "" : in.providerCode().trim().toUpperCase();
        if (!provider.matches("[A-Z0-9]{3,20}")) throw bad("Give the provider code the CIC assigned to the cooperative (letters and digits).");
        String version = in.version() == null ? "" : in.version().trim();
        if (!version.matches("\\d+(\\.\\d+)*")) throw bad("The version must look like 1.0.");
        LocalDate cutOff;
        try {
            cutOff = in.cutOffDate() == null || in.cutOffDate().isBlank() ? LocalDate.now() : LocalDate.parse(in.cutOffDate().trim());
        } catch (DateTimeParseException e) {
            throw bad("The cut-off date must look like 2026-10-31.");
        }
        if (cutOff.isAfter(LocalDate.now())) throw bad("The cut-off date cannot be in the future.");
        String remarks = in.remarks() == null || in.remarks().isBlank() ? null : in.remarks().trim();
        if (remarks != null && remarks.length() > 255) throw bad("Remarks can be at most 255 characters.");
        long id = db.insertReturningId("INSERT INTO ecis_cic_batch (submission_type, cut_off_date, provider_code, version, remarks, status, created_by, created_at) VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, ?)", "id",
            type, java.sql.Date.valueOf(cutOff), provider, version, remarks, auth.getName(), Timestamp.valueOf(LocalDateTime.now()));
        String ref = "CIC-" + LocalDate.now().getYear() + "-" + String.format("%04d", id);
        jdbc.update("UPDATE ecis_cic_batch SET ref = ? WHERE id = ?", ref, id);
        jdbc.update("INSERT INTO ecis_cic_log (batch_id, kind, at, line) VALUES (?, 'FILES', ?, ?)", id, Timestamp.valueOf(LocalDateTime.now()), "Header created for cut-off " + cutOff + ".");
        AuditLog.write(jdbc, auth.getName(), "cic.batch.created", "cic_batch", ref, null, SUBMISSION_TYPES.get(type) + ", cut-off " + cutOff + ", provider " + provider, remarks);
        return get(id);
    }

    // ---- not built: waiting on the CIC file layout -------------------------------------------------------------------

    @PostMapping("/batches/{id}/members") public Map<String, Object> members(@PathVariable long id) { return notBuilt(id, "Members' data"); }

    @PostMapping("/batches/{id}/loans") public Map<String, Object> loans(@PathVariable long id) { return notBuilt(id, "Loans' data"); }

    @PostMapping("/batches/{id}/files") public Map<String, Object> files(@PathVariable long id) { return notBuilt(id, "The .txt and .zip files"); }

    @PostMapping("/batches/{id}/encrypt") public Map<String, Object> encrypt(@PathVariable long id) { return notBuilt(id, "Encryption"); }

    @PostMapping("/batches/{id}/excel") public Map<String, Object> excel(@PathVariable long id) { return notBuilt(id, "The Excel copy"); }

    @PostMapping("/batches/{id}/transmittal") public Map<String, Object> transmittal(@PathVariable long id) { return notBuilt(id, "The transmittal letter"); }

    @PostMapping("/batches/{id}/submit") public Map<String, Object> submit(@PathVariable long id) { return notBuilt(id, "Sending to the CIC"); }

    @PostMapping("/batches/{id}/outstanding-balance") public Map<String, Object> outstandingBalance(@PathVariable long id) { return notBuilt(id, "The outstanding balance computation"); }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private Map<String, Object> notBuilt(long id, String what) {
        one(id);
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, what + " is not built yet: it needs the CIC file layout.");
    }

    private Map<String, Object> one(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM ecis_cic_batch WHERE id = ?", id);
        if (rows.isEmpty()) throw notFound("Submission batch not found.");
        return plain(rows.get(0));
    }

    /** The HD row: record type, provider code, file reference date (DDMMYYYY), version, submission type code and the provider's comments. */
    private static Map<String, Object> header(Map<String, Object> b) {
        LocalDate d = LocalDate.parse(String.valueOf(b.get("cut_off_date")));
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("recordType", "HD");
        h.put("providerCode", b.get("provider_code"));
        h.put("fileReferenceDate", String.format("%02d%02d%04d", d.getDayOfMonth(), d.getMonthValue(), d.getYear()));
        h.put("version", b.get("version"));
        h.put("submissionType", String.valueOf(b.get("submission_type")));
        h.put("providerComments", b.get("remarks") == null ? "" : b.get("remarks"));
        return h;
    }

    private static Map<String, Object> plain(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(k, v instanceof java.sql.Timestamp t ? t.toLocalDateTime().toString() : v instanceof java.util.Date d ? d.toString() : v));
        return out;
    }
}
