package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import ph.coop.core.frontoffice.SavingsPostingController.Check;
import ph.coop.core.frontoffice.SavingsPostingController.Receipt;
import ph.coop.core.frontoffice.SavingsPostingController.Request;

/**
 * Posts a real savings deposit and withdrawal through the real code, compares the rows with the old system's Cash Receipt and
 * Deposit Withdrawal (documentation/savings-posting-map.md), then rolls everything back. Uses an existing teller who may add both,
 * and existing active savings accounts; skips when the database has none. Runs only when DB_PASSWORD is set; needs the V006, V007,
 * V011 and V012 migrations.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class SavingsLedgerPostingTest {

    @Autowired SavingsPostingController savings;
    @Autowired JdbcTemplate jdbc;

    private String teller;
    private int tellerId, tellerNumber, dept;
    /** An active savings account with a minimum balance, plenty in it, and nothing on hold. */
    private int client, type, ref;
    private BigDecimal balance, min;

    @BeforeEach
    void setUp() {
        List<Map<String, Object>> t = jdbc.queryForList("""
                SELECT a.AccessID, a.AccessUserName, a.AccessTellerNumber, a.AccessDepartment FROM access a
                JOIN access_trans r ON r.USERID = a.AccessID AND r.BR_CODE = a.AccessBR_CODE AND r.TR_CODE = 10 AND r.CAN_NEW = 1
                JOIN access_trans w ON w.USERID = a.AccessID AND w.BR_CODE = a.AccessBR_CODE AND w.TR_CODE = 23 AND w.CAN_NEW = 1
                WHERE a.AccessCurrAssign LIKE '%Teller%' AND COALESCE(a.AccessIsHeadCashier, 0) = 0 AND COALESCE(a.AccessIsDisabled, 0) = 0
                  AND a.AccessTellerNumber > 0 AND a.AccessBR_CODE = 1 AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 1 AND COALESCE(a.AccessDepartment, 0) > 0
                  AND a.AccessLevel <> 'SUPERADMIN' AND a.AccessDisbursementLimit >= 50000 AND a.AccessMaxWithdrawal >= 50000 ORDER BY a.AccessID LIMIT 1
                """);
        assumeTrue(!t.isEmpty(), "no teller who may add receipts and withdrawals with a 50,000 limit");
        tellerId = ((Number) t.get(0).get("AccessID")).intValue();
        teller = (String) t.get(0).get("AccessUserName");
        tellerNumber = ((Number) t.get(0).get("AccessTellerNumber")).intValue();
        dept = ((Number) t.get(0).get("AccessDepartment")).intValue();
        List<Map<String, Object>> a = jdbc.queryForList("""
                SELECT s.ClientIDSD AS client, s.SDSLT_CODE AS typ, s.SDREF_NO AS ref, s.SDMIN_BAL AS minb,
                       (SELECT -SUM(d.AMT) FROM sldtl d WHERE d.SL_BRCODE = 1 AND d.SL_CLIENTID = s.ClientIDSD AND d.SLC_CODE = 22 AND d.SLT_CODE = s.SDSLT_CODE AND d.REF_NO = s.SDREF_NO AND d.SLE_CODE = 11) AS bal
                FROM sd s JOIN client c ON c.ClientIDBrCode = s.SDBR_CODE AND c.ClientID = s.ClientIDSD AND c.AccountStatus = 1
                JOIN glcontrol g ON g.GLControlBR_CODE = s.SDBR_CODE AND g.GLControlSLC_CODE = 22 AND g.GLControlSLT_CODE = s.SDSLT_CODE AND g.GLControlSLE_CODE = 11 AND g.GLControlCTS_NUM = 15
                WHERE s.SDBR_CODE = 1 AND s.SDSLC_CODE = 22 AND s.SDSTATUS = 15 AND s.SDSLT_CODE = 1 AND s.SDMIN_BAL >= 500
                  AND NOT EXISTS (SELECT 1 FROM hold h WHERE h.HBR_CODE = 1 AND h.ClientIDH = s.ClientIDSD AND h.HSLC_CODE = 22 AND h.HSLT_CODE = s.SDSLT_CODE AND h.HREF_NO = s.SDREF_NO)
                HAVING bal BETWEEN 2000 AND 40000 ORDER BY s.ClientIDSD DESC LIMIT 1
                """);
        assumeTrue(!a.isEmpty(), "no active savings account with a minimum balance and 2,000 to 40,000 in it");
        client = ((Number) a.get(0).get("client")).intValue();
        type = ((Number) a.get(0).get("typ")).intValue();
        ref = ((Number) a.get(0).get("ref")).intValue();
        min = (BigDecimal) a.get(0).get("minb");
        balance = (BigDecimal) a.get(0).get("bal");
    }

    private Authentication as(String name) { return new TestingAuthenticationToken(name, null); }

    private BigDecimal balanceNow() {
        return jdbc.queryForObject("SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 22 AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = 11", BigDecimal.class, client, type, ref);
    }

    private BigDecimal glNet(String account, int code) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT), 0) FROM gltrans WHERE GLTransBR_CODE = 1 AND GLTransTR_DATE = ? AND GLTransTR_CODE = ? AND GLTransACCT_CODE = ?",
            BigDecimal.class, LocalDate.now().toString(), code, account);
    }

    private Request req(String amount) { return new Request(client, type, ref, new BigDecimal(amount), "itest", null, false); }

    private long approvedOverride(Check c, String amount) {
        long last = 0;
        for (SavingsPostingController.NeedStatus n : c.needs()) {
            jdbc.update("INSERT INTO ecis_fo_override (branch_code, kind, description, amount, limit_amount, requested_by, requested_at, status, decided_by, decided_at) VALUES (1, ?, ?, ?, ?, ?, NOW(), 'APPROVED', ?, NOW())",
                n.kind(), n.description(), new BigDecimal(amount), n.limit(), tellerId, tellerId + 1);
            last = jdbc.queryForObject("SELECT MAX(id) FROM ecis_fo_override", Long.class);
        }
        return last;
    }

    @Test
    void aDepositPostsTheSameRowsTheOldCashReceiptWrote() {
        BigDecimal cashBefore = glNet("10003", 10), savBefore = glNet("2010101", 10);
        Integer seqBefore = jdbc.queryForObject("SELECT COALESCE(MAX(SEQNO), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 22 AND SLT_CODE = ? AND REF_NO = ?", Integer.class, client, type, ref);

        Receipt r = savings.deposit(req("800.25"), as(teller));

        assertEquals("DEPOSIT", r.kind());
        assertEquals(0, balance.add(new BigDecimal("800.25")).compareTo(r.balanceAfter()));
        assertEquals(0, balance.add(new BigDecimal("800.25")).compareTo(balanceNow()));
        assertEquals(String.format("*%07d", r.controlNo()), r.documentNo());

        Map<String, Object> h = jdbc.queryForMap("SELECT TR_CLIENTID, EXPLANATION, IS_FO FROM transsum WHERE TR_BRCODE = 1 AND TR_YEAR = ? AND TR_MODULE = 3 AND TR_CODE = 10 AND TR_CTLNO = ?", r.year(), r.controlNo());
        assertEquals(client, ((Number) h.get("TR_CLIENTID")).intValue());
        assertEquals("itest", h.get("EXPLANATION"));
        assertEquals(1, ((Number) h.get("IS_FO")).intValue());

        List<Map<String, Object>> dt = jdbc.queryForList("SELECT * FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 3 AND DTTR_CODE = 10 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO", r.year(), r.controlNo());
        assertEquals(2, dt.size());
        assertEquals("10003", dt.get(0).get("DTACCT_CODE"));
        assertEquals(53, ((Number) dt.get(0).get("DTSLE_CODE")).intValue());
        assertEquals(tellerNumber, ((Number) dt.get(0).get("DTCTS_NUM")).intValue());
        assertEquals(0, new BigDecimal("800.25").compareTo((BigDecimal) dt.get(0).get("DTAMT")));
        assertEquals("2010101", dt.get(1).get("DTACCT_CODE"));
        assertEquals(22, ((Number) dt.get(1).get("DTSLC_CODE")).intValue());
        assertEquals(ref, ((Number) dt.get(1).get("DTREF_NO")).intValue());
        assertEquals(11, ((Number) dt.get(1).get("DTSLE_CODE")).intValue());
        assertEquals(15, ((Number) dt.get(1).get("DTCTS_NUM")).intValue());
        assertEquals(0, new BigDecimal("-800.25").compareTo((BigDecimal) dt.get(1).get("DTAMT")));
        for (Map<String, Object> l : dt) {
            assertEquals(tellerId, ((Number) l.get("DTENCDBY")).intValue());
            assertEquals(dept, ((Number) l.get("DTDEPT")).intValue());
        }

        Map<String, Object> sl = jdbc.queryForMap("SELECT SEQNO, AMT, TR_DOCNO FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 22 AND SLT_CODE = ? AND REF_NO = ? AND TR_CTLNO = ? AND TR_CODE = 10", client, type, ref, r.controlNo());
        assertEquals(seqBefore + 1, ((Number) sl.get("SEQNO")).intValue());
        assertEquals(0, new BigDecimal("-800.25").compareTo((BigDecimal) sl.get("AMT")));
        assertEquals(0, cashBefore.add(new BigDecimal("800.25")).compareTo(glNet("10003", 10)));
        assertEquals(0, savBefore.subtract(new BigDecimal("800.25")).compareTo(glNet("2010101", 10)));
    }

    @Test
    void aWithdrawalPostsTheSameRowsTheOldDepositWithdrawalWrote() {
        BigDecimal cashBefore = glNet("10003", 23), savBefore = glNet("2010101", 23);
        Integer seqBefore = jdbc.queryForObject("SELECT COALESCE(MAX(SEQNO), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 22 AND SLT_CODE = ? AND REF_NO = ?", Integer.class, client, type, ref);
        BigDecimal amount = new BigDecimal("100.00"); // leaves at least the minimum: the balance is at least 2,000
        assumeTrue(balance.subtract(amount).compareTo(min) >= 0);

        Check c = savings.check(req("100.00"), as(teller));
        assertTrue(c.ready() && c.needs().isEmpty() && c.problems().isEmpty());

        Receipt r = savings.withdraw(req("100.00"), as(teller));

        assertEquals("WITHDRAWAL", r.kind());
        assertEquals(0, balance.subtract(amount).compareTo(balanceNow()));
        Map<String, Object> h = jdbc.queryForMap("SELECT TR_CLIENTID, IS_FO FROM transsum WHERE TR_BRCODE = 1 AND TR_YEAR = ? AND TR_MODULE = 2 AND TR_CODE = 23 AND TR_CTLNO = ?", r.year(), r.controlNo());
        assertEquals(client, ((Number) h.get("TR_CLIENTID")).intValue());

        List<Map<String, Object>> dt = jdbc.queryForList("SELECT * FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 2 AND DTTR_CODE = 23 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO", r.year(), r.controlNo());
        assertEquals(2, dt.size());
        assertEquals("2010101", dt.get(0).get("DTACCT_CODE"), "the member's account comes first on a withdrawal");
        assertEquals(client, ((Number) dt.get(0).get("ClientIDDT")).intValue());
        assertEquals(15, ((Number) dt.get(0).get("DTCTS_NUM")).intValue());
        assertEquals(0, amount.compareTo((BigDecimal) dt.get(0).get("DTAMT")), "debit is positive");
        assertEquals("10003", dt.get(1).get("DTACCT_CODE"));
        assertEquals(53, ((Number) dt.get(1).get("DTSLE_CODE")).intValue());
        assertEquals(tellerNumber, ((Number) dt.get(1).get("DTCTS_NUM")).intValue());
        assertEquals(0, amount.negate().compareTo((BigDecimal) dt.get(1).get("DTAMT")), "cash paid out is a credit");
        assertEquals(0, ((BigDecimal) dt.get(0).get("DTAMT")).add((BigDecimal) dt.get(1).get("DTAMT")).signum());

        Map<String, Object> sl = jdbc.queryForMap("SELECT SEQNO, AMT FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 22 AND SLT_CODE = ? AND REF_NO = ? AND TR_CTLNO = ? AND TR_CODE = 23", client, type, ref, r.controlNo());
        assertEquals(seqBefore + 1, ((Number) sl.get("SEQNO")).intValue());
        assertEquals(0, amount.compareTo((BigDecimal) sl.get("AMT")), "a withdrawal is a debit in the member's ledger");
        assertEquals(0, savBefore.add(amount).compareTo(glNet("2010101", 23)), "savings deposits fall (debit)");
        assertEquals(0, cashBefore.subtract(amount).compareTo(glNet("10003", 23)), "cash on hand falls (credit)");
    }

    @Test
    void aWithdrawalUnderTheMinimumNeedsAnApprovalThatCanBeSpentOnce() {
        String amount = balance.subtract(new BigDecimal("100")).toPlainString(); // leaves 100, below the minimum of at least 500
        Check c = savings.check(req(amount), as(teller));
        assertEquals(1, c.needs().size());
        assertEquals("SAV_MIN_BALANCE", c.needs().get(0).kind());
        assertEquals("NONE", c.needs().get(0).status());
        assertFalse(c.ready());

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> savings.withdraw(req(amount), as(teller)));
        assertEquals(409, e.getStatusCode().value());
        assertEquals(0, balance.compareTo(balanceNow()), "nothing was posted");
    }

    @Test
    void anApprovedOverrideLetsItThroughOnceAndIsMarkedUsed() {
        String amount = balance.subtract(new BigDecimal("100")).toPlainString();
        Check c = savings.check(req(amount), as(teller));
        long ov = approvedOverride(c, amount);
        assertEquals("APPROVED", savings.check(req(amount), as(teller)).needs().get(0).status());
        assertTrue(savings.check(req(amount), as(teller)).ready());

        Receipt r = savings.withdraw(req(amount), as(teller));
        assertEquals(0, new BigDecimal("100").compareTo(balanceNow()));
        assertEquals("1/" + r.year() + "/2/23/" + r.controlNo(), jdbc.queryForObject("SELECT used_ref FROM ecis_fo_override WHERE id = ?", String.class, ov));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'savings.withdrawal.override.used' AND entity_id = ?", Integer.class, String.valueOf(ov)));
    }

    @Test
    void aDepositIsRefusedOnADormantOrClosedAccountAndForPeopleWhoMayNotWithdraw() {
        List<Map<String, Object>> dormant = jdbc.queryForList("SELECT ClientIDSD AS client, SDSLT_CODE AS typ, SDREF_NO AS ref FROM sd WHERE SDBR_CODE = 1 AND SDSLC_CODE = 22 AND SDSTATUS = 16 LIMIT 1");
        if (!dormant.isEmpty()) {
            Map<String, Object> d = dormant.get(0);
            ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> savings.deposit(
                new Request(((Number) d.get("client")).intValue(), ((Number) d.get("typ")).intValue(), ((Number) d.get("ref")).intValue(), BigDecimal.ONE, null, null, false), as(teller)));
            assertTrue(e.getReason().contains("dormant"));
        }
        List<String> other = jdbc.queryForList("SELECT a.AccessUserName FROM access a WHERE COALESCE(a.AccessIsDisabled, 0) = 0 AND a.AccessLevel <> 'SUPERADMIN' AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 0 LIMIT 1", String.class);
        if (!other.isEmpty()) {
            assertThrows(ResponseStatusException.class, () -> savings.withdraw(req("1"), as(other.get(0))));
        }
        assertThrows(ResponseStatusException.class, () -> savings.withdraw(req("0"), as(teller)));
        assertThrows(ResponseStatusException.class, () -> savings.deposit(new Request(client, type, 99999999, BigDecimal.ONE, null, null, false), as(teller)));
    }
}
