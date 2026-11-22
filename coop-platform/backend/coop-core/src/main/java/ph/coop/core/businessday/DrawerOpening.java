package ph.coop.core.businessday;

import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;

/**
 * The start-of-day count of a teller's or cashier's drawer (Front Office > Cash Handover > Open my drawer). Each person counts their own drawer once
 * per business day before taking or paying out cash; the count is compared with what the ledger says the drawer holds and any difference is recorded
 * with a reason (ecis_drawer_open). Required while the business day is in use and the policy {@value #POLICY} is not switched off (on by default;
 * Business Day screen, administrators). Hand-offs between drawers do not need it: receiving cash is itself counted.
 */
@Component
public class DrawerOpening {

    public static final String POLICY = "teller.require_drawer_open";
    public static final String NOT_OPEN = "Open your drawer first: count it on Front Office > Cash Handover > Open my drawer. Each drawer is counted once at the start of the business day.";

    private final JdbcTemplate jdbc;
    private final Db db;
    private final BusinessDay businessDay;

    DrawerOpening(JdbcTemplate jdbc, Db db, BusinessDay businessDay) {
        this.jdbc = jdbc;
        this.db = db;
        this.businessDay = businessDay;
    }

    /** Whether this database has the opening table (PostgreSQL V030, MySQL V067). */
    public boolean available() {
        return db.tableExists("ecis_drawer_open");
    }

    /** Whether the policy is on: anything but "false" in ecis_setting (no row: on). */
    public boolean policyOn() {
        if (!db.tableExists("ecis_setting")) return true;
        List<String> v = jdbc.queryForList("SELECT setting_value FROM ecis_setting WHERE setting_key = ?", String.class, POLICY);
        return v.isEmpty() || !"false".equals(v.get(0));
    }

    /** Whether cash at the window waits for the drawer to be opened. */
    public boolean required() {
        return businessDay.inUse() && available() && policyOn();
    }

    public boolean isOpen(int branch, LocalDate day, int event, int number) {
        if (!available()) return false;
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_drawer_open WHERE branch_code = ? AND business_date = ? AND event = ? AND drawer_number = ?",
            Integer.class, branch, java.sql.Date.valueOf(day), event, number);
        return n != null && n > 0;
    }

    /** Refuses (409) cash at the window from a drawer not opened on the business day, while the rule is in force. */
    public void requireOpen(int branch, LocalDate day, int event, int number) {
        if (!required()) return;
        if (!isOpen(branch, day, event, number)) throw new ResponseStatusException(HttpStatus.CONFLICT, NOT_OPEN);
    }
}
