package ph.coop.core.accounting;

import static ph.coop.core.web.ApiErrors.bad;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.connection.DatabaseChanges;
import ph.coop.core.db.Db;

/**
 * Budgets: the annual budget of each income and expense account, and Budget versus actual for a period of the year (rules in {@link BudgetRules}).
 * Accountants and administrators set them, a list at a time with one reason; auditors read them.
 */
@RestController
@RequestMapping("/api/accounting/budgets")
@PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
class BudgetController {

    /** An income or expense posting account with its budget for the year and, for reference, its actual of the year before. */
    record BudgetLine(String accountCode, String name, int type, String group, BigDecimal budget, BigDecimal lastYearActual) {}

    record BudgetIn(String accountCode, BigDecimal amount) {}

    record BudgetChange(List<BudgetIn> lines, String reason) {}

    /** One account of Budget versus actual; {@code favourable} as in {@link BudgetRules#favourable}. */
    record VarianceLine(String accountCode, String name, int type, String group, BigDecimal annual, BigDecimal budget, BigDecimal actual, BigDecimal variance,
                        boolean favourable) {}

    record Variance(int branchCode, int year, LocalDate from, LocalDate to, int months, List<VarianceLine> lines, BigDecimal revenueBudget, BigDecimal revenueActual,
                    BigDecimal expenseBudget, BigDecimal expenseActual) {}

    private record Acct(String code, String name, int type, String parent) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final Clock clock;

    BudgetController(JdbcTemplate jdbc, Db db, Clock clock) {
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
    }

    private void requireTable() {
        if (!db.tableExists("ecis_budget")) throw new ResponseStatusException(HttpStatus.CONFLICT, DatabaseChanges.notSetUp("Budgets"));
    }

    private List<Acct> accounts(int branch) {
        return jdbc.query("""
                SELECT COAID, COADesc, COAacct_type, COAacct_sum FROM coa
                WHERE COABR_CODE = ? AND COAacct_type IN (4, 5) AND COAacct_tag = 'D' AND COALESCE(COAisdisabled, 0) = 0 ORDER BY COAID
                """, (rs, i) -> new Acct(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)), branch);
    }

    private Map<String, String> names(int branch) {
        Map<String, String> m = new HashMap<>();
        jdbc.query("SELECT COAID, COADesc FROM coa WHERE COABR_CODE = ? ORDER BY COAID", (rs) -> { m.put(rs.getString(1), rs.getString(2)); }, branch);
        return m;
    }

    private Map<String, BigDecimal> budgets(int branch, int year) {
        Map<String, BigDecimal> m = new HashMap<>();
        if (db.tableExists("ecis_budget")) {
            jdbc.query("SELECT account_code, amount FROM ecis_budget WHERE branch_code = ? AND budget_year = ? ORDER BY account_code",
                (rs) -> { m.put(rs.getString(1), rs.getBigDecimal(2)); }, branch, year);
        }
        return m;
    }

    /** Income and expense of each account over a period, as positive figures (revenue credit, expense debit), leaving out the year-end closing. */
    private Map<String, BigDecimal> actual(int branch, LocalDate from, LocalDate to) {
        Map<String, BigDecimal> m = new HashMap<>();
        jdbc.query("""
                SELECT GLTransACCT_CODE, SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT) FROM gltrans
                WHERE GLTransBR_CODE = ? AND GLTransTR_DATE BETWEEN ? AND ? AND GLTransTR_CODE NOT IN (98, 99) AND (GLTransACCT_CODE LIKE '4%' OR GLTransACCT_CODE LIKE '5%')
                GROUP BY GLTransACCT_CODE ORDER BY GLTransACCT_CODE
                """, (rs) -> { m.put(rs.getString(1), rs.getBigDecimal(2)); }, branch, from, to);
        return m;
    }

    private static BigDecimal shown(int type, BigDecimal net) {
        BigDecimal n = net == null ? BigDecimal.ZERO : net;
        return type == 4 ? n.negate() : n;
    }

    /** Every income and expense posting account with its budget for the year and last year's actual. */
    @GetMapping("/{year}")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN','AUDITOR')")
    List<BudgetLine> list(@RequestParam int branchCode, @PathVariable int year) {
        Map<String, BigDecimal> b = budgets(branchCode, year);
        Map<String, BigDecimal> last = actual(branchCode, LocalDate.of(year - 1, 1, 1), LocalDate.of(year - 1, 12, 31));
        Map<String, String> names = names(branchCode);
        return accounts(branchCode).stream().map((a) -> new BudgetLine(a.code(), a.name(), a.type(), names.getOrDefault(a.parent(), ""),
            b.getOrDefault(a.code(), BigDecimal.ZERO), shown(a.type(), last.get(a.code())))).toList();
    }

    /** Sets the budget of the accounts sent, for one year, with one reason; each account that changes is audited with its amount before and after. */
    @PutMapping("/{year}")
    @Transactional
    List<BudgetLine> save(@RequestParam int branchCode, @PathVariable int year, @RequestBody BudgetChange in, Authentication auth) {
        requireTable();
        if (year < 2000 || year > 2100) throw bad("Choose a budget year between 2000 and 2100. Nothing was saved.");
        if (in == null || in.lines() == null || in.lines().isEmpty()) throw bad("Nothing to save: change at least one account's budget.");
        if (in.reason() == null || in.reason().trim().length() < 3) throw bad("Give the reason for the change (at least 3 characters); it is kept in the audit log. Nothing was saved.");
        Map<String, Acct> known = new HashMap<>();
        for (Acct a : accounts(branchCode)) known.put(a.code(), a);
        Map<String, BigDecimal> before = budgets(branchCode, year);
        int changed = 0;
        for (BudgetIn l : in.lines()) {
            if (l == null || !known.containsKey(l.accountCode()))
                throw bad("Account " + (l == null ? "" : l.accountCode()) + " is not an income or expense account in use, so it has no budget. Nothing was saved.");
            BigDecimal amount;
            try {
                amount = BudgetRules.amount(l.amount());
            } catch (IllegalArgumentException e) {
                throw bad(l.accountCode() + ": " + e.getMessage() + " Nothing was saved.");
            }
            BigDecimal was = before.get(l.accountCode());
            if (was != null && was.compareTo(amount) == 0) continue;
            db.upsert("ecis_budget", Map.of("branch_code", branchCode, "budget_year", year, "account_code", l.accountCode()),
                Map.of("amount", amount, "updated_by", auth.getName(), "updated_at", Timestamp.valueOf(LocalDateTime.now(clock))));
            AuditLog.write(jdbc, auth.getName(), "accounting.budget.change", "budget", branchCode + "/" + year + "/" + l.accountCode(),
                was == null ? null : was.toPlainString(), amount.toPlainString(), in.reason().trim());
            changed++;
        }
        if (changed == 0) throw bad("Nothing changed: every amount sent is already the budget. Nothing was saved.");
        return list(branchCode, year);
    }

    /** Budget versus actual for a period inside one budget year: the budget spread evenly by month, the actual, and the variance per account. */
    @GetMapping("/{year}/variance")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN','AUDITOR')")
    Variance variance(@RequestParam int branchCode, @PathVariable int year, @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        LocalDate f = from != null ? from : LocalDate.of(year, 1, 1);
        LocalDate t = to != null ? to : LocalDate.of(year, 12, 31);
        if (f.getYear() != year || t.getYear() != year) throw bad("Budget versus actual covers one budget year: choose dates within " + year + ".");
        int months;
        try {
            months = BudgetRules.months(year, f, t);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        Map<String, BigDecimal> b = budgets(branchCode, year);
        Map<String, BigDecimal> act = actual(branchCode, f, t);
        Map<String, String> names = names(branchCode);
        List<VarianceLine> lines = new ArrayList<>();
        BigDecimal rb = BigDecimal.ZERO, ra = BigDecimal.ZERO, eb = BigDecimal.ZERO, ea = BigDecimal.ZERO;
        for (Acct a : accounts(branchCode)) {
            BigDecimal annual = b.getOrDefault(a.code(), BigDecimal.ZERO);
            BigDecimal budget = BudgetRules.forMonths(annual, months);
            BigDecimal actual = shown(a.type(), act.get(a.code()));
            if (annual.signum() == 0 && actual.signum() == 0) continue;
            lines.add(new VarianceLine(a.code(), a.name(), a.type(), names.getOrDefault(a.parent(), ""), annual, budget, actual, actual.subtract(budget),
                BudgetRules.favourable(a.type(), actual, budget)));
            if (a.type() == 4) { rb = rb.add(budget); ra = ra.add(actual); } else { eb = eb.add(budget); ea = ea.add(actual); }
        }
        return new Variance(branchCode, year, f, t, months, lines, rb, ra, eb, ea);
    }
}
