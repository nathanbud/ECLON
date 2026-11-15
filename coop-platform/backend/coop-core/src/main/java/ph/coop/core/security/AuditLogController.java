package ph.coop.core.security;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditChanges;
import ph.coop.core.member.MemberFieldLabels;

/** Read-only view of the audit trail ({@code ecis_audit_log}) for administrators: newest first, filtered by person, kind of record, action, date and text; each entry with its time to the millisecond and what changed, field by field. */
@RestController
@RequestMapping("/api/admin/audit-log")
@PreAuthorize("hasRole('ADMIN')")
class AuditLogController {

    static final int MAX_PAGE = 200;

    private final JdbcTemplate jdbc;

    AuditLogController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code at} is to the millisecond; {@code changes} is the before and after read field by field ({@link AuditChanges}), with the stored text kept in previous and current. */
    record Entry(long id, LocalDateTime at, String username, String action, String entity, String entityId, String previous, String current, String reason, String clientIp, String clientInfo,
                 List<AuditChanges.Change> changes) {}

    record PageOf(List<Entry> content, long totalElements, int number, int size) {}

    record Facets(List<String> entities, List<String> actions, List<String> users) {}

    /** The values the filters can offer. */
    @GetMapping("/facets")
    Facets facets() {
        return new Facets(
            jdbc.queryForList("SELECT DISTINCT entity FROM ecis_audit_log ORDER BY entity", String.class),
            jdbc.queryForList("SELECT DISTINCT action FROM ecis_audit_log ORDER BY action", String.class),
            jdbc.queryForList("SELECT DISTINCT username FROM ecis_audit_log ORDER BY username", String.class));
    }

    @GetMapping
    PageOf list(@RequestParam(required = false) String user, @RequestParam(required = false) String entity, @RequestParam(required = false) String action,
                @RequestParam(required = false) String q, @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page size must be 1 to " + MAX_PAGE + ".");
        if (from != null && to != null && to.isBefore(from)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The end date is before the start date.");
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (notBlank(user)) { where.append(" AND username = ?"); args.add(user.trim()); }
        if (notBlank(entity)) { where.append(" AND entity = ?"); args.add(entity.trim()); }
        if (notBlank(action)) { where.append(" AND action = ?"); args.add(action.trim()); }
        if (from != null) { where.append(" AND at >= ?"); args.add(Timestamp.valueOf(from.atStartOfDay())); }
        if (to != null) { where.append(" AND at < ?"); args.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay())); }
        if (notBlank(q)) {
            String like = "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (LOWER(entity_id) LIKE ? OR LOWER(COALESCE(reason, '')) LIKE ? OR LOWER(COALESCE(prev_value, '')) LIKE ? OR LOWER(COALESCE(new_value, '')) LIKE ?)");
            for (int i = 0; i < 4; i++) args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<Entry> rows = jdbc.query("SELECT id, at, username, action, entity, entity_id, prev_value, new_value, reason, client_ip, client_info FROM ecis_audit_log" + where
                + " ORDER BY id DESC LIMIT ? OFFSET ?",
            (rs, i) -> new Entry(rs.getLong(1), rs.getTimestamp(2).toLocalDateTime().truncatedTo(ChronoUnit.MILLIS), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
                AuditChanges.of(rs.getString(4), rs.getString(7), rs.getString(8), MemberFieldLabels::of)),
            pageArgs.toArray());
        return new PageOf(rows, total == null ? 0 : total, page, size);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
