package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.frontoffice.SavingsPostingController.NeedStatus;
import ph.coop.core.frontoffice.VoucherPayoutController.Check;
import ph.coop.core.frontoffice.VoucherPayoutController.Payable;
import ph.coop.core.frontoffice.VoucherPayoutController.Receipt;
import ph.coop.core.frontoffice.VoucherPayoutController.Request;

/**
 * The payout rules, and a real vouchers-payable balance paid out in cash through the real code, compared with the old Cash Voucher payment
 * (module 2, type 24; documentation/voucher-payout-map.md); rolled back. Runs only when DB_PASSWORD is set.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@Import(VoucherPayoutTest.FixedClock.class)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class VoucherPayoutTest {

    static final LocalDate TODAY = LocalDate.of(2026, 4, 25);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(LocalDateTime.of(TODAY, java.time.LocalTime.of(10, 0)).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }
    }

    @Autowired VoucherPayoutController payouts;
    @Autowired SavingsPostingController savings;
    @Autowired MemberLedgerPoster poster;
    @Autowired JdbcTemplate jdbc;

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    private Authentication as(String name) { return new TestingAuthenticationToken(name, null); }

    @Test
    void theRulesRefuseWhatTheOldSystemRefusedAndAskForALimitApproval() {
        BigDecimal limit = d("50000.00");
        assertTrue(VoucherPayoutRules.check(d("1000.00"), 15, 1, d("5000.00"), limit).problems().isEmpty());
        assertTrue(VoucherPayoutRules.check(d("1000.00"), 15, 1, d("5000.00"), limit).needs().isEmpty());
        assertFalse(VoucherPayoutRules.check(d("1000.00"), 15, 1, d("999.99"), limit).problems().isEmpty(), "the drawer is short");
        assertFalse(VoucherPayoutRules.check(d("1000.00"), 17, 1, d("5000.00"), limit).problems().isEmpty(), "the account is closed");
        assertFalse(VoucherPayoutRules.check(d("1000.00"), 15, 3, d("5000.00"), limit).problems().isEmpty(), "the client is closed");
        assertFalse(VoucherPayoutRules.check(d("0.00"), 15, 1, d("5000.00"), limit).problems().isEmpty(), "nothing to pay");
        var over = VoucherPayoutRules.check(d("60000.00"), 15, 1, d("90000.00"), limit);
        assertTrue(over.problems().isEmpty());
        assertEquals(1, over.needs().size());
        assertEquals(VoucherPayoutRules.LIMIT_KIND, over.needs().get(0).kind());
    }

    @Test
    void aBalanceIsPaidInFullFromTheCashiersDrawerAndPostedLikeTheOldVoucherPayment() {
        List<Map<String, Object>> t = jdbc.queryForList("""
                SELECT a.AccessID, a.AccessUserName, a.AccessTellerNumber, a.AccessDepartment FROM access a
                JOIN access_trans r ON r.USERID = a.AccessID AND r.BR_CODE = a.AccessBR_CODE AND r.TR_CODE = 10 AND r.CAN_NEW = 1
                JOIN access_trans w ON w.USERID = a.AccessID AND w.BR_CODE = a.AccessBR_CODE AND w.TR_CODE = 24 AND w.CAN_NEW = 1
                WHERE a.AccessCurrAssign LIKE '%Teller%' AND COALESCE(a.AccessIsHeadCashier, 0) = 0 AND COALESCE(a.AccessIsDisabled, 0) = 0
                  AND a.AccessTellerNumber > 0 AND a.AccessBR_CODE = 1 AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 1 AND COALESCE(a.AccessDepartment, 0) > 0
                  AND a.AccessLevel <> 'SUPERADMIN' AND a.AccessDisbursementLimit >= 50000 AND a.AccessMaxWithdrawal >= 50000 ORDER BY a.AccessID LIMIT 1
                """);
        assumeTrue(!t.isEmpty(), "no teller who may add receipts and voucher payments with a 50,000 limit");
        int tellerId = ((Number) t.get(0).get("AccessID")).intValue(), tellerNumber = ((Number) t.get(0).get("AccessTellerNumber")).intValue();
        String teller = (String) t.get(0).get("AccessUserName");

        // every real voucher is long paid, so a release is simulated: money is credited to a member's vouchers-payable account
        Payable w = seedPayable(teller);
        assertTrue(payouts.member(w.clientId(), as(teller)).stream().anyMatch((x) -> x.refNo() == w.refNo() && x.balance().compareTo(w.balance()) == 0));

        // put cash in the cashier's drawer by a real savings deposit, so the drawer can cover the payment
        List<Map<String, Object>> sd = jdbc.queryForList("""
                SELECT s.ClientIDSD AS client, s.SDSLT_CODE AS typ, s.SDREF_NO AS ref FROM sd s JOIN client c ON c.ClientIDBrCode = s.SDBR_CODE AND c.ClientID = s.ClientIDSD AND c.AccountStatus = 1
                WHERE s.SDBR_CODE = 1 AND s.SDSLC_CODE = 22 AND s.SDSTATUS = 15 AND s.SDSLT_CODE = 1 ORDER BY s.ClientIDSD DESC LIMIT 1
                """);
        assumeTrue(!sd.isEmpty(), "no active savings account to take the cash in");
        savings.deposit(new SavingsPostingController.Request(((Number) sd.get(0).get("client")).intValue(), ((Number) sd.get(0).get("typ")).intValue(),
            ((Number) sd.get(0).get("ref")).intValue(), w.balance(), "itest cash in", null, true), as(teller));

        Request req = new Request(w.clientId(), w.refNo(), "itest payout", null);
        Check c = payouts.check(req, as(teller));
        assertTrue(c.problems().isEmpty(), c.problems().toString());
        assertTrue(c.ready());
        assertEquals(0, w.balance().compareTo(c.payable().balance()));
        BigDecimal payableGlBefore = glNet("2012305", 24), cashGlBefore = glNet(HandoverRules.account(53), 24);

        Receipt r = payouts.pay(req, as(teller));
        assertEquals(0, w.balance().compareTo(r.amount()));
        assertEquals(String.format("*%07d", r.controlNo()), r.documentNo());

        Map<String, Object> h = jdbc.queryForMap("SELECT TR_CLIENTID, EXPLANATION, IS_FO FROM transsum WHERE TR_BRCODE = 1 AND TR_YEAR = ? AND TR_MODULE = 2 AND TR_CODE = 24 AND TR_CTLNO = ?", r.year(), r.controlNo());
        assertEquals(w.clientId(), ((Number) h.get("TR_CLIENTID")).intValue());
        assertEquals("itest payout", h.get("EXPLANATION"));
        assertEquals(1, ((Number) h.get("IS_FO")).intValue());

        List<Map<String, Object>> dt = jdbc.queryForList("SELECT * FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 2 AND DTTR_CODE = 24 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO", r.year(), r.controlNo());
        assertEquals(2, dt.size());
        assertEquals("2012305", dt.get(0).get("DTACCT_CODE"));
        assertEquals(29, ((Number) dt.get(0).get("DTSLC_CODE")).intValue());
        assertEquals(1, ((Number) dt.get(0).get("DTSLT_CODE")).intValue());
        assertEquals(w.refNo(), ((Number) dt.get(0).get("DTREF_NO")).intValue());
        assertEquals(11, ((Number) dt.get(0).get("DTSLE_CODE")).intValue());
        assertEquals(15, ((Number) dt.get(0).get("DTCTS_NUM")).intValue());
        assertEquals(0, w.balance().compareTo((BigDecimal) dt.get(0).get("DTAMT")));
        assertEquals(HandoverRules.account(53), dt.get(1).get("DTACCT_CODE"));
        assertEquals(53, ((Number) dt.get(1).get("DTSLE_CODE")).intValue());
        assertEquals(tellerNumber, ((Number) dt.get(1).get("DTCTS_NUM")).intValue());
        assertEquals(0, w.balance().negate().compareTo((BigDecimal) dt.get(1).get("DTAMT")));
        for (Map<String, Object> l : dt) {
            assertEquals(tellerId, ((Number) l.get("DTENCDBY")).intValue());
            assertEquals(tellerId, ((Number) l.get("DTPOSTBY")).intValue());
            assertEquals(1, ((Number) l.get("DTUPD_TAG")).intValue(), "posted at once");
        }

        Map<String, Object> sl = jdbc.queryForMap("SELECT AMT, TR_DOCNO, COCI_TYPE FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 29 AND SLT_CODE = 1 AND REF_NO = ? AND TR_CTLNO = ? AND TR_CODE = 24",
            w.clientId(), w.refNo(), r.controlNo());
        assertEquals(0, w.balance().compareTo((BigDecimal) sl.get("AMT")));
        assertEquals(0, payableGlBefore.add(w.balance()).compareTo(glNet("2012305", 24)));
        assertEquals(0, cashGlBefore.subtract(w.balance()).compareTo(glNet(HandoverRules.account(53), 24)));
        assertEquals(0, jdbc.queryForObject("SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 29 AND SLT_CODE = 1 AND REF_NO = ? AND SLE_CODE = 11",
            BigDecimal.class, w.clientId(), w.refNo()).signum(), "the whole balance was paid");

        // nothing is left, so a second payment is refused, and the person is no longer waiting
        assertThrows(ResponseStatusException.class, () -> payouts.pay(req, as(teller)));
        assertTrue(payouts.check(req, as(teller)).problems().stream().anyMatch((p) -> p.contains("nothing to pay")));
    }

    @Test
    void theWaitingListHoldsOnlyAccountsWithMoneyInThem() {
        List<Map<String, Object>> t = jdbc.queryForList("SELECT AccessUserName FROM access WHERE AccessBR_CODE = 1 AND COALESCE(AccessIsDisabled, 0) = 0 LIMIT 1");
        assumeTrue(!t.isEmpty(), "no user");
        for (Payable x : payouts.waiting(as((String) t.get(0).get("AccessUserName")))) assertTrue(x.balance().signum() > 0);
    }

    /** Credits 1,234.56 to an active member's vouchers-payable account, as a posted release voucher would, and returns the account with what it now holds. */
    private Payable seedPayable(String teller) {
        List<Map<String, Object>> o = jdbc.queryForList("""
                SELECT o.ClientIDOSL AS c, o.OSLREF_NO AS r FROM osl o JOIN client k ON k.ClientIDBrCode = o.OSLBR_CODE AND k.ClientID = o.ClientIDOSL AND k.AccountStatus = 1
                WHERE o.OSLBR_CODE = 1 AND o.OSLSLC_CODE = 29 AND o.OSLSLT_CODE = 1 AND o.OSLSTATUS = 15 ORDER BY o.ClientIDOSL DESC LIMIT 1
                """);
        assumeTrue(!o.isEmpty(), "no active vouchers-payable account");
        int c = ((Number) o.get(0).get("c")).intValue(), r = ((Number) o.get(0).get("r")).intValue();
        poster.writeLedgerRow(1, c, new MemberLedgerPoster.MemberLine(29, 1, r, 11, 15, "2012305", d("-1234.56")), TODAY, 21, 99999999L, "*9999999");
        Payable w = payouts.member(c, as(teller)).stream().filter((x) -> x.refNo() == r).findFirst().orElse(null);
        assumeTrue(w != null && w.balance().compareTo(d("40000")) <= 0, "that account holds too much to use");
        return w;
    }

    @Test
    void aPaymentOverTheCashiersLimitNeedsASupervisorAndTheApprovalIsSpentOnce() {
        List<Map<String, Object>> t = jdbc.queryForList("""
                SELECT a.AccessID, a.AccessUserName FROM access a
                JOIN access_trans r ON r.USERID = a.AccessID AND r.BR_CODE = a.AccessBR_CODE AND r.TR_CODE = 10 AND r.CAN_NEW = 1
                JOIN access_trans w ON w.USERID = a.AccessID AND w.BR_CODE = a.AccessBR_CODE AND w.TR_CODE = 24 AND w.CAN_NEW = 1
                WHERE a.AccessCurrAssign LIKE '%Teller%' AND COALESCE(a.AccessIsHeadCashier, 0) = 0 AND COALESCE(a.AccessIsDisabled, 0) = 0
                  AND a.AccessTellerNumber > 0 AND a.AccessBR_CODE = 1 AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 1 AND COALESCE(a.AccessDepartment, 0) > 0
                  AND a.AccessLevel <> 'SUPERADMIN' ORDER BY a.AccessID LIMIT 1
                """);
        assumeTrue(!t.isEmpty(), "no teller who may add receipts and voucher payments");
        int tellerId = ((Number) t.get(0).get("AccessID")).intValue();
        String teller = (String) t.get(0).get("AccessUserName");
        Payable w = seedPayable(teller);
        // the limit is lowered inside this rolled-back test so the payment is over it
        try {
            jdbc.update("UPDATE access SET AccessDisbursementLimit = 50, AccessMaxWithdrawal = 50 WHERE AccessID = ?", tellerId);
        } catch (org.springframework.dao.DataAccessException e) {
            assumeTrue(false, "the database user cannot change the limit columns");
        }
        List<Map<String, Object>> sd = jdbc.queryForList("""
                SELECT s.ClientIDSD AS client, s.SDSLT_CODE AS typ, s.SDREF_NO AS ref FROM sd s JOIN client c ON c.ClientIDBrCode = s.SDBR_CODE AND c.ClientID = s.ClientIDSD AND c.AccountStatus = 1
                WHERE s.SDBR_CODE = 1 AND s.SDSLC_CODE = 22 AND s.SDSTATUS = 15 AND s.SDSLT_CODE = 1 ORDER BY s.ClientIDSD DESC LIMIT 1
                """);
        assumeTrue(!sd.isEmpty(), "no active savings account to take the cash in");
        // cash in by a deposit, which has no limit
        savings.deposit(new SavingsPostingController.Request(((Number) sd.get(0).get("client")).intValue(), ((Number) sd.get(0).get("typ")).intValue(),
            ((Number) sd.get(0).get("ref")).intValue(), w.balance(), "itest cash in", null, true), as(teller));

        Request req = new Request(w.clientId(), w.refNo(), "itest over limit", null);
        Check c = payouts.check(req, as(teller));
        assertEquals(1, c.needs().size());
        assertEquals("NONE", c.needs().get(0).status());
        assertFalse(c.ready());
        assertThrows(ResponseStatusException.class, () -> payouts.pay(req, as(teller)), "not allowed yet");

        NeedStatus n = c.needs().get(0);
        jdbc.update("INSERT INTO ecis_fo_override (branch_code, kind, description, amount, limit_amount, requested_by, requested_at, status, decided_by, decided_at) VALUES (1, ?, ?, ?, ?, ?, NOW(), 'APPROVED', ?, NOW())",
            n.kind(), n.description(), w.balance(), n.limit(), tellerId, tellerId + 1);
        assertTrue(payouts.check(req, as(teller)).ready());
        Receipt r = payouts.pay(req, as(teller));
        assertEquals(0, w.balance().compareTo(r.amount()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_fo_override WHERE kind = ? AND used_ref = ?", Integer.class, VoucherPayoutRules.LIMIT_KIND, "1/" + r.year() + "/2/24/" + r.controlNo()));
    }

    private BigDecimal glNet(String account, int code) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT), 0) FROM gltrans WHERE GLTransBR_CODE = 1 AND GLTransTR_DATE = ? AND GLTransTR_CODE = ? AND GLTransACCT_CODE = ?",
            BigDecimal.class, TODAY.toString(), code, account);
    }
}
