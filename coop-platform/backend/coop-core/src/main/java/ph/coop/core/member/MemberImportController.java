package ph.coop.core.member;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;

/**
 * Advanced mode: registering many members from a CSV or Excel list. An administrator uploads the list. It is checked row by row with the same rules as a single registration (required
 * fields, age, coded values, no same name and birth date already on file, in the file or waiting); every valid row becomes a pending registration request belonging to one batch. A
 * Membership Officer other than the uploader then approves the batch, which opens the members one at a time exactly as a single approval does and reports what succeeded; or rejects it.
 * Photos and fingerprints are not part of a list: they are added afterwards on each member.
 */
@RestController
@RequestMapping("/api/member-imports")
public class MemberImportController {

    public record RowResult(int line, String name, boolean ok, List<String> problems) {}

    public record Preview(String fileName, int total, int ok, int errors, List<String> ignoredColumns, List<RowResult> rows) {}

    public record Batch(long id, int branchCode, String fileName, int totalRows, int okRows, String uploadedBy, String uploadedAt, String status,
                        String decidedBy, String decidedAt, String decisionNote, Integer openedCount, Integer failedCount) {}

    public record Decision(String note) {}

    /** A checked row: the cleaned fields, the member type, and what is wrong with it. */
    private record Checked(int line, String name, int clientType, Map<String, String> fields, List<String> problems) {}

    private static final String SELECT = "SELECT id, branch_code, file_name, total_rows, ok_rows, uploaded_by, uploaded_at, status, decided_by, decided_at, decision_note, opened_count, failed_count FROM ecis_member_import";

    private final JdbcTemplate jdbc;
    private final Db db;
    private final LookupController lookups;
    private final MemberLifecycleController lifecycle;
    private final ObjectMapper json;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    MemberImportController(JdbcTemplate jdbc, Db db, LookupController lookups, MemberLifecycleController lifecycle, ObjectMapper json,
                           org.springframework.transaction.PlatformTransactionManager txManager) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.jdbc = jdbc;
        this.db = db;
        this.lookups = lookups;
        this.lifecycle = lifecycle;
        this.json = json;
    }

    private static boolean has(Authentication auth, String authority) {
        return auth.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals(authority));
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    private void requireInstalled() {
        if (!db.allTablesExist("ecis_member_import", "ecis_member_request", "ecis_audit_log") || !db.columnExists("ecis_member_request", "batch_id")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Importing a list is not installed on this server (V043).");
        }
    }

    // ---- the file -----------------------------------------------------------------------------------------------

    /** A blank list to fill in as an Excel workbook: a Members sheet (column names and one example row) and a Guide sheet. */
    @GetMapping(value = "/template", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> template() {
        byte[] book = TemplateWorkbook.build(lookups.all().getOrDefault("gender", Map.of()), lookups.all().getOrDefault("civilStatus", Map.of()));
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"members-template.xlsx\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(book);
    }

    /** The same blank list as plain CSV. */
    @GetMapping(value = "/template.csv", produces = "text/csv")
    public ResponseEntity<String> templateCsv() {
        String csv = String.join(",", TemplateWorkbook.COLUMNS) + "\r\nReyes,Ana,Santos,,1990-02-03,Female,Single,Regular,09171234567,ana@example.com,12 Rizal St,Poblacion,Baguio City,Benguet,2600,Teacher,,\r\n";
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"members-template.csv\"").contentType(MediaType.parseMediaType("text/csv")).body(csv);
    }

    /** Checks the list and reports every row, without saving anything. */
    @PostMapping("/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public Preview preview(@RequestParam("file") MultipartFile file, @RequestParam(defaultValue = "1") int branchCode) throws IOException {
        requireInstalled();
        return check(file, branchCode).preview();
    }

    /** Saves the list as one batch of pending registrations waiting for a Membership Officer. Rows with problems are left out when {@code skipErrors} is true; otherwise any problem stops it. */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Batch upload(@RequestParam("file") MultipartFile file, @RequestParam(defaultValue = "1") int branchCode, @RequestParam(defaultValue = "false") boolean skipErrors,
                        Authentication auth) throws IOException {
        requireInstalled();
        Checks c = check(file, branchCode);
        if (c.preview().errors() > 0 && !skipErrors) throw new ResponseStatusException(HttpStatus.CONFLICT, c.preview().errors() + " row(s) have problems. Fix them, or choose to leave those rows out.");
        List<Checked> good = c.rows().stream().filter((r) -> r.problems().isEmpty()).toList();
        if (good.isEmpty()) throw bad("There is no valid row to register.");
        return saveBatch(branchCode, c.preview().fileName(), c.preview().total(), good, auth.getName());
    }

    private Batch saveBatch(int branch, String fileName, int total, List<Checked> good, String user) {
        return tx.execute((status) -> store(branch, fileName, total, good, user));
    }

    private Batch store(int branch, String fileName, int total, List<Checked> good, String user) {
        long batchId = db.insertReturningId("INSERT INTO ecis_member_import (branch_code, file_name, total_rows, ok_rows, uploaded_by, uploaded_at, status) VALUES (?, ?, ?, ?, ?, NOW(), 'PENDING')", "id",
            branch, fileName, total, good.size(), user);
        for (Checked r : good) {
            Map<String, String> payload = new LinkedHashMap<>(r.fields());
            payload.put("_clientType", Integer.toString(r.clientType()));
            jdbc.update("INSERT INTO ecis_member_request (branch_code, kind, client_id, summary, payload, status, requested_by, requested_at, batch_id) VALUES (?, 'REGISTER', NULL, ?, ?, 'PENDING', ?, NOW(), ?)",
                branch, r.name() + " (" + LifecycleRules.TYPES.get(r.clientType()) + ")", write(payload), user, batchId);
        }
        audit(user, "member.import.uploaded", String.valueOf(batchId), null, good.size() + " of " + total + " rows from " + fileName, "Batch #" + batchId);
        return load(batchId);
    }

    // ---- the batch ----------------------------------------------------------------------------------------------

    @GetMapping
    public List<Batch> list(@RequestParam(defaultValue = "PENDING") String status, Authentication auth) {
        boolean officer = has(auth, "ROLE_MEMBERSHIP_OFFICER");
        String where = " WHERE status = ?" + (officer ? "" : " AND uploaded_by = ?");
        Object[] args = officer ? new Object[] {status} : new Object[] {status, auth.getName()};
        return jdbc.query(SELECT + where + " ORDER BY uploaded_at DESC LIMIT 100", (rs, i) -> map(rs), args);
    }

    /** Opens every pending member of the batch, one at a time like a single approval. Rows that cannot be opened (for example a duplicate that appeared since) are left as ordinary pending requests. */
    @PostMapping("/{id}/approve")
    public Batch approve(@PathVariable long id, Authentication auth) {
        requireInstalled();
        Batch b = pendingBatch(id);
        if (!has(auth, "ROLE_MEMBERSHIP_OFFICER")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a Membership Officer can approve a list.");
        if (b.uploadedBy().equalsIgnoreCase(auth.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot approve your own list. Another Membership Officer must approve it.");
        List<Long> ids = jdbc.queryForList("SELECT id FROM ecis_member_request WHERE batch_id = ? AND status = 'PENDING' ORDER BY id", Long.class, id);
        int opened = 0, failed = 0;
        List<String> why = new ArrayList<>();
        for (Long rid : ids) {
            try {
                lifecycle.approve(rid, null, auth);
                opened++;
            } catch (RuntimeException e) {
                failed++;
                if (why.size() < 5) why.add("#" + rid + ": " + (e instanceof ResponseStatusException r ? r.getReason() : e.getMessage()));
                jdbc.update("UPDATE ecis_member_request SET batch_id = NULL WHERE id = ?", rid); // left for a person to look at
            }
        }
        jdbc.update("UPDATE ecis_member_import SET status = 'APPROVED', decided_by = ?, decided_at = NOW(), opened_count = ?, failed_count = ?, decision_note = ? WHERE id = ?",
            auth.getName(), opened, failed, why.isEmpty() ? null : String.join(" | ", why).substring(0, Math.min(250, String.join(" | ", why).length())), id);
        audit(auth.getName(), "member.import.approved", String.valueOf(id), null, opened + " opened, " + failed + " left pending", "Batch #" + id + " uploaded by " + b.uploadedBy());
        return load(id);
    }

    @PostMapping("/{id}/reject")
    public Batch reject(@PathVariable long id, @RequestBody Decision d, Authentication auth) {
        requireInstalled();
        Batch b = pendingBatch(id);
        if (!has(auth, "ROLE_MEMBERSHIP_OFFICER")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a Membership Officer can reject a list.");
        if (d == null || d.note() == null || d.note().trim().length() < 3) throw bad("Give a reason for rejecting the list.");
        for (Long rid : jdbc.queryForList("SELECT id FROM ecis_member_request WHERE batch_id = ? AND status = 'PENDING'", Long.class, id)) {
            lifecycle.reject(rid, new MemberLifecycleController.Decision(d.note().trim()), auth);
        }
        jdbc.update("UPDATE ecis_member_import SET status = 'REJECTED', decided_by = ?, decided_at = NOW(), decision_note = ? WHERE id = ?", auth.getName(), d.note().trim(), id);
        audit(auth.getName(), "member.import.rejected", String.valueOf(id), null, null, "Batch #" + id + " uploaded by " + b.uploadedBy() + ". " + d.note().trim());
        return load(id);
    }

    @PostMapping("/{id}/withdraw")
    public Batch withdraw(@PathVariable long id, Authentication auth) {
        requireInstalled();
        Batch b = pendingBatch(id);
        if (!b.uploadedBy().equalsIgnoreCase(auth.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the person who uploaded the list can withdraw it.");
        for (Long rid : jdbc.queryForList("SELECT id FROM ecis_member_request WHERE batch_id = ? AND status = 'PENDING'", Long.class, id)) lifecycle.withdraw(rid, auth);
        jdbc.update("UPDATE ecis_member_import SET status = 'WITHDRAWN', decided_by = ?, decided_at = NOW() WHERE id = ?", auth.getName(), id);
        audit(auth.getName(), "member.import.withdrawn", String.valueOf(id), null, null, "Batch #" + id);
        return load(id);
    }

    // ---- checking rows ------------------------------------------------------------------------------------------

    private record Checks(Preview preview, List<Checked> rows) {}

    private Checks check(MultipartFile file, int branch) throws IOException {
        List<SpreadsheetReader.Row> rows;
        try {
            rows = SpreadsheetReader.read(file.getOriginalFilename(), file.getBytes());
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        if (rows.isEmpty()) throw bad("The file has a header but no members.");

        Map<String, ClientDetailController.Spec> byName = new LinkedHashMap<>();
        ClientDetailController.EDITABLE.forEach((col, spec) -> byName.put(col.toLowerCase(Locale.ROOT), spec));
        Map<String, Integer> lengths = db.textColumnLengths("client");
        Set<String> ignored = new HashSet<>();
        Set<String> seen = new HashSet<>();
        Set<String> onFile = existingKeys();
        List<Checked> checked = new ArrayList<>();
        List<RowResult> results = new ArrayList<>();

        for (SpreadsheetReader.Row row : rows) {
            List<String> problems = new ArrayList<>();
            Map<String, String> clean = new LinkedHashMap<>();
            int type = 1;
            for (Map.Entry<String, String> cell : row.values().entrySet()) {
                String header = cell.getKey().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
                if (header.equals("membertype")) {
                    Integer t = memberType(cell.getValue());
                    if (t == null) problems.add("Member type must be Regular or Associate (or 1 or 2).");
                    else type = t;
                    continue;
                }
                ClientDetailController.Spec spec = byName.get(header);
                if (spec == null || spec.column().equals("ClientType") || spec.column().equals("AccountType")) { ignored.add(cell.getKey()); continue; }
                String raw = cell.getValue();
                if ("date".equals(spec.kind())) { String d = SpreadsheetReader.excelDate(raw); if (d != null) raw = d; }
                Map<Integer, String> lookup = spec.how() == null ? null : lookups.all().get(spec.how());
                if (lookup != null) raw = labelToCode(lookup, raw);
                try {
                    String v = EditRules.normalize(spec.label(), spec.kind(), lookup, lengths.get(spec.column()), raw);
                    if (v != null) clean.put(spec.column(), v);
                } catch (IllegalArgumentException ex) {
                    problems.add(ex.getMessage());
                }
            }
            String name = (clean.getOrDefault("LName", "") + ", " + clean.getOrDefault("FName", "")).trim();
            if (problems.isEmpty()) {
                problems.addAll(LifecycleRules.registrationProblems(type, clean, LocalDate.now()));
                if (problems.isEmpty()) {
                    String key = LifecycleRules.duplicateKey(clean);
                    if (onFile.contains(key)) problems.add("A member with the same name and birth date is already on file or waiting for approval.");
                    else if (!seen.add(key)) problems.add("The same person appears earlier in this file.");
                }
            }
            checked.add(new Checked(row.line(), name.equals(",") ? "(no name)" : name, type, clean, problems));
            results.add(new RowResult(row.line(), name.equals(",") ? "(no name)" : name, problems.isEmpty(), problems));
        }
        int ok = (int) results.stream().filter(RowResult::ok).count();
        return new Checks(new Preview(file.getOriginalFilename(), results.size(), ok, results.size() - ok, ignored.stream().sorted().toList(), results), checked);
    }

    /** Name and birth date keys of members already on file and of registrations waiting for approval. */
    private Set<String> existingKeys() {
        Set<String> keys = new HashSet<>();
        jdbc.query("SELECT LName, FName, DateOfBirth FROM client WHERE DateOfBirth IS NOT NULL AND LName IS NOT NULL", (rs) -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("LName", rs.getString(1));
            m.put("FName", rs.getString(2));
            m.put("DateOfBirth", String.valueOf(rs.getString(3)).substring(0, Math.min(10, String.valueOf(rs.getString(3)).length())));
            keys.add(LifecycleRules.duplicateKey(m));
        });
        jdbc.query("SELECT payload FROM ecis_member_request WHERE kind = 'REGISTER' AND status = 'PENDING'", (rs) -> {
            try {
                @SuppressWarnings("unchecked") Map<String, String> m = json.readValue(rs.getString(1), Map.class);
                keys.add(LifecycleRules.duplicateKey(m));
            } catch (JsonProcessingException ignored) { /* a payload that cannot be read cannot clash */ }
        });
        return keys;
    }

    private static Integer memberType(String v) {
        String t = v.trim().toLowerCase(Locale.ROOT);
        if (t.equals("1") || t.startsWith("regular")) return 1;
        if (t.equals("2") || t.startsWith("associate")) return 2;
        return null;
    }

    /** A list can say "Female" or "Single"; the database stores the code. A value that is already a code, or unknown, is passed on to be checked. */
    private static String labelToCode(Map<Integer, String> lookup, String raw) {
        String t = raw.trim();
        for (Map.Entry<Integer, String> e : lookup.entrySet()) if (e.getValue() != null && e.getValue().trim().equalsIgnoreCase(t)) return Integer.toString(e.getKey());
        return raw;
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private Batch pendingBatch(long id) {
        List<String> status = jdbc.query("SELECT status FROM ecis_member_import WHERE id = ? FOR UPDATE", (rs, i) -> rs.getString(1), id);
        if (status.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "List not found.");
        if (!"PENDING".equals(status.get(0))) throw new ResponseStatusException(HttpStatus.CONFLICT, "This list has already been " + status.get(0).toLowerCase(Locale.ROOT) + ".");
        return load(id);
    }

    private Batch load(long id) {
        List<Batch> found = jdbc.query(SELECT + " WHERE id = ?", (rs, i) -> map(rs), id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "List not found.");
        return found.get(0);
    }

    private Batch map(java.sql.ResultSet rs) throws java.sql.SQLException {
        int opened = rs.getInt(12);
        Integer o = rs.wasNull() ? null : opened;
        int failed = rs.getInt(13);
        Integer f = rs.wasNull() ? null : failed;
        return new Batch(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), o, f);
    }

    private String write(Map<String, String> m) {
        try {
            return json.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void audit(String user, String action, String entityId, String prev, String next, String reason) {
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, prev_value, new_value, reason) VALUES (NOW(), ?, ?, 'member_import', ?, ?, ?, ?)", user, action, entityId, prev, next, reason);
    }
}
