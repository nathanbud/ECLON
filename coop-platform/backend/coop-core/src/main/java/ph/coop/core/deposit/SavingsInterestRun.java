package ph.coop.core.deposit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.deposit.SavingsInterest.Movement;

/**
 * Works out the quarterly interest on deposits for every savings account (documentation/savings-interest-map.md). Read only: it returns the credits the old
 * system would encode, the accounts it holds back for a person to check, and the expense account per type. Rules, all from the real March 2026 run:
 * rate from the loan type (sltype, class 22), nothing under the type's minimum A.D.B. (500), only accounts that end the quarter with a balance of 1 peso or more,
 * only active (15) and dormant (16) accounts, the department of the client. An account whose interest is 100,000 or more, or without a department, is held back.
 */
@Component
public class SavingsInterestRun {

    public static final BigDecimal MINIMUM_ADB = new BigDecimal("500"), CHECK_ABOVE = new BigDecimal("100000");

    /** Interest expense account per savings type (50102 for Regular and Associate, 50103 for Kiddies, as the old explanation text says). */
    public static String expenseAccount(int typeCode) {
        return switch (typeCode) {
            case 1, 2 -> "50102";
            case 3 -> "50103";
            default -> null;
        };
    }

    public record Credit(int clientId, int typeCode, int refNo, int status, int dept, BigDecimal interest) {}

    public record Held(int clientId, int typeCode, int refNo, BigDecimal interest, String reason) {}

    public record Plan(LocalDate from, LocalDate to, List<Credit> credits, List<Held> held, Map<Integer, BigDecimal> rates) {}

    private record Key(int client, int type, int ref) {}

    private final JdbcTemplate jdbc;

    SavingsInterestRun(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The quarter that ends on {@code to}, or an IllegalArgumentException when {@code to} is not the last day of March, June, September or December. */
    public static LocalDate quarterStart(LocalDate to) {
        Month m = to.getMonth();
        boolean end = (m == Month.MARCH || m == Month.JUNE || m == Month.SEPTEMBER || m == Month.DECEMBER) && to.equals(to.withDayOfMonth(to.lengthOfMonth()));
        if (!end) throw new IllegalArgumentException("Interest is computed to the last day of March, June, September or December.");
        return to.minusMonths(2).withDayOfMonth(1);
    }

    public Plan plan(int branch, LocalDate to) {
        LocalDate from = quarterStart(to);
        Map<Integer, BigDecimal> rate = new HashMap<>();
        jdbc.query("SELECT SLTypeSLT_CODE, SLTypeINT_RATE FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = 22", rs -> { rate.put(rs.getInt(1), rs.getBigDecimal(2)); }, branch);
        Map<Key, int[]> accounts = new HashMap<>(); // status, department
        jdbc.query("""
                SELECT s.ClientIDSD, s.SDSLT_CODE, s.SDREF_NO, s.SDSTATUS, COALESCE(c.ClientDEPT, 0) FROM sd s
                LEFT JOIN client c ON c.ClientIDBrCode = s.SDBR_CODE AND c.ClientID = s.ClientIDSD
                WHERE s.SDBR_CODE = ? AND s.SDSLC_CODE = 22
                """, rs -> { accounts.put(new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3)), new int[] {rs.getInt(4), rs.getInt(5)}); }, branch);
        Map<Key, BigDecimal> opening = new HashMap<>();
        jdbc.query("""
                SELECT SL_CLIENTID, SLT_CODE, REF_NO, -SUM(AMT) FROM sldtl
                WHERE SL_BRCODE = ? AND SLC_CODE = 22 AND SLE_CODE = 11 AND TR_DATE < ? GROUP BY SL_CLIENTID, SLT_CODE, REF_NO
                """, rs -> { opening.put(new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3)), rs.getBigDecimal(4)); }, branch, from);
        Map<Key, List<Movement>> moves = new HashMap<>();
        jdbc.query("""
                SELECT SL_CLIENTID, SLT_CODE, REF_NO, TR_DATE, -AMT FROM sldtl
                WHERE SL_BRCODE = ? AND SLC_CODE = 22 AND SLE_CODE = 11 AND TR_DATE >= ? AND TR_DATE <= ? AND NOT (TR_CODE = 53 AND TR_DATE >= ?)
                """, rs -> { moves.computeIfAbsent(new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3)), k -> new ArrayList<>()).add(new Movement(rs.getDate(4).toLocalDate(), rs.getBigDecimal(5))); },
            branch, from, to, from);

        List<Credit> credits = new ArrayList<>();
        List<Held> held = new ArrayList<>();
        int days = SavingsInterest.days(from, to);
        for (Map.Entry<Key, int[]> e : accounts.entrySet()) {
            Key k = e.getKey();
            int status = e.getValue()[0], dept = e.getValue()[1];
            if (status != 15 && status != 16) continue;
            if (expenseAccount(k.type()) == null) continue;
            List<Movement> m = moves.getOrDefault(k, List.of());
            BigDecimal start = opening.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal end = start;
            for (Movement x : m) end = end.add(x.amount());
            if (end.compareTo(BigDecimal.ONE) < 0) continue;
            BigDecimal interest = SavingsInterest.interest(SavingsInterest.balanceDays(start, m, from, to), days, rate.get(k.type()), MINIMUM_ADB);
            if (interest.signum() <= 0) continue;
            if (dept <= 0) held.add(new Held(k.client(), k.type(), k.ref(), interest, "the client has no department"));
            else if (interest.compareTo(CHECK_ABOVE) >= 0) held.add(new Held(k.client(), k.type(), k.ref(), interest, "the interest is " + CHECK_ABOVE.toPlainString() + " or more; check the account's ledger"));
            else credits.add(new Credit(k.client(), k.type(), k.ref(), status, dept, interest));
        }
        credits.sort((a, b) -> a.typeCode() != b.typeCode() ? Integer.compare(a.typeCode(), b.typeCode()) : a.clientId() != b.clientId() ? Integer.compare(a.clientId(), b.clientId()) : Integer.compare(a.refNo(), b.refNo()));
        return new Plan(from, to, credits, held, rate);
    }
}
