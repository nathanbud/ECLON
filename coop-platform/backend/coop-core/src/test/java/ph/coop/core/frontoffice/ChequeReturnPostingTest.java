package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;
import ph.coop.core.frontoffice.ChequeReturnController.Candidate;
import ph.coop.core.frontoffice.ChequeReturnController.Request;

/**
 * Returns a real old savings cheque through the real code (find, ask, a second person approves, post), compares the rows with the old General Journal
 * (documentation/cheque-receipt-posting-map.md), and rolls everything back. Needs V026 (the cleared flag) and a head cashier with a supervisor
 * other than themselves; skips otherwise. Runs only when DB_PASSWORD is set.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class ChequeReturnPostingTest {

    @Autowired ChequeReturnController returns;
    @Autowired OverrideController overrides;
    @Autowired JdbcTemplate jdbc;
    @Autowired Db db;

    @Test
    void aReturnedSavingsChequeDebitsTheMemberAndCreditsTheVaultWithAnotherPersonApproving() {
        assumeTrue(db.canUpdateColumn("transck", "CKISCLEARED"), "V026_cheque_clearing.sql has not been run");
        List<Map<String, Object>> h = jdbc.queryForList("""
                SELECT AccessID, AccessUserName FROM access WHERE AccessBR_CODE = 1 AND COALESCE(AccessIsHeadCashier, 0) = 1 AND COALESCE(AccessIsDisabled, 0) = 0
                  AND COALESCE(AccessCanAccessFrontOffice, 0) = 1 AND COALESCE(AccessDepartment, 0) > 0 AND AccessLevel <> 'SUPERADMIN' ORDER BY AccessID LIMIT 1
                """);
        assumeTrue(!h.isEmpty(), "no head cashier in this database");
        int headId = ((Number) h.get(0).get("AccessID")).intValue();
        Authentication head = new TestingAuthenticationToken((String) h.get(0).get("AccessUserName"), null);
        List<Map<String, Object>> o = jdbc.queryForList("""
                SELECT AccessUserName FROM access WHERE AccessBR_CODE = 1 AND AccessID <> ? AND COALESCE(AccessIsDisabled, 0) = 0 AND COALESCE(AccessCanAccessFrontOffice, 0) = 1 ORDER BY AccessID LIMIT 1
                """, headId);
        assumeTrue(!o.isEmpty(), "no second person to approve");
        Authentication boss = new TestingAuthenticationToken((String) o.get(0).get("AccessUserName"), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        // a real cheque that paid a savings deposit
        List<String> numbers = jdbc.queryForList("""
                SELECT DISTINCT CHECKNO FROM sldtl WHERE SL_BRCODE = 1 AND SLC_CODE = 22 AND SLE_CODE = 11 AND TR_CODE = 10 AND COCI_TYPE = 2 AND TR_DATE >= '2026-04-01' AND CHECKNO IS NOT NULL LIMIT 30
                """, String.class);
        Candidate c = null;
        for (String n : numbers) {
            for (Candidate x : returns.find(n, head)) if (x.problem() == null && !x.returned()) { c = x; break; }
            if (c != null) break;
        }
        assumeTrue(c != null, "no returnable savings cheque found");
        final Candidate cheque = c;
        Request req = new Request(c.year(), c.ctlNo(), c.seq(), "VAULT", null, null, null);
        String balanceSql = "SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 22 AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = 11";
        BigDecimal balanceBefore = jdbc.queryForObject(balanceSql, BigDecimal.class, c.clientId(), c.typeCode(), c.refNo());

        // not allowed before another person approves
        assertEquals("NONE", returns.check(req, head).approval());
        assertThrows(ResponseStatusException.class, () -> returns.post(req, head));
        OverrideController.Override ask = returns.ask(req, head);
        assertEquals("PENDING", ask.status());
        assertThrows(ResponseStatusException.class, () -> overrides.decide(ask.id(), new OverrideController.Decision(true, "ok"), head), "nobody approves their own request");
        overrides.decide(ask.id(), new OverrideController.Decision(true, "bank returned it"), boss);
        assertTrue(returns.check(req, head).ready());

        var posted = returns.post(req, head);
        assertEquals(0, c.amount().compareTo(posted.amount()));

        List<Map<String, Object>> dt = jdbc.queryForList("SELECT * FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 1 AND DTTR_CODE = 1 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO",
            posted.year(), posted.controlNo());
        assertEquals(2, dt.size());
        assertEquals(22, ((Number) dt.get(0).get("DTSLC_CODE")).intValue());
        assertEquals(11, ((Number) dt.get(0).get("DTSLE_CODE")).intValue());
        assertEquals(0, c.amount().compareTo((BigDecimal) dt.get(0).get("DTAMT")), "the member is debited");
        assertEquals("100111", dt.get(1).get("DTACCT_CODE"));
        assertEquals(3, ((Number) dt.get(1).get("DTSLT_CODE")).intValue());
        assertEquals(51, ((Number) dt.get(1).get("DTSLE_CODE")).intValue());
        assertEquals(0, c.amount().negate().compareTo((BigDecimal) dt.get(1).get("DTAMT")), "the vault cheques go down");
        assertEquals(headId, ((Number) dt.get(0).get("DTPOSTBY")).intValue());
        assertTrue(((Number) dt.get(0).get("DTAPPRVBY")).intValue() != headId, "a different person approved it");

        Map<String, Object> sl = jdbc.queryForMap("SELECT AMT, ADJ_FLAG, COCI_TYPE, TR_CODE FROM sldtl WHERE SL_BRCODE = 1 AND TR_CODE = 1 AND TR_CTLNO = ? AND SL_CLIENTID = ?", posted.controlNo(), c.clientId());
        assertEquals(0, c.amount().compareTo((BigDecimal) sl.get("AMT")));
        assertEquals(1, ((Number) sl.get("ADJ_FLAG")).intValue());
        assertNull(sl.get("COCI_TYPE"), "a journal row has no tender type");
        BigDecimal balanceAfter = jdbc.queryForObject(balanceSql, BigDecimal.class, c.clientId(), c.typeCode(), c.refNo());
        assertEquals(0, c.amount().compareTo(balanceBefore.subtract(balanceAfter)), "the savings balance went down by the cheque");
        Integer cleared = jdbc.queryForObject("SELECT CKISCLEARED FROM transck WHERE CKTR_BRCODE = 1 AND CKTR_YEAR = ? AND CKTR_CODE = 10 AND CKTR_CTLNO = ? AND CKSEQ_NO = ?", Integer.class, c.year(), c.ctlNo(), c.seq());
        assertEquals(1, cleared);

        // once only
        assertTrue(returns.find(cheque.number(), head).stream().anyMatch((x) -> x.ctlNo() == req.ctlNo() && x.returned()));
        assertThrows(ResponseStatusException.class, () -> returns.ask(req, head));
    }

    @Test
    void anOrdinaryUserCannotRecordAReturn() {
        List<Map<String, Object>> u = jdbc.queryForList("SELECT AccessUserName FROM access WHERE COALESCE(AccessIsHeadCashier, 0) = 0 AND COALESCE(AccessIsDisabled, 0) = 0 AND AccessBR_CODE = 1 LIMIT 1");
        assumeTrue(!u.isEmpty(), "no ordinary user");
        Authentication teller = new TestingAuthenticationToken((String) u.get(0).get("AccessUserName"), null);
        assertThrows(ResponseStatusException.class, () -> returns.find("123", teller));
    }
}
