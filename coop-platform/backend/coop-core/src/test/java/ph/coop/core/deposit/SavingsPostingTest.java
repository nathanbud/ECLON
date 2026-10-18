package ph.coop.core.deposit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Reads real savings accounts through the real code (nothing is written). Runs only when DB_PASSWORD is set. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class SavingsPostingTest {

    @Autowired SavingsController savings;
    @Autowired JdbcTemplate jdbc;

    private Map<String, Object> accountWithHistory(boolean withHold) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.ClientIDSD AS client, s.SDSLT_CODE AS typ, s.SDREF_NO AS ref FROM sd s
                WHERE s.SDBR_CODE = 1 AND s.SDSLC_CODE = 22 AND s.SDSTATUS = 15
                  AND (SELECT COUNT(*) FROM sldtl d WHERE d.SL_BRCODE = 1 AND d.SL_CLIENTID = s.ClientIDSD AND d.SLC_CODE = 22 AND d.SLT_CODE = s.SDSLT_CODE AND d.REF_NO = s.SDREF_NO AND d.SLE_CODE = 11) >= 4
                """ + (withHold ? " AND EXISTS (SELECT 1 FROM hold h WHERE h.HBR_CODE = 1 AND h.ClientIDH = s.ClientIDSD AND h.HSLC_CODE = 22 AND h.HSLT_CODE = s.SDSLT_CODE AND h.HREF_NO = s.SDREF_NO)" : "")
                + " ORDER BY s.ClientIDSD LIMIT 1");
        assumeTrue(!rows.isEmpty(), "no savings account with history" + (withHold ? " and a hold" : "") + " in this database");
        return rows.get(0);
    }

    @Test
    void balanceIsTheNegatedSumOfThePrincipalRows() {
        Map<String, Object> a = accountWithHistory(false);
        int client = ((Number) a.get("client")).intValue(), typ = ((Number) a.get("typ")).intValue(), ref = ((Number) a.get("ref")).intValue();
        BigDecimal expected = jdbc.queryForObject("SELECT -SUM(AMT) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 22 AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = 11",
                BigDecimal.class, client, typ, ref);
        SavingsController.Summary s = savings.accounts(1, client, false);
        SavingsController.Account acc = s.accounts().stream().filter((x) -> x.typeCode() == typ && x.refNo() == ref).findFirst().orElseThrow();
        assertEquals(0, expected.compareTo(acc.balance()));
        assertEquals("Active", acc.statusName());
        assertEquals(0, acc.balance().subtract(acc.held()).max(BigDecimal.ZERO).compareTo(acc.available()));
        assertEquals(0, s.accounts().stream().map(SavingsController.Account::balance).reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(s.totalBalance()));
    }

    @Test
    void historyRunsBackFromTheBalance() {
        Map<String, Object> a = accountWithHistory(false);
        int client = ((Number) a.get("client")).intValue(), typ = ((Number) a.get("typ")).intValue(), ref = ((Number) a.get("ref")).intValue();
        SavingsController.History h = savings.history(1, client, typ, ref, 0, 25);
        assertTrue(h.entries().size() >= 4);
        assertEquals(0, h.account().balance().compareTo(h.entries().get(0).balanceAfter()));
        for (int i = 1; i < h.entries().size(); i++) {
            SavingsController.Entry newer = h.entries().get(i - 1), older = h.entries().get(i);
            assertEquals(0, newer.balanceAfter().subtract(newer.amount()).compareTo(older.balanceAfter()));
        }
        SavingsController.History second = savings.history(1, client, typ, ref, 1, 2);
        assertEquals(h.entries().get(2).documentNo(), second.entries().get(0).documentNo());
        assertEquals(0, h.entries().get(2).balanceAfter().compareTo(second.entries().get(0).balanceAfter()));
    }

    @Test
    void holdsReduceTheAvailableBalance() {
        Map<String, Object> a = accountWithHistory(true);
        int client = ((Number) a.get("client")).intValue(), typ = ((Number) a.get("typ")).intValue(), ref = ((Number) a.get("ref")).intValue();
        SavingsController.History h = savings.history(1, client, typ, ref, 0, 5);
        assertTrue(!h.holds().isEmpty());
        BigDecimal held = h.holds().stream().map(SavingsController.Hold::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, held.compareTo(h.account().held()));
        assertTrue(h.account().available().compareTo(h.account().balance()) <= 0);
    }

    @Test
    void refusesUnknownAccountsAndHugeWindows() {
        assertThrows(ResponseStatusException.class, () -> savings.history(1, 1, 99, 99999, 0, 25));
        assertThrows(ResponseStatusException.class, () -> savings.history(1, 1, 1, 1, 0, 5000));
    }
}
