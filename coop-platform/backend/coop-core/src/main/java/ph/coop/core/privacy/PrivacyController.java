package ph.coop.core.privacy;

import static ph.coop.core.web.ApiErrors.bad;
import static ph.coop.core.web.ApiErrors.notFound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ph.coop.core.audit.AuditLog;

/**
 * The privacy registers a data protection officer works from (guide phase 19): data sharing, impact assessments, retention rules, data classification, incidents and privacy notices.
 * Their columns and rules are described once in {@link PrivacyRegisters}; this class lists, adds and changes rows, records who did it, and audits every change with the values before
 * and after. Administrators and data protection officers only.
 */
@RestController
@RequestMapping("/api/privacy")
@PreAuthorize("hasAnyRole('ADMIN', 'DATA_PROTECTION_OFFICER')")
public class PrivacyController {

    private final JdbcTemplate jdbc;
    private final ph.coop.core.db.Db db;
    private final ObjectMapper json;

    PrivacyController(JdbcTemplate jdbc, ph.coop.core.db.Db db, ObjectMapper json) {
        this.jdbc = jdbc;
        this.db = db;
        this.json = json;
    }

    static PrivacyRegisters.Reg reg(String key) {
        PrivacyRegisters.Reg r = PrivacyRegisters.ALL.get(key);
        if (r == null) throw notFound("There is no register called '" + key + "'.");
        return r;
    }

    /** A database value as plain text for the screen: dates and times in ISO form. */
    static Object plain(Object v) {
        if (v instanceof Timestamp t) return t.toLocalDateTime().toString();
        if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
        if (v instanceof LocalDateTime || v instanceof LocalDate) return v.toString();
        return v;
    }

    static Map<String, Object> plain(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(k, plain(v)));
        return out;
    }

    // ---- registers ----------------------------------------------------------------------------------------------

    @GetMapping("/registers/{key}")
    public List<Map<String, Object>> list(@PathVariable String key) {
        PrivacyRegisters.Reg r = reg(key);
        return jdbc.queryForList("SELECT * FROM " + r.table() + " ORDER BY " + r.orderBy() + " LIMIT 500").stream().map(PrivacyController::plain).toList();
    }

    @PostMapping("/registers/{key}")
    @Transactional
    public Map<String, Object> create(@PathVariable String key, @RequestBody Map<String, String> body, Authentication auth) {
        PrivacyRegisters.Reg r = reg(key);
        Map<String, Object> values = PrivacyRegisters.clean(r, body, true);
        addServerColumns(key, values, auth, true);
        long id = insert(r.table(), values);
        if (r.refPrefix() != null) {
            String ref = r.refPrefix() + "-" + LocalDate.now().getYear() + "-" + String.format("%04d", id);
            jdbc.update("UPDATE " + r.table() + " SET ref = ? WHERE id = ?", ref, id);
        }
        AuditLog.write(jdbc, auth.getName(), "privacy." + key + ".created", "privacy_" + key, String.valueOf(id), null, write(values), null);
        return one(r, id);
    }

    @PutMapping("/registers/{key}/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable String key, @PathVariable long id, @RequestBody Map<String, String> body, Authentication auth) {
        PrivacyRegisters.Reg r = reg(key);
        Map<String, Object> before = one(r, id);
        Map<String, Object> values = PrivacyRegisters.clean(r, body, false);
        if (values.isEmpty()) throw bad("Nothing was changed.");
        addServerColumns(key, values, auth, false);
        if (key.equals("notices") && "ACTIVE".equals(String.valueOf(before.get("status"))) && values.containsKey("body")) throw bad("An active notice cannot be rewritten. Add a new version instead.");
        String set = String.join(", ", values.keySet().stream().map((c) -> c + " = ?").toList());
        List<Object> args = new ArrayList<>(values.values());
        args.add(id);
        jdbc.update("UPDATE " + r.table() + " SET " + set + " WHERE id = ?", args.toArray());
        Map<String, Object> was = new LinkedHashMap<>();
        values.keySet().forEach((c) -> was.put(c, before.get(c)));
        AuditLog.write(jdbc, auth.getName(), "privacy." + key + ".updated", "privacy_" + key, String.valueOf(id), write(was), write(values), null);
        return one(r, id);
    }

    /** Makes one notice the active one. The notice that was active is retired, so only one version is in force at a time. */
    @PostMapping("/registers/notices/{id}/activate")
    @Transactional
    public Map<String, Object> activate(@PathVariable long id, Authentication auth) {
        PrivacyRegisters.Reg r = reg("notices");
        Map<String, Object> notice = one(r, id);
        if ("ACTIVE".equals(String.valueOf(notice.get("status")))) throw bad("This notice is already the active one.");
        jdbc.update("UPDATE ecis_privacy_notice SET status = 'RETIRED' WHERE status = 'ACTIVE'");
        jdbc.update("UPDATE ecis_privacy_notice SET status = 'ACTIVE', effective_from = COALESCE(effective_from, ?) WHERE id = ?", java.sql.Date.valueOf(LocalDate.now()), id);
        AuditLog.write(jdbc, auth.getName(), "privacy.notices.activated", "privacy_notices", String.valueOf(id), null, "version " + notice.get("version"), "Now the notice in force");
        return one(r, id);
    }

    // ---- retention review and the export log --------------------------------------------------------------------

    public record ReviewRow(String what, long count, String note) {}

    /** What is past its retention date and waiting for a decision. Nothing is deleted here: a document is removed only through the document register, with its own rules. */
    @GetMapping("/retention/review")
    public List<ReviewRow> retentionReview() {
        List<ReviewRow> out = new ArrayList<>();
        Long docs = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_document WHERE status = 'ACTIVE' AND retention_date IS NOT NULL AND retention_date < ?", Long.class, java.sql.Date.valueOf(LocalDate.now()));
        out.add(new ReviewRow("Documents past their retention date", docs == null ? 0 : docs, "Review in Documents; a document is removed only after its retention date, and its file is kept."));
        Long unconfirmed = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_retention_rule WHERE confirmed = 0", Long.class);
        out.add(new ReviewRow("Retention rules not yet confirmed", unconfirmed == null ? 0 : unconfirmed, "Confirm each rule against the Data Privacy Act and the cooperative's own record-keeping rules."));
        Long unreviewed = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_data_classification WHERE reviewed = 0", Long.class);
        out.add(new ReviewRow("Data classifications not yet reviewed", unreviewed == null ? 0 : unreviewed, "Review each item and mark it reviewed."));
        Long dpiaDue = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_dpia WHERE review_due IS NOT NULL AND review_due < ? AND status <> 'PLANNED'", Long.class, java.sql.Date.valueOf(LocalDate.now()));
        out.add(new ReviewRow("Impact assessments overdue for review", dpiaDue == null ? 0 : dpiaDue, "Open the assessment and set its status and next review date."));
        Long late = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_privacy_incident WHERE notified_at IS NULL AND status <> 'RESOLVED' AND notify_by < NOW()", Long.class);
        out.add(new ReviewRow("Incidents past the notification time and not reported", late == null ? 0 : late, "Decide whether the regulator and those affected must be told, and record when they were."));
        return out;
    }

    @GetMapping("/exports")
    public List<Map<String, Object>> exports() {
        return jdbc.queryForList("SELECT id, at, username, export_type, subject, row_count, purpose FROM ecis_data_export ORDER BY id DESC LIMIT 500").stream().map(PrivacyController::plain).toList();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private void addServerColumns(String key, Map<String, Object> values, Authentication auth, boolean creating) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        switch (key) {
            case "sharing", "dpia" -> { if (creating) { values.put("recorded_by", auth.getName()); values.put("recorded_at", now); } }
            case "notices" -> { if (creating) { values.put("created_by", auth.getName()); values.put("created_at", now); values.put("status", "DRAFT"); } }
            case "incidents" -> {
                if (creating) { values.put("reported_by", auth.getName()); values.put("created_at", now); }
                Object detected = values.get("detected_at");
                if (detected instanceof Timestamp t) values.put("notify_by", Timestamp.valueOf(t.toLocalDateTime().plusHours(PrivacyRegisters.NOTIFY_HOURS)));
            }
            case "retention" -> { if (Integer.valueOf(1).equals(values.get("confirmed"))) { values.put("confirmed_by", auth.getName()); values.put("confirmed_at", now); } }
            default -> { }
        }
    }

    private long insert(String table, Map<String, Object> values) {
        String cols = String.join(", ", values.keySet());
        String marks = String.join(", ", values.keySet().stream().map((c) -> "?").toList());
        return db.insertReturningId("INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ")", "id", values.values().toArray());
    }

    private Map<String, Object> one(PrivacyRegisters.Reg r, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM " + r.table() + " WHERE id = ?", id);
        if (rows.isEmpty()) throw notFound("Not found.");
        return plain(rows.get(0));
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
