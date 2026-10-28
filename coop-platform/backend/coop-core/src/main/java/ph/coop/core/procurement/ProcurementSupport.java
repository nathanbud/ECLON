package ph.coop.core.procurement;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** What the purchasing controllers share: who may do what, the next document number, and the audit row. */
@Component
class ProcurementSupport {

    static final int BRANCH = 1;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    ProcurementSupport(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

    private static boolean has(Authentication auth, String role) {
        return auth.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals("ROLE_" + role));
    }

    /** Administrators and purchasers make requests, orders and suppliers. */
    void requirePurchaser(Authentication auth) {
        if (!has(auth, "ADMIN") && !has(auth, "PURCHASER")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You need the Purchaser role for this.");
    }

    /** Administrators and receivers (the people at the receiving point) record goods received. */
    void requireReceiver(Authentication auth) {
        if (!has(auth, "ADMIN") && !has(auth, "RECEIVER")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You need the Receiver role for this.");
    }

    /** Administrators and purchase approvers approve or reject. */
    void requireApprover(Authentication auth) {
        if (!has(auth, "ADMIN") && !has(auth, "PURCHASE_APPROVER")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You need the Purchase Approver role for this.");
    }

    boolean isAdmin(Authentication auth) {
        return has(auth, "ADMIN");
    }

    /** The next number for a prefix and year (PR-2026-0001); the row lock keeps two people from taking the same one, the unique key is the backstop. */
    String nextNumber(String table, String column, String prefix, int year) {
        List<String> last = jdbc.queryForList("SELECT " + column + " FROM " + table + " WHERE branch_code = ? AND " + column + " LIKE ? ORDER BY id DESC LIMIT 1 FOR UPDATE",
            String.class, BRANCH, prefix + "-" + year + "-%");
        int seq = last.isEmpty() ? 0 : ProcurementRules.sequenceOf(last.get(0), prefix, year);
        return ProcurementRules.number(prefix, year, seq + 1);
    }

    void audit(Authentication auth, String action, String entity, String id, String detail) {
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value) VALUES (?, ?, ?, ?, ?, ?)",
            Timestamp.valueOf(now()), auth.getName(), action, entity, id, detail);
    }

    static ResponseStatusException bad(List<String> problems) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", problems));
    }
}
