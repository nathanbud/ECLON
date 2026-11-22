package ph.coop.core.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import ph.coop.core.accounting.close.ClosedBooks;
import ph.coop.core.connection.DatabaseChanges;
import ph.coop.core.db.Db;
import ph.coop.core.organization.UnitRules;
import ph.coop.core.organization.Units;

/**
 * Read-only financial statements over the legacy ledger (guide step 6.7): the Statement of Financial Condition
 * (as at a date) and the Statement of Operations (over a date range). Account types come from coa.COAacct_type:
 * 1 assets, 2 liabilities, 3 equity, 4 revenues, 5 expenses. Credit-natured types are shown as positive figures.
 * Statement of Changes in Equity, Statement of Cash Flows and supporting schedules are not built yet.
 *
 * <p>By unit: with {@code department}, a statement covers that unit (a main office, branch, satellite office or consolidation) and every unit under it in the
 * department tree, so a consolidation is the sum of its units; without it, the whole branch's books. {@code /consolidating} shows the units directly under one
 * side by side, the entries posted to the consolidation itself (its adjustments and eliminations) and the consolidated total. Units in {@link Units}.
 *
 * <p>The ledger has no opening-balance table, so "as at" needs every earlier year. Finished years are summed once
 * into ecis_gl_year_balance (see V009) and, per department, ecis_gl_year_dept_balance (V038), by {@code POST /balances/build}; the current year is read live.
 */
@RestController
@RequestMapping("/api/financial-statements")
class FinancialStatementController {

    private static final int FIRST_YEAR = 2013;
    private static final int MAX_RANGE_DAYS = 400;
    /** Legacy transaction type 98 (Profit Loss Summ): one-sided running rows on the net surplus for the period account, left out as in the trial balance. */
    private static final int PROFIT_LOSS_SUMMARY = ClosedBooks.PROFIT_LOSS_SUMMARY;
    /** Legacy transaction type 99: the year-end Closing Entry, dated 31 December. */
    private static final int CLOSING_ENTRY = ClosedBooks.CLOSING_ENTRY;

    record Line(String accountCode, String description, BigDecimal amount) {}

    record Group(String name, List<Line> lines, BigDecimal total) {}

    record Section(String name, List<Group> groups, BigDecimal total) {}

    /** The unit a statement is for: its number, name and kind, and how many units it covers (itself included). Null for the whole branch. */
    record UnitInfo(int id, String name, String kind, String kindName, int units) {}

    record FinancialCondition(int branchCode, LocalDate asOf, UnitInfo unit, List<Section> sections, BigDecimal totalAssets,
                              BigDecimal totalLiabilitiesAndEquity, boolean balanced) {}

    record Operations(int branchCode, LocalDate from, LocalDate to, UnitInfo unit, List<Section> sections, BigDecimal netSurplus) {}

    /** A unit choice for the statements: the department tree with each unit's kind. */
    record UnitChoice(int id, String name, Integer parent, String kind, String kindName, boolean kindChosen, boolean disabled, int children) {}

    private record Acct(String desc, String parent, int type) {}

    private final JdbcTemplate jdbc;
    private final ClosedBooks books;
    private final Units units;
    private final Db db;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, String> buildState = new ConcurrentHashMap<>();

    FinancialStatementController(JdbcTemplate jdbc, ClosedBooks books, Units units, Db db) {
        this.jdbc = jdbc;
        this.books = books;
        this.units = units;
        this.db = db;
    }

    // ---- year balances ----

    /** Sums each finished year (FIRST_YEAR up to last year) once, in the background, for the whole branch and per department. Safe to call again; built years are skipped. */
    @PostMapping("/balances/build")
    @PreAuthorize("hasRole('ADMIN')")
    Map<String, String> build(@RequestParam int branchCode, Authentication auth) {
        String key = String.valueOf(branchCode);
        if ("running".equals(buildState.get(key))) return Map.of("state", "running");
        buildState.put(key, "running");
        String user = auth.getName();
        boolean perUnit = deptBalancesStored();
        worker.submit(() -> {
            try {
                for (int y = FIRST_YEAR; y < LocalDate.now().getYear(); y++) {
                    buildYear(branchCode, y, user);
                    if (perUnit) buildDeptYear(branchCode, y, user);
                }
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
        List<Integer> byUnit = deptBalancesStored()
            ? jdbc.queryForList("SELECT tr_year FROM ecis_gl_year_dept_built WHERE branch_code = ? ORDER BY tr_year", Integer.class, branchCode) : List.of();
        return Map.of("state", buildState.getOrDefault(String.valueOf(branchCode), "idle"), "builtYears", built, "builtYearsByUnit", byUnit);
    }

    private boolean deptBalancesStored() {
        return db.allTablesExist("ecis_gl_year_dept_balance", "ecis_gl_year_dept_built");
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

    /** The year summed per department and account, once. */
    private void buildDeptYear(int branch, int year, String user) {
        Integer done = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_gl_year_dept_built WHERE branch_code = ? AND tr_year = ?", Integer.class, branch, year);
        if (done != null && done > 0) return;
        List<Object[]> rows = jdbc.query("""
                SELECT COALESCE(GLTransDEPT, 0), GLTransACCT_CODE, SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT) FROM gltrans
                WHERE GLTransBR_CODE = ? AND GLTransTR_DATE BETWEEN ? AND ? GROUP BY COALESCE(GLTransDEPT, 0), GLTransACCT_CODE
                ORDER BY 1, 2
                """, (rs, i) -> new Object[]{rs.getInt(1), rs.getString(2), rs.getBigDecimal(3)}, branch, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        jdbc.batchUpdate("INSERT INTO ecis_gl_year_dept_balance (branch_code, tr_year, dept, account_code, net) VALUES (?,?,?,?,?)",
            rows.stream().filter((r) -> ((BigDecimal) r[2]).signum() != 0).map((r) -> new Object[]{branch, year, r[0], r[1], r[2]}).toList());
        jdbc.update("INSERT INTO ecis_gl_year_dept_built (branch_code, tr_year, built_by, built_at) VALUES (?,?,?,?)", branch, year, user, LocalDateTime.now());
    }

    // ---- units ----

    @GetMapping("/units")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
    List<UnitChoice> unitChoices() {
        return units.all().stream().map((u) -> new UnitChoice(u.id(), u.name(), u.parent(), u.kind(), u.kindName(), u.kindChosen(), u.disabled(), u.children())).toList();
    }

    /** The unit and the departments it covers, or null for the whole branch. */
    private record Scope(UnitInfo unit, Set<Integer> depts) {}

    private Scope scope(Integer department) {
        if (department == null) return new Scope(null, null);
        Units.Unit u = units.find(department).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Department " + department + " is not in this database, so no statement was made. Choose the unit again from the list."));
        Set<Integer> depts = units.subtree(department);
        return new Scope(new UnitInfo(u.id(), u.name(), u.kind(), u.kindName(), depts.size()), depts);
    }

    // ---- statements ----

    @GetMapping("/financial-condition")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
    FinancialCondition financialCondition(@RequestParam int branchCode, @RequestParam LocalDate asOf, @RequestParam(required = false) Integer department) {
        Scope s = scope(department);
        return condition(branchCode, asOf, s.unit(), netAsOf(branchCode, asOf, s.depts()));
    }

    @GetMapping("/operations")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
    Operations operations(@RequestParam int branchCode, @RequestParam LocalDate from, @RequestParam LocalDate to, @RequestParam(required = false) Integer department) {
        checkRange(from, to);
        Scope s = scope(department);
        return operations(branchCode, from, to, s.unit(), netRange(branchCode, from, to, s.depts()));
    }

    /**
     * A consolidating statement for a unit with units under it: one column per unit directly under it (each with everything under that unit), a column for the
     * entries posted to the consolidation itself when there are any (its adjustments and eliminations), and the consolidated total, which equals the unit's
     * own statement. {@code statement} is {@code financial-condition} (with {@code asOf}) or {@code operations} (with {@code from} and {@code to}).
     */
    @GetMapping("/consolidating")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
    StatementColumns.Consolidating consolidating(@RequestParam int branchCode, @RequestParam String statement, @RequestParam int department,
                                                 @RequestParam(required = false) LocalDate asOf, @RequestParam(required = false) LocalDate from,
                                                 @RequestParam(required = false) LocalDate to) {
        boolean fc = "financial-condition".equals(statement);
        if (!fc && !"operations".equals(statement)) throw bad("Choose the statement: financial-condition or operations.");
        if (fc && asOf == null) throw bad("Give the date the statement is as at (asOf), so it can be made.");
        if (!fc && (from == null || to == null)) throw bad("Give the first and last day of the period (from and to), so the statement can be made.");
        if (!fc) checkRange(from, to);
        Scope whole = scope(department);
        List<Units.Unit> all = units.all();
        List<UnitRules.Dept> tree = units.tree();
        List<UnitRules.Dept> kids = UnitRules.children(department, tree);
        if (kids.isEmpty()) {
            throw bad(whole.unit().name() + " has no units under it, so there is nothing to show side by side. Open its own statement instead, or put its branches and "
                + "satellite offices under it in Settings > Organization > Departments.");
        }
        Map<Integer, Units.Unit> byId = new HashMap<>();
        for (Units.Unit u : all) byId.put(u.id(), u);
        List<StatementColumns.Column> columns = new ArrayList<>();
        List<List<Section>> perColumn = new ArrayList<>();
        for (UnitRules.Dept d : kids) {
            Set<Integer> depts = UnitRules.subtree(d.id(), tree);
            Units.Unit u = byId.get(d.id());
            columns.add(new StatementColumns.Column(d.id(), d.name(), u.kind(), u.kindName(), false));
            perColumn.add(fc ? condition(branchCode, asOf, null, netAsOf(branchCode, asOf, depts)).sections()
                : operations(branchCode, from, to, null, netRange(branchCode, from, to, depts)).sections());
        }
        Set<Integer> own = Set.of(department);
        Map<String, BigDecimal> ownNet = fc ? netAsOf(branchCode, asOf, own) : netRange(branchCode, from, to, own);
        if (ownNet.values().stream().anyMatch((v) -> v.signum() != 0)) {
            columns.add(new StatementColumns.Column(department, "Entries of " + whole.unit().name(), whole.unit().kind(), "Adjustments and eliminations", true));
            perColumn.add(fc ? condition(branchCode, asOf, null, ownNet).sections() : operations(branchCode, from, to, null, ownNet).sections());
        }
        return StatementColumns.merge(branchCode, fc ? "financial-condition" : "operations", asOf, from, to,
            new StatementColumns.Column(whole.unit().id(), whole.unit().name(), whole.unit().kind(), whole.unit().kindName(), false), columns, perColumn);
    }

    // ---- the figures ----

    private static void checkRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw bad("The end date is before the start date.");
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) throw bad("A Statement of Operations covers at most " + MAX_RANGE_DAYS + " days, so none was made. Choose a shorter period, such as one year.");
    }

    /** Net per account as at a date: finished years from the stored balances, the current year live; {@code depts} null for the whole branch. */
    private Map<String, BigDecimal> netAsOf(int branchCode, LocalDate asOf, Collection<Integer> depts) {
        int year = asOf.getYear();
        if (year < FIRST_YEAR) throw bad("The ledger starts in " + FIRST_YEAR + ".");
        if (depts != null && !deptBalancesStored()) throw new ResponseStatusException(HttpStatus.CONFLICT, DatabaseChanges.notSetUp("Statements for a single unit or consolidation"));
        String builtTable = depts == null ? "ecis_gl_year_built" : "ecis_gl_year_dept_built";
        List<Integer> built = jdbc.queryForList("SELECT tr_year FROM " + builtTable + " WHERE branch_code = ?", Integer.class, branchCode);
        for (int y = FIRST_YEAR; y < year; y++) {
            if (!built.contains(y)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The balances for " + y + (depts == null ? "" : " by unit") + " are not built yet, so this statement "
                    + "cannot be made. An administrator presses Build balances on Accounting > Statements (it runs in the background, a few minutes per year).");
            }
        }
        Map<String, BigDecimal> net = new HashMap<>();
        if (depts == null) {
            jdbc.query("SELECT account_code, SUM(net) FROM ecis_gl_year_balance WHERE branch_code = ? AND tr_year < ? GROUP BY account_code ORDER BY account_code",
                (rs) -> { net.merge(rs.getString(1), rs.getBigDecimal(2), BigDecimal::add); }, branchCode, year);
        } else {
            List<Object> args = new ArrayList<>(List.of(branchCode, year));
            args.addAll(depts);
            jdbc.query("SELECT account_code, SUM(net) FROM ecis_gl_year_dept_balance WHERE branch_code = ? AND tr_year < ? AND dept IN (" + marks(depts) + ") GROUP BY account_code ORDER BY account_code",
                (rs) -> { net.merge(rs.getString(1), rs.getBigDecimal(2), BigDecimal::add); }, args.toArray());
        }
        // A closed year's Closing Entry is included (income and expenses end at zero, the surplus sits in 30198), except its one-sided rows
        // on the net surplus for the period account, which only cancel the type 98 rows left out here.
        List<Object> args = new ArrayList<>(List.of(branchCode, LocalDate.of(year, 1, 1), asOf, PROFIT_LOSS_SUMMARY, CLOSING_ENTRY, books.profitLossAccount(branchCode)));
        if (depts != null) args.addAll(depts);
        jdbc.query("""
                SELECT GLTransACCT_CODE, SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT) FROM gltrans
                WHERE GLTransBR_CODE = ? AND GLTransTR_DATE BETWEEN ? AND ? AND GLTransTR_CODE <> ?
                  AND NOT (GLTransTR_CODE = ? AND GLTransACCT_CODE = ?)""" + deptFilter(depts) + " GROUP BY GLTransACCT_CODE ORDER BY GLTransACCT_CODE",
                (rs) -> { net.merge(rs.getString(1), rs.getBigDecimal(2), BigDecimal::add); }, args.toArray());
        return net;
    }

    /** Net per account over a period, leaving out the Closing Entry (it zeroes income and expenses on 31 December, which would hide the year's results). */
    private Map<String, BigDecimal> netRange(int branchCode, LocalDate from, LocalDate to, Collection<Integer> depts) {
        Map<String, BigDecimal> net = new HashMap<>();
        List<Object> args = new ArrayList<>(List.of(branchCode, from, to, PROFIT_LOSS_SUMMARY, CLOSING_ENTRY));
        if (depts != null) args.addAll(depts);
        jdbc.query("""
                SELECT GLTransACCT_CODE, SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT) FROM gltrans
                WHERE GLTransBR_CODE = ? AND GLTransTR_DATE BETWEEN ? AND ? AND GLTransTR_CODE NOT IN (?, ?)""" + deptFilter(depts) + " GROUP BY GLTransACCT_CODE ORDER BY GLTransACCT_CODE",
                (rs) -> { net.put(rs.getString(1), rs.getBigDecimal(2)); }, args.toArray());
        return net;
    }

    private static String deptFilter(Collection<Integer> depts) {
        return depts == null ? "" : " AND COALESCE(GLTransDEPT, 0) IN (" + marks(depts) + ")";
    }

    private static String marks(Collection<Integer> depts) {
        return String.join(", ", java.util.Collections.nCopies(Math.max(1, depts.size()), "?"));
    }

    private FinancialCondition condition(int branchCode, LocalDate asOf, UnitInfo unit, Map<String, BigDecimal> net) {
        int year = asOf.getYear();
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
        return new FinancialCondition(branchCode, asOf, unit, List.of(assets, liabilities, equity), assets.total(), le, assets.total().compareTo(le) == 0);
    }

    private Operations operations(int branchCode, LocalDate from, LocalDate to, UnitInfo unit, Map<String, BigDecimal> net) {
        Map<String, Acct> coa = loadCoa(branchCode);
        Section revenue = section("REVENUES", 4, net, coa, true);
        Section expenses = section("EXPENSES", 5, net, coa, false);
        return new Operations(branchCode, from, to, unit, List.of(revenue, expenses), revenue.total().subtract(expenses.total()));
    }

    // ---- helpers ----

    private static ResponseStatusException bad(String why) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, why);
    }

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
