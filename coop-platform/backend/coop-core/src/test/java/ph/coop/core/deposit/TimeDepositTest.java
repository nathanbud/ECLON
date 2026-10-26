package ph.coop.core.deposit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ph.coop.core.deposit.TimeDepositRules.Status;

/** The rules on their own, then the formula against the real rollovers and the screen data for a real member. Read only. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class TimeDepositTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 24);

    @Autowired JdbcTemplate jdbc;
    @Autowired TimeDepositController controller;

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    @Test
    void statusComesFromTheLedgerBecauseTheOldMasterIsNeverClosed() {
        assertEquals(Status.ACTIVE, TimeDepositRules.status(15, true, d("1000"), LocalDate.of(2026, 7, 13), TODAY));
        assertEquals(Status.MATURED, TimeDepositRules.status(15, true, d("1000"), LocalDate.of(2026, 4, 1), TODAY));
        assertEquals(Status.CLOSED, TimeDepositRules.status(15, true, d("0.00"), LocalDate.of(2026, 7, 13), TODAY), "paid out in the ledger, master still says 15");
        assertEquals(Status.CLOSED, TimeDepositRules.status(17, true, d("500"), LocalDate.of(2026, 7, 13), TODAY));
        assertEquals(Status.NO_LEDGER, TimeDepositRules.status(15, false, d("0"), LocalDate.of(2026, 7, 13), TODAY));
        assertEquals(Status.ACTIVE, TimeDepositRules.status(15, true, d("1000"), TODAY, TODAY), "the day it matures it is still running");
    }

    @Test
    void interestIsThirtyThreeSixtyAndTheRolloverKeepsBackTen() {
        assertEquals(d("8259.33"), TimeDepositRules.termInterest(d("600678.33"), d("2.75"), 180));
        assertEquals(d("8249.33"), TimeDepositRules.rolledInterest(d("600678.33"), d("2.75"), 180));
        assertEquals(d("0.00"), TimeDepositRules.rolledInterest(d("100.00"), d("1.00"), 30), "never below zero");
        assertEquals(d("0"), TimeDepositRules.termInterest(null, d("2"), 90));
    }

    @Test
    void theFormulaMatchesMostRealRollovers() {
        // A rollover (type 4) takes the old certificate out with one debit and puts everything on the new one; the interest is the difference.
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT x.debit AS debit, x.credits - x.debit AS interest, d.TDINT_RATE AS rate, d.TDTERMS AS terms
                FROM (
                  SELECT s.SL_CLIENTID c, s.TR_CTLNO ctl, SUM(CASE WHEN s.AMT > 0 THEN s.AMT ELSE 0 END) debit, -SUM(CASE WHEN s.AMT < 0 THEN s.AMT ELSE 0 END) credits,
                         MAX(CASE WHEN s.AMT > 0 THEN s.REF_NO END) oldref, MAX(CASE WHEN s.AMT > 0 THEN s.SLT_CODE END) oldslt, SUM(CASE WHEN s.AMT > 0 THEN 1 ELSE 0 END) ndebit
                  FROM sldtl s WHERE s.SL_BRCODE = 1 AND s.SLC_CODE = 24 AND s.SLE_CODE = 11 AND s.TR_CODE = 4 AND s.TR_DATE BETWEEN '2026-01-01' AND '2026-04-24'
                  GROUP BY s.SL_CLIENTID, s.TR_CTLNO, s.TR_DATE HAVING SUM(CASE WHEN s.AMT > 0 THEN 1 ELSE 0 END) = 1
                ) x JOIN td d ON d.TDBR_CODE = 1 AND d.ClientIDTD = x.c AND d.TDREF_NO = x.oldref AND d.TDSLT_CODE = x.oldslt
                """);
        assumeTrue(rows.size() >= 100, "not enough rollovers in this database to measure");
        long hits = rows.stream().filter((r) -> {
            BigDecimal mine = TimeDepositRules.rolledInterest((BigDecimal) r.get("debit"), (BigDecimal) r.get("rate"), ((Number) r.get("terms")).intValue());
            return mine.subtract((BigDecimal) r.get("interest")).abs().compareTo(d("0.02")) < 0;
        }).count();
        double share = hits / (double) rows.size();
        assertTrue(share >= 0.65, "matched " + hits + " of " + rows.size() + " (" + Math.round(share * 100) + "%)");
    }

    @Test
    void aRealMembersCertificatesShowBalanceStatusAndHistory() {
        List<Map<String, Object>> c = jdbc.queryForList("""
                SELECT ClientIDTD, TDSLT_CODE, TDREF_NO FROM td WHERE TDBR_CODE = 1 AND TDSTATUS = 15 AND TDTR_DATE = '2026-04-24' AND TDREF_NO = 12098
                """);
        assumeTrue(!c.isEmpty(), "the sample certificate is not in this database");
        int client = ((Number) c.get(0).get("ClientIDTD")).intValue();
        var summary = controller.deposits(1, client, false);
        var dep = summary.deposits().stream().filter((x) -> x.refNo() == 12098).findFirst().orElseThrow();
        // placed 2026-04-24 for 90 days, so it matures on 2026-07-23: running until then, matured after (the app clock is the real date)
        assertEquals(LocalDate.now().isAfter(LocalDate.of(2026, 7, 23)) ? "MATURED" : "ACTIVE", dep.status());
        assertEquals(0, d("247493.96").compareTo(dep.balance()));
        assertEquals(0, d("1.75").compareTo(dep.ratePercent()));
        assertEquals(90, dep.termDays());
        assertEquals(0, TimeDepositRules.termInterest(d("247493.96"), d("1.75"), 90).compareTo(dep.estimatedInterest()));
        // the certificate it was rolled from is paid out in the ledger even though its master row still says active
        var old = controller.deposits(1, client, true).deposits().stream().filter((x) -> x.refNo() == 11994).findFirst().orElseThrow();
        assertTrue(!old.status().equals("NO_LEDGER"), old.status());
        assertEquals(0, d("4014.49").compareTo(old.balance()), "only what was left after the pre-termination");
        var h = controller.history(1, client, dep.typeCode(), dep.refNo(), 0, 25);
        assertEquals(1, h.entries().size());
        assertEquals(0, d("247493.96").compareTo(h.entries().get(0).amount()));
        assertEquals(0, d("247493.96").compareTo(h.entries().get(0).balanceAfter()));
    }
}
