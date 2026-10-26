package ph.coop.core.deposit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.deposit.SavingsInterest.Movement;

/**
 * Works out the interest the old system accrues on time deposit certificates, "Accrued Interest on Deposits (Computer Generated) - A.D.B." (Interest, module 8
 * type 52; documentation/time-deposit-accrual-map.md). Read only. Lock In types (2, 4) are accrued every month end ("Normal Accrual"); Short Term types (1, 3)
 * once a year on 12/31 for the whole year ("End of Period Accrual"). A certificate earns, for each day of the period it holds money (the end-of-day balance of
 * ledger event 11, the placement day counted), balance x its own rate / 100 / 360; the total is rounded half up to the cent.
 */
@Component
public class TimeDepositAccrualRun {

    public static final String EXPENSE_ACCOUNT = "50105";
    public static final List<Integer> LOCK_IN_TYPES = List.of(2, 4), SHORT_TERM_TYPES = List.of(1, 3);
    private static final BigDecimal DAY_BASE = new BigDecimal("36000");

    public enum Group {
        LOCK_IN("Normal Accrual"), SHORT_TERM("End of Period Accrual");
        public final String accrualType;
        Group(String accrualType) { this.accrualType = accrualType; }
        public List<Integer> types() { return this == LOCK_IN ? LOCK_IN_TYPES : SHORT_TERM_TYPES; }
    }

    public record Credit(int clientId, int typeCode, int refNo, int dept, BigDecimal rate, BigDecimal interest) {}

    public record Plan(Group group, LocalDate from, LocalDate to, List<Credit> credits, List<Integer> noDepartment) {}

    private record Key(int client, int type, int ref) {}

    private final JdbcTemplate jdbc;

    TimeDepositAccrualRun(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The period an accrual to {@code to} covers, or an IllegalArgumentException when {@code to} is not a date the old system ran it on. */
    public static LocalDate periodStart(Group group, LocalDate to) {
        if (group == Group.LOCK_IN) {
            if (!to.equals(to.withDayOfMonth(to.lengthOfMonth()))) throw new IllegalArgumentException("Lock In interest is accrued to the last day of a month.");
            return to.withDayOfMonth(1);
        }
        if (to.getMonthValue() != 12 || to.getDayOfMonth() != 31) throw new IllegalArgumentException("Short Term interest is accrued once a year, to December 31.");
        return LocalDate.of(to.getYear(), 1, 1);
    }

    /** The interest of one certificate: the days it holds money, at its own rate. */
    public static BigDecimal interest(BigDecimal opening, List<Movement> movements, LocalDate from, LocalDate to, BigDecimal ratePercent) {
        if (ratePercent == null || ratePercent.signum() <= 0) return BigDecimal.ZERO.setScale(2);
        BigDecimal sum = SavingsInterest.balanceDays(opening, movements, from, to);
        if (sum.signum() <= 0) return BigDecimal.ZERO.setScale(2);
        return sum.multiply(ratePercent).divide(DAY_BASE, 2, RoundingMode.HALF_UP);
    }

    public Plan plan(int branch, Group group, LocalDate to) {
        LocalDate from = periodStart(group, to);
        List<Integer> types = group.types();
        String in = types.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("0");
        // the certificates placed by the end of the period, with their ledger movements of event 11 up to it (each certificate is looked up by its key)
        Map<Key, Object[]> certs = new LinkedHashMap<>(); // rate, department
        Map<Key, BigDecimal> opening = new LinkedHashMap<>();
        Map<Key, List<Movement>> moves = new LinkedHashMap<>();
        jdbc.query("""
                SELECT d.ClientIDTD, d.TDSLT_CODE, d.TDREF_NO, d.TDINT_RATE, COALESCE(k.ClientDEPT, 0), l.TR_DATE, l.AMT
                FROM td d
                JOIN sldtl l ON l.SL_BRCODE = d.TDBR_CODE AND l.SL_CLIENTID = d.ClientIDTD AND l.SLC_CODE = d.TDSLC_CODE AND l.SLT_CODE = d.TDSLT_CODE AND l.REF_NO = d.TDREF_NO
                  AND l.SLE_CODE = 11 AND l.TR_DATE <= ?
                LEFT JOIN client k ON k.ClientIDBrCode = d.TDBR_CODE AND k.ClientID = d.ClientIDTD
                WHERE d.TDBR_CODE = ? AND d.TDSLC_CODE = 24 AND d.TDSLT_CODE IN (""" + in + """
                ) AND d.TDSTATUS = 15 AND d.TDTR_DATE <= ?
                """, rs -> {
                    Key key = new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3));
                    certs.putIfAbsent(key, new Object[] {rs.getBigDecimal(4), rs.getInt(5)});
                    LocalDate day = rs.getDate(6).toLocalDate();
                    BigDecimal change = rs.getBigDecimal(7).negate(); // credits are stored negative; money in raises the balance
                    if (day.isBefore(from)) opening.merge(key, change, BigDecimal::add);
                    else moves.computeIfAbsent(key, k -> new ArrayList<>()).add(new Movement(day, change));
                }, java.sql.Date.valueOf(to), branch, java.sql.Date.valueOf(to));

        List<Credit> credits = new ArrayList<>();
        List<Integer> noDept = new ArrayList<>();
        for (Map.Entry<Key, Object[]> e : certs.entrySet()) {
            Key k = e.getKey();
            BigDecimal rate = (BigDecimal) e.getValue()[0];
            int dept = (Integer) e.getValue()[1];
            BigDecimal end = opening.getOrDefault(k, BigDecimal.ZERO);
            for (Movement m : moves.getOrDefault(k, List.of())) end = end.add(m.amount());
            if (end.compareTo(BigDecimal.ONE) < 0) continue; // a certificate paid out or rolled over before the end of the period is not accrued
            BigDecimal interest = interest(opening.getOrDefault(k, BigDecimal.ZERO), moves.getOrDefault(k, List.of()), from, to, rate);
            if (interest.signum() <= 0) continue;
            if (dept <= 0) { noDept.add(k.client()); continue; }
            credits.add(new Credit(k.client(), k.type(), k.ref(), dept, rate, interest));
        }
        credits.sort((a, b) -> a.typeCode() != b.typeCode() ? Integer.compare(a.typeCode(), b.typeCode()) : a.clientId() != b.clientId() ? Integer.compare(a.clientId(), b.clientId()) : Integer.compare(a.refNo(), b.refNo()));
        return new Plan(group, from, to, credits, noDept);
    }
}
