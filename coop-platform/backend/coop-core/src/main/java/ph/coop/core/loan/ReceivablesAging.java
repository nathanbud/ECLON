package ph.coop.core.loan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aging of receivables (after the old system's AGING OF RECEIVABLES FILTER, {@code pics/reports.png}), the pure part: given the accounts with a balance on the cut-off date,
 * it ages each one, puts it in a bucket, nests the accounts in up to three groupings with a subtotal per group, and adds the grand total. No database here.
 *
 * <p>Two ways to age, as on the old screen: <b>PAR</b> counts the days the oldest unpaid instalment is late (from the repayment schedule), <b>maturity</b> counts the days
 * since the account's maturity date. The number beside it is the threshold: an account aged at least that many days counts as at risk (PAR @ 30 is the usual PAR 30).
 */
final class ReceivablesAging {

    private ReceivablesAging() {}

    /** Upper bounds of the buckets in days; past the last is the last bucket. */
    static final int[] BOUNDS = {0, 30, 60, 90, 180, 365};
    static final List<String> BUCKETS = List.of("Current", "1-30 days", "31-60 days", "61-90 days", "91-180 days", "181-365 days", "Over 365 days");
    static final String NONE = "(not set)";

    enum Basis { PAR, MATURITY }

    /** The group a person can choose (key and words); "aging" and "client" are worked out here, the rest come from the account's {@code groups}. */
    static final Map<String, String> GROUPINGS = groupings();

    private static Map<String, String> groupings() {
        Map<String, String> g = new LinkedHashMap<>();
        g.put("slclass", "SL class");
        g.put("sltype", "SL type");
        g.put("aging", "Aging bucket");
        g.put("slstatus", "SL status");
        g.put("dept", "Member department");
        g.put("collector", "Collector");
        g.put("geo", "Geographical area");
        g.put("employer", "Employer");
        g.put("purpose", "Loan purpose");
        g.put("loanclass", "Loan class");
        g.put("borrower", "Borrower class");
        g.put("collateral", "Collateral type");
        g.put("paymode", "Pay mode");
        g.put("collecttype", "Collection type");
        g.put("dosri", "DOSRI tag");
        g.put("gender", "Gender");
        g.put("clienttype", "Member type");
        g.put("acctstatus", "Member account status");
        g.put("client", "Client");
        return Collections.unmodifiableMap(g);
    }

    static final Map<String, String> SORTS = sorts();

    private static Map<String, String> sorts() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("name", "Member name (A to Z)");
        s.put("member", "Member number");
        s.put("balance", "Outstanding (largest first)");
        s.put("days", "Days aged (most first)");
        s.put("maturity", "Maturity date (soonest first)");
        s.put("released", "Date released (newest first)");
        s.put("interest", "Interest due (largest first)");
        s.put("totaldue", "Total owed (largest first)");
        return Collections.unmodifiableMap(s);
    }

    /**
     * One receivable with a balance on the cut-off date. {@code groups} holds the words for each grouping key (missing or blank = not set). The interest and penalty
     * figures come from the Accrued Interest tab ({@link ReceivableAccrual}) and are zero when it is off.
     */
    record Account(int client, String name, int slClass, int slType, String typeName, int ref, BigDecimal balance, BigDecimal pastDue, long daysLate,
                   LocalDate released, LocalDate maturity, LocalDate lastTrans, Map<String, String> groups,
                   BigDecimal interestDue, BigDecimal interestAhead, BigDecimal penaltyDue) {

        /** An account without interest and penalty figures. */
        Account(int client, String name, int slClass, int slType, String typeName, int ref, BigDecimal balance, BigDecimal pastDue, long daysLate,
                LocalDate released, LocalDate maturity, LocalDate lastTrans, Map<String, String> groups) {
            this(client, name, slClass, slType, typeName, ref, balance, pastDue, daysLate, released, maturity, lastTrans, groups, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        /** Principal outstanding plus interest and penalty due. */
        BigDecimal totalDue() {
            return balance.add(interestDue).add(penaltyDue);
        }
    }

    record Spec(Basis basis, int parDays, List<String> groupBy, String sort, boolean totalsOnly) {
        Spec {
            if (parDays < 1 || parDays > 3650) throw new IllegalArgumentException("Give the PAR or maturity days as a number from 1 to 3650.");
            if (groupBy.size() > 3) throw new IllegalArgumentException("Choose at most three groupings.");
            if (Set.copyOf(groupBy).size() != groupBy.size()) throw new IllegalArgumentException("A grouping is chosen twice.");
            for (String g : groupBy) if (!GROUPINGS.containsKey(g)) throw new IllegalArgumentException("Unknown grouping: " + g);
            if (sort == null || !SORTS.containsKey(sort)) sort = "name";
        }
    }

    /**
     * One line of the result. kind is "group" (a group heading carrying its subtotal), "account" or "total". level is the nesting depth (0 = outermost group, or the
     * accounts when nothing is grouped). The bucket amounts are outstanding balances; atRisk is the outstanding of the accounts aged at least the threshold. interestDue,
     * interestAhead and penaltyDue are the Accrued Interest tab's figures (zero when it is off); totalDue is outstanding plus interest and penalty due.
     */
    record Line(String kind, int level, String label, String detail, Integer client, Integer slClass, Integer slType, Integer ref, long accounts,
                BigDecimal outstanding, List<BigDecimal> buckets, BigDecimal pastDue, BigDecimal atRisk, BigDecimal riskPct, Long days,
                LocalDate released, LocalDate maturity, LocalDate lastTrans, BigDecimal interestDue, BigDecimal interestAhead, BigDecimal penaltyDue, BigDecimal totalDue) {}

    /** Days aged on the chosen basis, never negative. */
    static long age(Account a, Basis basis, LocalDate cutOff) {
        if (basis == Basis.PAR) return Math.max(0, a.daysLate());
        if (a.maturity() == null || !a.maturity().isBefore(cutOff)) return 0;
        return ChronoUnit.DAYS.between(a.maturity(), cutOff);
    }

    static int bucket(long days) {
        if (days <= 0) return 0;
        for (int i = 1; i < BOUNDS.length; i++) if (days <= BOUNDS[i]) return i;
        return BUCKETS.size() - 1;
    }

    static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        if (whole.signum() == 0) return BigDecimal.ZERO.setScale(2);
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 2, RoundingMode.HALF_UP);
    }

    static String groupValue(Account a, String key, Basis basis, LocalDate cutOff) {
        if (key.equals("aging")) return BUCKETS.get(bucket(age(a, basis, cutOff)));
        if (key.equals("client")) return a.name() + " (" + a.client() + ")";
        String v = a.groups() == null ? null : a.groups().get(key);
        return v == null || v.isBlank() ? NONE : v.trim();
    }

    /** The lines: groups (each followed by what is inside it), the accounts unless only totals are wanted, and the grand total last. */
    static List<Line> build(List<Account> accounts, Spec spec, LocalDate cutOff) {
        List<Line> out = new ArrayList<>();
        emit(accounts, 0, spec, cutOff, out);
        out.add(summary("total", 0, "All receivables", null, accounts, spec, cutOff));
        return out;
    }

    private static void emit(List<Account> list, int level, Spec spec, LocalDate cutOff, List<Line> out) {
        if (level == spec.groupBy().size()) {
            if (spec.totalsOnly()) return;
            List<Account> sorted = new ArrayList<>(list);
            sorted.sort(order(spec, cutOff));
            for (Account a : sorted) out.add(accountLine(a, level, spec, cutOff));
            return;
        }
        String key = spec.groupBy().get(level);
        Map<String, List<Account>> parts = new LinkedHashMap<>();
        for (Account a : list) parts.computeIfAbsent(groupValue(a, key, spec.basis(), cutOff), k -> new ArrayList<>()).add(a);
        List<String> labels = new ArrayList<>(parts.keySet());
        labels.sort(groupOrder(key));
        for (String label : labels) {
            List<Account> part = parts.get(label);
            out.add(summary("group", level, label, GROUPINGS.get(key), part, spec, cutOff));
            emit(part, level + 1, spec, cutOff, out);
        }
    }

    /** Aging buckets keep their order; other groups go A to Z with "(not set)" last. */
    private static Comparator<String> groupOrder(String key) {
        if (key.equals("aging")) return Comparator.comparingInt(BUCKETS::indexOf);
        return (x, y) -> {
            if (x.equals(y)) return 0;
            if (x.equals(NONE)) return 1;
            if (y.equals(NONE)) return -1;
            return String.CASE_INSENSITIVE_ORDER.compare(x, y);
        };
    }

    private static Comparator<Account> order(Spec spec, LocalDate cutOff) {
        Comparator<Account> byName = Comparator.comparing((Account a) -> a.name() == null ? "" : a.name(), String.CASE_INSENSITIVE_ORDER);
        Comparator<Account> tie = byName.thenComparingInt(Account::client).thenComparingInt(Account::slType).thenComparingInt(Account::ref);
        Comparator<Account> first = switch (spec.sort()) {
            case "client" -> Comparator.comparingInt(Account::client);
            case "balance" -> Comparator.comparing(Account::balance).reversed();
            case "days" -> Comparator.comparingLong((Account a) -> age(a, spec.basis(), cutOff)).reversed();
            case "maturity" -> Comparator.comparing(Account::maturity, Comparator.nullsLast(Comparator.naturalOrder()));
            case "released" -> Comparator.comparing(Account::released, Comparator.nullsLast(Comparator.reverseOrder()));
            case "interest" -> Comparator.comparing(Account::interestDue).reversed();
            case "totaldue" -> Comparator.comparing(Account::totalDue).reversed();
            default -> byName;
        };
        return first.thenComparing(tie);
    }

    private static Line accountLine(Account a, int level, Spec spec, LocalDate cutOff) {
        long days = age(a, spec.basis(), cutOff);
        List<BigDecimal> b = zeros();
        b.set(bucket(days), a.balance());
        BigDecimal risk = days >= spec.parDays() ? a.balance() : BigDecimal.ZERO;
        return new Line("account", level, a.name(), a.typeName() + " · " + a.ref(), a.client(), a.slClass(), a.slType(), a.ref(), 1, a.balance(), b, a.pastDue(), risk,
            pct(risk, a.balance()), days, a.released(), a.maturity(), a.lastTrans(), a.interestDue(), a.interestAhead(), a.penaltyDue(), a.totalDue());
    }

    private static Line summary(String kind, int level, String label, String detail, List<Account> list, Spec spec, LocalDate cutOff) {
        List<BigDecimal> b = zeros();
        BigDecimal total = BigDecimal.ZERO, past = BigDecimal.ZERO, risk = BigDecimal.ZERO, interest = BigDecimal.ZERO, ahead = BigDecimal.ZERO, penalty = BigDecimal.ZERO;
        for (Account a : list) {
            long days = age(a, spec.basis(), cutOff);
            int i = bucket(days);
            b.set(i, b.get(i).add(a.balance()));
            total = total.add(a.balance());
            past = past.add(a.pastDue());
            if (days >= spec.parDays()) risk = risk.add(a.balance());
            interest = interest.add(a.interestDue());
            ahead = ahead.add(a.interestAhead());
            penalty = penalty.add(a.penaltyDue());
        }
        return new Line(kind, level, label, detail, null, null, null, null, list.size(), total, b, past, risk, pct(risk, total), null, null, null, null,
            interest, ahead, penalty, total.add(interest).add(penalty));
    }

    private static List<BigDecimal> zeros() {
        return new ArrayList<>(Collections.nCopies(BUCKETS.size(), BigDecimal.ZERO));
    }
}
