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
            }), fixedAssetsAgainstLedger(today), journalListing(today));
    }

    /**
     * Every entry of the books in a period, line by line, as auditors ask for it: date, transaction type and control number, document number, explanation,
     * account, debit and credit, and who encoded, approved and posted it. Optionally one account. Printed, or exported as CSV from the Reports screen.
     */
    private Report journalListing(LocalDate today) {
        return of("journal-listing", "Accounting", "Journal listing",
            "Every entry in a period, line by line: date, type and control number, document, explanation, account, debit, credit and who encoded, approved and posted it.",
            List.of(dateParam("from", "From", today.withDayOfMonth(1)), dateParam("to", "To", today), textParam("account", "Account number (optional)", "")),
            Set.of("AUDITOR"), (a) -> {
                LocalDate[] p = a.period(today.withDayOfMonth(1), today, 370);
                String account = a.text("account");
                List<List<Object>> rows = new ArrayList<>();
                BigDecimal[] sums = {BigDecimal.ZERO, BigDecimal.ZERO};
                jdbc.query("""
                        SELECT s.TR_DATE, d.DTTR_MODULE, d.DTTR_CODE, d.DTTR_CTLNO, s.TR_DOCNO, s.EXPLANATION, d.DTACCT_CODE, c.COADesc, d.DTAMT, d.DTENCDBY, d.DTAPPRVBY, d.DTPOSTBY
                        FROM transsum s
                        JOIN transdt d ON d.DTTR_BRCODE = s.TR_BRCODE AND d.DTTR_YEAR = s.TR_YEAR AND d.DTTR_MODULE = s.TR_MODULE AND d.DTTR_CODE = s.TR_CODE AND d.DTTR_CTLNO = s.TR_CTLNO
                        LEFT JOIN coa c ON c.COABR_CODE = d.DTTR_BRCODE AND c.COAID = d.DTACCT_CODE
                        WHERE s.TR_BRCODE = ? AND s.TR_DATE BETWEEN ? AND ? AND (CAST(? AS CHAR) IS NULL OR d.DTACCT_CODE = ?)
                        ORDER BY s.TR_DATE, d.DTTR_MODULE, d.DTTR_CODE, d.DTTR_CTLNO, d.DTSEQ_NO
                        LIMIT %d
                        """.formatted(LIMIT + 1), (rs) -> {
                        if (rows.size() >= LIMIT) { rows.add(null); return; }
                        BigDecimal amt = rs.getBigDecimal(9) == null ? BigDecimal.ZERO : rs.getBigDecimal(9);
                        BigDecimal dr = amt.signum() > 0 ? amt : null, cr = amt.signum() < 0 ? amt.negate() : null;
                        if (dr != null) sums[0] = sums[0].add(dr);
                        if (cr != null) sums[1] = sums[1].add(cr);
                        rows.add(row(rs.getDate(1).toLocalDate().toString(), rs.getInt(2) + "-" + rs.getInt(3) + " no. " + rs.getLong(4), rs.getString(5), rs.getString(6),
                            rs.getString(7) + (rs.getString(8) == null ? "" : " " + rs.getString(8)), dr, cr,
                            String.join(" / ", java.util.Arrays.asList(nz(rs.getString(10)), nz(rs.getString(11)), nz(rs.getString(12))))));
                    }, BRANCH, p[0], p[1], account, account);
                boolean cut = !rows.isEmpty() && rows.get(rows.size() - 1) == null;
                if (cut) rows.remove(rows.size() - 1);
                return new Table(List.of(date("date", "Date"), text("entry", "Type and control no."), text("doc", "Document"), text("explanation", "Explanation"),
                        text("account", "Account"), money("debit", "Debit"), money("credit", "Credit"), text("by", "Encoded / approved / posted by")), rows,
                    row("Total", "", "", "", "", sums[0], sums[1], ""),
                    p[0] + " to " + p[1] + (account == null ? "" : ", account " + account) + (cut ? ". Only the first " + LIMIT + " lines are shown; choose a shorter period or one account." : ""));
            });
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "–" : s.trim();
    }

    /**
     * The fixed asset register against the general ledger, per asset category, as at a date: the cost of the assets in service and not disposed of against the
     * balance of the category's asset account, and the depreciation recorded in the register against the balance of its accumulated depreciation account. A
     * difference means an asset or an entry that is in one and not the other (an asset bought before the register was kept, a journal posted by hand).
     */
    private Report fixedAssetsAgainstLedger(LocalDate today) {
        return of("fixed-assets-vs-ledger", "Accounting", "Fixed assets against the ledger",
            "The asset register's cost and accumulated depreciation per category against the balances of the category's ledger accounts, with the differences.",
            List.of(dateParam("asOf", "As at", today)), Set.of("ASSET_CUSTODIAN", "AUDITOR"), (a) -> {
                LocalDate asOf = a.date("asOf", today);
                List<List<Object>> rows = new ArrayList<>();
                BigDecimal[] t = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
                jdbc.query("""
                        SELECT c.name, c.asset_account, c.accum_account,
                               (SELECT COALESCE(SUM(x.cost), 0) FROM ecis_asset x WHERE x.category_id = c.id AND x.branch_code = ? AND x.acquisition_date <= ?
                                  AND (x.disposed_on IS NULL OR x.disposed_on > ?)),
                               (SELECT COALESCE(SUM(d.amount), 0) FROM ecis_asset_depreciation d JOIN ecis_asset x ON x.id = d.asset_id
                                  WHERE x.category_id = c.id AND x.branch_code = ? AND d.period_end <= ? AND (x.disposed_on IS NULL OR x.disposed_on > ?))
                        FROM ecis_asset_category c ORDER BY c.name
                        """, (rs) -> {
                        String costAccount = rs.getString(2), accumAccount = rs.getString(3);
                        BigDecimal cost = rs.getBigDecimal(4), dep = rs.getBigDecimal(5);
                        BigDecimal glCost = balance(costAccount, asOf);
                        BigDecimal glAccum = balance(accumAccount, asOf).negate(); // a credit balance, shown positive
                        t[0] = t[0].add(cost); t[1] = t[1].add(glCost); t[2] = t[2].add(dep); t[3] = t[3].add(glAccum);
                        rows.add(row(rs.getString(1), costAccount + " / " + accumAccount, cost, glCost, glCost.subtract(cost), dep, glAccum, glAccum.subtract(dep)));
                    }, BRANCH, asOf, asOf, BRANCH, asOf, asOf);
                return new Table(List.of(text("category", "Category"), text("accounts", "Cost / accumulated depreciation accounts"), money("cost", "Register cost"),
                        money("glCost", "Ledger cost"), money("costDiff", "Difference"), money("dep", "Register depreciation"), money("glDep", "Ledger accumulated depreciation"),
                        money("depDiff", "Difference")), rows,
                    row("Total", "", t[0], t[1], t[1].subtract(t[0]), t[2], t[3], t[3].subtract(t[2])),
                    "As at " + asOf + ". The register counts depreciation once its draft journals are made; until they are posted, the ledger shows less.");
            });
    }

    /** An account's balance in the ledger as at a date: debits plus, credits minus. */
    private BigDecimal balance(String account, LocalDate asOf) {
        BigDecimal b = jdbc.queryForObject("SELECT COALESCE(SUM(GLTransDR_AMT + GLTransCR_AMT), 0) FROM gltrans WHERE GLTransBR_CODE = ? AND GLTransACCT_CODE = ? AND GLTransTR_DATE <= ?",
            BigDecimal.class, BRANCH, account, asOf);
        return b == null ? BigDecimal.ZERO : b;
    }
}
