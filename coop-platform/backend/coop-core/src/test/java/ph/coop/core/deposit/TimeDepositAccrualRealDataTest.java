package ph.coop.core.deposit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ph.coop.core.deposit.SavingsInterest.Movement;
import ph.coop.core.deposit.TimeDepositAccrualRun.Group;
import ph.coop.core.deposit.TimeDepositAccrualRun.Plan;

/**
 * The accrual calculation against the runs the old system posted (documentation/time-deposit-accrual-map.md): the same certificates with the same amounts.
 * Runs only when DB_PASSWORD is set.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Tag("realdata")
class TimeDepositAccrualRealDataTest {

    @Autowired TimeDepositAccrualRun run;
    @Autowired JdbcTemplate jdbc;

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    @Test
    void theDaysACertificateHoldsMoneyAreCountedFromThePlacementDay() {
        // placed on the 30th, a month of 31 days: 2 days of 1,000,000 at 5% (old voucher 105, 277.78)
        var moves = List.of(new Movement(LocalDate.of(2026, 3, 30), d("1000000.00")));
        assertEquals(d("277.78"), TimeDepositAccrualRun.interest(BigDecimal.ZERO, moves, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), d("5")));
        // a whole month of 200,000 at 6% (old 1,033.33)
        assertEquals(d("1033.33"), TimeDepositAccrualRun.interest(d("200000.00"), List.of(), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), d("6")));
        // paid out on the 10th: the money is gone from that day
        assertEquals(d("150.00"), TimeDepositAccrualRun.interest(d("100000.00"), List.of(new Movement(LocalDate.of(2026, 3, 10), d("-100000.00"))), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), d("6")));
        assertEquals(d("0.00"), TimeDepositAccrualRun.interest(d("100000.00"), List.of(), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), BigDecimal.ZERO));
    }

    @Test
    void onlyTheDatesTheOldSystemRanOnAreAccepted() {
        assertThrows(IllegalArgumentException.class, () -> TimeDepositAccrualRun.periodStart(Group.LOCK_IN, LocalDate.of(2026, 3, 30)));
        assertThrows(IllegalArgumentException.class, () -> TimeDepositAccrualRun.periodStart(Group.SHORT_TERM, LocalDate.of(2025, 6, 30)));
        assertEquals(LocalDate.of(2026, 2, 1), TimeDepositAccrualRun.periodStart(Group.LOCK_IN, LocalDate.of(2026, 2, 28)));
        assertEquals(LocalDate.of(2025, 1, 1), TimeDepositAccrualRun.periodStart(Group.SHORT_TERM, LocalDate.of(2025, 12, 31)));
    }

    /** The lines of the old vouchers of a run date, keyed by client/type/ref. */
    private Map<String, BigDecimal> old(LocalDate to, List<Integer> types) {
        Map<String, BigDecimal> m = new HashMap<>();
        String in = types.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
        jdbc.query("""
                SELECT x.ClientIDDT, x.DTSLT_CODE, x.DTREF_NO, -x.DTAMT FROM transsum t JOIN transdt x ON x.DTTR_BRCODE = t.TR_BRCODE AND x.DTTR_YEAR = t.TR_YEAR AND x.DTTR_MODULE = t.TR_MODULE
                  AND x.DTTR_CODE = t.TR_CODE AND x.DTTR_CTLNO = t.TR_CTLNO
                WHERE t.TR_BRCODE = 1 AND t.TR_YEAR = ? AND t.TR_MODULE = 8 AND t.TR_CODE = 52 AND t.TR_DATE = ? AND x.DTSLC_CODE = 24 AND x.DTSLT_CODE IN (""" + in + ")",
            rs -> { m.put(rs.getInt(1) + "/" + rs.getInt(2) + "/" + rs.getInt(3), rs.getBigDecimal(4)); }, to.getYear(), java.sql.Date.valueOf(to));
        return m;
    }

    private void compare(Group group, LocalDate to, double minimum) {
        Map<String, BigDecimal> old = old(to, group.types());
        Plan plan = run.plan(1, group, to);
        int same = 0, off = 0, extra = 0;
        List<String> sample = new java.util.ArrayList<>();
        Map<String, BigDecimal> mine = new HashMap<>();
        for (var c : plan.credits()) mine.put(c.clientId() + "/" + c.typeCode() + "/" + c.refNo(), c.interest());
        for (var e : old.entrySet()) {
            BigDecimal got = mine.get(e.getKey());
            if (got != null && got.subtract(e.getValue()).abs().compareTo(d("0.015")) < 0) same++;
            else { off++; if (sample.size() < 8) sample.add(e.getKey() + " old " + e.getValue() + " mine " + got); }
        }
        for (String k : mine.keySet()) if (!old.containsKey(k)) { extra++; if (sample.size() < 12) sample.add(k + " not in old, mine " + mine.get(k)); }
        System.out.println("ACCRUAL " + group + " " + to + ": old " + old.size() + ", same " + same + ", off " + off + ", extra " + extra + " " + sample);
        assertTrue(old.size() > 100, "the old run is in the data");
        assertTrue(same >= old.size() * minimum, group + " " + to + ": " + same + " of " + old.size() + " match; " + sample);
    }

    @Test
    void lockInMonthsMatchTheOldRuns() {
        compare(Group.LOCK_IN, LocalDate.of(2026, 3, 31), 0.97);
        compare(Group.LOCK_IN, LocalDate.of(2026, 2, 28), 0.97);
        compare(Group.LOCK_IN, LocalDate.of(2025, 12, 31), 0.97);
    }

    @Test
    void theShortTermYearEndMatchesTheOldRun() {
        compare(Group.SHORT_TERM, LocalDate.of(2025, 12, 31), 0.90);
    }
}
