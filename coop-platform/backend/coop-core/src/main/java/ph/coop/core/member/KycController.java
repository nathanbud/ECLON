package ph.coop.core.member;

import java.io.IOException;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;

/**
 * Know Your Customer for a member: the ID presented, source of funds, politically-exposed status, risk rating and next review date, with an optional scan of the ID.
 * One current record per member in {@code ecis_member_kyc}; every change is audited (the ID number is masked there), so the history is in the audit log.
 * Reading needs sign-in; saving needs the "edit client" permission and the scan also "edit client images".
 */
@RestController
@RequestMapping("/api/members/{branchCode}/{clientId}/kyc")
class KycController {

    static final int MAX_SCAN_BYTES = 3 * 1024 * 1024;

    record Kyc(String idType, String idNumber, String idIssuer, String idExpiry, String sourceOfFunds, boolean pep, String pepNote, String risk, String nextReview, String remarks,
               boolean hasScan, String updatedBy, String updatedAt, String status, List<String> idTypes) {}

    record Save(String idType, String idNumber, String idIssuer, String idExpiry, String sourceOfFunds, boolean pep, String pepNote, String risk, String nextReview, String remarks) {}

    private final JdbcTemplate jdbc;

    KycController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static LocalDate date(String s, String what) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The " + what + " is not a valid date.");
        }
    }

    private static String text(Object o) { return o == null ? null : o.toString(); }

    private static LocalDate toDate(Object o) {
        if (o == null) return null;
        if (o instanceof Date d) return d.toLocalDate();
        return LocalDate.parse(o.toString().substring(0, 10));
    }

    private Kyc read(int branchCode, int clientId) {
        var rows = jdbc.queryForList("SELECT id_type, id_number, id_issuer, id_expiry, source_of_funds, pep, pep_note, risk, next_review, remarks, "
            + "CASE WHEN id_scan IS NULL THEN 0 ELSE 1 END AS has_scan, updated_by, updated_at FROM ecis_member_kyc WHERE branch_code = ? AND client_id = ?", branchCode, clientId);
        if (rows.isEmpty()) return new Kyc(null, null, null, null, null, false, null, null, null, null, false, null, null, KycRules.Status.NONE.name(), KycRules.ID_TYPES);
        Map<String, Object> r = rows.get(0);
        var rec = new KycRules.Record((String) r.get("id_type"), (String) r.get("id_number"), toDate(r.get("id_expiry")), (String) r.get("source_of_funds"), (String) r.get("risk"),
            toDate(r.get("next_review")), ((Number) r.get("pep")).intValue() != 0, (String) r.get("pep_note"));
        return new Kyc(rec.idType(), rec.idNumber(), (String) r.get("id_issuer"), text(rec.idExpiry()), rec.sourceOfFunds(), rec.pep(), rec.pepNote(), rec.risk(), text(rec.nextReview()), (String) r.get("remarks"),
            ((Number) r.get("has_scan")).intValue() != 0, (String) r.get("updated_by"), text(r.get("updated_at")), KycRules.status(rec, LocalDate.now()).name(), KycRules.ID_TYPES);
    }

    private void requireMember(int branchCode, int clientId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", Integer.class, branchCode, clientId);
        if (n == null || n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That member was not found. The member number may be mistyped, or the member is in another branch: search for the member again.");
    }

    @GetMapping
    Kyc get(@PathVariable int branchCode, @PathVariable int clientId) {
        requireMember(branchCode, clientId);
        return read(branchCode, clientId);
    }

    @PutMapping
    @PreAuthorize("hasAuthority('PERM_MEMBER_EDIT')")
    Kyc save(@PathVariable int branchCode, @PathVariable int clientId, @RequestBody Save in, Authentication auth) {
        requireMember(branchCode, clientId);
        String risk = blankToNull(in.risk());
        var problems = KycRules.problems(blankToNull(in.idType()), blankToNull(in.idNumber()), risk, in.pep(), in.pepNote());
        if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", problems));
        LocalDate expiry = date(in.idExpiry(), "ID expiry date");
        LocalDate review = date(in.nextReview(), "next review date");
        // A review date is suggested from the risk rating when none is given.
        if (review == null && risk != null) review = KycRules.suggestedReview(risk, LocalDate.now());
        Kyc before = read(branchCode, clientId);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Object[] vals = {blankToNull(in.idType()), blankToNull(in.idNumber()), blankToNull(in.idIssuer()), expiry == null ? null : Date.valueOf(expiry), blankToNull(in.sourceOfFunds()),
            in.pep() ? 1 : 0, in.pep() ? blankToNull(in.pepNote()) : null, risk, review == null ? null : Date.valueOf(review), blankToNull(in.remarks()), auth.getName(), now};
        int n = jdbc.update("UPDATE ecis_member_kyc SET id_type = ?, id_number = ?, id_issuer = ?, id_expiry = ?, source_of_funds = ?, pep = ?, pep_note = ?, risk = ?, next_review = ?, remarks = ?, "
            + "updated_by = ?, updated_at = ? WHERE branch_code = ? AND client_id = ?", append(vals, branchCode, clientId));
        if (n == 0) {
            jdbc.update("INSERT INTO ecis_member_kyc (id_type, id_number, id_issuer, id_expiry, source_of_funds, pep, pep_note, risk, next_review, remarks, updated_by, updated_at, branch_code, client_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", append(vals, branchCode, clientId));
        }
        Kyc after = read(branchCode, clientId);
        AuditLog.write(jdbc, auth.getName(), "member.kyc.update", "client", branchCode + "-" + clientId, summary(before), summary(after), before.status().equals("NONE") ? "KYC recorded" : "KYC changed");
        return after;
    }

    private static Object[] append(Object[] a, Object... more) {
        Object[] out = java.util.Arrays.copyOf(a, a.length + more.length);
        System.arraycopy(more, 0, out, a.length, more.length);
        return out;
    }

    /** What goes in the audit log: the facts, with the ID number masked. */
    private static String summary(Kyc k) {
        List<String> parts = new ArrayList<>();
        parts.add("status " + k.status());
        if (k.idType() != null) parts.add("ID " + k.idType() + " " + KycRules.mask(k.idNumber()) + (k.idExpiry() != null ? " exp " + k.idExpiry() : ""));
        if (k.sourceOfFunds() != null) parts.add("funds: " + k.sourceOfFunds());
        if (k.pep()) parts.add("PEP: " + k.pepNote());
        if (k.risk() != null) parts.add("risk " + k.risk());
        if (k.nextReview() != null) parts.add("review " + k.nextReview());
        return String.join("; ", parts);
    }

    @PostMapping("/id-scan")
    @PreAuthorize("hasAuthority('PERM_MEMBER_IMAGES') and hasAuthority('PERM_MEMBER_EDIT')")
    ResponseEntity<Void> setScan(@PathVariable int branchCode, @PathVariable int clientId, @RequestParam("file") MultipartFile file, Authentication auth) throws IOException {
        requireMember(branchCode, clientId);
        byte[] data = file.getBytes();
        if (data.length == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The scan is empty, so nothing was saved. Scan or choose the ID picture again.");
        if (data.length > MAX_SCAN_BYTES) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The scan is too large: it can be at most 3 MB. Scan it at a lower resolution or save it as JPEG, then try again.");
        boolean jpeg = data.length > 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8;
        boolean png = data.length > 3 && (data[0] & 0xFF) == 0x89 && data[1] == 'P';
        if (!jpeg && !png) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only JPEG and PNG scans are accepted.");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        String type = jpeg ? "image/jpeg" : "image/png";
        int n = jdbc.update("UPDATE ecis_member_kyc SET id_scan = ?, id_scan_type = ?, updated_by = ?, updated_at = ? WHERE branch_code = ? AND client_id = ?", data, type, auth.getName(), now, branchCode, clientId);
        if (n == 0) {
            jdbc.update("INSERT INTO ecis_member_kyc (id_scan, id_scan_type, updated_by, updated_at, branch_code, client_id) VALUES (?, ?, ?, ?, ?, ?)", data, type, auth.getName(), now, branchCode, clientId);
        }
        AuditLog.write(jdbc, auth.getName(), "member.kyc.id-scan", "client", branchCode + "-" + clientId, null, data.length + " bytes, " + type, "ID scan added");
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/id-scan")
    ResponseEntity<byte[]> scan(@PathVariable int branchCode, @PathVariable int clientId, Authentication auth) {
        var rows = jdbc.queryForList("SELECT id_scan, id_scan_type FROM ecis_member_kyc WHERE branch_code = ? AND client_id = ? AND id_scan IS NOT NULL", branchCode, clientId);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        AuditLog.write(jdbc, auth.getName(), "member.kyc.id-scan.view", "client", branchCode + "-" + clientId, null, null, "ID scan viewed");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType((String) rows.get(0).get("id_scan_type"))).cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff").body((byte[]) rows.get(0).get("id_scan"));
    }
}
