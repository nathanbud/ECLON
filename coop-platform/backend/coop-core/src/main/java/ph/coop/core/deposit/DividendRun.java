package ph.coop.core.deposit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Works out the annual dividend and patronage refund (documentation/dividend-map.md). Read only: it returns what the old system would credit each member.
 * Fitted on the real February 2026 run (25,165 of 25,189 members to the cent):
 * the dividend is the rate (8% in 2026) of the average of the 12 month-end paid-up share capital balances of the year before the cut-off, and the patronage refund
 * is the rate (8.23%) of the loan interest, service fees and event 24 charges paid in that year, each rounded half up. Everyone with a total above zero is included.
 * What is left after the mutual benefit and hospitalization amounts (typed by the accountant, the old rule for them is not known) goes to the member's first active savings
 * account, or to the paid-up share capital when there is none.
 */
@Component
public class DividendRun {

    public static final String FUND_ACCOUNT = "20126";
    public static final int MUTUAL_BENEFIT = 5, HOSPITALIZATION = 6, SHARE_CAPITAL_TYPE = 1, ACTIVE = 15;

    /** What the accountant types: the two rates in percent, the insurance amounts every member gets, and the members who get something else. */
    public record Params(BigDecimal dividendRate, BigDecimal patronageRate, BigDecimal mutualBenefit, BigDecimal hospitalization, List<MemberAmounts> overrides) {}

    /** A member's own insurance amounts; a null amount keeps the standard one. */
    public record MemberAmounts(int clientId, BigDecimal mutualBenefit, BigDecimal hospitalization) {}

    /** One credit to a member's account: class, type, reference, general ledger account and the amount. */
    public record Credit(int clientId, int slc, int typeCode, int refNo, String kind, BigDecimal amount) {}

    public record Held(int clientId, BigDecimal total, String reason) {}

    public record Member(int clientId, BigDecimal dividend, BigDecimal refund, BigDecimal total, BigDecimal mutualBenefit, BigDecimal hospitalization, BigDecimal rest) {}

    public record Plan(LocalDate cutoff, int year, Params params, List<Member> members, List<Credit> credits, List<Held> held) {}

    private final JdbcTemplate jdbc;

    DividendRun(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The year the run covers: the calendar year before the cut-off. */
    public static int periodYear(LocalDate cutoff) {
        return cutoff.getYear() - 1;
    }

    /** Dividend: the rate in percent of the average of the twelve month-end balances, whose sum is {@code sumOfMonthEnds}. */
    public static BigDecimal dividend(BigDecimal sumOfMonthEnds, BigDecimal ratePercent) {
        return sumOfMonthEnds.multiply(ratePercent).divide(new BigDecimal("1200"), 2, RoundingMode.HALF_UP);
    }

    /** Patronage refund: the rate in percent of the loan interest, service fees and charges paid in the year. */
    public static BigDecimal refund(BigDecimal paid, BigDecimal ratePercent) {
        return paid.multiply(ratePercent).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    /** The sum of the balances at the end of each month of {@code year}: {@code opening} before 1 January plus the dated movements. */
    public static BigDecimal sumOfMonthEnds(BigDecimal opening, List<Movement> moves, int year) {
        BigDecimal[] inMonth = new BigDecimal[13];
        for (int i = 0; i < 13; i++) inMonth[i] = BigDecimal.ZERO;
        for (Movement m : moves) {
            if (m.date().getYear() == year) inMonth[m.date().getMonthValue()] = inMonth[m.date().getMonthValue()].add(m.amount());
        }
        BigDecimal balance = opening, sum = BigDecimal.ZERO;
        for (int month = 1; month <= 12; month++) {
            balance = balance.add(inMonth[month]);
            sum = sum.add(balance);
        }
        return sum;
    }

    public record Movement(LocalDate date, BigDecimal amount) {}

    /** Splits a member's total: mutual benefit first, then hospitalization (each only when the member has that account), the rest to savings or share capital. */
    public static BigDecimal[] split(BigDecimal total, BigDecimal mutualBenefit, boolean hasMutualBenefit, BigDecimal hospitalization, boolean hasHospitalization) {
        BigDecimal m = hasMutualBenefit ? mutualBenefit.min(total) : BigDecimal.ZERO;
        BigDecimal h = hasHospitalization ? hospitalization.min(total.subtract(m)) : BigDecimal.ZERO;
        return new BigDecimal[] {m, h, total.subtract(m).subtract(h)};
    }

    /** The problems with what was typed, empty when it can be used. */
    public static List<String> problems(Params p) {
        List<String> out = new ArrayList<>();
        if (p == null) { out.add("Give the rates and amounts."); return out; }
        if (p.dividendRate() == null || p.dividendRate().signum() < 0 || p.dividendRate().compareTo(new BigDecimal("100")) > 0) out.add("The dividend rate must be between 0 and 100 percent.");
        if (p.patronageRate() == null || p.patronageRate().signum() < 0 || p.patronageRate().compareTo(new BigDecimal("100")) > 0) out.add("The patronage refund rate must be between 0 and 100 percent.");
        if (p.mutualBenefit() == null || p.mutualBenefit().signum() < 0) out.add("The mutual benefit amount cannot be negative.");
        if (p.hospitalization() == null || p.hospitalization().signum() < 0) out.add("The hospitalization amount cannot be negative.");
        if (p.overrides() != null) {
            for (MemberAmounts o : p.overrides()) {
                if ((o.mutualBenefit() != null && o.mutualBenefit().signum() < 0) || (o.hospitalization() != null && o.hospitalization().signum() < 0)) out.add("Member " + o.clientId() + ": an insurance amount cannot be negative.");
            }
        }
        return out;
    }

    private static int[] first(Map<Integer, int[]> map, int client) {
        return map.get(client);
    }

    public Plan plan(int branch, LocalDate cutoff, Params params) {
        List<String> bad = problems(params);
        if (!bad.isEmpty()) throw new IllegalArgumentException(String.join(" ", bad));
        int year = periodYear(cutoff);
        LocalDate jan1 = LocalDate.of(year, 1, 1), dec31 = LocalDate.of(year, 12, 31);

        Map<Integer, BigDecimal> opening = new HashMap<>();
        jdbc.query("""
                SELECT SL_CLIENTID, -SUM(AMT) FROM sldtl WHERE SL_BRCODE = ? AND SLC_CODE = 31 AND SLT_CODE = ? AND SLE_CODE = 11 AND TR_DATE < ? GROUP BY SL_CLIENTID
                """, rs -> { opening.put(rs.getInt(1), rs.getBigDecimal(2)); }, branch, SHARE_CAPITAL_TYPE, jan1);
        Map<Integer, List<Movement>> moves = new HashMap<>();
        jdbc.query("""
                SELECT SL_CLIENTID, TR_DATE, -AMT FROM sldtl WHERE SL_BRCODE = ? AND SLC_CODE = 31 AND SLT_CODE = ? AND SLE_CODE = 11 AND TR_DATE >= ? AND TR_DATE <= ?
                """, rs -> { moves.computeIfAbsent(rs.getInt(1), k -> new ArrayList<>()).add(new Movement(rs.getDate(2).toLocalDate(), rs.getBigDecimal(3))); },
            branch, SHARE_CAPITAL_TYPE, jan1, dec31);
        Map<Integer, BigDecimal> paid = new HashMap<>();
        jdbc.query("""
                SELECT SL_CLIENTID, -SUM(AMT) FROM sldtl WHERE SL_BRCODE = ? AND SLC_CODE = 12 AND SLE_CODE IN (23, 31, 24) AND TR_DATE >= ? AND TR_DATE <= ? GROUP BY SL_CLIENTID
                """, rs -> { paid.put(rs.getInt(1), rs.getBigDecimal(2)); }, branch, jan1, dec31);

        // each member's first account of the kinds that can be credited
        Map<Integer, int[]> savings = new HashMap<>(), shares = new HashMap<>(), mutual = new HashMap<>(), hospital = new HashMap<>();
        jdbc.query("SELECT ClientIDSD, SDSLT_CODE, SDREF_NO FROM sd WHERE SDBR_CODE = ? AND SDSLC_CODE = 22 AND SDSTATUS = ? ORDER BY SDSLT_CODE, SDREF_NO",
            rs -> { savings.putIfAbsent(rs.getInt(1), new int[] {rs.getInt(2), rs.getInt(3)}); }, branch, ACTIVE);
        jdbc.query("SELECT ClientIDSC, SCSLT_CODE, SCREF_NO FROM sc WHERE SCBR_CODE = ? AND SCSLC_CODE = 31 AND SCSLT_CODE = ? AND SCSTATUS = ? ORDER BY SCREF_NO",
            rs -> { shares.putIfAbsent(rs.getInt(1), new int[] {rs.getInt(2), rs.getInt(3)}); }, branch, SHARE_CAPITAL_TYPE, ACTIVE);
        jdbc.query("SELECT MCLIENTID, MSLT_CODE, MREF_NO FROM slmastersum WHERE MBR_CODE = ? AND MSLC_CODE = 29 AND MSLT_CODE IN (?, ?) ORDER BY MREF_NO",
            rs -> { (rs.getInt(2) == MUTUAL_BENEFIT ? mutual : hospital).putIfAbsent(rs.getInt(1), new int[] {rs.getInt(2), rs.getInt(3)}); }, branch, MUTUAL_BENEFIT, HOSPITALIZATION);

        Map<Integer, MemberAmounts> over = new HashMap<>();
        if (params.overrides() != null) for (MemberAmounts o : params.overrides()) over.put(o.clientId(), o);

        java.util.Set<Integer> ids = new java.util.TreeSet<>();
        ids.addAll(opening.keySet());
        ids.addAll(moves.keySet());
        ids.addAll(paid.keySet());
        List<Member> members = new ArrayList<>();
        List<Credit> credits = new ArrayList<>();
        List<Held> held = new ArrayList<>();
        for (int client : ids) {
            BigDecimal div = dividend(sumOfMonthEnds(opening.getOrDefault(client, BigDecimal.ZERO), moves.getOrDefault(client, List.of()), year), params.dividendRate());
            BigDecimal ref = refund(paid.getOrDefault(client, BigDecimal.ZERO), params.patronageRate());
            BigDecimal total = div.add(ref);
            if (total.signum() <= 0) continue;
            MemberAmounts o = over.get(client);
            BigDecimal mbf = o != null && o.mutualBenefit() != null ? o.mutualBenefit() : params.mutualBenefit();
            BigDecimal habp = o != null && o.hospitalization() != null ? o.hospitalization() : params.hospitalization();
            BigDecimal[] s = split(total, mbf, first(mutual, client) != null, habp, first(hospital, client) != null);
            int[] home = first(savings, client) != null ? first(savings, client) : first(shares, client);
            boolean toSavings = first(savings, client) != null;
            if (s[2].signum() > 0 && home == null) {
                held.add(new Held(client, total, "the member has no active savings or share capital account to credit"));
                continue;
            }
            members.add(new Member(client, div, ref, total, s[0], s[1], s[2]));
            if (s[0].signum() > 0) { int[] a = first(mutual, client); credits.add(new Credit(client, 29, a[0], a[1], "MUTUAL_BENEFIT", s[0])); }
            if (s[1].signum() > 0) { int[] a = first(hospital, client); credits.add(new Credit(client, 29, a[0], a[1], "HOSPITALIZATION", s[1])); }
            if (s[2].signum() > 0) credits.add(new Credit(client, toSavings ? 22 : 31, home[0], home[1], toSavings ? "SAVINGS" : "SHARE_CAPITAL", s[2]));
        }
        return new Plan(cutoff, year, params, members, credits, held);
    }
}
