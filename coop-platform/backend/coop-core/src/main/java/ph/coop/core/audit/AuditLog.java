package ph.coop.core.audit;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The one place a row is written to the audit trail ({@code ecis_audit_log}): who, when, what, which record, the values before and after, and why. Every module records through this
 * so the trail has one shape. Never pass secrets (passwords, fingerprint data) as values.
 */
public final class AuditLog {

    private AuditLog() {}

    private static final String INSERT =
        "INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, prev_value, new_value, reason) VALUES (%s, ?, ?, ?, ?, ?, ?, ?)";

    /**
     * Records one audit row.
     *
     * @param at the time to record (a {@link java.sql.Timestamp} or {@link java.time.LocalDateTime}); {@code null} means the database's clock
     */
    public static void write(JdbcTemplate jdbc, Object at, String user, String action, String entity, String entityId, String prev, String next, String reason) {
        if (at == null) jdbc.update(INSERT.formatted("NOW()"), user, action, entity, entityId, prev, next, reason);
        else jdbc.update(INSERT.formatted("?"), at, user, action, entity, entityId, prev, next, reason);
    }

    /** Records one audit row using the database's clock. */
    public static void write(JdbcTemplate jdbc, String user, String action, String entity, String entityId, String prev, String next, String reason) {
        write(jdbc, null, user, action, entity, entityId, prev, next, reason);
    }
}
