package ph.coop.core.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** Reads real share capital accounts through the real code (nothing is written). Runs only when DB_PASSWORD is set. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class ShareCapitalPostingTest {

    @Autowired ShareCapitalController shares;
    @Autowired JdbcTemplate jdbc;

    /** An active account that has several ledger rows. */
    private Map<String, Object> someAccount() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.ClientIDSC AS client, s.SCSLT_CODE AS typ, s.SCREF_NO AS ref FROM sc s
                WHERE s.SCBR_CODE = 1 AND s.SCSLC_CODE = 31 AND s.SCSTATUS = 15
                  AND (SELECT COUNT(*) FROM sldtl d WHERE d.SL_BRCODE = 1 AND d.SL_CLIENTID = s.ClientIDSC AND d.SLC_CODE = 31 AND d.SLT_CODE = s.SCSLT_CODE AND d.REF_NO = s.SCREF_NO) >= 3
                ORDER BY s.ClientIDSC LIMIT 1
                """);
        assumeTrue(!rows.isEmpty(), "no share capital account with history in this database");
        return rows.get(0);
    }

    @Test
    void balanceIsTheNegatedSumOfTheLedgerRows() {
        Map<String, Object> a = someAccount();
        int client = ((Number) a.get("client")).intValue(), typ = ((Number) a.get("typ")).intValue(), ref = ((Number) a.get("ref")).intValue();
        BigDecimal expected = jdbc.queryForObject("SELECT -SUM(AMT) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 31 AND SLT_CODE = ? AND REF_NO = ?",
                BigDecimal.class, client, typ, ref);
        ShareCapitalController.Summary s = shares.accounts(1, client, false);
        assertTrue(shares.accounts(1, client, true).accounts().size() >= s.accounts().size());
        ShareCapitalController.Account acc = s.accounts().stream().filter((x) -> x.typeCode() == typ && x.refNo() == ref).findFirst().orElseThrow();
        assertEquals(0, expected.compareTo(acc.balance()));
        assertEquals("Active", acc.statusName());
        assertFalse(s.accounts().isEmpty());
        assertEquals(0, s.accounts().stream().map(ShareCapitalController.Account::balance).reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(s.totalBalance()));
    }

    @Test
    void historyRunsBackFromTheBalance() {
        Map<String, Object> a = someAccount();
        int client = ((Number) a.get("client")).intValue(), typ = ((Number) a.get("typ")).intValue(), ref = ((Number) a.get("ref")).intValue();
        ShareCapitalController.History h = shares.history(1, client, typ, ref, 0, 25);
        assertTrue(h.entries().size() >= 3);
        assertEquals(0, h.account().balance().compareTo(h.entries().get(0).balanceAfter()), "the newest entry leaves the account at its balance");
        for (int i = 1; i < h.entries().size(); i++) {
            ShareCapitalController.Entry newer = h.entries().get(i - 1), older = h.entries().get(i);
            assertEquals(0, newer.balanceAfter().subtract(newer.amount()).compareTo(older.balanceAfter()), "each balance is the next one up minus that entry");
        }
        ShareCapitalController.History second = shares.history(1, client, typ, ref, 1, 1);
        assertEquals(1, second.entries().size());
        assertEquals(h.entries().get(1).documentNo(), second.entries().get(0).documentNo());
        assertEquals(0, h.entries().get(1).balanceAfter().compareTo(second.entries().get(0).balanceAfter()));
    }

    @Test
    void refusesUnknownAccountsAndHugeWindows() {
        assertThrows(ResponseStatusException.class, () -> shares.history(1, 1, 99, 99999, 0, 25));
        assertThrows(ResponseStatusException.class, () -> shares.history(1, 1, 1, 1, 0, 5000));
    }
}
