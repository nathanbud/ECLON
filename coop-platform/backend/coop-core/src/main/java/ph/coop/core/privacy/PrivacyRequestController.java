package ph.coop.core.privacy;

import static ph.coop.core.web.ApiErrors.bad;
import static ph.coop.core.web.ApiErrors.conflict;
import static ph.coop.core.web.ApiErrors.notFound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import ph.coop.core.audit.AuditLog;
import ph.coop.core.db.Db;

/**
 * Requests from people about their own data (guide phase 19, "data subject request tracking"): a person asks to see it, correct it, have it erased, object to its use, take a copy, or
 * withdraw consent. Each is logged with a due date, the person's identity is checked before anything is handed over or erased, and every step is audited. An access or portability
 * request is answered with a member data package (a file of what the system holds about that member), and the export is logged. An erasure request can wipe the member's fingerprints;
 * financial records are not erased by the application, and the officer records the reason in the outcome. Administrators and data protection officers only.
 */
@RestController
@RequestMapping("/api/privacy/requests")
@PreAuthorize("hasAnyRole('ADMIN', 'DATA_PROTECTION_OFFICER')")
public class PrivacyRequestController {

    static final Set<String> TYPES = Set.of("ACCESS", "CORRECTION", "ERASURE", "OBJECTION", "PORTABILITY", "WITHDRAW_CONSENT", "OTHER");
    private static final Set<String> OPEN = Set.of("OPEN", "IN_PROGRESS");

    public record NewRequest(String requestType, Integer branchCode, Integer clientId, String requesterName, String contact, String receivedOn, String summary) {}

    public record Note(String note) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final ObjectMapper json;
    private final int dueDays;

    PrivacyRequestController(JdbcTemplate jdbc, Db db, ObjectMapper json, @Value("${coop.privacy.request-days:30}") int dueDays) {
        this.jdbc = jdbc;
        this.db = db;
        this.json = json;
        this.dueDays = dueDays;
    }

    // ---- reading ------------------------------------------------------------------------------------------------

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        String where = status == null || status.isBlank() ? "" : " WHERE status = ?";
        Object[] args = where.isEmpty() ? new Object[0] : new Object[] {status.trim().toUpperCase()};
        return jdbc.queryForList("SELECT * FROM ecis_privacy_request" + where + " ORDER BY (status IN ('OPEN','IN_PROGRESS')) DESC, due_on, id DESC LIMIT 500", args).stream().map(PrivacyController::plain).toList();
    }

    // ---- steps --------------------------------------------------------------------------------------------------

    @PostMapping
    @Transactional
    public Map<String, Object> create(@RequestBody NewRequest in, Authentication auth) {
        String type = in.requestType() == null ? "" : in.requestType().trim().toUpperCase();
        if (!TYPES.contains(type)) throw bad("Choose what the person is asking for: " + String.join(", ", TYPES.stream().sorted().toList()) + ".");
        if (in.requesterName() == null || in.requesterName().trim().length() < 2) throw bad("Give the name of the person who made the request.");
        if (in.summary() == null || in.summary().trim().length() < 3) throw bad("Say what is being asked.");
        LocalDate received;
        try {
            received = in.receivedOn() == null || in.receivedOn().isBlank() ? LocalDate.now() : LocalDate.parse(in.receivedOn().trim());
        } catch (DateTimeParseException e) {
            throw bad("The date received must look like 2026-04-24.");
        }
        if (received.isAfter(LocalDate.now())) throw bad("The date received cannot be in the future.");
        Integer branch = in.branchCode(), client = in.clientId();
        if (client != null) {
            int b = branch == null ? 1 : branch;
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", Integer.class, b, client);
            if (n == null || n == 0) throw notFound("Member " + b + "-" + client + " was not found.");
            branch = b;
        }
        if (("ACCESS".equals(type) || "PORTABILITY".equals(type) || "ERASURE".equals(type) || "WITHDRAW_CONSENT".equals(type)) && client == null) {
            throw bad("This kind of request is about a member's data: give the member number.");
        }
        long id = db.insertReturningId("INSERT INTO ecis_privacy_request (request_type, branch_code, client_id, requester_name, contact, received_on, due_on, status, summary, created_by, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)", "id", type, branch, client, in.requesterName().trim(), blank(in.contact()), java.sql.Date.valueOf(received),
            java.sql.Date.valueOf(received.plusDays(dueDays)), in.summary().trim(), auth.getName(), Timestamp.valueOf(LocalDateTime.now()));
        String ref = "DSR-" + LocalDate.now().getYear() + "-" + String.format("%04d", id);
        jdbc.update("UPDATE ecis_privacy_request SET ref = ? WHERE id = ?", ref, id);
        AuditLog.write(jdbc, auth.getName(), "privacy.request.logged", "privacy_request", ref, null, type + (client == null ? "" : " for member " + branch + "-" + client) + ", due " + received.plusDays(dueDays), in.summary().trim());
        return one(id);
    }

    /** Records that the person's identity has been checked. Nothing is handed over or erased until it has. */
    @PostMapping("/{id}/verify-identity")
    @Transactional
    public Map<String, Object> verifyIdentity(@PathVariable long id, @RequestBody Note n, Authentication auth) {
        Map<String, Object> r = open(id);
        String how = need(n, "Say how the identity was checked (for example: matched the ID shown in person).");
        jdbc.update("UPDATE ecis_privacy_request SET identity_verified = 1, verified_by = ?, status = 'IN_PROGRESS', assigned_to = COALESCE(assigned_to, ?) WHERE id = ?", auth.getName(), auth.getName(), id);
        AuditLog.write(jdbc, auth.getName(), "privacy.request.identity", "privacy_request", String.valueOf(r.get("ref")), null, "identity checked", how);
        return one(id);
    }

    @PostMapping("/{id}/start")
    @Transactional
    public Map<String, Object> start(@PathVariable long id, Authentication auth) {
        Map<String, Object> r = open(id);
        jdbc.update("UPDATE ecis_privacy_request SET status = 'IN_PROGRESS', assigned_to = ? WHERE id = ?", auth.getName(), id);
        AuditLog.write(jdbc, auth.getName(), "privacy.request.started", "privacy_request", String.valueOf(r.get("ref")), String.valueOf(r.get("status")), "IN_PROGRESS", "Taken by " + auth.getName());
        return one(id);
    }

    @PostMapping("/{id}/fulfil")
    @Transactional
    public Map<String, Object> fulfil(@PathVariable long id, @RequestBody Note n, Authentication auth) {
        Map<String, Object> r = open(id);
        if (!"OTHER".equals(r.get("request_type")) && !"OBJECTION".equals(r.get("request_type")) && !truthy(r.get("identity_verified"))) {
            throw conflict("Check the person's identity before this request is fulfilled.");
        }
        close(id, "FULFILLED", need(n, "Record what was done."), auth, r);
        return one(id);
    }

    @PostMapping("/{id}/refuse")
    @Transactional
    public Map<String, Object> refuse(@PathVariable long id, @RequestBody Note n, Authentication auth) {
        Map<String, Object> r = open(id);
        close(id, "REFUSED", need(n, "Give the reason the request is refused (for example: the record must be kept by law)."), auth, r);
        return one(id);
    }

    @PostMapping("/{id}/withdraw")
    @Transactional
    public Map<String, Object> withdraw(@PathVariable long id, @RequestBody Note n, Authentication auth) {
        Map<String, Object> r = open(id);
        close(id, "WITHDRAWN", need(n, "Say why the request is withdrawn."), auth, r);
        return one(id);
    }

    /** Erases the member's fingerprints and ends their consent, for an erasure or consent-withdrawal request. Photos and financial records are not touched. */
    @PostMapping("/{id}/erase-biometrics")
    @Transactional
    public Map<String, Object> eraseBiometrics(@PathVariable long id, Authentication auth) {
        Map<String, Object> r = open(id);
        String type = String.valueOf(r.get("request_type"));
        if (!"ERASURE".equals(type) && !"WITHDRAW_CONSENT".equals(type)) throw conflict("Only an erasure or a consent-withdrawal request can erase fingerprints.");
        if (!truthy(r.get("identity_verified"))) throw conflict("Check the person's identity first.");
        int branch = ((Number) r.get("branch_code")).intValue(), client = ((Number) r.get("client_id")).intValue();
        String ref = String.valueOf(r.get("ref"));
        int wiped = jdbc.update("UPDATE ecis_member_fingerprint SET template = NULL, status = 'REVOKED', ended_at = NOW(), end_reason = ? WHERE branch_code = ? AND client_id = ? AND status = 'ACTIVE'",
            "Privacy request " + ref, branch, client);
        jdbc.update("UPDATE ecis_biometric_consent SET withdrawn_at = NOW(), withdrawn_by = ?, withdraw_reason = ? WHERE branch_code = ? AND client_id = ? AND withdrawn_at IS NULL", auth.getName(), "Privacy request " + ref, branch, client);
        AuditLog.write(jdbc, auth.getName(), "privacy.request.biometrics.erased", "privacy_request", ref, wiped + " fingerprint(s) of member " + branch + "-" + client, "erased", "Erased on request " + ref);
        return one(id);
    }

    /** The member data package for an access or portability request: what the system holds about the member, as a file. The download is logged. */
    @GetMapping("/{id}/package")
    @Transactional
    public ResponseEntity<byte[]> dataPackage(@PathVariable long id, Authentication auth) throws JsonProcessingException {
        Map<String, Object> r = one(id);
        String type = String.valueOf(r.get("request_type"));
        if (!"ACCESS".equals(type) && !"PORTABILITY".equals(type)) throw conflict("A data package is for an access or portability request.");
        if (!truthy(r.get("identity_verified"))) throw conflict("Check the person's identity before handing over their data.");
        if (!OPEN.contains(String.valueOf(r.get("status"))) && !"FULFILLED".equals(r.get("status"))) throw conflict("This request is closed without being fulfilled.");
        int branch = ((Number) r.get("branch_code")).intValue(), client = ((Number) r.get("client_id")).intValue();
        Map<String, Object> pack = MemberDataPackage.build(jdbc, branch, client, String.valueOf(r.get("ref")));
        byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(pack);
        jdbc.update("INSERT INTO ecis_data_export (at, username, export_type, subject, row_count, purpose) VALUES (?, ?, 'MEMBER_PACKAGE', ?, ?, ?)",
            Timestamp.valueOf(LocalDateTime.now()), auth.getName(), "member " + branch + "-" + client, MemberDataPackage.countRows(pack), "Privacy request " + r.get("ref"));
        AuditLog.write(jdbc, auth.getName(), "privacy.export.package", "privacy_request", String.valueOf(r.get("ref")), null, "member " + branch + "-" + client + ", " + bytes.length + " bytes", "Handed over under request " + r.get("ref"));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header("Content-Disposition", "attachment; filename=\"member-" + branch + "-" + client + "-data.json\"").body(bytes);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private void close(long id, String status, String outcome, Authentication auth, Map<String, Object> before) {
        jdbc.update("UPDATE ecis_privacy_request SET status = ?, outcome = ?, closed_by = ?, closed_at = ? WHERE id = ?", status, outcome, auth.getName(), Timestamp.valueOf(LocalDateTime.now()), id);
        AuditLog.write(jdbc, auth.getName(), "privacy.request." + status.toLowerCase(), "privacy_request", String.valueOf(before.get("ref")), String.valueOf(before.get("status")), status, outcome);
    }

    private Map<String, Object> one(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM ecis_privacy_request WHERE id = ?", id);
        if (rows.isEmpty()) throw notFound("That request was not found; it may have been withdrawn or decided already. Reload the list to see where it stands.");
        return PrivacyController.plain(rows.get(0));
    }

    private Map<String, Object> open(long id) {
        Map<String, Object> r = one(id);
        if (!OPEN.contains(String.valueOf(r.get("status")))) throw conflict("This request is already " + String.valueOf(r.get("status")).toLowerCase().replace('_', ' ') + ".");
        return r;
    }

    private static boolean truthy(Object v) {
        return v instanceof Number n && n.intValue() != 0 || Boolean.TRUE.equals(v);
    }

    private static String need(Note n, String message) {
        if (n == null || n.note() == null || n.note().trim().length() < 3) throw bad(message);
        return n.note().trim();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

/** What the system holds about one member, gathered into one structure. Fingerprint templates and photo contents are not included (a count and the fingerprints' details are). */
final class MemberDataPackage {

    private MemberDataPackage() {}

    static Map<String, Object> build(JdbcTemplate jdbc, int branch, int client, String ref) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", LocalDateTime.now().toString());
        out.put("request", ref);
        out.put("member", jdbc.queryForList("SELECT * FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", branch, client).stream().findFirst()
            .map((m) -> { Map<String, Object> clean = new LinkedHashMap<>(); m.forEach((k, v) -> { if (v != null && !String.valueOf(v).isBlank()) clean.put(k, PrivacyController.plain(v)); }); return clean; }).orElse(new LinkedHashMap<>()));
        out.put("accounts", jdbc.queryForList("SELECT MSLC_CODE AS class, MSLT_CODE AS type, MREF_NO AS number FROM slmastersum WHERE MBR_CODE = ? AND MCLIENTID = ? ORDER BY 1, 2, 3", branch, client));
        out.put("balancesByType", jdbc.queryForList("SELECT SLC_CODE AS class, SLT_CODE AS type, SUM(AMT) AS ledgerSum FROM sldtl WHERE SL_BRCODE = ? AND SL_CLIENTID = ? AND SLE_CODE = 11 GROUP BY SLC_CODE, SLT_CODE ORDER BY 1, 2", branch, client));
        out.put("fingerprints", jdbc.queryForList("SELECT finger, format, quality, device, status, captured_by AS capturedBy, captured_at AS capturedAt, ended_at AS endedAt FROM ecis_member_fingerprint WHERE branch_code = ? AND client_id = ? ORDER BY id", branch, client).stream().map(PrivacyController::plain).toList());
        out.put("biometricConsent", jdbc.queryForList("SELECT consent_version AS version, given_at AS givenAt, recorded_by AS recordedBy, withdrawn_at AS withdrawnAt FROM ecis_biometric_consent WHERE branch_code = ? AND client_id = ? ORDER BY id", branch, client).stream().map(PrivacyController::plain).toList());
        Integer photos = jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE ImageBR_CODE = ? AND ClientIDimg = ? AND ImageType = 'img'", Integer.class, branch, client);
        out.put("photosOnFile", photos == null ? 0 : photos);
        out.put("privacyNoticesGiven", jdbc.queryForList("SELECT n.version, a.how, a.recorded_by AS recordedBy, a.recorded_at AS recordedAt FROM ecis_privacy_ack a JOIN ecis_privacy_notice n ON n.id = a.notice_id WHERE a.branch_code = ? AND a.client_id = ? ORDER BY a.id", branch, client).stream().map(PrivacyController::plain).toList());
        out.put("changesRecorded", jdbc.queryForList("SELECT at, username, action, new_value AS value, reason FROM ecis_audit_log WHERE entity = 'client' AND entity_id = ? ORDER BY id DESC LIMIT 500", branch + "-" + client).stream().map(PrivacyController::plain).toList());
        return out;
    }

    /** How many records the package holds, for the export log. */
    static int countRows(Map<String, Object> pack) {
        int n = 0;
        for (Object v : pack.values()) if (v instanceof List<?> l) n += l.size();
        return n + 1;
    }
}
