package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.db.Db;
import ph.coop.core.deposit.TimeDepositAccrualRun;
import ph.coop.core.frontoffice.HandoverRules.Drawer;
import ph.coop.core.loan.LoanAging;

/**
 * The end-of-day jobs of a business day, run from End Day (and on their own from the Business Day screen) while the day is still open, each step on
 * its own so a step that fails can be run again without redoing the others (ecis_eod_run, ecis_eod_step). See documentation/state/business-day.md.
 *
 * <ol>
 *   <li><b>balances</b>: each drawer's cash, and the balance of every savings, time deposit, share capital, vouchers-payable and loan type, as of the day
 *       (ecis_eod_balance). The first snapshot sums the whole ledger once; later ones add the days since the last.</li>
 *   <li><b>loan_aging</b>: every open loan aged on the day ({@link LoanAging}: owed, past due, interest due, the formula's penalty, the bucket;
 *       ecis_loan_aging). Nothing is posted: the old system charges interest and penalties when a payment is taken.</li>
 *   <li><b>maturities</b>: time deposits matured by the day that still hold money (the Time Deposit maturities list), counted.</li>
 *   <li><b>accruals</b>: the interest runs whose period ended in the last month and are not encoded yet (time deposit Lock In each month end, Short
 *       Term on 31 December, savings each quarter end) are encoded, waiting for approval as when run by hand; a run that cannot be made yet (the
 *       period not over on the calendar, no right to encode it) is noted and tried again at the next End Day.</li>
 *   <li><b>cheques</b>: cheques taken on receipts that are past their expected clearing day and not marked cleared, counted.</li>
 * </ol>
 *
 * The balance, aging, maturity and cheque steps read the day, so End Day always does them again; the accrual step is done once per day.
 */
@Component
public class DayEndJobs {

    private static final Logger log = LoggerFactory.getLogger(DayEndJobs.class);

    public record Step(String key, String label, String status, int attempts, String finishedAt, String result, String error) {}

    public record State(String date, boolean available, String status, String startedBy, String finishedAt, List<Step> steps) {}

    static final Map<String, String> STEPS = new LinkedHashMap<>();
    static {
        STEPS.put("balances", "Balance snapshot");
        STEPS.put("loan_aging", "Loan aging and penalties");
        STEPS.put("maturities", "Time deposit maturities");
        STEPS.put("accruals", "Interest accruals due");
        STEPS.put("cheques", "Cheques past their clearing day");
    }
    /** Steps that read the day as it is now, so they are done again at End Day even when done before. */
    private static final java.util.Set<String> REDONE = java.util.Set.of("balances", "loan_aging", "maturities", "cheques");

    /** The ledger classes kept in the balance snapshot; a loan (12) is a debit balance, the others credit balances. */
    private static final List<Integer> CLASSES = List.of(12, 22, 24, 29, 31);

    private final JdbcTemplate jdbc;
    private final Db db;
    private final Clock clock;
    private final DrawerBalances balances;
    private final LoanAging aging;
    private final TimeDepositMaturityController maturities;
    private final TimeDepositAccrualController tdAccruals;
    private final SavingsInterestController savingsInterest;
    private final TransactionTemplate tx;

    DayEndJobs(JdbcTemplate jdbc, Db db, Clock clock, DrawerBalances balances, LoanAging aging, TimeDepositMaturityController maturities,
               TimeDepositAccrualController tdAccruals, SavingsInterestController savingsInterest, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
        this.balances = balances;
        this.aging = aging;
        this.maturities = maturities;
        this.tdAccruals = tdAccruals;
        this.savingsInterest = savingsInterest;
        this.tx = new TransactionTemplate(txm);
    }

    public boolean available() {
        return db.allTablesExist("ecis_eod_run", "ecis_eod_step", "ecis_eod_balance", "ecis_loan_aging");
    }

    /** What has been done for the day so far (every step NOT RUN when nothing has). */
    public State state(int branch, LocalDate day) {
        if (!available()) return new State(day.toString(), false, "NOT SET UP", null, null, List.of());
        List<Object[]> run = jdbc.query("SELECT id, status, started_by, finished_at FROM ecis_eod_run WHERE branch_code = ? AND business_date = ?",
            (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4)}, branch, java.sql.Date.valueOf(day));
        Map<String, Step> done = new LinkedHashMap<>();
        if (!run.isEmpty()) {
            jdbc.query("SELECT step, status, attempts, finished_at, result, error FROM ecis_eod_step WHERE run_id = ?", (rs) -> {
                Timestamp f = rs.getTimestamp(4);
                done.put(rs.getString(1), new Step(rs.getString(1), STEPS.getOrDefault(rs.getString(1), rs.getString(1)), rs.getString(2), rs.getInt(3), stamp(f), rs.getString(5), rs.getString(6)));
            }, run.get(0)[0]);
        }
        List<Step> steps = new ArrayList<>();
        for (Map.Entry<String, String> e : STEPS.entrySet()) steps.add(done.getOrDefault(e.getKey(), new Step(e.getKey(), e.getValue(), "NOT RUN", 0, null, null, null)));
        if (run.isEmpty()) return new State(day.toString(), true, "NOT RUN", null, null, steps);
        return new State(day.toString(), true, (String) run.get(0)[1], (String) run.get(0)[2], stamp((Timestamp) run.get(0)[3]), steps);
    }

    /**
     * Runs the day's steps that are not done (and, when {@code endOfDay}, again the ones that read the day). One step failing does not stop the others;
     * it is kept as FAILED with its error and done again on the next run. One run per branch at a time.
     */
    public synchronized State run(int branch, LocalDate day, Authentication auth, boolean endOfDay) {
        if (!available()) return state(branch, day);
        long runId = runId(branch, day, auth.getName());
        jdbc.update("UPDATE ecis_eod_run SET status = 'RUNNING', finished_at = NULL WHERE id = ?", runId);
        boolean failed = false;
        for (String key : STEPS.keySet()) {
            Step before = state(branch, day).steps().stream().filter((s) -> s.key().equals(key)).findFirst().orElseThrow();
            if ("DONE".equals(before.status()) && !(endOfDay && REDONE.contains(key))) continue;
            LocalDateTime started = LocalDateTime.now(clock);
            String result = null, error = null;
            try {
                result = switch (key) {
                    case "balances" -> balances(branch, day);
                    case "loan_aging" -> loanAging(branch, day);
                    case "maturities" -> maturities(auth);
                    case "accruals" -> accruals(branch, day, auth);
                    case "cheques" -> cheques(branch, day);
                    default -> throw new IllegalStateException(key);
                };
            } catch (RuntimeException e) {
                log.warn("End-of-day step {} failed for branch {} on {}", key, branch, day, e);
                error = e instanceof ResponseStatusException r && r.getReason() != null ? r.getReason() : String.valueOf(e.getMessage());
                failed = true;
            }
            record(runId, key, error == null ? "DONE" : "FAILED", before.attempts() + 1, started, result, error);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update("UPDATE ecis_eod_run SET status = ?, finished_at = ? WHERE id = ?", failed ? "FAILED" : "DONE", Timestamp.valueOf(now), runId);
        AuditLog.write(jdbc, auth.getName(), failed ? "eod.jobs.failed" : "eod.jobs.done", "ecis_eod_run", branch + "/" + day, null,
            state(branch, day).steps().stream().map((s) -> s.key() + " " + s.status()).reduce((a, b) -> a + ", " + b).orElse(""), null);
        return state(branch, day);
    }

    private long runId(int branch, LocalDate day, String username) {
        List<Long> id = jdbc.queryForList("SELECT id FROM ecis_eod_run WHERE branch_code = ? AND business_date = ?", Long.class, branch, java.sql.Date.valueOf(day));
        if (!id.isEmpty()) return id.get(0);
        return db.insertReturningId("INSERT INTO ecis_eod_run (branch_code, business_date, status, started_by, started_at) VALUES (?, ?, 'RUNNING', ?, ?)", "id",
            branch, java.sql.Date.valueOf(day), username, Timestamp.valueOf(LocalDateTime.now(clock)));
    }

    private void record(long runId, String key, String status, int attempts, LocalDateTime started, String result, String error) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(clock));
        String r = result == null ? null : result.substring(0, Math.min(result.length(), 2000));
        String e = error == null ? null : error.substring(0, Math.min(error.length(), 2000));
        int n = jdbc.update("UPDATE ecis_eod_step SET status = ?, attempts = ?, started_at = ?, finished_at = ?, result = ?, error = ? WHERE run_id = ? AND step = ?",
            status, attempts, Timestamp.valueOf(started), now, r, e, runId, key);
        if (n == 0) jdbc.update("INSERT INTO ecis_eod_step (run_id, step, status, attempts, started_at, finished_at, result, error) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            runId, key, status, attempts, Timestamp.valueOf(started), now, r, e);
    }

    // ---- the steps ------------------------------------------------------------------------------------------------

    /** Drawers and product balances as of the day; the products from the last snapshot plus the days since (or the whole ledger the first time). */
    String balances(int branch, LocalDate day) {
        return tx.execute((st) -> {
            jdbc.update("DELETE FROM ecis_eod_balance WHERE branch_code = ? AND business_date = ?", branch, java.sql.Date.valueOf(day));
            List<Object[]> rows = new ArrayList<>();
            BigDecimal cash = BigDecimal.ZERO;
            for (Map.Entry<Drawer, BigDecimal> d : new TreeMap<String, Map.Entry<Drawer, BigDecimal>>(
                    balances.all(branch).entrySet().stream().collect(java.util.stream.Collectors.toMap((x) -> x.getKey().label(), (x) -> x))).values()) {
                if (d.getValue().signum() == 0) continue;
                rows.add(new Object[] {branch, java.sql.Date.valueOf(day), "DRAWER", d.getKey().label(), d.getKey().label(), d.getValue(), 0});
                cash = cash.add(d.getValue());
            }
            List<java.sql.Date> last = jdbc.queryForList("SELECT MAX(business_date) FROM ecis_eod_balance WHERE branch_code = ? AND kind = 'PRODUCT' AND business_date < ?",
                java.sql.Date.class, branch, java.sql.Date.valueOf(day));
            LocalDate from = last.isEmpty() || last.get(0) == null ? null : last.get(0).toLocalDate();
            Map<String, BigDecimal> amount = new TreeMap<>();
            if (from != null) {
                jdbc.query("SELECT item, amount FROM ecis_eod_balance WHERE branch_code = ? AND business_date = ? AND kind = 'PRODUCT'",
                    (rs) -> { amount.put(rs.getString(1), rs.getBigDecimal(2)); }, branch, java.sql.Date.valueOf(from));
            }
            String in = String.join(", ", CLASSES.stream().map(String::valueOf).toList());
            jdbc.query("SELECT SLC_CODE, SLT_CODE, SUM(AMT) FROM sldtl WHERE SL_BRCODE = ? AND SLE_CODE = 11 AND SLC_CODE IN (" + in + ") AND TR_DATE > ? AND TR_DATE <= ? GROUP BY SLC_CODE, SLT_CODE",
                (rs) -> {
                    int slc = rs.getInt(1);
                    BigDecimal v = slc == 12 ? rs.getBigDecimal(3) : rs.getBigDecimal(3).negate();
                    amount.merge(slc + "/" + rs.getInt(2), v, BigDecimal::add);
                }, branch, java.sql.Date.valueOf(from == null ? LocalDate.of(1900, 1, 1) : from), java.sql.Date.valueOf(day));
            Map<String, String> names = new LinkedHashMap<>();
            jdbc.query("SELECT SLTypeSLC_CODE, SLTypeSLT_CODE, SLTypeM_DESC FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE IN (" + in + ")",
                (rs) -> { names.put(rs.getInt(1) + "/" + rs.getInt(2), rs.getString(3)); }, branch);
            Map<Integer, BigDecimal> perClass = new TreeMap<>();
            for (Map.Entry<String, BigDecimal> e : amount.entrySet()) {
                int slc = Integer.parseInt(e.getKey().split("/")[0]);
                rows.add(new Object[] {branch, java.sql.Date.valueOf(day), "PRODUCT", e.getKey(), names.getOrDefault(e.getKey(), "type " + e.getKey()), e.getValue(), 0});
                perClass.merge(slc, e.getValue(), BigDecimal::add);
            }
            jdbc.batchUpdate("INSERT INTO ecis_eod_balance (branch_code, business_date, kind, item, name, amount, accounts) VALUES (?, ?, ?, ?, ?, ?, ?)", rows);
            StringBuilder s = new StringBuilder("Cash in drawers ").append(peso(cash));
            perClass.forEach((slc, v) -> s.append("; ").append(className(slc)).append(' ').append(peso(v)));
            s.append(from == null ? " (first snapshot: the whole ledger was summed)." : " (from the snapshot of " + from + ").");
            return s.toString();
        });
    }

    /** Every open loan aged on the day, kept for the reports; the buckets and the portfolio at risk in the result. */
    String loanAging(int branch, LocalDate day) {
        List<LoanAging.Aged> aged = aging.age(branch, day);
        return tx.execute((st) -> {
            jdbc.update("DELETE FROM ecis_loan_aging WHERE branch_code = ? AND business_date = ?", branch, java.sql.Date.valueOf(day));
            List<Object[]> rows = new ArrayList<>();
            for (LoanAging.Aged a : aged) {
                rows.add(new Object[] {branch, java.sql.Date.valueOf(day), a.clientId(), a.typeCode(), a.refNo(), a.outstanding(), a.pastDuePrincipal(), a.daysPastDue(),
                    a.pastDueSince() == null ? null : java.sql.Date.valueOf(a.pastDueSince()), a.interestDue(), a.penalty(), a.bucket()});
            }
            jdbc.batchUpdate("""
                    INSERT INTO ecis_loan_aging (branch_code, business_date, client_id, slt_code, ref_no, outstanding, past_due_principal, days_past_due, past_due_since,
                      interest_due, penalty, bucket) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, rows);
            BigDecimal owed = aged.stream().map(LoanAging.Aged::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal atRisk = aged.stream().filter((a) -> a.daysPastDue() > 30).map(LoanAging.Aged::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal penalties = aged.stream().map(LoanAging.Aged::penalty).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, Integer> buckets = new LinkedHashMap<>();
            for (String b : List.of("CURRENT", "1-30", "31-60", "61-90", "91-180", "181-365", "OVER-365")) buckets.put(b, 0);
            aged.forEach((a) -> buckets.merge(a.bucket(), 1, Integer::sum));
            String par = owed.signum() == 0 ? "0" : atRisk.multiply(BigDecimal.valueOf(100)).divide(owed, 2, RoundingMode.HALF_UP).toPlainString();
            return aged.size() + " open loans, " + peso(owed) + " owed; past 30 days " + peso(atRisk) + " (" + par + "% at risk); penalties by the formula " + peso(penalties)
                + ". " + buckets.entrySet().stream().map((e) -> e.getKey().toLowerCase().replace("over-365", "over 365") + ": " + e.getValue()).reduce((a, b) -> a + ", " + b).orElse("");
        });
    }

    String maturities(Authentication auth) {
        TimeDepositMaturityController.Notices n = maturities.maturities(0, auth);
        long matured = n.notices().stream().filter(TimeDepositMaturityController.Notice::overdue).count();
        BigDecimal held = n.notices().stream().filter(TimeDepositMaturityController.Notice::overdue).map(TimeDepositMaturityController.Notice::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        long today = n.notices().stream().filter((x) -> !x.overdue() && x.daysLeft() == 0).count();
        if (matured == 0 && today == 0) return "No time deposit has matured without being paid or renewed.";
        return (today > 0 ? today + " mature today. " : "") + (matured > 0 ? matured + " matured earlier and still hold " + peso(held) + "." : "")
            + " They are paid out or renewed on Front Office > TD Maturities.";
    }

    /** The interest runs whose period ended in the 31 days up to the day, encoded where they are not yet. */
    String accruals(int branch, LocalDate day, Authentication auth) {
        List<String> notes = new ArrayList<>();
        LocalDate monthEnd = day.withDayOfMonth(day.lengthOfMonth()).equals(day) ? day : day.withDayOfMonth(1).minusDays(1);
        if (!monthEnd.isBefore(day.minusDays(31))) {
            notes.add(attempt("Time deposit Lock In accrual to " + monthEnd, () -> tdAccruals.encode(new TimeDepositAccrualController.Encode(TimeDepositAccrualRun.Group.LOCK_IN.name(), monthEnd.toString(), null), auth).size()));
        }
        LocalDate yearEnd = day.getMonth() == Month.DECEMBER && day.getDayOfMonth() == 31 ? day : LocalDate.of(day.getYear() - 1, 12, 31);
        if (!yearEnd.isBefore(day.minusDays(31))) {
            notes.add(attempt("Time deposit Short Term accrual to " + yearEnd, () -> tdAccruals.encode(new TimeDepositAccrualController.Encode(TimeDepositAccrualRun.Group.SHORT_TERM.name(), yearEnd.toString(), null), auth).size()));
        }
        LocalDate quarterEnd = monthEnd;
        while (quarterEnd.getMonthValue() % 3 != 0) quarterEnd = quarterEnd.withDayOfMonth(1).minusDays(1);
        LocalDate q = quarterEnd;
        if (!q.isBefore(day.minusDays(31))) {
            notes.add(attempt("Savings interest to " + q, () -> savingsInterest.encode(new SavingsInterestController.Encode(q.toString()), auth).size()));
        }
        return notes.isEmpty() ? "No interest period ended in the last month." : String.join(" ", notes);
    }

    private interface Encoding { int vouchers(); }

    /** One run: encoded (vouchers waiting for approval), or why not (already encoded, not over on the calendar yet, no right); never a failure of the step. */
    private static String attempt(String what, Encoding e) {
        try {
            int n = e.vouchers();
            return what + ": " + n + (n == 1 ? " voucher" : " vouchers") + " encoded, waiting for approval.";
        } catch (ResponseStatusException x) {
            String why = x.getReason() == null ? x.getStatusCode().toString() : x.getReason();
            if (why.contains("already")) return what + ": already encoded.";
            if (why.startsWith("No ")) return what + ": nothing to encode (" + why + ")";
            return what + ": not encoded now (" + why + ") It is tried again at the next End Day.";
        }
    }

    /** Cheques taken on receipts, not marked cleared, whose expected clearing day (receipt date, the kind's days and two) has passed. */
    String cheques(int branch, LocalDate day) {
        int[] late = {0};
        BigDecimal[] total = {BigDecimal.ZERO};
        jdbc.query("""
                SELECT t.TR_DATE, COALESCE(c.CKCLRG_DAYS, 0), c.CKAMT FROM transck c
                JOIN transsum t ON t.TR_BRCODE = c.CKTR_BRCODE AND t.TR_YEAR = c.CKTR_YEAR AND t.TR_MODULE = c.CKTR_MODULE AND t.TR_CODE = c.CKTR_CODE AND t.TR_CTLNO = c.CKTR_CTLNO
                WHERE c.CKTR_BRCODE = ? AND c.CKTR_YEAR >= ? AND c.CKTR_CODE = 10 AND c.CKCOCI_TYPE = 2 AND COALESCE(c.CKISCLEARED, 0) = 0
                """, (rs) -> {
            if (ChequeClearingController.clearsOn(rs.getDate(1).toLocalDate(), rs.getInt(2)).isBefore(day)) {
                late[0]++;
                total[0] = total[0].add(rs.getBigDecimal(3));
            }
        }, branch, day.getYear() - 1);
        if (late[0] == 0) return "No cheque is past its clearing day.";
        return late[0] + (late[0] == 1 ? " cheque" : " cheques") + " (" + peso(total[0]) + ") passed the expected clearing day without being marked cleared. Ask the bank, then mark them on Cash Handover > Cheque clearing.";
    }

    private static String className(int slc) {
        return switch (slc) {
            case 12 -> "loans owed";
            case 22 -> "savings";
            case 24 -> "time deposits";
            case 29 -> "vouchers payable";
            case 31 -> "share capital";
            default -> "class " + slc;
        };
    }

    private static String peso(BigDecimal v) {
        return new java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ENGLISH)).format(v.setScale(2, RoundingMode.HALF_UP));
    }

    private static String stamp(Timestamp t) {
        return t == null ? null : t.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }
}
