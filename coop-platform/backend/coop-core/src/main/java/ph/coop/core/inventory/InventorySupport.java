package ph.coop.core.inventory;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** What the inventory controllers share: who may do what, document numbers, the audit row. */
@Component
public class InventorySupport {

    public static final int BRANCH = 1;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    InventorySupport(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

    public java.time.LocalDate today() {
        return java.time.LocalDate.now(clock);
    }

    private static boolean has(Authentication auth, String role) {
        return auth.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals("ROLE_" + role));
    }

    /** Administrators and storekeepers keep the product list and record stock movements. */
    void requireKeeper(Authentication auth) {
        if (!has(auth, "ADMIN") && !has(auth, "STOREKEEPER")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You need the Storekeeper role for this.");
    }

    /** Administrators and stock approvers decide adjustments and counts. */
    void requireApprover(Authentication auth) {
        if (!has(auth, "ADMIN") && !has(auth, "STOCK_APPROVER")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You need the Stock Approver role for this.");
    }

    /** The next document number for a table and prefix (SA-2026-0001); the row lock keeps two people from taking the same one, the unique key is the backstop. */
    public String nextNumber(String table, String column, String prefix, int year) {
        List<String> last = jdbc.queryForList("SELECT " + column + " FROM " + table + " WHERE branch_code = ? AND " + column + " LIKE ? ORDER BY id DESC LIMIT 1 FOR UPDATE",
            String.class, BRANCH, prefix + "-" + year + "-%");
        int seq = 0;
        if (!last.isEmpty()) {
            try {
                seq = Integer.parseInt(last.get(0).substring((prefix + "-" + year + "-").length()));
            } catch (RuntimeException e) {
                seq = 0;
            }
        }
        return String.format("%s-%d-%04d", prefix, year, seq + 1);
    }

    public void audit(Authentication auth, String action, String entity, String id, String detail) {
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value) VALUES (?, ?, ?, ?, ?, ?)",
            Timestamp.valueOf(now()), auth.getName(), action, entity, id, detail);
    }

    public static ResponseStatusException bad(List<String> problems) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", problems));
    }

    public static String own(String maker, String decider, String what) {
        return maker != null && maker.equalsIgnoreCase(decider) ? "You made this " + what + ", so someone else must decide it." : null;
    }
}
