package ph.coop.core.loan;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.connection.OnConnectionSwitch;
import ph.coop.core.db.Db;

/**
 * Profiling and Deduction as separate steps of a loan application (PostgreSQL V025, MySQL V062). A person needs Add loan application
 * ({@code AccessCanAddLoanApplication}, "1 · Profiling") to encode an application and {@code AccessCanDoLoanDeduction} ("2 · Deduction") to fill in its
 * deductions; with both they do both at once, as before. An application saved without its deductions waits for someone who may do them, and cannot be
 * verified or approved until then. Who did each step and when is kept in {@code ecis_loanapp_step} and in the audit log.
 *
 * <p>On a database without the migration, Deduction counts as part of Profiling (the old behaviour) and nothing is tracked.
 */
@Component
public class LoanSteps implements OnConnectionSwitch {

    /** The switch on the old system's user table for the Deduction step. */
    public static final String DEDUCTION_COLUMN = "AccessCanDoLoanDeduction";

    private final JdbcTemplate jdbc;
    private final Db db;
    private final Clock clock;
    private volatile Boolean available;

    LoanSteps(JdbcTemplate jdbc, Db db, Clock clock) {
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
    }

    /** Whether this database has the Deduction switch and the step table. Asked once and remembered (the column search is slow when it is missing). */
    public boolean available() {
        Boolean a = available;
        if (a == null) {
            a = db.tableExists("ecis_loanapp_step") && db.columnExists("access", DEDUCTION_COLUMN);
            available = a;
        }
        return a;
    }

    @Override
    public void connectionSwitched() {
        available = null;
    }

    /** The SQL for the person's Deduction switch, falling back to Add loan application where the database has no such column. */
    public String deductionFlagSql(String accessAlias) {
        String p = accessAlias == null || accessAlias.isEmpty() ? "" : accessAlias + ".";
        return available() ? "COALESCE(" + p + DEDUCTION_COLUMN + ", 0)" : "COALESCE(" + p + "AccessCanAddLoanApplication, 0)";
    }

    /** Records Profiling, and Deduction too when it was done in the same save. */
    void profiled(int branch, long appId, String by, boolean withDeductions) {
        if (!available()) return;
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(clock).withNano(0));
        jdbc.update("INSERT INTO ecis_loanapp_step (branch_code, loanapp_id, profiled_by, profiled_at, deductions_by, deductions_at) VALUES (?, ?, ?, ?, ?, ?)",
            branch, appId, by, now, withDeductions ? by : null, withDeductions ? now : null);
    }

    /** Records the Deduction step; false when someone did it in the meantime. */
    boolean deducted(int branch, long appId, String by) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(clock).withNano(0));
        return jdbc.update("UPDATE ecis_loanapp_step SET deductions_by = ?, deductions_at = ? WHERE branch_code = ? AND loanapp_id = ? AND deductions_at IS NULL",
            by, now, branch, appId) == 1;
    }

    /** Whether the application still waits for its deductions. Applications with no step row (saved before, or elsewhere) are complete. */
    public boolean waitingForDeductions(int branch, long appId) {
        if (!available()) return false;
        List<Integer> r = jdbc.queryForList("SELECT CASE WHEN deductions_at IS NULL THEN 1 ELSE 0 END FROM ecis_loanapp_step WHERE branch_code = ? AND loanapp_id = ?", Integer.class, branch, appId);
        return !r.isEmpty() && r.get(0) == 1;
    }

    /** The SQL expression (1 or 0) for "waiting for deductions" of loanapp {@code a}, for list queries. */
    public String waitingSql(String loanappAlias) {
        if (!available()) return "0";
        return "(SELECT COUNT(*) FROM ecis_loanapp_step s WHERE s.branch_code = " + loanappAlias + ".BR_CODE AND s.loanapp_id = " + loanappAlias + ".ID AND s.deductions_at IS NULL)";
    }
}
