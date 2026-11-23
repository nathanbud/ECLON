package ph.coop.core.accounting.close;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.db.Db;

/**
 * The month-end review and the year-end checklist (rules in {@link CloseChecklist}): what the application finds in the books for a month or a year, what
 * accountants recorded, and the closing policy (accounting.monthly_close, accounting.year_end_due_days in ecis_setting).
 */
@Component
public class CloseReviews {

    public static final String MONTHLY_CLOSE = "accounting.monthly_close";
    public static final String YEAR_END_DUE_DAYS = "accounting.year_end_due_days";
    public static final int DEFAULT_DUE_DAYS = 120;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final Clock clock;

    CloseReviews(JdbcTemplate jdbc, Db db, Clock clock) {
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
    }

    public boolean stored() {
        return db.allTablesExist("ecis_close_review", "ecis_close_signoff");
    }

    private String setting(String key) {
        if (!db.tableExists("ecis_setting")) return null;
        List<String> v = jdbc.queryForList("SELECT setting_value FROM ecis_setting WHERE setting_key = ?", String.class, key);
        return v.isEmpty() ? null : v.get(0);
    }

    /** Whether signing a month off also closes its accounting period, and so the year-end checklist asks for every month. Off by default. */
    public boolean monthlyClose() {
        return "true".equalsIgnoreCase(setting(MONTHLY_CLOSE));
    }

    /** How many days after 31 December the year-end close is due. */
    public int dueDays() {
        try {
            String v = setting(YEAR_END_DUE_DAYS);
            return v == null ? DEFAULT_DUE_DAYS : Math.max(1, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_DUE_DAYS;
        }
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The first and last day of a period: "2026-03" for a month, "2026" for a year. */
    public static LocalDate[] bounds(String scope, String period) {
        if (CloseChecklist.MONTH.equals(scope)) {
            YearMonth m = YearMonth.parse(period);
            return new LocalDate[] {m.atDay(1), m.atEndOfMonth()};
        }
        int y = Integer.parseInt(period);
        return new LocalDate[] {LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31)};
    }

    public Map<String, CloseChecklist.Tick> ticks(int branch, String scope, String period) {
        Map<String, CloseChecklist.Tick> m = new HashMap<>();
        if (!stored()) return m;
        jdbc.query("SELECT item, note, done_by, done_at FROM ecis_close_review WHERE branch_code = ? AND scope = ? AND period = ? ORDER BY item",
            (rs) -> { m.put(rs.getString(1), new CloseChecklist.Tick(rs.getString(2), rs.getString(3), String.valueOf(rs.getTimestamp(4).toLocalDateTime()))); },
            branch, scope, period);
        return m;
    }

    /** Who signed a month off and when, or null. */
    public String[] signoff(int branch, String scope, String period) {
        if (!stored()) return null;
        List<String[]> r = jdbc.query("SELECT signed_by, signed_at FROM ecis_close_signoff WHERE branch_code = ? AND scope = ? AND period = ?",
            (rs, i) -> new String[] {rs.getString(1), String.valueOf(rs.getTimestamp(2).toLocalDateTime())}, branch, scope, period);
        return r.isEmpty() ? null : r.get(0);
    }

    /** What the application finds in the books for a period. */
    public CloseChecklist.Facts facts(int branch, String scope, String period) {
        LocalDate[] b = bounds(scope, period);
        LocalDate lastMonthStart = YearMonth.from(b[1]).atDay(1);
        BigDecimal net = jdbc.queryForObject("SELECT COALESCE(SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT), 0) FROM gltrans WHERE GLTransBR_CODE = ? AND GLTransTR_DATE BETWEEN ? AND ?",
            BigDecimal.class, branch, b[0], b[1]);
        boolean balanced = net == null || net.signum() == 0;
        int pending = count("SELECT COUNT(*) FROM ecis_journal WHERE branch_code = ? AND journal_date BETWEEN ? AND ? AND status NOT IN ('POSTED', 'CANCELLED', 'REJECTED')",
            "ecis_journal", branch, b[0], b[1])
            + count("SELECT COUNT(*) FROM ecis_memo WHERE branch_code = ? AND memo_date BETWEEN ? AND ? AND status NOT IN ('POSTED', 'CANCELLED', 'REJECTED')",
            "ecis_memo", branch, b[0], b[1]);
        int banks = jdbc.queryForObject("SELECT COUNT(*) FROM bankcode WHERE BCBR_CODE = ? AND BCCOAID IS NOT NULL", Integer.class, branch);
        int reconciled = count("SELECT COUNT(DISTINCT bank_id) FROM ecis_bank_recon WHERE branch_code = ? AND status = 'FINAL' AND statement_date BETWEEN ? AND ?",
            "ecis_bank_recon", branch, lastMonthStart, b[1]);
        int assetsDue = count("""
                SELECT COUNT(*) FROM ecis_asset WHERE branch_code = ? AND in_service_date < ? AND (disposed_on IS NULL OR disposed_on > ?)
                """, "ecis_asset", branch, lastMonthStart, b[1]);
        int depreciated = assetsDue == 0 ? 0 : count("""
                SELECT COUNT(DISTINCT d.asset_id) FROM ecis_asset_depreciation d JOIN ecis_asset a ON a.id = d.asset_id WHERE a.branch_code = ? AND d.period_end BETWEEN ? AND ?
                """, "ecis_asset_depreciation", branch, lastMonthStart, b[1]);
        int open = count("SELECT COUNT(*) FROM ecis_business_day WHERE branch_code = ? AND status = 'OPEN' AND business_date BETWEEN ? AND ?",
            "ecis_business_day", branch, b[0], b[1]);
        Integer signed = null;
        int due = 12;
        if (CloseChecklist.YEAR.equals(scope) && monthlyClose() && stored()) {
            signed = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_close_signoff WHERE branch_code = ? AND scope = 'MONTH' AND period BETWEEN ? AND ?",
                Integer.class, branch, period + "-01", period + "-12");
        }
        return new CloseChecklist.Facts(balanced, net == null ? "0.00" : net.abs().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(), pending, banks, reconciled,
            assetsDue, depreciated, open, signed, due);
    }

    private int count(String sql, String table, Object... args) {
        if (!db.tableExists(table)) return 0;
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    public List<CloseChecklist.Item> items(int branch, String scope, String period) {
        return CloseChecklist.evaluate(scope, facts(branch, scope, period), ticks(branch, scope, period));
    }

    /** How many items of a year's checklist are not settled: the year-end close waits for none. */
    public long yearOpen(int branch, int year) {
        return CloseChecklist.open(items(branch, CloseChecklist.YEAR, String.valueOf(year)));
    }

    void tick(int branch, String scope, String period, String item, String note, String user) {
        Map<String, Object> key = new java.util.LinkedHashMap<>();
        key.put("branch_code", branch);
        key.put("scope", scope);
        key.put("period", period);
        key.put("item", item);
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("note", note == null || note.isBlank() ? null : note.trim());
        values.put("done_by", user);
        values.put("done_at", Timestamp.valueOf(LocalDateTime.now(clock)));
        db.upsert("ecis_close_review", key, values);
    }

    void untick(int branch, String scope, String period, String item) {
        jdbc.update("DELETE FROM ecis_close_review WHERE branch_code = ? AND scope = ? AND period = ? AND item = ?", branch, scope, period, item);
    }

    void sign(int branch, String scope, String period, String note, String user) {
        jdbc.update("INSERT INTO ecis_close_signoff (branch_code, scope, period, signed_by, signed_at, note) VALUES (?, ?, ?, ?, ?, ?)",
            branch, scope, period, user, Timestamp.valueOf(LocalDateTime.now(clock)), note == null || note.isBlank() ? null : note.trim());
    }
}
