package ph.coop.core.cic;

import static ph.coop.core.web.ApiErrors.bad;
import static ph.coop.core.web.ApiErrors.conflict;
import static ph.coop.core.web.ApiErrors.forbidden;
import static ph.coop.core.web.ApiErrors.notFound;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.db.Db;

/**
 * CIC submission (guide phase 11). A batch is made with its header, GENERATED from the books as at its cut-off date (borrowers as ID records, loans as CI or CN records, mapping
 * {@link Csdf1}), VALIDATED (VALID, WARNING or ERROR per record), APPROVED by someone other than its maker, SUBMITTED (the file handed over, with the CIC's reference), given the CIC's
 * RESULT and RECONCILED; a correction batch can follow. The file is only released as final once approved, and approval needs the mapping confirmed against a file the CIC accepted.
 * Encryption, the Excel copy and the transmittal letter answer 501: their layouts are not known. Administrators and accountants only; every step is audited.
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
    private final CicSource source;
    private final boolean mappingConfirmed;

    CicController(JdbcTemplate jdbc, Db db, CicSource source, @Value("${coop.cic.mapping-confirmed:false}") boolean mappingConfirmed) {
        this.jdbc = jdbc;
        this.db = db;
        this.source = source;
        this.mappingConfirmed = mappingConfirmed;
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
        out.put("mappingVersion", Csdf1.VERSION);
        out.put("mappingConfirmed", mappingConfirmed);
        out.put("unverified", Csdf1.UNVERIFIED);
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

    // ---- the workflow: generate, validate, approve, file, submit, result, reconcile, correction -----------------------------

    private static final String TYPE_ORDER = "CASE r.record_type WHEN 'ID' THEN 1 WHEN 'CI' THEN 2 ELSE 3 END";

    private static final List<String> ORDER = List.of("DRAFT", "GENERATED", "VALIDATED", "APPROVED", "SUBMITTED", "RESULT", "RECONCILED");

    public record Approval(boolean excludeErrors, String note) {}

    public record Submission(String reference) {}

    public record Result(int accepted, int rejected, String note) {}

    private static String status(Map<String, Object> b) {
        return String.valueOf(b.get("status"));
    }

    private void require(Map<String, Object> b, String what, String... allowed) {
        if (!List.of(allowed).contains(status(b))) throw conflict(what + " is not possible while the batch is " + status(b) + ".");
    }

    private void log(long id, String kind, String line) {
        jdbc.update("INSERT INTO ecis_cic_log (batch_id, kind, at, line) VALUES (?, ?, ?, ?)", id, kind, Timestamp.valueOf(LocalDateTime.now()), line);
    }

    private void move(long id, String status) {
        jdbc.update("UPDATE ecis_cic_batch SET status = ? WHERE id = ?", status, id);
    }

    /**
     * Builds the batch's records from the books as at its cut-off date (the borrowers as ID records, their loans as CI or CN records) and checks them. A batch can be generated
     * again until it is approved; the earlier records and findings are replaced. {@code sample} (0 for all) builds only that many loans, for a trial run.
     */
    @PostMapping("/batches/{id}/generate")
    @Transactional
    public Map<String, Object> generate(@PathVariable long id, @RequestParam(defaultValue = "0") int sample, Authentication auth) {
        Map<String, Object> b = one(id);
        require(b, "Generating", "DRAFT", "GENERATED", "VALIDATED");
        if (sample < 0 || sample > 100000) throw bad("The sample must be between 0 and 100000 loans.");
        LocalDate cutOff = LocalDate.parse(String.valueOf(b.get("cut_off_date")));
        String provider = String.valueOf(b.get("provider_code"));
        LocalDate fileDate = cutOff; // the submitted files carry the month-end cut-off as their file date
        List<Csdf1.Loan> loans = source.loans(cutOff, sample);
        List<Csdf1.Subject> subjects = source.subjects(loans);
        jdbc.update("DELETE FROM ecis_cic_validation WHERE batch_id = ?", id);
        jdbc.update("DELETE FROM ecis_cic_record WHERE batch_id = ?", id);
        List<Object[]> rows = new ArrayList<>();
        java.util.Set<String> known = new java.util.HashSet<>();
        int seq = 0;
        for (Csdf1.Subject s : subjects) {
            Csdf.Rec r = Csdf1.individual(s, provider, fileDate);
            known.add(r.get("E"));
            rows.add(new Object[] {id, ++seq, "ID", r.get("E"), null, r.line()});
        }
        int ids = seq, ci = 0, cn = 0, orphans = 0;
        seq = 0;
        for (Csdf1.Loan l : loans) {
            Csdf.Rec r = Csdf1.contract(l, provider, fileDate);
            if (!known.contains(r.get("E"))) orphans++;
            if (l.lumpSum()) cn++; else ci++;
            rows.add(new Object[] {id, ++seq, r.type, r.get("E"), r.get("G"), r.line()});
        }
        jdbc.batchUpdate("INSERT INTO ecis_cic_record (batch_id, seq, record_type, subject_key, contract_no, line) VALUES (?, ?, ?, ?, ?, ?)", rows);
        jdbc.update("UPDATE ecis_cic_batch SET subjects = ?, installment_contracts = ?, other_contracts = ?, generated_at = ?, status = 'GENERATED', errors = 0, warnings = 0, excluded = 0 WHERE id = ?",
            ids, ci, cn, Timestamp.valueOf(LocalDateTime.now()), id);
        String ref = String.valueOf(b.get("ref"));
        log(id, "MEMBERS", ids + " borrowers read from the member records.");
        log(id, "LOANS", ci + " installment and " + cn + " lump-sum loans with a balance on " + cutOff + (sample > 0 ? " (sample of " + sample + ")" : "") + ".");
        AuditLog.write(jdbc, auth.getName(), "cic.batch.generated", "cic_batch", ref, null, ids + " ID, " + ci + " CI, " + cn + " CN records, cut-off " + cutOff, sample > 0 ? "sample " + sample : null);
        if (orphans > 0) log(id, "LOANS", orphans + " loans have no member record.");
        validateBatch(id, auth.getName(), cutOff, provider, orphans);
        return get(id);
    }

    @PostMapping("/batches/{id}/validate")
    @Transactional
    public Map<String, Object> validate(@PathVariable long id, Authentication auth) {
        Map<String, Object> b = one(id);
        require(b, "Validating", "GENERATED", "VALIDATED");
        validateBatch(id, auth.getName(), LocalDate.parse(String.valueOf(b.get("cut_off_date"))), String.valueOf(b.get("provider_code")), 0);
        return get(id);
    }

    private void validateBatch(long id, String user, LocalDate cutOff, String provider, int orphans) {
        jdbc.update("DELETE FROM ecis_cic_validation WHERE batch_id = ?", id);
        List<Object[]> findings = new ArrayList<>();
        List<Object[]> statuses = new ArrayList<>();
        int errors = 0, warnings = 0;
        for (Map<String, Object> r : jdbc.queryForList("SELECT id, line FROM ecis_cic_record WHERE batch_id = ?", id)) {
            long rid = ((Number) r.get("id")).longValue();
            List<CicValidator.Finding> f = CicValidator.validate(Csdf.parse(String.valueOf(r.get("line"))), provider, cutOff);
            String worst = CicValidator.worst(f);
            if (CicValidator.ERROR.equals(worst)) errors++;
            else if (CicValidator.WARNING.equals(worst)) warnings++;
            statuses.add(new Object[] {worst, rid});
            for (CicValidator.Finding x : f) findings.add(new Object[] {id, rid, x.severity(), x.field(), x.message()});
        }
        jdbc.batchUpdate("UPDATE ecis_cic_record SET status = ? WHERE id = ?", statuses);
        if (orphans > 0) findings.add(new Object[] {id, null, "ERROR", "Borrowers", orphans + " loans belong to a member who has no member record."});
        for (String u : Csdf1.UNVERIFIED) findings.add(new Object[] {id, null, "WARNING", "Mapping " + Csdf1.VERSION, u});
        jdbc.batchUpdate("INSERT INTO ecis_cic_validation (batch_id, record_id, severity, field, message) VALUES (?, ?, ?, ?, ?)", findings);
        jdbc.update("UPDATE ecis_cic_batch SET errors = ?, warnings = ?, validated_at = ?, status = 'VALIDATED' WHERE id = ?", errors + (orphans > 0 ? 1 : 0), warnings, Timestamp.valueOf(LocalDateTime.now()), id);
        log(id, "FILES", "Validated: " + errors + " records with errors, " + warnings + " with warnings.");
        AuditLog.write(jdbc, user, "cic.batch.validated", "cic_batch", String.valueOf(jdbc.queryForObject("SELECT ref FROM ecis_cic_batch WHERE id = ?", String.class, id)), null, errors + " errors, " + warnings + " warnings", null);
    }

    @GetMapping("/batches/{id}/records")
    public Map<String, Object> records(@PathVariable long id, @RequestParam(required = false) String type, @RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        one(id);
        size = Math.max(1, Math.min(size, 200));
        StringBuilder where = new StringBuilder("WHERE r.batch_id = ?");
        List<Object> args = new ArrayList<>(List.of(id));
        if (type != null && !type.isBlank()) { where.append(" AND r.record_type = ?"); args.add(type); }
        if (status != null && !status.isBlank()) { where.append(" AND r.status = ?"); args.add(status); }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_cic_record r " + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(Math.max(0, page) * size);
        List<Map<String, Object>> items = jdbc.queryForList("SELECT r.id, r.record_type, r.seq, r.subject_key, r.contract_no, r.status, r.excluded FROM ecis_cic_record r " + where + " ORDER BY " + TYPE_ORDER + ", r.seq LIMIT ? OFFSET ?", pageArgs.toArray());
        return Map.of("total", total, "items", items);
    }

    /** What validation found, worst first; {@code record} limits it to one record. */
    @GetMapping("/batches/{id}/findings")
    public List<Map<String, Object>> findings(@PathVariable long id, @RequestParam(required = false) Long record) {
        one(id);
        return jdbc.queryForList("SELECT v.severity, v.field, v.message, v.record_id, r.record_type, r.subject_key, r.contract_no FROM ecis_cic_validation v LEFT JOIN ecis_cic_record r ON r.id = v.record_id "
            + "WHERE v.batch_id = ?" + (record == null ? "" : " AND v.record_id = " + record) + " ORDER BY v.severity, v.id LIMIT 500", id);
    }

    /**
     * The reviewer's decision. Someone other than the person who made the batch approves it, it must have been validated, and the mapping must be confirmed against a file the CIC
     * has accepted (property {@code coop.cic.mapping-confirmed}). A batch with errors is approved only with {@code excludeErrors}: records with errors, and the loans of a borrower who
     * is left out, then stay out of the file.
     */
    @PostMapping("/batches/{id}/approve")
    @Transactional
    public Map<String, Object> approve(@PathVariable long id, @RequestBody Approval in, Authentication auth) {
        Map<String, Object> b = one(id);
        require(b, "Approval", "VALIDATED");
        if (!mappingConfirmed) throw conflict("The column mapping " + Csdf1.VERSION + " is not confirmed yet: it has not been checked against a file the CIC accepted, so the file can be previewed but not approved.");
        if (auth.getName().equals(b.get("created_by"))) throw forbidden("The person who made the batch cannot approve it.");
        int errors = ((Number) b.get("errors")).intValue();
        int excluded = 0;
        if (errors > 0) {
            if (!in.excludeErrors()) throw conflict(errors + " records have errors. Correct them, or approve with the records that have errors left out of the file.");
            excluded = jdbc.update("UPDATE ecis_cic_record SET excluded = 1 WHERE batch_id = ? AND status = 'ERROR'", id);
            List<Object[]> left = jdbc.queryForList("SELECT subject_key FROM ecis_cic_record WHERE batch_id = ? AND record_type = 'ID' AND excluded = 1", String.class, id).stream().map((k) -> new Object[] {id, k}).toList();
            for (int n : jdbc.batchUpdate("UPDATE ecis_cic_record SET excluded = 1 WHERE batch_id = ? AND subject_key = ? AND record_type <> 'ID' AND excluded = 0", left)) excluded += Math.max(n, 0);
        }
        String note = in.note() == null || in.note().isBlank() ? null : in.note().trim();
        jdbc.update("UPDATE ecis_cic_batch SET status = 'APPROVED', approved_by = ?, approved_at = ?, approval_note = ?, excluded = ? WHERE id = ?", auth.getName(), Timestamp.valueOf(LocalDateTime.now()), note, excluded, id);
        log(id, "FILES", "Approved by " + auth.getName() + (excluded > 0 ? "; " + excluded + " records left out." : "."));
        AuditLog.write(jdbc, auth.getName(), "cic.batch.approved", "cic_batch", String.valueOf(b.get("ref")), "VALIDATED", "APPROVED, " + excluded + " records left out", note);
        return get(id);
    }

    /** The file as the CIC reads it: HD, every ID, every CI, every CN, FT, lines ended by CR LF. Before approval it is a preview, named so. */
    @GetMapping("/batches/{id}/file")
    @Transactional
    public org.springframework.http.ResponseEntity<byte[]> file(@PathVariable long id, Authentication auth) {
        Map<String, Object> b = one(id);
        require(b, "The file", "GENERATED", "VALIDATED", "APPROVED", "SUBMITTED", "RESULT", "RECONCILED");
        boolean final_ = ORDER.indexOf(status(b)) >= ORDER.indexOf("APPROVED");
        String provider = String.valueOf(b.get("provider_code"));
        LocalDate fileDate = LocalDate.parse(String.valueOf(b.get("cut_off_date")));
        List<String> lines = new ArrayList<>();
        lines.add(Csdf1.header(provider, fileDate, String.valueOf(b.get("version")), ((Number) b.get("submission_type")).intValue(), String.valueOf(b.get("remarks") == null ? "" : b.get("remarks"))).line());
        lines.addAll(jdbc.queryForList("SELECT line FROM ecis_cic_record WHERE batch_id = ? AND excluded = 0 ORDER BY " + TYPE_ORDER.replace("r.", "") + ", seq", String.class, id));
        lines.add(Csdf1.footer(provider, fileDate, lines.size() - 1).line());
        String base = provider + "_CSDF_" + ((Timestamp) jdbc.queryForObject("SELECT COALESCE(generated_at, created_at) FROM ecis_cic_batch WHERE id = ?", Timestamp.class, id)).toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String name = (final_ ? "" : "PREVIEW_") + base + ".zip";
        log(id, "FILES", (final_ ? "File" : "Preview") + " " + name + " downloaded by " + auth.getName() + " (" + (lines.size() - 2) + " records).");
        AuditLog.write(jdbc, auth.getName(), "cic.file.downloaded", "cic_batch", String.valueOf(b.get("ref")), null, name + ", " + (lines.size() - 2) + " records", final_ ? null : "preview");
        return org.springframework.http.ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
            .header("X-Content-Type-Options", "nosniff")
            .contentType(org.springframework.http.MediaType.parseMediaType("application/zip"))
            .body(zip(base, String.join("\r\n", lines)));
    }

    /** The CIC's naming, as in every file the cooperative submitted: {provider}_CSDF_{yyyyMMddHHmmss}.zip holding {provider}_CSDF_{yyyyMMddHHmmss}.txt at its root. */
    private static byte[] zip(String base, String text) {
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(out, java.nio.charset.StandardCharsets.UTF_8)) {
            z.putNextEntry(new java.util.zip.ZipEntry(base + ".txt"));
            z.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            z.closeEntry();
            z.finish();
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The file was handed to the CIC. {@code reference} is the receipt or reference the CIC gave. */
    @PostMapping("/batches/{id}/submit")
    @Transactional
    public Map<String, Object> submit(@PathVariable long id, @RequestBody Submission in, Authentication auth) {
        Map<String, Object> b = one(id);
        require(b, "Submitting", "APPROVED");
        String reference = in.reference() == null ? "" : in.reference().trim();
        if (reference.isEmpty() || reference.length() > 80) throw bad("Give the reference or receipt the CIC issued (up to 80 characters).");
        jdbc.update("UPDATE ecis_cic_batch SET status = 'SUBMITTED', submitted_by = ?, submitted_at = ?, submission_reference = ? WHERE id = ?", auth.getName(), Timestamp.valueOf(LocalDateTime.now()), reference, id);
        log(id, "FILES", "Recorded as submitted to the CIC by " + auth.getName() + ", reference " + reference + ".");
        AuditLog.write(jdbc, auth.getName(), "cic.batch.submitted", "cic_batch", String.valueOf(b.get("ref")), "APPROVED", "SUBMITTED", reference);
        return get(id);
    }

    /** The CIC's answer: how many records it accepted and how many it rejected. */
    @PostMapping("/batches/{id}/response")
    @Transactional
    public Map<String, Object> response(@PathVariable long id, @RequestBody Result in, Authentication auth) {
        Map<String, Object> b = one(id);
        require(b, "Recording the result", "SUBMITTED");
        if (in.accepted() < 0 || in.rejected() < 0) throw bad("The counts cannot be negative.");
        String note = in.note() == null || in.note().isBlank() ? null : in.note().trim();
        if (note != null && note.length() > 500) throw bad("The note can be at most 500 characters.");
        jdbc.update("INSERT INTO ecis_cic_response (batch_id, recorded_at, recorded_by, accepted, rejected, note) VALUES (?, ?, ?, ?, ?, ?)", id, Timestamp.valueOf(LocalDateTime.now()), auth.getName(), in.accepted(), in.rejected(), note);
        move(id, "RESULT");
        log(id, "FILES", "CIC result recorded: " + in.accepted() + " accepted, " + in.rejected() + " rejected.");
        AuditLog.write(jdbc, auth.getName(), "cic.batch.result", "cic_batch", String.valueOf(b.get("ref")), "SUBMITTED", in.accepted() + " accepted, " + in.rejected() + " rejected", note);
        return get(id);
    }

    /** Closes the batch once the counts agree: everything sent was either accepted or rejected, and rejected records are handled in a correction batch. */
    @PostMapping("/batches/{id}/reconcile")
    @Transactional
    public Map<String, Object> reconcile(@PathVariable long id, Authentication auth) {
        Map<String, Object> b = one(id);
        require(b, "Reconciling", "RESULT");
        Map<String, Object> r = jdbc.queryForList("SELECT accepted, rejected FROM ecis_cic_response WHERE batch_id = ? ORDER BY id DESC LIMIT 1", id).get(0);
        int sent = ((Number) b.get("subjects")).intValue() + ((Number) b.get("installment_contracts")).intValue() + ((Number) b.get("other_contracts")).intValue() - ((Number) b.get("excluded")).intValue();
        int answered = ((Number) r.get("accepted")).intValue() + ((Number) r.get("rejected")).intValue();
        if (answered != sent) throw conflict("The CIC answered for " + answered + " records but " + sent + " were sent. Check the result and record it again, or ask the CIC.");
        move(id, "RECONCILED");
        log(id, "FILES", "Reconciled: " + sent + " records, " + r.get("rejected") + " rejected.");
        AuditLog.write(jdbc, auth.getName(), "cic.batch.reconciled", "cic_batch", String.valueOf(b.get("ref")), "RESULT", "RECONCILED", null);
        return get(id);
    }

    /** Starts a correction batch (submission type 1) for a batch the CIC has answered, to resend what it rejected or what was found wrong afterwards. */
    @PostMapping("/batches/{id}/correction")
    @Transactional
    public Map<String, Object> correction(@PathVariable long id, Authentication auth) {
        Map<String, Object> b = one(id);
        require(b, "A correction", "RESULT", "RECONCILED");
        long nid = db.insertReturningId("INSERT INTO ecis_cic_batch (submission_type, cut_off_date, provider_code, version, remarks, status, created_by, created_at, corrects_batch_id) VALUES (1, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)", "id",
            java.sql.Date.valueOf(LocalDate.parse(String.valueOf(b.get("cut_off_date")))), b.get("provider_code"), b.get("version"), "Corrects " + b.get("ref"), auth.getName(), Timestamp.valueOf(LocalDateTime.now()), id);
        String ref = "CIC-" + LocalDate.now().getYear() + "-" + String.format("%04d", nid);
        jdbc.update("UPDATE ecis_cic_batch SET ref = ? WHERE id = ?", ref, nid);
        log(nid, "FILES", "Correction batch for " + b.get("ref") + ".");
        AuditLog.write(jdbc, auth.getName(), "cic.batch.correction", "cic_batch", ref, null, "corrects " + b.get("ref"), null);
        return get(nid);
    }

    // ---- not built: no encryption or transmittal layout is known ---------------------------------------------------------------

    @PostMapping("/batches/{id}/encrypt") public Map<String, Object> encrypt(@PathVariable long id) { return notBuilt(id, "Encryption"); }

    @PostMapping("/batches/{id}/excel") public Map<String, Object> excel(@PathVariable long id) { return notBuilt(id, "The Excel copy"); }

    @PostMapping("/batches/{id}/transmittal") public Map<String, Object> transmittal(@PathVariable long id) { return notBuilt(id, "The transmittal letter"); }

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
