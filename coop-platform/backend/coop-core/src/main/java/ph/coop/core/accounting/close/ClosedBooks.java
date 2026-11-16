package ph.coop.core.accounting.close;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Which years are closed. A year counts as closed once it has a Closing Entry in the legacy ledger (transsum module 1, type 99), whether the
 * old system wrote it or this application did; the old system keeps no other closing date.
 */
@Component
public class ClosedBooks {

    /** Legacy transaction type of the year-end Closing Entry. */
    public static final int CLOSING_ENTRY = 99;
    /** Legacy transaction type 98 (Profit Loss Summ): one-sided running rows on the net surplus for the period account. */
    public static final int PROFIT_LOSS_SUMMARY = 98;
    /** Used when the branch names no net surplus for the period account. */
    static final String DEFAULT_PROFIT_LOSS_ACCOUNT = "30199";

    private final JdbcTemplate jdbc;

    ClosedBooks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isClosed(int branch, int year) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM transsum WHERE TR_BRCODE = ? AND TR_YEAR = ? AND TR_MODULE = 1 AND TR_CODE = ?",
                Integer.class, branch, year, CLOSING_ENTRY);
        return n != null && n > 0;
    }

    /** The latest closed year, or null when none was ever closed. */
    public Integer lastClosedYear(int branch) {
        return jdbc.queryForObject("SELECT MAX(TR_YEAR) FROM transsum WHERE TR_BRCODE = ? AND TR_MODULE = 1 AND TR_CODE = ?", Integer.class, branch, CLOSING_ENTRY);
    }

    /**
     * The branch's net surplus for the period account (branches.ProfitLossAcct_Code, 30199 here). The old system's daily type 98 rows run on it,
     * and the closing cancels them there with one-sided type 99 rows, so both are left out wherever the ledger must balance.
     */
    public String profitLossAccount(int branch) {
        List<String> a = jdbc.queryForList("SELECT ProfitLossAcct_Code FROM branches WHERE BranchesID = ?", String.class, branch);
        return a.isEmpty() || a.get(0) == null || a.get(0).isBlank() ? DEFAULT_PROFIT_LOSS_ACCOUNT : a.get(0).trim();
    }
}
