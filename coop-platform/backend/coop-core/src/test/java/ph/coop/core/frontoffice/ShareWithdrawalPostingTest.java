package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
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
import ph.coop.core.frontoffice.ShareWithdrawalController.Encoded;
import ph.coop.core.frontoffice.ShareWithdrawalController.Request;

/**
 * Encodes a share capital withdrawal (Cash Voucher type 21, see documentation/share-withdrawal-posting-map.md), approves it and posts it
 * through the loan release screen's code, compares the rows with the real voucher, then rolls everything back. Needs three people who may
 * add vouchers of type 21 and an active share capital account with capital and a vouchers-payable account; skips when the database has none.
 * Runs only when DB_PASSWORD is set; needs the V015 grants.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class ShareWithdrawalPostingTest {

    @Autowired ShareWithdrawalController withdrawals;
    @Autowired LoanReleaseController releases;
    @Autowired JdbcTemplate jdbc;

    private Authentication encoder, approver, poster;
    private int encoderId, client, type, ref, payableRef;

    @BeforeEach
    void setUp() {
        try {
            jdbc.update("UPDATE transdt SET DTPOSTBY = DTPOSTBY WHERE DTTR_YEAR = -1");
        } catch (RuntimeException e) {
            assumeTrue(false, "the UPDATE grants (V015) are missing, or MySQL is read only");
        }
        List<Map<String, Object>> people = jdbc.queryForList("""
                SELECT a.AccessID, a.AccessUserName FROM access a
                JOIN access_trans x ON x.USERID = a.AccessID AND x.BR_CODE = a.AccessBR_CODE AND x.TR_CODE = 21 AND x.CAN_NEW = 1
                WHERE a.AccessBR_CODE = 1 AND COALESCE(a.AccessIsDisabled, 0) = 0 AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 1 AND COALESCE(a.AccessDepartment, 0) > 0
                ORDER BY a.AccessID LIMIT 3
                """);
        assumeTrue(people.size() == 3, "fewer than three people may add vouchers of type 21");
        encoderId = ((Number) people.get(0).get("AccessID")).intValue();
        encoder = new TestingAuthenticationToken((String) people.get(0).get("AccessUserName"), null);
        approver = new TestingAuthenticationToken((String) people.get(1).get("AccessUserName"), null);
        poster = new TestingAuthenticationToken((String) people.get(2).get("AccessUserName"), null);
        List<Map<String, Object>> a = jdbc.queryForList("""
                SELECT s.ClientIDSC AS client, s.SCSLT_CODE AS typ, s.SCREF_NO AS ref, m.MREF_NO AS pref FROM sc s
                JOIN client c ON c.ClientIDBrCode = s.SCBR_CODE AND c.ClientID = s.ClientIDSC AND c.AccountStatus = 1
                JOIN slmastersum m ON m.MBR_CODE = s.SCBR_CODE AND m.MCLIENTID = s.ClientIDSC AND m.MSLC_CODE = 29 AND m.MSLT_CODE = 1
                JOIN glcontrol g ON g.GLControlBR_CODE = s.SCBR_CODE AND g.GLControlSLC_CODE = 31 AND g.GLControlSLT_CODE = s.SCSLT_CODE AND g.GLControlSLE_CODE = 11 AND g.GLControlCTS_NUM = 15
                WHERE s.SCBR_CODE = 1 AND s.SCSLC_CODE = 31 AND s.SCSTATUS = 15 AND s.SCSLT_CODE = 1
                  AND (SELECT -SUM(d.AMT) FROM sldtl d WHERE d.SL_BRCODE = 1 AND d.SL_CLIENTID = s.ClientIDSC AND d.SLC_CODE = 31 AND d.SLT_CODE = 1 AND d.REF_NO = s.SCREF_NO AND d.SLE_CODE = 11) >= 1000
                ORDER BY s.ClientIDSC DESC LIMIT 1
                """);
        assumeTrue(!a.isEmpty(), "no active share capital account with capital and a vouchers-payable account");
        client = ((Number) a.get(0).get("client")).intValue();
        type = ((Number) a.get(0).get("typ")).intValue();
        ref = ((Number) a.get(0).get("ref")).intValue();
        payableRef = ((Number) a.get(0).get("pref")).intValue();
    }

    private BigDecimal balance(int slc, int typ, int r) {
        return jdbc.queryForObject("SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = ? AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = 11",
            BigDecimal.class, client, slc, typ, r);
    }

    @Test
    void encodesLikeTheOldVoucherThenPostsAfterTwoOthersAct() {
        BigDecimal amount = new BigDecimal("123.45");
        BigDecimal shareBefore = balance(31, type, ref), payableBefore = balance(29, 1, payableRef);

        Encoded e = withdrawals.encode(new Request(client, type, ref, amount, "itest"), encoder);
        assertEquals(String.format("*%07d", e.controlNo()), e.documentNo());
        assertEquals(0, shareBefore.subtract(amount).compareTo(e.availableAfter()));

        Map<String, Object> h = jdbc.queryForMap("SELECT TR_DOCNO, TR_CLIENTID, IS_FO, EXPLANATION FROM transsum WHERE TR_BRCODE = 1 AND TR_YEAR = ? AND TR_MODULE = 2 AND TR_CODE = 21 AND TR_CTLNO = ?",
            e.year(), e.controlNo());
        assertEquals(client, ((Number) h.get("TR_CLIENTID")).intValue());
        assertEquals(0, ((Number) h.get("IS_FO")).intValue(), "a back-office voucher");
        List<Map<String, Object>> dt = jdbc.queryForList("SELECT * FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 2 AND DTTR_CODE = 21 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO",
            e.year(), e.controlNo());
        assertEquals(2, dt.size());
        assertEquals(31, ((Number) dt.get(0).get("DTSLC_CODE")).intValue());
        assertEquals(0, amount.compareTo((BigDecimal) dt.get(0).get("DTAMT")), "the share capital is debited");
        assertEquals(29, ((Number) dt.get(1).get("DTSLC_CODE")).intValue());
        assertEquals(payableRef, ((Number) dt.get(1).get("DTREF_NO")).intValue());
        assertEquals(0, amount.negate().compareTo((BigDecimal) dt.get(1).get("DTAMT")), "vouchers payable is credited");
        for (Map<String, Object> l : dt) {
            assertEquals(2, ((Number) l.get("DTUPD_TAG")).intValue(), "waiting for approval");
            assertEquals(encoderId, ((Number) l.get("DTENCDBY")).intValue());
            assertEquals(11, ((Number) l.get("DTSLE_CODE")).intValue());
        }
        assertEquals(0, shareBefore.compareTo(balance(31, type, ref)), "nothing reaches the ledger before posting");

        assertThrows(ResponseStatusException.class, () -> releases.approve(e.year(), e.controlNo(), encoder), "the encoder cannot approve");
        releases.approve(e.year(), e.controlNo(), approver);
        assertThrows(ResponseStatusException.class, () -> releases.post(e.year(), e.controlNo(), approver), "the approver cannot post");
        releases.post(e.year(), e.controlNo(), poster);

        assertEquals(0, shareBefore.subtract(amount).compareTo(balance(31, type, ref)), "capital went down");
        assertEquals(0, payableBefore.add(amount).compareTo(balance(29, 1, payableRef)), "payable went up");
        Integer ledger = jdbc.queryForObject("SELECT COUNT(*) FROM sldtl WHERE SL_BRCODE = 1 AND TR_CODE = 21 AND TR_CTLNO = ? AND SL_CLIENTID = ?", Integer.class, e.controlNo(), client);
        assertTrue(ledger != null && ledger >= 2);
    }

    @Test
    void refusesMoreThanTheCapitalAndRepeatsThatWaitInOtherVouchers() {
        BigDecimal balance = balance(31, type, ref);
        assertThrows(ResponseStatusException.class, () -> withdrawals.encode(new Request(client, type, ref, balance.add(BigDecimal.ONE), "itest"), encoder));
        withdrawals.encode(new Request(client, type, ref, balance, "itest"), encoder);
        assertThrows(ResponseStatusException.class, () -> withdrawals.encode(new Request(client, type, ref, new BigDecimal("1.00"), "itest"), encoder),
            "the capital is already promised to the first voucher");
    }
}
