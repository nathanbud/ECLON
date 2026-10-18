package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.frontoffice.HandoverController.Count;
import ph.coop.core.frontoffice.HandoverController.Handover;
import ph.coop.core.frontoffice.HandoverController.Issue;
import ph.coop.core.frontoffice.HandoverRules.Drawer;

/**
 * Posts real cash hand-offs through the real code against the database, then rolls everything back, so nothing stays behind
 * (the ledger tables are InnoDB). Runs only when DB_USER and DB_PASSWORD are set (use the limited coop_app user, with the
 * V006 grants); otherwise it is skipped. Uses whoever the database has as head cashier and teller, and skips if there is none.
 * Note: rolled-back inserts still use up auto-increment ids, which leaves harmless gaps.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class HandoverPostingTest {

    @Autowired HandoverController handover;
    @Autowired JdbcTemplate jdbc;
    @Autowired DrawerBalances balances;

    private String headName, tellerName;
    private int branch, headNumber, tellerNumber, tellerId;
    private Drawer head, teller;

    @BeforeEach
    void people() {
        List<Map<String, Object>> h = jdbc.queryForList("""
                SELECT AccessUserName, AccessBR_CODE, AccessTellerNumber FROM access
                WHERE AccessIsHeadCashier = 1 AND AccessTellerNumber > 0 AND COALESCE(AccessIsDisabled, 0) = 0 AND AccessLevel <> 'SUPERADMIN'
                  AND COALESCE(AccessCanAccessFrontOffice, 0) = 1 AND COALESCE(AccessDepartment, 0) > 0 ORDER BY AccessID LIMIT 1
                """);
        assumeTrue(!h.isEmpty(), "no head cashier in this database");
        headName = (String) h.get(0).get("AccessUserName");
        branch = ((Number) h.get(0).get("AccessBR_CODE")).intValue();
        headNumber = ((Number) h.get(0).get("AccessTellerNumber")).intValue();
        List<Map<String, Object>> t = jdbc.queryForList("""
                SELECT a.AccessID, a.AccessUserName, a.AccessTellerNumber FROM access a
                JOIN access_trans x ON x.USERID = a.AccessID AND x.BR_CODE = a.AccessBR_CODE AND x.TR_CODE = 32 AND x.CAN_NEW = 1
                WHERE a.AccessCurrAssign LIKE '%Teller%' AND a.AccessCurrAssign NOT LIKE '%Cashier%' AND COALESCE(a.AccessIsHeadCashier, 0) = 0
                  AND COALESCE(a.AccessIsDisabled, 0) = 0 AND a.AccessTellerNumber > 0 AND a.AccessBR_CODE = ?
                  AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 1 AND COALESCE(a.AccessDepartment, 0) > 0 ORDER BY a.AccessID LIMIT 1
                """, branch);
        assumeTrue(!t.isEmpty(), "no teller who may add transfers in this database");
        tellerId = ((Number) t.get(0).get("AccessID")).intValue();
        tellerName = (String) t.get(0).get("AccessUserName");
        tellerNumber = ((Number) t.get(0).get("AccessTellerNumber")).intValue();
        head = new Drawer(52, headNumber);
        teller = new Drawer(53, tellerNumber);
    }

    private static Authentication as(String name) {
        return new TestingAuthenticationToken(name, null);
    }

    private static Issue give(int fromEvent, int toEvent, Integer toNumber, String amount, Map<Integer, Integer> counts, String key, String reason) {
        return new Issue(fromEvent, toEvent, toNumber, new BigDecimal(amount), counts, "integration test", key, reason);
    }

    private BigDecimal cash(Drawer d) {
        return balances.of(branch, d);
    }

    private static void sameAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }

    private List<Map<String, Object>> lines(int code, long ctl) {
        return jdbc.queryForList("""
                SELECT DTSEQ_NO, DTACCT_CODE, DTAMT, DTSLE_CODE, DTCTS_NUM, DTSLE_CODE2, DTCTS_NUM2, DTENCDBY, DTAPPRVBY, DTPOSTBY, DTUPD_TAG, DTCOCI_TYPE
                FROM transdt WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = 4 AND DTTR_CODE = ? AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO
                """, branch, LocalDate.now().getYear(), code, ctl);
    }

    @Test
    void vaultTakeAndReturnPostBalancedLedgerRows() {
        BigDecimal before = cash(head);

        Handover take = handover.issue(give(50, 52, headNumber, "1000", Map.of(1, 1), "it-take-" + System.nanoTime(), null), as(headName));
        assertEquals("POSTED", take.status());
        assertEquals(31, take.trCode());
        assertNotNull(take.ctlNo());
        sameAmount("1000", cash(head).subtract(before));

        // The header and the two entries are written the way the old window wrote them.
        Map<String, Object> header = jdbc.queryForMap("SELECT TR_DOCNO, IS_FO, BATCH_NO FROM transsum WHERE TR_BRCODE = ? AND TR_YEAR = ? AND TR_MODULE = 4 AND TR_CODE = 31 AND TR_CTLNO = ?",
            branch, LocalDate.now().getYear(), take.ctlNo());
        assertEquals(String.format("*%07d", take.ctlNo()), header.get("TR_DOCNO"));
        assertEquals(1, ((Number) header.get("IS_FO")).intValue());
        List<Map<String, Object>> l = lines(31, take.ctlNo());
        assertEquals(2, l.size());
        assertEquals("10001", l.get(0).get("DTACCT_CODE"));
        sameAmount("-1000", (BigDecimal) l.get(0).get("DTAMT"));
        assertEquals(50, ((Number) l.get(0).get("DTSLE_CODE")).intValue());
        assertEquals(99, ((Number) l.get(0).get("DTCTS_NUM")).intValue());
        assertEquals("10002", l.get(1).get("DTACCT_CODE"));
        sameAmount("1000", (BigDecimal) l.get(1).get("DTAMT"));
        assertEquals(headNumber, ((Number) l.get(1).get("DTCTS_NUM")).intValue());
        assertNull(l.get(1).get("DTAPPRVBY"), "a vault move has no approver in the old window");
        assertEquals(1, ((Number) l.get(1).get("DTUPD_TAG")).intValue());
        sameAmount("0", ((BigDecimal) l.get(0).get("DTAMT")).add((BigDecimal) l.get(1).get("DTAMT")));

        // The count is stored by note and coin.
        Number qty = jdbc.queryForObject("SELECT d.QTY FROM denomsum s JOIN denomdtl d ON d.DENOMSUMID = s.ID WHERE s.BR_CODE = ? AND s.TR_CODE = 31 AND s.TR_CTLNO = ? AND d.DENOMTYPEID = 1",
            Number.class, branch, take.ctlNo());
        assertEquals(1, qty.intValue());
        // And in the general ledger, credit negative.
        Integer gl = jdbc.queryForObject("SELECT COUNT(*) FROM gltrans WHERE GLTransBR_CODE = ? AND GLTransTR_DATE = ? AND GLTransTR_CODE = 31 AND ((GLTransACCT_CODE = '10001' AND GLTransCR_AMT = -1000) OR (GLTransACCT_CODE = '10002' AND GLTransDR_AMT = 1000))",
            Integer.class, branch, LocalDate.now().toString());
        assertTrue(gl != null && gl >= 2);

        // Putting it back needs a reason when the day is not ready to close; the reason is accepted and everything balances again.
        Handover back = handover.issue(give(52, 50, 99, "1000", Map.of(1, 1), "it-back-" + System.nanoTime(), "integration test"), as(headName));
        assertEquals("POSTED", back.status());
        assertEquals(32, back.trCode());
        sameAmount("0", cash(head).subtract(before));
    }

    @Test
    void theSameAttemptSentTwiceIsPostedOnce() {
        Integer hasKey = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ecis_cash_handover' AND column_name = 'request_key'", Integer.class);
        assumeTrue(hasKey != null && hasKey > 0, "V007_drawer_controls.sql has not been run, so there is no request key");
        String key = "it-twice-" + System.nanoTime();
        BigDecimal before = cash(head);
        Handover first = handover.issue(give(50, 52, headNumber, "1000", Map.of(1, 1), key, null), as(headName));
        Handover again = handover.issue(give(50, 52, headNumber, "1000", Map.of(1, 1), key, null), as(headName));
        assertEquals(first.id(), again.id());
        sameAmount("1000", cash(head).subtract(before));
    }

    @Test
    void aHandOffBetweenPeopleIsPostedOnlyWhenTheReceiversCountMatches() {
        handover.issue(give(50, 52, headNumber, "5000", Map.of(1, 5), "it-fund-" + System.nanoTime(), null), as(headName));
        BigDecimal headBefore = cash(head);
        BigDecimal tellerBefore = cash(teller);

        // Given to the teller: waiting, nothing in the ledger yet.
        Handover sent = handover.issue(give(52, 53, tellerNumber, "500", Map.of(2, 1), "it-send-" + System.nanoTime(), null), as(headName));
        assertEquals("PENDING", sent.status());
        assertNull(sent.ctlNo());
        sameAmount("0", cash(head).subtract(headBefore));

        // A wrong count is recorded as a difference and posts nothing.
        Handover wrong = handover.accept(sent.id(), new Count(Map.of(1, 1)), as(tellerName));
        assertEquals("DISPUTED", wrong.status());
        sameAmount("0", cash(teller).subtract(tellerBefore));

        // The giver takes it back, and tries again.
        assertEquals("CANCELLED", handover.cancel(sent.id(), as(headName)).status());
        Handover second = handover.issue(give(52, 53, tellerNumber, "500", Map.of(2, 1), "it-send2-" + System.nanoTime(), null), as(headName));
        Handover accepted = handover.accept(second.id(), new Count(Map.of(2, 1)), as(tellerName));
        assertEquals("POSTED", accepted.status());
        sameAmount("500", cash(teller).subtract(tellerBefore));
        sameAmount("-500", cash(head).subtract(headBefore));

        // The receiving teller is the approver; the giver encoded and posted it.
        List<Map<String, Object>> l = lines(32, accepted.ctlNo());
        assertEquals(2, l.size());
        sameAmount("-500", (BigDecimal) l.get(0).get("DTAMT"));
        assertEquals(53, ((Number) l.get(1).get("DTSLE_CODE")).intValue());
        assertEquals(tellerNumber, ((Number) l.get(1).get("DTCTS_NUM")).intValue());
        assertEquals(tellerId, ((Number) l.get(1).get("DTAPPRVBY")).intValue());

        // It cannot be accepted twice.
        assertThrows(ResponseStatusException.class, () -> handover.accept(second.id(), new Count(Map.of(2, 1)), as(tellerName)));

        // And the teller hands it back; the head cashier counts it in.
        Handover ret = handover.issue(give(53, 52, headNumber, "500", Map.of(2, 1), "it-ret-" + System.nanoTime(), null), as(tellerName));
        assertEquals("PENDING", ret.status());
        assertEquals("POSTED", handover.accept(ret.id(), new Count(Map.of(2, 1)), as(headName)).status());
        sameAmount("0", cash(teller).subtract(tellerBefore));
        sameAmount("0", cash(head).subtract(headBefore));
    }

    @Test
    void theRulesAreEnforcedOnTheServer() {
        // A teller cannot touch the vault.
        assertThrows(ResponseStatusException.class, () -> handover.issue(give(53, 50, 99, "100", Map.of(3, 0, 4, 1), "it-r1-" + System.nanoTime(), null), as(tellerName)));
        // The notes and coins must add up to the amount.
        assertThrows(ResponseStatusException.class, () -> handover.issue(give(52, 53, tellerNumber, "500", Map.of(2, 2), "it-r2-" + System.nanoTime(), null), as(headName)));
        // Nobody can give more than their drawer has free.
        assumeTrue(cash(teller).compareTo(new BigDecimal("1000000")) < 0, "this teller holds more than the amount used for the test");
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> handover.issue(give(53, 52, headNumber, "1000000", Map.of(1, 1000), "it-r3-" + System.nanoTime(), null), as(tellerName)));
        assertTrue(String.valueOf(e.getReason()).contains("free to give"), "refused for the right reason: " + e.getReason());
    }
}
