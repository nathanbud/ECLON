package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import ph.coop.core.frontoffice.SharePaymentController.Receipt;
import ph.coop.core.frontoffice.SharePaymentController.Request;

/**
 * Posts a real share capital payment through the real code, compares the rows with the old system's Cash Receipt
 * (documentation/share-payment-posting-map.md), then rolls everything back. Uses an existing teller who may add cash
 * receipts and an existing active share capital account; skips when the database has none. Runs only when DB_PASSWORD is set;
 * needs the V006 and V011 grants.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class SharePaymentPostingTest {

    @Autowired SharePaymentController payments;
    @Autowired JdbcTemplate jdbc;

    private String teller;
    private int tellerId, tellerNumber, dept, branch = 1;
    private int client, type, ref;

    @BeforeEach
    void setUp() {
        List<Map<String, Object>> t = jdbc.queryForList("""
                SELECT a.AccessID, a.AccessUserName, a.AccessTellerNumber, a.AccessDepartment FROM access a
                JOIN access_trans x ON x.USERID = a.AccessID AND x.BR_CODE = a.AccessBR_CODE AND x.TR_CODE = 10 AND x.CAN_NEW = 1
                WHERE a.AccessCurrAssign LIKE '%Teller%' AND COALESCE(a.AccessIsHeadCashier, 0) = 0 AND COALESCE(a.AccessIsDisabled, 0) = 0
                  AND a.AccessTellerNumber > 0 AND a.AccessBR_CODE = 1 AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 1 AND COALESCE(a.AccessDepartment, 0) > 0
                  AND a.AccessLevel <> 'SUPERADMIN' ORDER BY a.AccessID LIMIT 1
                """);
        assumeTrue(!t.isEmpty(), "no teller who may add cash receipts");
        tellerId = ((Number) t.get(0).get("AccessID")).intValue();
        teller = (String) t.get(0).get("AccessUserName");
        tellerNumber = ((Number) t.get(0).get("AccessTellerNumber")).intValue();
        dept = ((Number) t.get(0).get("AccessDepartment")).intValue();
        List<Map<String, Object>> a = jdbc.queryForList("""
                SELECT s.ClientIDSC AS client, s.SCSLT_CODE AS typ, s.SCREF_NO AS ref FROM sc s
                JOIN client c ON c.ClientIDBrCode = s.SCBR_CODE AND c.ClientID = s.ClientIDSC AND c.AccountStatus = 1
                JOIN glcontrol g ON g.GLControlBR_CODE = s.SCBR_CODE AND g.GLControlSLC_CODE = 31 AND g.GLControlSLT_CODE = s.SCSLT_CODE AND g.GLControlSLE_CODE = 11 AND g.GLControlCTS_NUM = 15
                WHERE s.SCBR_CODE = 1 AND s.SCSLC_CODE = 31 AND s.SCSTATUS = 15 AND s.SCSLT_CODE = 1 AND s.SCMAX_BAL > 100000000
                ORDER BY s.ClientIDSC DESC LIMIT 1
                """);
        assumeTrue(!a.isEmpty(), "no active share capital account");
        client = ((Number) a.get(0).get("client")).intValue();
        type = ((Number) a.get(0).get("typ")).intValue();
        ref = ((Number) a.get(0).get("ref")).intValue();
    }

    private Authentication as(String name) { return new TestingAuthenticationToken(name, null); }

    private BigDecimal balance() {
        return jdbc.queryForObject("SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 31 AND SLT_CODE = ? AND REF_NO = ?", BigDecimal.class, client, type, ref);
    }

    private BigDecimal glNet(String account) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT), 0) FROM gltrans WHERE GLTransBR_CODE = 1 AND GLTransTR_DATE = ? AND GLTransTR_CODE = 10 AND GLTransACCT_CODE = ?",
            BigDecimal.class, LocalDate.now().toString(), account);
    }

    @Test
    void postsTheSameRowsTheOldCashReceiptWrote() {
        BigDecimal before = balance();
        BigDecimal cashBefore = glNet("10003"), shareBefore = glNet("3010101");
        Integer seqBefore = jdbc.queryForObject("SELECT COALESCE(MAX(SEQNO), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 31 AND SLT_CODE = ? AND REF_NO = ?", Integer.class, client, type, ref);

        Receipt r = payments.post(new Request(client, type, ref, new BigDecimal("123.45"), "itest", null, false), as(teller));

        assertEquals(0, new BigDecimal("123.45").compareTo(r.amount()));
        assertEquals(0, before.add(new BigDecimal("123.45")).compareTo(r.balanceAfter()));
        assertEquals(0, before.add(new BigDecimal("123.45")).compareTo(balance()), "the member's balance went up by the payment");
        assertEquals(String.format("*%07d", r.controlNo()), r.documentNo());

        Map<String, Object> h = jdbc.queryForMap("SELECT TR_DOCNO, TR_DATE, TR_CLIENTID, BATCH_NO, EXPLANATION, IS_FO FROM transsum WHERE TR_BRCODE = 1 AND TR_YEAR = ? AND TR_MODULE = 3 AND TR_CODE = 10 AND TR_CTLNO = ?", r.year(), r.controlNo());
        assertEquals(client, ((Number) h.get("TR_CLIENTID")).intValue());
        assertEquals("itest", h.get("EXPLANATION"));
        assertEquals(1, ((Number) h.get("IS_FO")).intValue());
        assertEquals(LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE), h.get("BATCH_NO"));

        List<Map<String, Object>> dt = jdbc.queryForList("SELECT * FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 3 AND DTTR_CODE = 10 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO", r.year(), r.controlNo());
        assertEquals(2, dt.size());
        Map<String, Object> cash = dt.get(0), share = dt.get(1);
        assertEquals("10003", cash.get("DTACCT_CODE"));
        assertEquals(53, ((Number) cash.get("DTSLE_CODE")).intValue(), "the teller's drawer");
        assertEquals(tellerNumber, ((Number) cash.get("DTCTS_NUM")).intValue());
        assertEquals(0, new BigDecimal("123.45").compareTo((BigDecimal) cash.get("DTAMT")));
        assertEquals("3010101", share.get("DTACCT_CODE"));
        assertEquals(client, ((Number) share.get("ClientIDDT")).intValue());
        assertEquals(31, ((Number) share.get("DTSLC_CODE")).intValue());
        assertEquals(ref, ((Number) share.get("DTREF_NO")).intValue());
        assertEquals(11, ((Number) share.get("DTSLE_CODE")).intValue());
        assertEquals(15, ((Number) share.get("DTCTS_NUM")).intValue(), "the account status");
        assertEquals(0, new BigDecimal("-123.45").compareTo((BigDecimal) share.get("DTAMT")));
        assertEquals(0, ((BigDecimal) cash.get("DTAMT")).add((BigDecimal) share.get("DTAMT")).signum(), "the two entries cancel out");
        for (Map<String, Object> l : dt) {
            assertEquals(tellerId, ((Number) l.get("DTENCDBY")).intValue());
            assertEquals(tellerId, ((Number) l.get("DTPOSTBY")).intValue());
            assertEquals(dept, ((Number) l.get("DTDEPT")).intValue());
            assertEquals(1, ((Number) l.get("DTCOCI_TYPE")).intValue(), "cash");
            assertEquals(1, ((Number) l.get("DTUPD_TAG")).intValue());
        }

        Map<String, Object> sl = jdbc.queryForMap("SELECT SEQNO, TR_CODE, TR_CTLNO, TR_DOCNO, AMT, ADJ_FLAG, COCI_TYPE FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 31 AND SLT_CODE = ? AND REF_NO = ? AND TR_CTLNO = ? AND TR_CODE = 10",
            client, type, ref, r.controlNo());
        assertEquals(seqBefore + 1, ((Number) sl.get("SEQNO")).intValue());
        assertEquals(r.documentNo(), sl.get("TR_DOCNO"));
        assertEquals(0, new BigDecimal("-123.45").compareTo((BigDecimal) sl.get("AMT")));

        assertEquals(0, cashBefore.add(new BigDecimal("123.45")).compareTo(glNet("10003")), "cash on hand went up in the general ledger");
        assertEquals(0, shareBefore.subtract(new BigDecimal("123.45")).compareTo(glNet("3010101")), "share capital went up (credit)");
    }

    @Test
    void aTypedReceiptNumberIsKept() {
        Receipt r = payments.post(new Request(client, type, ref, new BigDecimal("1"), null, "OR12345", false), as(teller));
        assertEquals("OR12345", r.documentNo());
        assertEquals("OR12345", jdbc.queryForObject("SELECT TR_DOCNO FROM transsum WHERE TR_BRCODE = 1 AND TR_YEAR = ? AND TR_MODULE = 3 AND TR_CODE = 10 AND TR_CTLNO = ?", String.class, r.year(), r.controlNo()));
    }

    @Test
    void anotherReceiptTakesTheNextControlNumberAndARepeatNeedsConfirming() {
        Receipt a = payments.post(new Request(client, type, ref, new BigDecimal("5"), null, null, false), as(teller));
        ResponseStatusException dup = assertThrows(ResponseStatusException.class,
            () -> payments.post(new Request(client, type, ref, new BigDecimal("5"), null, null, false), as(teller)));
        assertEquals(409, dup.getStatusCode().value());
    }

    @Test
    void confirmedRepeatPostsWithTheNextControlNumber() {
        Receipt a = payments.post(new Request(client, type, ref, new BigDecimal("5"), null, null, false), as(teller));
        Receipt b = payments.post(new Request(client, type, ref, new BigDecimal("5"), null, null, true), as(teller));
        assertEquals(a.controlNo() + 1, b.controlNo());
        assertNotEquals(a.documentNo(), b.documentNo());
    }

    @Test
    void refusesPeopleWhoMayNotTakeCashAndBadRequests() {
        // a person without a drawer or without the right to add cash receipts
        List<String> other = jdbc.queryForList("""
                SELECT a.AccessUserName FROM access a WHERE COALESCE(a.AccessIsDisabled, 0) = 0 AND a.AccessLevel <> 'SUPERADMIN' AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 0 LIMIT 1
                """, String.class);
        if (!other.isEmpty()) {
            assertThrows(ResponseStatusException.class, () -> payments.post(new Request(client, type, ref, new BigDecimal("1"), null, null, false), as(other.get(0))));
        }
        assertThrows(ResponseStatusException.class, () -> payments.post(new Request(client, type, ref, new BigDecimal("0"), null, null, false), as(teller)));
        assertThrows(ResponseStatusException.class, () -> payments.post(new Request(client, type, ref, new BigDecimal("1.005"), null, null, false), as(teller)));
        assertThrows(ResponseStatusException.class, () -> payments.post(new Request(client, type, 99999999, new BigDecimal("1"), null, null, false), as(teller)));
        assertThrows(ResponseStatusException.class, () -> payments.post(new Request(client, type, ref, new BigDecimal("1"), null, "bad number!", false), as(teller)));
    }

    @Test
    void refusesAClosedShareAccount() {
        List<Map<String, Object>> closed = jdbc.queryForList("SELECT ClientIDSC AS client, SCSLT_CODE AS typ, SCREF_NO AS ref FROM sc WHERE SCBR_CODE = 1 AND SCSLC_CODE = 31 AND SCSTATUS = 17 LIMIT 1");
        assumeTrue(!closed.isEmpty(), "no closed share capital account");
        Map<String, Object> c = closed.get(0);
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> payments.post(
            new Request(((Number) c.get("client")).intValue(), ((Number) c.get("typ")).intValue(), ((Number) c.get("ref")).intValue(), new BigDecimal("1"), null, null, false), as(teller)));
        assertTrue(e.getReason() != null && e.getReason().contains("not active"));
    }
}
