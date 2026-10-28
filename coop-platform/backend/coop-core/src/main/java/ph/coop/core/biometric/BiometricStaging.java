package ph.coop.core.biometric;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The photo, consent and fingerprints taken while a member is being registered, before the member has a number. They are held against the registration request
 * ({@code ecis_member_request_item}; fingerprint templates encrypted and bound to the request) and, when a Membership Officer approves, moved into the member's photo and fingerprint
 * records; a rejected or withdrawn request has them wiped. The same rules as for a member already on file apply: consent first, a quality floor, and no finger that is already
 * enrolled for someone else (checked when the scan is taken, when the request is sent and again at approval).
 */
@Component
public class BiometricStaging {

    public record StagedFinger(int finger, String template, String format, int quality, String device) {}

    /** What the registration form carries along: a photo (base64 JPEG or PNG), whether the member consented to fingerprints, and the scans. All optional. */
    public record StagedItems(String photo, boolean consent, List<StagedFinger> fingerprints) {
        public boolean isEmpty() {
            return (photo == null || photo.isBlank()) && (fingerprints == null || fingerprints.isEmpty());
        }

        public List<StagedFinger> prints() {
            return fingerprints == null ? List.of() : fingerprints;
        }
    }

    private static final int MAX_PHOTO_BYTES = 3 * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final BiometricVault vault;
    private final List<FingerprintMatcher> matchers;

    BiometricStaging(JdbcTemplate jdbc, BiometricVault vault, List<FingerprintMatcher> matchers) {
        this.jdbc = jdbc;
        this.vault = vault;
        this.matchers = matchers;
    }

    private static ResponseStatusException bad(HttpStatus s, String m) {
        return new ResponseStatusException(s, m);
    }

    private FingerprintMatcher matcherFor(String format) {
        return matchers.stream().filter((m) -> m.supports(format)).findFirst().orElseThrow(() -> bad(HttpStatus.BAD_REQUEST, "No matcher is installed for the template format '" + format + "'."));
    }

    private static byte[] decode(String b64) {
        try {
            return Base64.getDecoder().decode(b64 == null ? "" : b64);
        } catch (IllegalArgumentException e) {
            throw bad(HttpStatus.BAD_REQUEST, "The picture or fingerprint data is not valid.");
        }
    }

    private static String scope(long requestId, int finger) {
        return "req|" + requestId + "|" + finger;
    }

    /** A message when this template matches a finger already enrolled for a member, or held for another registration; otherwise null. */
    public String duplicate(String template, String format, long exceptRequestId, List<StagedFinger> alsoThese) {
        byte[] t = decode(template);
        FingerprintMatcher matcher = matcherFor(format);
        List<Object[]> enrolled = new ArrayList<>();
        jdbc.query("SELECT branch_code, client_id, finger, template FROM ecis_member_fingerprint WHERE status = 'ACTIVE' AND format = ? AND template IS NOT NULL", (rs) -> {
            int b = rs.getInt(1), c = rs.getInt(2), f = rs.getInt(3);
            enrolled.add(new Object[] {b, c, vault.open(rs.getBytes(4), b, c, f)});
        }, format);
        for (Object[] e : enrolled) {
            if (matcher.score(t, (byte[]) e[2]) >= BiometricRules.MATCH_THRESHOLD) return "This fingerprint is already enrolled for member " + e[0] + "-" + e[1] + ". A person can have only one membership record.";
        }
        List<Object[]> held = new ArrayList<>();
        jdbc.query("SELECT request_id, finger, data FROM ecis_member_request_item WHERE kind = 'FINGER' AND status = 'HELD' AND format = ? AND data IS NOT NULL AND request_id <> ?", (rs) -> {
            long r = rs.getLong(1);
            int f = rs.getInt(2);
            held.add(new Object[] {r, vault.openScoped(rs.getBytes(3), scope(r, f))});
        }, format, exceptRequestId);
        for (Object[] h : held) {
            if (matcher.score(t, (byte[]) h[1]) >= BiometricRules.MATCH_THRESHOLD) return "This fingerprint is already held for another registration that is waiting for approval (request #" + h[0] + ").";
        }
        if (alsoThese != null) {
            for (StagedFinger o : alsoThese) {
                if (o.format().equals(format) && matcher.score(t, decode(o.template())) >= BiometricRules.MATCH_THRESHOLD) {
                    return "This fingerprint matches the " + BiometricRules.FINGERS.get(o.finger()).toLowerCase() + " already scanned for this member.";
                }
            }
        }
        return null;
    }

    /** Checks what a registration carries before the request is stored. Throws a message fit to show the user. */
    public void validate(StagedItems items) {
        if (items == null || items.isEmpty()) return;
        if (items.photo() != null && !items.photo().isBlank()) {
            byte[] p = decode(items.photo());
            if (p.length == 0 || p.length > MAX_PHOTO_BYTES) throw bad(HttpStatus.BAD_REQUEST, "The photo is empty or too large (at most 3 MB).");
            boolean jpeg = p.length > 3 && (p[0] & 0xFF) == 0xFF && (p[1] & 0xFF) == 0xD8;
            boolean png = p.length > 3 && (p[0] & 0xFF) == 0x89 && p[1] == 'P';
            if (!jpeg && !png) throw bad(HttpStatus.BAD_REQUEST, "Only JPEG and PNG photos are accepted.");
        }
        if (!items.prints().isEmpty()) {
            if (!items.consent()) throw bad(HttpStatus.CONFLICT, "Record the member's consent before fingerprints are kept.");
            Set<Integer> seen = new HashSet<>();
            List<StagedFinger> earlier = new ArrayList<>();
            for (StagedFinger f : items.prints()) {
                List<String> problems = BiometricRules.captureProblems(f.finger(), f.quality(), f.format(), decode(f.template()));
                if (!problems.isEmpty()) throw bad(HttpStatus.BAD_REQUEST, String.join(" ", problems));
                if (!seen.add(f.finger())) throw bad(HttpStatus.BAD_REQUEST, "The " + BiometricRules.FINGERS.get(f.finger()).toLowerCase() + " is listed twice.");
                String dup = duplicate(f.template(), f.format(), -1, earlier);
                if (dup != null) throw bad(HttpStatus.CONFLICT, dup);
                earlier.add(f);
            }
        }
    }

    /** Holds the validated items against the request. */
    public void stage(long requestId, StagedItems items) {
        if (items == null || items.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now().withNano(0);
        if (items.photo() != null && !items.photo().isBlank()) {
            jdbc.update("INSERT INTO ecis_member_request_item (request_id, kind, data, status, created_at) VALUES (?, 'PHOTO', ?, 'HELD', ?)", requestId, decode(items.photo()), now);
        }
        if (!items.prints().isEmpty()) {
            jdbc.update("INSERT INTO ecis_member_request_item (request_id, kind, status, created_at) VALUES (?, 'CONSENT', 'HELD', ?)", requestId, now);
            for (StagedFinger f : items.prints()) {
                jdbc.update("INSERT INTO ecis_member_request_item (request_id, kind, finger, format, quality, device, data, status, created_at) VALUES (?, 'FINGER', ?, ?, ?, ?, ?, 'HELD', ?)",
                    requestId, f.finger(), f.format(), f.quality(), f.device(), vault.sealScoped(decode(f.template()), scope(requestId, f.finger())), now);
            }
        }
    }

    /** A few words on what a request carries, for lists. */
    public String describe(StagedItems items) {
        if (items == null || items.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        if (items.photo() != null && !items.photo().isBlank()) parts.add("photo");
        if (!items.prints().isEmpty()) parts.add(items.prints().size() + (items.prints().size() == 1 ? " fingerprint" : " fingerprints"));
        return " + " + String.join(", ", parts);
    }

    /** Moves what the request held into the new member's photo and fingerprint records. Refuses (so the officer can reject) when a held finger now matches another member. */
    public void release(long requestId, int branch, int clientId, String approver, String requester) {
        List<Object[]> items = new ArrayList<>();
        jdbc.query("SELECT id, kind, finger, format, quality, device, data, created_at FROM ecis_member_request_item WHERE request_id = ? AND status = 'HELD' ORDER BY id", (rs) -> {
            items.add(new Object[] {rs.getLong(1), rs.getString(2), rs.getObject(3), rs.getString(4), rs.getObject(5), rs.getString(6), rs.getBytes(7), rs.getTimestamp(8)});
        }, requestId);
        if (items.isEmpty()) return;
        for (Object[] it : items) {
            String kind = (String) it[1];
            byte[] data = (byte[]) it[6];
            if (kind.equals("PHOTO") && data != null) {
                jdbc.update("INSERT INTO image (ImageBR_CODE, ClientIDimg, ImageType, ImageFileSize, ImageFile, ImageRemarks, ImageDateTimeAdded) VALUES (?, ?, 'img', ?, ?, ?, NOW())",
                    branch, clientId, data.length, data, "Taken at registration by " + requester);
                audit(approver, "member.photo.set", branch, clientId, data.length + " bytes", "Photo taken at registration by " + requester);
            } else if (kind.equals("CONSENT")) {
                jdbc.update("INSERT INTO ecis_biometric_consent (branch_code, client_id, consent_version, given_at, recorded_by) VALUES (?, ?, ?, ?, ?)",
                    branch, clientId, BiometricRules.CONSENT_VERSION, it[7], requester);
                audit(approver, "member.biometric.consent", branch, clientId, "version " + BiometricRules.CONSENT_VERSION, "Consent recorded at registration by " + requester);
            } else if (kind.equals("FINGER") && data != null) {
                int finger = ((Number) it[2]).intValue();
                String format = (String) it[3];
                byte[] template = vault.openScoped(data, scope(requestId, finger));
                String dup = duplicate(java.util.Base64.getEncoder().encodeToString(template), format, requestId, null);
                if (dup != null) throw bad(HttpStatus.CONFLICT, dup + " Reject this registration.");
                jdbc.update("INSERT INTO ecis_member_fingerprint (branch_code, client_id, finger, format, quality, device, template, status, captured_by, captured_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                    branch, clientId, finger, format, ((Number) it[4]).intValue(), it[5], vault.seal(template, branch, clientId, finger), requester, it[7]);
                audit(approver, "member.fingerprint.enrolled", branch, clientId, BiometricRules.FINGERS.get(finger) + ", quality " + it[4], "Captured at registration by " + requester);
            }
        }
        jdbc.update("UPDATE ecis_member_request_item SET data = NULL, status = 'RELEASED' WHERE request_id = ? AND status = 'HELD'", requestId);
    }

    /** A rejected or withdrawn request keeps nothing: the photo and templates are wiped. */
    public void discard(long requestId) {
        jdbc.update("UPDATE ecis_member_request_item SET data = NULL, status = 'DISCARDED' WHERE request_id = ? AND status = 'HELD'", requestId);
    }

    private void audit(String user, String action, int branch, int client, String next, String reason) {
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, prev_value, new_value, reason) VALUES (NOW(), ?, ?, 'client', ?, NULL, ?, ?)", user, action, branch + "-" + client, next, reason);
    }
}
