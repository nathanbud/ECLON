package ph.coop.core.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The one place a row is written to the audit trail ({@code ecis_audit_log}): who, when, what, which record, the values before and after, why, and where the request came from (its
 * address and device, taken from the request in progress; empty for work done by the system itself, such as a nightly job). Every module records through this so the trail has one
 * shape. Never pass secrets (passwords, fingerprint data) as values. The application's database user can only insert into the log, never change or remove a row, and
 * {@link AuditSeals} chains the rows so tampering by anyone else can be detected.
 */
public final class AuditLog {

    private AuditLog() {}

    private static final String NARROW =
        "INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, prev_value, new_value, reason) VALUES (%s, ?, ?, ?, ?, ?, ?, ?)";
    private static final String WIDE =
        "INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, prev_value, new_value, reason, client_ip, client_info) VALUES (%s, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** False once the address and device columns turn out not to exist yet (a database not migrated to V045), so the older shape is used from then on. */
    private static volatile boolean wide = true;

    /**
     * Records one audit row.
     *
     * @param at the time to record (a {@link java.sql.Timestamp} or {@link java.time.LocalDateTime}); {@code null} means the database's clock
     */
    public static void write(JdbcTemplate jdbc, Object at, String user, String action, String entity, String entityId, String prev, String next, String reason) {
        if (wide) {
            try {
                String[] where = where();
                if (at == null) jdbc.update(WIDE.formatted("NOW()"), user, action, entity, entityId, prev, next, reason, where[0], where[1]);
                else jdbc.update(WIDE.formatted("?"), at, user, action, entity, entityId, prev, next, reason, where[0], where[1]);
                return;
            } catch (BadSqlGrammarException e) {
                wide = false;
            }
        }
        if (at == null) jdbc.update(NARROW.formatted("NOW()"), user, action, entity, entityId, prev, next, reason);
        else jdbc.update(NARROW.formatted("?"), at, user, action, entity, entityId, prev, next, reason);
    }

    /** Records one audit row using the database's clock. */
    public static void write(JdbcTemplate jdbc, String user, String action, String entity, String entityId, String prev, String next, String reason) {
        write(jdbc, null, user, action, entity, entityId, prev, next, reason);
    }

    /** The address and device of the request being served, or nulls when there is none. The address is the connection's own: a forwarded-for header is not trusted here. */
    static String[] where() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servlet)) return new String[] {null, null};
        HttpServletRequest request = servlet.getRequest();
        String agent = request.getHeader("User-Agent");
        if (agent != null && agent.length() > 200) agent = agent.substring(0, 200);
        String ip = request.getRemoteAddr();
        return new String[] {ip != null && ip.length() > 45 ? ip.substring(0, 45) : ip, agent};
    }
}
