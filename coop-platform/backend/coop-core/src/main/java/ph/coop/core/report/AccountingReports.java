package ph.coop.core.report;

import static ph.coop.core.report.Reports.date;
import static ph.coop.core.report.Reports.dateParam;
import static ph.coop.core.report.Reports.money;
import static ph.coop.core.report.Reports.of;
import static ph.coop.core.report.Reports.row;
import static ph.coop.core.report.Reports.text;
import static ph.coop.core.report.Reports.textParam;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.report.Reports.Provider;
import ph.coop.core.report.Reports.Report;
import ph.coop.core.report.Reports.Table;

/**
 * Accounting reports (guide phase 20). The trial balance and the financial statements already have their own screen (Reports > Accounting); this adds the general ledger of one
 * account: opening balance, every posting in the period with a running balance. Debits are positive, credits negative, as in the old ledger.
 */
@Component
class AccountingReports implements Provider {

    private static final int BRANCH = 1, LIMIT = 3000;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    AccountingReports(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public List<Report> reports() {
        LocalDate today = LocalDate.now(clock);
        return List.of(of("general-ledger", "Accounting", "General ledger", "One account's postings in a period with the balance after each (debits plus, credits minus).",
            List.of(textParam("account", "Account number", ""), dateParam("from", "From", today.withDayOfMonth(1)), dateParam("to", "To", today)), Set.of(), (a) -> {
                String account = a.text("account");
                if (account == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the account number.");
                List<String> name = jdbc.queryForList("SELECT COADesc FROM coa WHERE COABR_CODE = ? AND COAID = ?", String.class, BRANCH, account);
                if (name.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such account " + account + ".");
                LocalDate[] p = a.period(today.withDayOfMonth(1), today, 3700);
                BigDecimal opening = jdbc.queryForObject("SELECT COALESCE(SUM(GLTransDR_AMT + GLTransCR_AMT), 0) FROM gltrans WHERE GLTransBR_CODE = ? AND GLTransACCT_CODE = ? AND GLTransTR_DATE < ?", BigDecimal.class, BRANCH, account, p[0]);
                List<List<Object>> rows = new ArrayList<>();
                rows.add(row(p[0].toString(), "", "Opening balance", null, null, opening));
                BigDecimal[] run = {opening};
                BigDecimal[] sums = {BigDecimal.ZERO, BigDecimal.ZERO};
                jdbc.query("""
                        SELECT GLTransTR_DATE, GLTransTR_CODE, GLTransDEPT, GLTransDR_AMT, GLTransCR_AMT FROM gltrans
                        WHERE GLTransBR_CODE = ? AND GLTransACCT_CODE = ? AND GLTransTR_DATE >= ? AND GLTransTR_DATE <= ? ORDER BY GLTransTR_DATE, GLTransTR_CODE LIMIT %d
                        """.formatted(LIMIT + 1), (rs) -> {
                        if (rows.size() > LIMIT) return;
                        BigDecimal dr = rs.getBigDecimal(4), cr = rs.getBigDecimal(5);
                        run[0] = run[0].add(dr).add(cr);
                        sums[0] = sums[0].add(dr);
                        sums[1] = sums[1].add(cr.negate());
                        rows.add(row(rs.getDate(1).toLocalDate().toString(), "type " + rs.getInt(2) + (rs.getInt(3) > 0 ? ", dept " + rs.getInt(3) : ""), "", dr.signum() == 0 ? null : dr, cr.signum() == 0 ? null : cr.negate(), run[0]));
                    }, BRANCH, account, p[0], p[1]);
                boolean cut = rows.size() > LIMIT;
                if (cut) rows.remove(rows.size() - 1);
                return new Table(List.of(date("date", "Date"), text("source", "Transaction"), text("memo", ""), money("debit", "Debit"), money("credit", "Credit"), money("balance", "Balance")), rows,
                    row("Total", "", "", sums[0], sums[1], run[0]), account + " " + name.get(0) + (cut ? ". Only the first " + LIMIT + " postings are shown; choose a shorter period." : ""));
            }));
    }
}
