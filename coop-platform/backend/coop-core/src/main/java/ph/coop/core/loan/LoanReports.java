package ph.coop.core.loan;

import static ph.coop.core.report.Reports.date;
import static ph.coop.core.report.Reports.dateParam;
import static ph.coop.core.report.Reports.money;
import static ph.coop.core.report.Reports.number;
import static ph.coop.core.report.Reports.numberParam;
import static ph.coop.core.report.Reports.of;
import static ph.coop.core.report.Reports.percent;
import static ph.coop.core.report.Reports.pct;
import static ph.coop.core.report.Reports.row;
import static ph.coop.core.report.Reports.text;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.loan.LoanRules.Arrears;
import ph.coop.core.loan.LoanRules.Due;
import ph.coop.core.report.Reports.Provider;
import ph.coop.core.report.Reports.Report;
import ph.coop.core.report.Reports.Table;

/**
 * Loan reports (guide phase 20): the portfolio and outstanding balance, status, portfolio at risk by days late, the delinquent loans, maturity, releases, payments and collection.
 * They read the loan subsidiary ledger (class 12: event 11 principal, 23 interest, 41 penalty) and the repayment schedule; arrears are principal only, the way the loan screens
 * work them out. They scan the whole loan ledger, so a run takes several seconds.
 */
@Component
class LoanReports implements Provider {

    private static final int BRANCH = 1, CLASS = 12, LIST_LIMIT = 500;
    private static final Set<String> NONE = Set.of();
    private static final Map<Integer, String> STATUS = Map.of(11, "Current", 12, "Past due", 13, "In litigation", 14, "Restructured");
    private static final int[] BOUNDS = {0, 30, 60, 90, 180, 365};
    private static final String[] BUCKETS = {"Not late", "1 to 30 days", "31 to 60 days", "61 to 90 days", "91 to 180 days", "181 to 365 days", "Over 365 days"};

    record Key(int client, int type, int ref) {}

    /** One loan with a balance on the report date. */
    record Pos(Key key, BigDecimal balance, BigDecimal pastDue, long daysLate, LocalDate oldest, int status, LocalDate maturity) {}

    private record Cached(LocalDate asOf, long at, List<Pos> positions) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile Cached cache;

    LoanReports(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Index of the arrears bucket for a number of days late. */
    static int bucket(long daysLate) {
        if (daysLate <= 0) return 0;
        for (int i = 1; i < BOUNDS.length; i++) if (daysLate <= BOUNDS[i]) return i;
        return BUCKETS.length - 1;
    }

    private Map<Integer, String> typeNames() {
        Map<Integer, String> out = new HashMap<>();
        jdbc.query("SELECT SLTypeSLT_CODE, SLTypeM_DESC FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ?", (rs) -> { out.put(rs.getInt(1), rs.getString(2)); }, BRANCH, CLASS);
        return out;
    }

    private static String typeName(Map<Integer, String> names, int type) {
        return names.getOrDefault(type, "Type " + type);
    }

    /** Every loan with principal outstanding on {@code asOf}, with how much is past due and for how long (the schedule is read only when {@code withArrears}). */
    private List<Pos> positions(LocalDate asOf, boolean withArrears) {
        Cached c = cache;
        if (withArrears && c != null && c.asOf().equals(asOf) && System.currentTimeMillis() - c.at() < 300_000) return c.positions();
        Map<Key, BigDecimal[]> ledger = new HashMap<>();
        jdbc.query("""
                SELECT SL_CLIENTID, SLT_CODE, REF_NO, SUM(AMT), SUM(CASE WHEN AMT > 0 THEN AMT ELSE 0 END) FROM sldtl
                WHERE SL_BRCODE = ? AND SLC_CODE = ? AND SLE_CODE = 11 AND TR_DATE <= ? GROUP BY SL_CLIENTID, SLT_CODE, REF_NO HAVING SUM(AMT) > 0.004
                """, (rs) -> { ledger.put(new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3)), new BigDecimal[] {rs.getBigDecimal(4), rs.getBigDecimal(5)}); }, BRANCH, CLASS, asOf);
        Map<Key, Object[]> info = new HashMap<>();
        jdbc.query("SELECT ClientIDLoan, LoanSLT_CODE, LoanREF_NO, LoanSTATUS, LoanMAT_DATE FROM loan WHERE LoanBR_CODE = ? AND LoanSLC_CODE = ?",
            (rs) -> { info.put(new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3)), new Object[] {rs.getInt(4), rs.getDate(5) == null ? null : rs.getDate(5).toLocalDate()}); }, BRANCH, CLASS);
        Map<Key, List<Due>> dues = new HashMap<>();
        if (withArrears) {
            jdbc.query("SELECT CLIENTID, SLT_CODE, REF_NO, DUEDATE, COALESCE(PRINDUE, 0), COALESCE(INTDUE, 0) FROM amortsched WHERE BR_CODE = ? AND SLC_CODE = ? ORDER BY CLIENTID, SLT_CODE, REF_NO, DUEDATE", (rs) -> {
                Key k = new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3));
                if (ledger.containsKey(k)) dues.computeIfAbsent(k, x -> new ArrayList<>()).add(new Due(rs.getDate(4).toLocalDate(), rs.getBigDecimal(5), rs.getBigDecimal(6)));
            }, BRANCH, CLASS);
        }
        List<Pos> out = new ArrayList<>();
        for (Map.Entry<Key, BigDecimal[]> e : ledger.entrySet()) {
            Object[] i = info.get(e.getKey());
            BigDecimal paid = e.getValue()[1].subtract(e.getValue()[0]);
            Arrears ar = withArrears ? LoanRules.arrears(dues.getOrDefault(e.getKey(), List.of()), paid, asOf) : new Arrears(BigDecimal.ZERO, 0, null);
            out.add(new Pos(e.getKey(), e.getValue()[0], ar.pastDuePrincipal(), ar.daysPastDue(), ar.oldestUnpaidDate(), i == null ? 0 : (Integer) i[0], i == null ? null : (LocalDate) i[1]));
        }
        if (withArrears) cache = new Cached(asOf, System.currentTimeMillis(), out);
        return out;
    }

    private Map<Integer, String> names(List<Pos> list) {
        Map<Integer, String> out = new HashMap<>();
        List<Integer> ids = list.stream().map((p) -> p.key().client()).distinct().toList();
        for (int from = 0; from < ids.size(); from += 400) {
            List<Integer> part = ids.subList(from, Math.min(ids.size(), from + 400));
            String marks = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(BRANCH);
            args.addAll(part);
            jdbc.query("SELECT ClientID, COALESCE(NULLIF(AccountName, ''), CONCAT_WS(', ', LName, FName)) FROM client WHERE ClientIDBrCode = ? AND ClientID IN (" + marks + ")", (rs) -> { out.put(rs.getInt(1), rs.getString(2)); }, args.toArray());
        }
        return out;
    }

    @Override
    public List<Report> reports() {
        LocalDate today = LocalDate.now(clock);
        var asOfParam = List.of(dateParam("asOf", "As of", today));
        var period = List.of(dateParam("from", "From", today.withDayOfMonth(1)), dateParam("to", "To", today));
        return List.of(
            of("loan-portfolio", "Loans", "Loan portfolio and outstanding balance", "The loans with principal outstanding, by loan type.", asOfParam, NONE, (a) -> {
                LocalDate asOf = a.date("asOf", today);
                List<Pos> list = positions(asOf, false);
                Map<Integer, String> names = typeNames();
                Map<Integer, long[]> count = new HashMap<>();
                Map<Integer, BigDecimal> sum = new HashMap<>();
                for (Pos p : list) { count.computeIfAbsent(p.key().type(), k -> new long[1])[0]++; sum.merge(p.key().type(), p.balance(), BigDecimal::add); }
                BigDecimal total = sum.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                List<List<Object>> rows = new ArrayList<>();
                sum.entrySet().stream().sorted(Map.Entry.<Integer, BigDecimal>comparingByValue().reversed()).forEach((e) ->
                    rows.add(row(typeName(names, e.getKey()), count.get(e.getKey())[0], e.getValue(), pct(e.getValue(), total), e.getValue().divide(BigDecimal.valueOf(count.get(e.getKey())[0]), 2, java.math.RoundingMode.HALF_UP))));
                return new Table(List.of(text("type", "Loan type"), number("loans", "Loans"), money("outstanding", "Outstanding principal"), percent("share", "Share"), money("average", "Average balance")), rows,
                    row("Total", list.size(), total, new BigDecimal("100.00"), list.isEmpty() ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(list.size()), 2, java.math.RoundingMode.HALF_UP)), "Principal only (ledger event 11), as of " + asOf + ".");
            }),
            of("loan-status", "Loans", "Loans by status", "The loans with a balance by the status recorded on the loan (current, past due, in litigation, restructured).", asOfParam, NONE, (a) -> {
                LocalDate asOf = a.date("asOf", today);
                List<Pos> list = positions(asOf, false);
                Map<Integer, long[]> count = new LinkedHashMap<>();
                Map<Integer, BigDecimal> sum = new LinkedHashMap<>();
                for (Pos p : list) { count.computeIfAbsent(p.status(), k -> new long[1])[0]++; sum.merge(p.status(), p.balance(), BigDecimal::add); }
                BigDecimal total = sum.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                List<List<Object>> rows = new ArrayList<>();
                sum.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach((e) -> rows.add(row(STATUS.getOrDefault(e.getKey(), "Status " + e.getKey()), count.get(e.getKey())[0], e.getValue(), pct(e.getValue(), total))));
                return new Table(List.of(text("status", "Status"), number("loans", "Loans"), money("outstanding", "Outstanding principal"), percent("share", "Share")), rows, row("Total", list.size(), total, new BigDecimal("100.00")),
                    "The status is the one stored on the loan; the next report works lateness out from the repayment schedule.");
            }),
            of("loan-par", "Loans", "Portfolio at risk (PAR)", "Outstanding principal by how many days the oldest unpaid instalment is late.", asOfParam, NONE, (a) -> {
                LocalDate asOf = a.date("asOf", today);
                List<Pos> list = positions(asOf, true);
                long[] loans = new long[BUCKETS.length];
                BigDecimal[] bal = new BigDecimal[BUCKETS.length], past = new BigDecimal[BUCKETS.length];
                for (int i = 0; i < BUCKETS.length; i++) { bal[i] = BigDecimal.ZERO; past[i] = BigDecimal.ZERO; }
                BigDecimal total = BigDecimal.ZERO, atRisk = BigDecimal.ZERO;
                for (Pos p : list) {
                    int b = bucket(p.daysLate());
                    loans[b]++;
                    bal[b] = bal[b].add(p.balance());
                    past[b] = past[b].add(p.pastDue());
                    total = total.add(p.balance());
                    if (p.daysLate() > 30) atRisk = atRisk.add(p.balance());
                }
                List<List<Object>> rows = new ArrayList<>();
                for (int i = 0; i < BUCKETS.length; i++) rows.add(row(BUCKETS[i], loans[i], bal[i], past[i], pct(bal[i], total)));
                BigDecimal pastTotal = past[0];
                for (int i = 1; i < BUCKETS.length; i++) pastTotal = pastTotal.add(past[i]);
                return new Table(List.of(text("bucket", "Days late"), number("loans", "Loans"), money("outstanding", "Outstanding principal"), money("pastDue", "Past-due principal"), percent("share", "Share of portfolio")), rows,
                    row("Total", list.size(), total, pastTotal, new BigDecimal("100.00")), "PAR over 30 days: " + pct(atRisk, total) + "% of the portfolio (" + atRisk.toPlainString() + " of " + total.toPlainString() + "). Lateness is that of the oldest unpaid instalment.");
            }),
            of("loan-delinquent", "Loans", "Delinquent loans", "The loans that are late, worst first, with the member and how much is past due.",
                List.of(dateParam("asOf", "As of", today), numberParam("minDays", "Late at least (days)", "1")), NONE, (a) -> {
                    LocalDate asOf = a.date("asOf", today);
                    int min = a.integer("minDays", 1, 1, 3650);
                    List<Pos> late = new ArrayList<>(positions(asOf, true).stream().filter((p) -> p.daysLate() >= min).toList());
                    late.sort(Comparator.comparingLong(Pos::daysLate).reversed().thenComparing(Comparator.comparing(Pos::pastDue).reversed()));
                    int total = late.size();
                    BigDecimal bal = late.stream().map(Pos::balance).reduce(BigDecimal.ZERO, BigDecimal::add), past = late.stream().map(Pos::pastDue).reduce(BigDecimal.ZERO, BigDecimal::add);
                    List<Pos> shown = late.subList(0, Math.min(total, LIST_LIMIT));
                    Map<Integer, String> member = names(shown), types = typeNames();
                    List<List<Object>> rows = new ArrayList<>();
                    for (Pos p : shown) rows.add(row(p.key().client(), member.getOrDefault(p.key().client(), ""), typeName(types, p.key().type()), p.key().ref(), p.balance(), p.pastDue(), p.daysLate(), p.oldest() == null ? null : p.oldest().toString(), STATUS.getOrDefault(p.status(), "")));
                    return new Table(List.of(number("client", "Client no."), text("name", "Member"), text("type", "Loan type"), number("ref", "Loan"), money("outstanding", "Outstanding"), money("pastDue", "Past due"), number("days", "Days late"), date("since", "Oldest unpaid since"), text("status", "Status")),
                        rows, row("All " + total + " late loans", null, null, null, bal, past, null, null, null), total > LIST_LIMIT ? "Only the " + LIST_LIMIT + " latest-running loans are listed; raise the minimum days to narrow it." : null);
                }),
            of("loan-maturity", "Loans", "Loans maturing", "Loans with a balance whose final instalment falls in the period.",
                List.of(dateParam("from", "From", today), dateParam("to", "To", today.plusDays(90))), NONE, (a) -> {
                    LocalDate[] p = a.period(today, today.plusDays(90), 1500);
                    List<Pos> due = positions(today, false).stream().filter((x) -> x.maturity() != null && !x.maturity().isBefore(p[0]) && !x.maturity().isAfter(p[1])).sorted(Comparator.comparing(Pos::maturity)).toList();
                    Map<Integer, String> member = names(due.subList(0, Math.min(due.size(), LIST_LIMIT))), types = typeNames();
                    List<List<Object>> rows = new ArrayList<>();
                    for (Pos x : due.subList(0, Math.min(due.size(), LIST_LIMIT))) rows.add(row(x.maturity().toString(), x.key().client(), member.getOrDefault(x.key().client(), ""), typeName(types, x.key().type()), x.key().ref(), x.balance()));
                    BigDecimal total = due.stream().map(Pos::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
                    return new Table(List.of(date("maturity", "Matures"), number("client", "Client no."), text("name", "Member"), text("type", "Loan type"), number("ref", "Loan"), money("outstanding", "Outstanding")), rows,
                        row("All " + due.size() + " loans", null, null, null, null, total), "Balances are as of today.");
                }),
            of("loan-releases", "Loans", "Loan releases", "Money lent in the period, by loan type (debits to loan principal).", period, NONE, (a) -> {
                LocalDate[] p = a.period(today.withDayOfMonth(1), today, 1500);
                Map<Integer, String> types = typeNames();
                List<List<Object>> rows = new ArrayList<>();
                BigDecimal[] t = {BigDecimal.ZERO};
                long[] n = {0};
                jdbc.query("SELECT SLT_CODE, COUNT(*), SUM(AMT) FROM sldtl WHERE SL_BRCODE = ? AND SLC_CODE = ? AND SLE_CODE = 11 AND AMT > 0 AND TR_DATE >= ? AND TR_DATE <= ? GROUP BY SLT_CODE ORDER BY SUM(AMT) DESC",
                    (rs) -> { n[0] += rs.getLong(2); t[0] = t[0].add(rs.getBigDecimal(3)); rows.add(row(typeName(types, rs.getInt(1)), rs.getLong(2), rs.getBigDecimal(3), rs.getBigDecimal(3).divide(BigDecimal.valueOf(rs.getLong(2)), 2, java.math.RoundingMode.HALF_UP))); }, BRANCH, CLASS, p[0], p[1]);
                return new Table(List.of(text("type", "Loan type"), number("loans", "Releases"), money("amount", "Amount released"), money("average", "Average")), rows, row("Total", n[0], t[0], n[0] == 0 ? BigDecimal.ZERO : t[0].divide(BigDecimal.valueOf(n[0]), 2, java.math.RoundingMode.HALF_UP)),
                    "Counts every debit to a loan's principal in the period (a release; a pay-off and renewal appears as a release of the new loan and a payment of the old one).");
            }),
            of("loan-payments", "Loans", "Loan payments", "Money collected in the period by loan type: principal, interest and penalties (credits to the loan ledger).", period, NONE, (a) -> {
                LocalDate[] p = a.period(today.withDayOfMonth(1), today, 1500);
                Map<Integer, String> types = typeNames();
                Map<Integer, BigDecimal[]> by = new LinkedHashMap<>();
                jdbc.query("SELECT SLT_CODE, SLE_CODE, SUM(-AMT) FROM sldtl WHERE SL_BRCODE = ? AND SLC_CODE = ? AND SLE_CODE IN (11, 23, 41) AND AMT < 0 AND TR_DATE >= ? AND TR_DATE <= ? GROUP BY SLT_CODE, SLE_CODE", (rs) -> {
                    BigDecimal[] x = by.computeIfAbsent(rs.getInt(1), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                    int slot = rs.getInt(2) == 11 ? 0 : rs.getInt(2) == 23 ? 1 : 2;
                    x[slot] = x[slot].add(rs.getBigDecimal(3));
                }, BRANCH, CLASS, p[0], p[1]);
                BigDecimal[] t = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
                List<List<Object>> rows = new ArrayList<>();
                by.entrySet().stream().sorted((x, y) -> y.getValue()[0].add(y.getValue()[1]).compareTo(x.getValue()[0].add(x.getValue()[1]))).forEach((e) -> {
                    BigDecimal[] v = e.getValue();
                    for (int i = 0; i < 3; i++) t[i] = t[i].add(v[i]);
                    rows.add(row(typeName(types, e.getKey()), v[0], v[1], v[2], v[0].add(v[1]).add(v[2])));
                });
                return new Table(List.of(text("type", "Loan type"), money("principal", "Principal"), money("interest", "Interest"), money("penalty", "Penalty"), money("total", "Total")), rows, row("Total", t[0], t[1], t[2], t[0].add(t[1]).add(t[2])),
                    "Principal includes amounts cleared by a renewal or pay-off voucher, not only cash received.");
            }),
            of("loan-collection", "Loans", "Collection efficiency", "What the schedule said was due in the period against the principal and interest actually paid, by loan type.", period, NONE, (a) -> {
                LocalDate[] p = a.period(today.withDayOfMonth(1), today, 1500);
                Map<Integer, String> types = typeNames();
                Map<Integer, BigDecimal[]> by = new LinkedHashMap<>();
                jdbc.query("SELECT SLT_CODE, SUM(COALESCE(PRINDUE, 0) + COALESCE(INTDUE, 0)) FROM amortsched WHERE BR_CODE = ? AND SLC_CODE = ? AND DUEDATE >= ? AND DUEDATE <= ? GROUP BY SLT_CODE",
                    (rs) -> { by.computeIfAbsent(rs.getInt(1), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO})[0] = rs.getBigDecimal(2); }, BRANCH, CLASS, p[0], p[1]);
                jdbc.query("SELECT SLT_CODE, SUM(-AMT) FROM sldtl WHERE SL_BRCODE = ? AND SLC_CODE = ? AND SLE_CODE IN (11, 23) AND AMT < 0 AND TR_DATE >= ? AND TR_DATE <= ? GROUP BY SLT_CODE",
                    (rs) -> { by.computeIfAbsent(rs.getInt(1), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO})[1] = rs.getBigDecimal(2); }, BRANCH, CLASS, p[0], p[1]);
                BigDecimal[] t = {BigDecimal.ZERO, BigDecimal.ZERO};
                List<List<Object>> rows = new ArrayList<>();
                by.entrySet().stream().sorted((x, y) -> y.getValue()[0].compareTo(x.getValue()[0])).forEach((e) -> {
                    t[0] = t[0].add(e.getValue()[0]); t[1] = t[1].add(e.getValue()[1]);
                    rows.add(row(typeName(types, e.getKey()), e.getValue()[0], e.getValue()[1], pct(e.getValue()[1], e.getValue()[0])));
                });
                return new Table(List.of(text("type", "Loan type"), money("due", "Due by schedule"), money("collected", "Collected (principal and interest)"), percent("rate", "Collected / due")), rows, row("Total", t[0], t[1], pct(t[1], t[0])),
                    "Collected can be more than due where members pay ahead, settle old arrears or renew; compare over a full quarter or year.");
            }));
    }
}
