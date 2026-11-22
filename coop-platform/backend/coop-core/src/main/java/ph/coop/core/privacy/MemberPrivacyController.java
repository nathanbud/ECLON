package ph.coop.core.privacy;

import static ph.coop.core.web.ApiErrors.bad;
import static ph.coop.core.web.ApiErrors.conflict;
import static ph.coop.core.web.ApiErrors.notFound;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
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
import ph.coop.core.audit.AuditLog;

/**
 * The privacy notice and a member: which notice is in force and whether this member has been given it. Staff who may edit members record that the notice was given (and how); the
 * record is kept with the version that was given, so a new version later shows who has not yet received it.
 */
@RestController
public class MemberPrivacyController {

    public record Notice(long id, String version, String title, String body) {}

    public record Given(String version, String how, String by, String at) {}

    public record State(Notice active, Given latest, boolean upToDate) {}

    public record Ack(String how) {}

    private static final Set<String> HOW = Set.of("VERBAL", "WRITTEN", "ELECTRONIC");

    private final JdbcTemplate jdbc;

    MemberPrivacyController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/members/{branchCode}/{clientId}/privacy")
    public State state(@PathVariable int branchCode, @PathVariable int clientId) {
        List<Notice> active = jdbc.query("SELECT id, version, title, body FROM ecis_privacy_notice WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1",
            (rs, i) -> new Notice(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        List<Object[]> latest = jdbc.query("SELECT n.id, n.version, a.how, a.recorded_by, a.recorded_at FROM ecis_privacy_ack a JOIN ecis_privacy_notice n ON n.id = a.notice_id "
            + "WHERE a.branch_code = ? AND a.client_id = ? ORDER BY a.id DESC LIMIT 1", (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5)}, branchCode, clientId);
        Given given = latest.isEmpty() ? null : new Given((String) latest.get(0)[1], (String) latest.get(0)[2], (String) latest.get(0)[3], ((Timestamp) latest.get(0)[4]).toLocalDateTime().toString());
        boolean upToDate = !active.isEmpty() && !latest.isEmpty() && ((Long) latest.get(0)[0]).longValue() == active.get(0).id();
        return new State(active.isEmpty() ? null : active.get(0), given, upToDate);
    }

    @PostMapping("/api/members/{branchCode}/{clientId}/privacy")
    @PreAuthorize("hasAuthority('PERM_MEMBER_EDIT')")
    @Transactional
    public State acknowledge(@PathVariable int branchCode, @PathVariable int clientId, @RequestBody Ack in, Authentication auth) {
        String how = in.how() == null ? "" : in.how().trim().toUpperCase();
        if (!HOW.contains(how)) throw bad("Say how the notice was given: verbally, in writing or electronically.");
        Integer member = jdbc.queryForObject("SELECT COUNT(*) FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", Integer.class, branchCode, clientId);
        if (member == null || member == 0) throw notFound("That member was not found. The member number may be mistyped, or the member is in another branch: search for the member again.");
        State s = state(branchCode, clientId);
        if (s.active() == null) throw conflict("There is no privacy notice in force yet. A data protection officer must activate one first.");
        if (s.upToDate()) throw conflict("This member has already been given the current notice (version " + s.active().version() + ").");
        jdbc.update("INSERT INTO ecis_privacy_ack (branch_code, client_id, notice_id, how, recorded_by, recorded_at) VALUES (?, ?, ?, ?, ?, ?)",
            branchCode, clientId, s.active().id(), how, auth.getName(), Timestamp.valueOf(LocalDateTime.now()));
        AuditLog.write(jdbc, auth.getName(), "privacy.notice.given", "client", branchCode + "-" + clientId, null, "version " + s.active().version() + ", " + how.toLowerCase(), "Privacy notice given");
        return state(branchCode, clientId);
    }
}

/** Logging an export made on a screen (a report downloaded as a spreadsheet): who, what and why. Any signed-in person who can run reports may log one; the log is read by the data protection officer. */
@RestController
class ExportLogController {

    record Entry(String type, String subject, Integer rows, String purpose) {}

    private final JdbcTemplate jdbc;

    ExportLogController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/api/exports/log")
    void log(@RequestBody Entry e, Authentication auth) {
        String type = e.type() == null ? "REPORT" : e.type().trim().toUpperCase();
        if (!Set.of("REPORT", "AUDIT").contains(type)) throw bad("Only report and audit exports are logged from a screen.");
        String subject = e.subject() == null ? null : e.subject().length() > 160 ? e.subject().substring(0, 160) : e.subject();
        String purpose = e.purpose() == null ? null : e.purpose().length() > 255 ? e.purpose().substring(0, 255) : e.purpose();
        jdbc.update("INSERT INTO ecis_data_export (at, username, export_type, subject, row_count, purpose) VALUES (?, ?, ?, ?, ?, ?)",
            Timestamp.valueOf(LocalDateTime.now()), auth.getName(), type, subject, e.rows(), purpose);
    }
}
