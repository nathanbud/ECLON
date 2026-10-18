package ph.coop.core.deposit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ph.coop.core.deposit.SavingsInterest.Movement;

/**
 * Recomputes the interest the old system posted for 1 January to 31 March 2026 (Interest on Deposits, module 8 type 53, vouchers 145 to 151 are
 * posted) for a sample of accounts and compares it to the cent. The rate is the loan type's (sltype), not the one stored on the account. Read only. Runs only when DB_PASSWORD is set.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class SavingsInterestRealDataTest {

    static final LocalDate FROM = LocalDate.of(2026, 1, 1), TO = LocalDate.of(2026, 3, 31);

    @Autowired JdbcTemplate jdbc;

    @Test
    void recomputesThePostedInterestOfASample() {
        int checked = 0, exact = 0, withinCent = 0;
        List<String> misses = new ArrayList<>();
        for (int voucher : new int[] {145, 146, 148}) {
            List<Map<String, Object>> lines = jdbc.queryForList("""
                    SELECT ClientIDDT c, DTSLT_CODE t, DTREF_NO r, DTAMT a FROM transdt
                    WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = 2026 AND DTTR_MODULE = 8 AND DTTR_CODE = 53 AND DTTR_CTLNO = ? AND DTSEQ_NO <= 120 AND DTAMT < 0
                    """, voucher);
            assumeTrue(!lines.isEmpty(), "voucher " + voucher + " is not in this database");
            for (Map<String, Object> l : lines) {
                int client = ((Number) l.get("c")).intValue(), type = ((Number) l.get("t")).intValue(), ref = ((Number) l.get("r")).intValue();
                BigDecimal posted = ((BigDecimal) l.get("a")).negate();
                List<BigDecimal> rate = jdbc.queryForList("SELECT t.SLTypeINT_RATE FROM sd s JOIN sltype t ON t.SLTypeBR_CODE = s.SDBR_CODE AND t.SLTypeSLC_CODE = 22 AND t.SLTypeSLT_CODE = s.SDSLT_CODE WHERE s.SDBR_CODE = 1 AND s.SDSLC_CODE = 22 AND s.SDSLT_CODE = ? AND s.SDREF_NO = ? AND s.ClientIDSD = ?", BigDecimal.class, type, ref, client);
                if (rate.isEmpty() || rate.get(0) == null) continue;
                BigDecimal opening = jdbc.queryForObject("""
                        SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 22 AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = 11 AND TR_DATE < ?
                        """, BigDecimal.class, client, type, ref, FROM);
                List<Movement> moves = jdbc.query("""
                        SELECT TR_DATE, -AMT FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 22 AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = 11
                          AND TR_DATE >= ? AND TR_DATE <= ? AND NOT (TR_CODE = 53 AND TR_DATE >= ?)
                        """, (rs, i) -> new Movement(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2)), client, type, ref, FROM, TO, FROM);
                BigDecimal mine = SavingsInterest.interest(SavingsInterest.balanceDays(opening, moves, FROM, TO), SavingsInterest.days(FROM, TO), rate.get(0), new BigDecimal("500"));
                checked++;
                BigDecimal diff = mine.subtract(posted).abs();
                if (diff.signum() == 0) exact++;
                if (diff.compareTo(new BigDecimal("0.01")) <= 0) withinCent++;
                else if (misses.size() < 8) misses.add(type + "/" + ref + " mine " + mine + " posted " + posted + " rate " + rate.get(0));
            }
        }
        System.out.println("INTEREST SAMPLE: checked " + checked + ", exact " + exact + ", within a cent " + withinCent + ", misses " + misses);
        assumeTrue(checked > 50, "too few accounts checked");
        assertTrue(withinCent * 100 >= checked * 95, "only " + withinCent + " of " + checked + " within a cent; " + misses);
    }
}
