package ph.coop.core.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only financial statements over the legacy ledger (guide step 6.7): the Statement of Financial Condition
 * (as at a date) and the Statement of Operations (over a date range). Account types come from coa.COAacct_type:
 * 1 assets, 2 liabilities, 3 equity, 4 revenues, 5 expenses. Credit-natured types are shown as positive figures.
 * Statement of Changes in Equity, Statement of Cash Flows and supporting schedules are not built yet.
 *
 * <p>The ledger has no opening-balance table, so "as at" needs every earlier year. Finished years are summed once
 * into ecis_gl_year_balance (see V009) by {@code POST /balances/build}; the current year is read live.
 */
@RestController
@RequestMapping("/api/financial-statements")
class FinancialStatementController {

    private static final int FIRST_YEAR = 2013;
    private static final int MAX_RANGE_DAYS = 400;
    /** Legacy transaction type 98 (Profit Loss Summ): year-end summary rows, left out as in the trial balance. */
    private static final int PROFIT_LOSS_SUMMARY = 98;

    record Line(String accountCode, String description, BigDecimal amount) {}

    record Group(String name, List<Line> lines, BigDecimal total) {}

    record Section(String name, List<Group> groups, BigDecimal total) {}

    record FinancialCondition(int branchCode, LocalDate asOf, List<Section> sections, BigDecimal totalAssets,
                              BigDecimal totalLiabilitiesAndEquity, boolean balanced) {}

    record Operations(int branchCode, LocalDate from, LocalDate to, List<Section> sections, BigDecimal netSurplus) {}

    private record Acct(String desc, String parent, int type) {}

    private final JdbcTemplate jdbc;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, String> buildState = new ConcurrentHashMap<>();

    FinancialStatementController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- year balances ----

    /** Sums each finished year (FIRST_YEAR up to last year) once, in the background. Safe to call again; built years are skipped. */
    @PostMapping("/balances/build")
    @PreAuthorize("hasRole('ADMIN')")
    Map<String, String> build(@RequestParam int branchCode, Authentication auth) {
        String key = String.valueOf(branchCode);
        if ("running".equals(buildState.get(key))) return Map.of("state", "running");
        buildState.put(key, "running");
        String user = auth.getName();
        worker.submit(() -> {
            try {
                for (int y = FIRST_YEAR; y < LocalDate.now().getYear(); y++) buildYear(branchCode, y, user);
                buildState.put(key, "done");
            } catch (RuntimeException e) {
                buildState.put(key, "failed: " + e.getMessage());
            }
        });
        return Map.of("state", "running");
    }

    @GetMapping("/balances/status")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
    Map<String, Object> status(@RequestParam int branchCode) {
        List<Integer> built = jdbc.queryForList("SELECT tr_year FROM ecis_gl_year_built WHERE branch_code = ? ORDER BY tr_year", Integer.class, branchCode);
        return Map.of("state", buildState.getOrDefault(String.valueOf(branchCode), "idle"), "builtYears", built);
    }

    private void buildYear(int branch, int year, String user) {
        Integer done = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_gl_year_built WHERE branch_code = ? AND tr_year = ?", Integer.class, branch, year);
        if (done != null && done > 0) return;
        List<Object[]> rows = jdbc.query("""
                SELECT GLTransACCT_CODE, SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT) FROM gltrans
                WHERE GLTransBR_CODE = ? AND GLTransTR_DATE BETWEEN ? AND ? GROUP BY GLTransACCT_CODE
                """, (rs, i) -> new Object[]{rs.getString(1), rs.getBigDecimal(2)}, branch, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        for (Object[] r : rows) {
            jdbc.update("INSERT INTO ecis_gl_year_balance (branch_code, tr_year, account_code, net) VALUES (?,?,?,?)", branch, year, r[0], r[1]);
        }
        jdbc.update("INSERT INTO ecis_gl_year_built (branch_code, tr_year, built_by, built_at) VALUES (?,?,?,?)", branch, year, user, LocalDateTime.now());
    }

    // ---- statements ----

    @GetMapping("/financial-condition")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
    FinancialCondition financialCondition(@RequestParam int branchCode, @RequestParam LocalDate asOf) {
        int year = asOf.getYear();
        if (year < FIRST_YEAR) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The ledger starts in " + FIRST_YEAR + ".");
        List<Integer> built = jdbc.queryForList("SELECT tr_year FROM ecis_gl_year_built WHERE branch_code = ?", Integer.class, branchCode);
        for (int y = FIRST_YEAR; y < year; y++) {
            if (!built.contains(y)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Balances for " + y + " are not built yet. An administrator has to start POST /api/financial-statements/balances/build.");
            }
        }
        Map<String, BigDecimal> net = new HashMap<>();
        jdbc.query("SELECT account_code, SUM(net) FROM ecis_gl_year_balance WHERE branch_code = ? AND tr_year < ? GROUP BY account_code",
                (rs) -> { net.merge(rs.getString(1), rs.getBigDecimal(2), BigDecimal::add); }, branchCode, year);
        jdbc.query("""
                SELECT GLTransACCT_CODE, SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT) FROM gltrans
                WHERE GLTransBR_CODE = ? AND GLTransTR_DATE BETWEEN ? AND ? AND GLTransTR_CODE <> ? GROUP BY GLTransACCT_CODE
                """, (rs) -> { net.merge(rs.getString(1), rs.getBigDecimal(2), BigDecimal::add); },
                branchCode, LocalDate.of(year, 1, 1), asOf, PROFIT_LOSS_SUMMARY);

        Map<String, Acct> coa = loadCoa(branchCode);
        Section assets = section("ASSETS", 1, net, coa, false);
        Section liabilities = section("LIABILITIES", 2, net, coa, true);
        Section equity = section("EQUITY", 3, net, coa, true);
        // Current-year surplus is not closed into equity until year end, so it is shown as its own equity line.
        BigDecimal surplus = BigDecimal.ZERO;
        for (var e : net.entrySet()) {
            Acct a = coa.get(e.getKey());
            if (a != null && (a.type() == 4 || a.type() == 5)) surplus = surplus.subtract(e.getValue());
        }
        List<Group> eqGroups = new ArrayList<>(equity.groups());
        eqGroups.add(new Group("Current year surplus", List.of(new Line("", "Surplus for " + year + " to date", surplus)), surplus));
        equity = new Section("EQUITY", eqGroups, equity.total().add(surplus));
        BigDecimal le = liabilities.total().add(equity.total());
        return new FinancialCondition(branchCode, asOf, List.of(assets, liabilities, equity), assets.total(), le, assets.total().compareTo(le) == 0);
    }

    @GetMapping("/operations")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
    Operations operations(@RequestParam int branchCode, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        if (to.isBefore(from)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The end date is before the start date.");
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "range is limited to " + MAX_RANGE_DAYS + " days");
        Map<String, BigDecimal> net = new HashMap<>();
        jdbc.query("""
                SELECT GLTransACCT_CODE, SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT) FROM gltrans
                WHERE GLTransBR_CODE = ? AND GLTransTR_DATE BETWEEN ? AND ? AND GLTransTR_CODE <> ? GROUP BY GLTransACCT_CODE
                """, (rs) -> { net.put(rs.getString(1), rs.getBigDecimal(2)); }, branchCode, from, to, PROFIT_LOSS_SUMMARY);
        Map<String, Acct> coa = loadCoa(branchCode);
        Section revenue = section("REVENUES", 4, net, coa, true);
        Section expenses = section("EXPENSES", 5, net, coa, false);
        return new Operations(branchCode, from, to, List.of(revenue, expenses), revenue.total().subtract(expenses.total()));
    }

    // ---- helpers ----

    private Map<String, Acct> loadCoa(int branch) {
        Map<String, Acct> m = new HashMap<>();
        jdbc.query("SELECT COAID, COADesc, COAacct_sum, COAacct_type FROM coa WHERE COABR_CODE = ?",
                (rs) -> { m.put(rs.getString(1), new Acct(rs.getString(2), rs.getString(3), rs.getInt(4))); }, branch);
        return m;
    }

    /** Groups accounts of one type under their first-level ancestor in the chart; credit-natured types are negated. */
    private static Section section(String name, int type, Map<String, BigDecimal> net, Map<String, Acct> coa, boolean negate) {
        Map<String, List<Line>> groups = new LinkedHashMap<>();
        net.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach((e) -> {
            Acct a = coa.get(e.getKey());
            if (a == null || a.type() != type || e.getValue().signum() == 0) return;
            BigDecimal amount = negate ? e.getValue().negate() : e.getValue();
            groups.computeIfAbsent(groupName(e.getKey(), coa, type), (k) -> new ArrayList<>()).add(new Line(e.getKey(), a.desc(), amount));
        });
        List<Group> out = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var g : groups.entrySet()) {
            BigDecimal t = g.getValue().stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            out.add(new Group(g.getKey(), g.getValue(), t));
            total = total.add(t);
        }
        return new Section(name, out, total);
    }

    /** Walks up COAacct_sum to the account just below the type's top account (e.g. 10 CURRENT ASSETS under 1 ASSETS). */
    private static String groupName(String code, Map<String, Acct> coa, int type) {
        String cur = code;
        String best = code;
        for (int i = 0; i < 10; i++) {
            Acct a = coa.get(cur);
            if (a == null || a.parent() == null || a.parent().isBlank() || a.parent().equals(cur)) break;
            Acct p = coa.get(a.parent());
            if (p == null || p.type() != type) break;
            best = cur;
            cur = a.parent();
        }
        Acct g = coa.get(best);
        return g == null ? "Other" : g.desc();
    }
}
