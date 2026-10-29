package ph.coop.core.biometric;


import ph.coop.core.audit.AuditLog;import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;

/**
 * Member fingerprints, built the way a biometric enrolment is run in industry: the member's consent is recorded first and can be withdrawn (which wipes every template); the scan
 * is made on the workstation and arrives as a standard template with its quality score; a poor scan is refused; a finger already enrolled for another member is refused (one
 * person, one record); templates are encrypted before storage and never returned by any endpoint; replaced or revoked templates are wiped; every step is audited without the
 * template. Enrolling needs both the edit-client and edit-client-images permissions, the same as changing a photo.
 */
@RestController
@RequestMapping("/api/members/{branchCode}/{clientId}")
public class FingerprintController {

    private static final String WHO = "hasAuthority('PERM_MEMBER_EDIT') and hasAuthority('PERM_MEMBER_IMAGES')";

    public record FingerRow(int finger, String name, int quality, String device, String capturedAt, String capturedBy) {}

    public record Consent(String version, String givenAt, String recordedBy) {}

    public record Summary(Consent consent, List<FingerRow> fingers, int minQuality, double threshold, int recommended, boolean simulated, String consentVersion) {}

    public record Capture(int finger, String template, String format, int quality, String device) {}

    public record Verify(String template, String format) {}

    public record VerifyResult(boolean matched, Integer finger, String fingerName, double score) {}

    public record Reason(String reason) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final BiometricVault vault;
    private final List<FingerprintMatcher> matchers;
    private final TransactionTemplate tx;

    FingerprintController(JdbcTemplate jdbc, Db db, BiometricVault vault, List<FingerprintMatcher> matchers, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.db = db;
        this.vault = vault;
        this.matchers = matchers;
        this.tx = new TransactionTemplate(txManager);
    }

    // ---- reading ------------------------------------------------------------------------------------------------

    /** What is on file for a member: whether there is consent and which fingers are enrolled. Never any template. */
    @GetMapping("/fingerprints")
    public Summary summary(@PathVariable int branchCode, @PathVariable int clientId) {
        requireInstalled();
        Consent consent = activeConsent(branchCode, clientId);
        List<FingerRow> fingers = jdbc.query("SELECT finger, quality, device, captured_at, captured_by FROM ecis_member_fingerprint WHERE branch_code = ? AND client_id = ? AND status = 'ACTIVE' ORDER BY finger",
            (rs, i) -> new FingerRow(rs.getInt(1), BiometricRules.FINGERS.get(rs.getInt(1)), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5)), branchCode, clientId);
        return new Summary(consent, fingers, BiometricRules.MIN_QUALITY, BiometricRules.MATCH_THRESHOLD, BiometricRules.RECOMMENDED_FINGERS, matchers.stream().anyMatch(FingerprintMatcher::simulated), BiometricRules.CONSENT_VERSION);
    }

    // ---- steps --------------------------------------------------------------------------------------------------

    @PostMapping("/biometric-consent")
    @PreAuthorize(WHO)
    public Summary giveConsent(@PathVariable int branchCode, @PathVariable int clientId, Authentication auth) {
        requireInstalled();
        return tx.execute((s) -> {
            requireMember(branchCode, clientId);
            if (activeConsent(branchCode, clientId) != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "The member's consent is already recorded.");
            jdbc.update("INSERT INTO ecis_biometric_consent (branch_code, client_id, consent_version, given_at, recorded_by) VALUES (?, ?, ?, NOW(), ?)", branchCode, clientId, BiometricRules.CONSENT_VERSION, auth.getName());
            audit(auth.getName(), "member.biometric.consent", branchCode, clientId, null, "version " + BiometricRules.CONSENT_VERSION, "Consent recorded");
            return summary(branchCode, clientId);
        });
    }

    /** Withdrawing consent erases every enrolled fingerprint of the member. */
    @PostMapping("/biometric-consent/withdraw")
    @PreAuthorize(WHO)
    public Summary withdrawConsent(@PathVariable int branchCode, @PathVariable int clientId, @RequestBody Reason r, Authentication auth) {
        requireInstalled();
        String reason = needReason(r);
        return tx.execute((s) -> {
            if (activeConsent(branchCode, clientId) == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "There is no consent on file to withdraw.");
            int wiped = jdbc.update("UPDATE ecis_member_fingerprint SET template = NULL, status = 'REVOKED', ended_at = NOW(), end_reason = ? WHERE branch_code = ? AND client_id = ? AND status = 'ACTIVE'", "Consent withdrawn: " + reason, branchCode, clientId);
            jdbc.update("UPDATE ecis_biometric_consent SET withdrawn_at = NOW(), withdrawn_by = ?, withdraw_reason = ? WHERE branch_code = ? AND client_id = ? AND withdrawn_at IS NULL", auth.getName(), reason, branchCode, clientId);
            audit(auth.getName(), "member.biometric.withdrawn", branchCode, clientId, wiped + " fingerprint(s)", "erased", reason);
            return summary(branchCode, clientId);
        });
    }

    @PostMapping("/fingerprints")
    @PreAuthorize(WHO)
    public Summary enrol(@PathVariable int branchCode, @PathVariable int clientId, @RequestBody Capture c, Authentication auth) {
        requireInstalled();
        byte[] template = decode(c.template());
        List<String> problems = BiometricRules.captureProblems(c.finger(), c.quality(), c.format(), template);
        if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", problems));
        FingerprintMatcher matcher = matcherFor(c.format());
        return tx.execute((s) -> {
            requireMember(branchCode, clientId);
            if (activeConsent(branchCode, clientId) == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Record the member's consent before scanning.");

            // One person, one record: the same finger may not be enrolled for another member, nor again under a different finger of this member.
            for (Stored other : activeTemplates(c.format())) {
                boolean sameMember = other.branch == branchCode && other.client == clientId;
                if (sameMember && other.finger == c.finger()) continue; // this is the finger being replaced
                if (matcher.score(template, other.template) >= BiometricRules.MATCH_THRESHOLD) {
                    if (sameMember) throw new ResponseStatusException(HttpStatus.CONFLICT, "This finger is already enrolled as the " + BiometricRules.FINGERS.get(other.finger).toLowerCase() + ".");
                    audit(auth.getName(), "member.fingerprint.duplicate", branchCode, clientId, null, "matches member " + other.branch + "-" + other.client, "Enrolment refused");
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "This fingerprint is already enrolled for member " + other.branch + "-" + other.client + ". A person can have only one membership record.");
                }
            }
            int replaced = jdbc.update("UPDATE ecis_member_fingerprint SET template = NULL, status = 'REPLACED', ended_at = NOW(), end_reason = 'Scanned again' WHERE branch_code = ? AND client_id = ? AND finger = ? AND status = 'ACTIVE'", branchCode, clientId, c.finger());
            jdbc.update("INSERT INTO ecis_member_fingerprint (branch_code, client_id, finger, format, quality, device, template, status, captured_by, captured_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, NOW())",
                branchCode, clientId, c.finger(), c.format(), c.quality(), c.device(), vault.seal(template, branchCode, clientId, c.finger()), auth.getName());
            audit(auth.getName(), "member.fingerprint.enrolled", branchCode, clientId, replaced > 0 ? "replaced" : null, BiometricRules.FINGERS.get(c.finger()) + ", quality " + c.quality(), c.device() == null ? "Scanner" : c.device());
            return summary(branchCode, clientId);
        });
    }

    /** Checks a fresh scan against the member's own enrolled fingers (one to one). */
    @PostMapping("/fingerprints/verify")
    public VerifyResult verify(@PathVariable int branchCode, @PathVariable int clientId, @RequestBody Verify v, Authentication auth) {
        requireInstalled();
        byte[] template = decode(v.template());
        FingerprintMatcher matcher = matcherFor(v.format());
        double best = 0;
        Integer bestFinger = null;
        for (Stored s : activeTemplates(v.format())) {
            if (s.branch != branchCode || s.client != clientId) continue;
            double score = matcher.score(template, s.template);
            if (score > best) { best = score; bestFinger = s.finger; }
        }
        boolean matched = bestFinger != null && best >= BiometricRules.MATCH_THRESHOLD;
        audit(auth.getName(), matched ? "member.fingerprint.verified" : "member.fingerprint.verify.failed", branchCode, clientId, null, matched ? BiometricRules.FINGERS.get(bestFinger) : "no match", "Score " + Math.round(best * 100));
        return new VerifyResult(matched, matched ? bestFinger : null, matched ? BiometricRules.FINGERS.get(bestFinger) : null, best);
    }

    @PostMapping("/fingerprints/{finger}/revoke")
    @PreAuthorize(WHO)
    public Summary revoke(@PathVariable int branchCode, @PathVariable int clientId, @PathVariable int finger, @RequestBody Reason r, Authentication auth) {
        requireInstalled();
        String reason = needReason(r);
        return tx.execute((s) -> {
            int n = jdbc.update("UPDATE ecis_member_fingerprint SET template = NULL, status = 'REVOKED', ended_at = NOW(), end_reason = ? WHERE branch_code = ? AND client_id = ? AND finger = ? AND status = 'ACTIVE'", reason, branchCode, clientId, finger);
            if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That finger is not enrolled.");
            audit(auth.getName(), "member.fingerprint.revoked", branchCode, clientId, BiometricRules.FINGERS.get(finger), "erased", reason);
            return summary(branchCode, clientId);
        });
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private record Stored(int branch, int client, int finger, byte[] template) {}

    private List<Stored> activeTemplates(String format) {
        List<Stored> out = new ArrayList<>();
        jdbc.query("SELECT branch_code, client_id, finger, template FROM ecis_member_fingerprint WHERE status = 'ACTIVE' AND format = ? AND template IS NOT NULL", (rs) -> {
            int b = rs.getInt(1), c = rs.getInt(2), f = rs.getInt(3);
            out.add(new Stored(b, c, f, vault.open(rs.getBytes(4), b, c, f)));
        }, format);
        return out;
    }

    private Consent activeConsent(int branch, int client) {
        List<Consent> found = jdbc.query("SELECT consent_version, given_at, recorded_by FROM ecis_biometric_consent WHERE branch_code = ? AND client_id = ? AND withdrawn_at IS NULL ORDER BY id DESC LIMIT 1",
            (rs, i) -> new Consent(rs.getString(1), rs.getString(2), rs.getString(3)), branch, client);
        return found.isEmpty() ? null : found.get(0);
    }

    private FingerprintMatcher matcherFor(String format) {
        return matchers.stream().filter((m) -> m.supports(format)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No matcher is installed for the template format '" + format + "'."));
    }

    private void requireInstalled() {
        if (!db.allTablesExist("ecis_member_fingerprint", "ecis_biometric_consent", "ecis_audit_log")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Fingerprints are not installed on this server (V040).");
    }

    private void requireMember(int branch, int client) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", Integer.class, branch, client);
        if (n == null || n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found.");
    }

    private static byte[] decode(String base64) {
        try {
            return base64 == null ? new byte[0] : Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The fingerprint data is not valid.");
        }
    }

    private static String needReason(Reason r) {
        if (r == null || r.reason() == null || r.reason().trim().length() < 3) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give a reason.");
        return r.reason().trim();
    }

    private void audit(String user, String action, int branch, int client, String prev, String next, String reason) {
        AuditLog.write(jdbc, null, user, action, "client", branch + "-" + client, prev, next, reason);
    }

    /** Used by tests to tell the matcher apart from the stand-in. */
    Map<String, Boolean> matcherInfo() {
        return Map.of("simulated", matchers.stream().anyMatch(FingerprintMatcher::simulated));
    }
}
