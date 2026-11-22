package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;
import ph.coop.core.frontoffice.DraftPostController.OwnChequeIn;
import ph.coop.core.frontoffice.DraftPostController.PostIn;

/**
 * The rest of the teller module through the real code, compared with the old system's own rows (documentation/general-voucher-map.md), all rolled back:
 * an other receipt and a mixed payment posted from drafts, a Cash Voucher and a Check Voucher that wait for a second person's approval and a third
 * person's posting, and a returned cheque that paid a loan. Runs only when DB_PASSWORD is set; skips what this database cannot do.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class TellerCompletionTest {

    @Autowired DraftController drafts;
    @Autowired DraftPostController post;
    @Autowired GeneralVoucherController vouchers;
    @Autowired VoucherPayoutController payouts;
    @Autowired SavingsPostingController savings;
    @Autowired BankCashController banks;
    @Autowired ChequeReturnController returns;
    @Autowired OverrideController overrides;
    @Autowired MemberLedgerPoster poster;
    @Autowired JdbcTemplate jdbc;
    @Autowired Db db;

    private record Teller(int id, String username, int number, int dept) {}

    private List<Teller> tellers;
    private int payee;

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    private static Authentication as(String name) { return new TestingAuthenticationToken(name, null); }

    @BeforeEach
    void setUp() {
        tellers = jdbc.query("""
                SELECT a.AccessID, a.AccessUserName, a.AccessTellerNumber, a.AccessDepartment FROM access a
                WHERE a.AccessBR_CODE = 1 AND a.AccessCurrAssign LIKE '%Teller%' AND COALESCE(a.AccessIsHeadCashier, 0) = 0 AND COALESCE(a.AccessIsDisabled, 0) = 0
                  AND a.AccessTellerNumber > 0 AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 1 AND COALESCE(a.AccessDepartment, 0) > 0 AND a.AccessLevel = 'USER'
                  AND a.AccessDisbursementLimit >= 50000 AND a.AccessMaxWithdrawal >= 50000
                  AND (SELECT COUNT(*) FROM access_trans r WHERE r.USERID = a.AccessID AND r.BR_CODE = a.AccessBR_CODE AND r.CAN_NEW = 1 AND r.TR_CODE IN (10, 21, 22, 24)) = 4
                ORDER BY a.AccessID LIMIT 3
                """, (rs, i) -> new Teller(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getInt(4)));
        assumeTrue(tellers.size() == 3, "three tellers who may make receipts and vouchers are needed");
        List<Integer> c = jdbc.queryForList("""
                SELECT o.ClientIDOSL FROM osl o JOIN client k ON k.ClientIDBrCode = o.OSLBR_CODE AND k.ClientID = o.ClientIDOSL AND k.AccountStatus = 1
                WHERE o.OSLBR_CODE = 1 AND o.OSLSLC_CODE = 29 AND o.OSLSLT_CODE = 1 AND o.OSLSTATUS = 15 ORDER BY o.ClientIDOSL DESC LIMIT 1
                """, Integer.class);
        assumeTrue(!c.isEmpty(), "no active member with a vouchers-payable account");
        payee = c.get(0);
    }

    private DraftRules.Line line(String account, String debit, String credit, Integer slc, Integer slt, String ref) {
        return new DraftRules.Line(account, null, ref, debit, credit, null, slc, slt);
    }

    private long draft(Teller t, int code, Integer client, String name, List<DraftRules.Line> lines) {
        return drafts.create(new DraftRules.Payload(code, LocalDate.now().toString(), null, t.dept(), client, name, null, "itest " + code, lines, List.of()), as(t.username())).id();
    }

    private void cashIn(Teller t, BigDecimal amount) {
        List<Map<String, Object>> sd = jdbc.queryForList("""
                SELECT s.ClientIDSD AS client, s.SDSLT_CODE AS typ, s.SDREF_NO AS ref FROM sd s JOIN client c ON c.ClientIDBrCode = s.SDBR_CODE AND c.ClientID = s.ClientIDSD AND c.AccountStatus = 1
                WHERE s.SDBR_CODE = 1 AND s.SDSLC_CODE = 22 AND s.SDSTATUS = 15 AND s.SDSLT_CODE = 1 ORDER BY s.ClientIDSD DESC LIMIT 1
                """);
        assumeTrue(!sd.isEmpty(), "no active savings account to take the cash in");
        savings.deposit(new SavingsPostingController.Request(((Number) sd.get(0).get("client")).intValue(), ((Number) sd.get(0).get("typ")).intValue(),
            ((Number) sd.get(0).get("ref")).intValue(), amount, "itest cash in", null, true), as(t.username()));
    }

    private List<Map<String, Object>> lines(int year, int module, int code, long ctl) {
        return jdbc.queryForList("SELECT * FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = ? AND DTTR_CODE = ? AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO",
            year, module, code, ctl);
    }

    private static Object col(Map<String, Object> row, String name) {
        for (Map.Entry<String, Object> e : row.entrySet()) if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }

    private static int num(Map<String, Object> row, String name) {
        Object v = col(row, name);
        return v == null ? 0 : ((Number) v).intValue();
    }

    // ---- the pure rules ---------------------------------------------------------------------------------------------

    @Test
    void theRulesSayWhatEachKindOfDraftMayHold() {
        var cash = new DraftPostRules.In(1, "10003", null, null, null, d("150.00"), 2);
        var fee = new DraftPostRules.In(2, "4416", null, null, null, d("-150.00"), 2);
        var receipt = DraftPostRules.plan(10, List.of(cash, fee), true, null, "A PAYOR", null, null);
        assertTrue(receipt.problems().isEmpty(), receipt.problems().toString());
        assertEquals(0, d("150.00").compareTo(receipt.total()));
        assertEquals(1, receipt.lines().size(), "the window's cash line is the ledger's to write");
        assertFalse(DraftPostRules.plan(10, List.of(cash, fee), true, null, "", null, null).problems().isEmpty(), "who paid must be named");
        var savingsLine = new DraftPostRules.In(2, "20101", 22, 1, 5, d("-150.00"), 2);
        assertFalse(DraftPostRules.plan(10, List.of(cash, savingsLine), true, 7, null, null, null).problems().isEmpty(), "member payments stay on the Teller tab");

        var expense = new DraftPostRules.In(1, "30302", null, null, null, d("500.00"), 2);
        var payable = new DraftPostRules.In(2, "2012305", null, null, null, d("-500.00"), 2);
        assertTrue(DraftPostRules.plan(21, List.of(expense, payable), true, 9, null, "2012305", null).problems().isEmpty());
        assertFalse(DraftPostRules.plan(21, List.of(expense, payable), true, null, null, "2012305", null).problems().isEmpty(), "a Cash Voucher needs a payee");
        assertFalse(DraftPostRules.plan(21, List.of(expense, new DraftPostRules.In(2, "10003", null, null, null, d("-500.00"), 2)), true, 9, null, "2012305", null).problems().isEmpty(),
            "a Cash Voucher never touches the cash");
        var bank = new DraftPostRules.In(2, "10020208", null, null, null, d("-500.00"), 2);
        assertTrue(DraftPostRules.plan(22, List.of(expense, bank), true, null, null, null, "10020208").problems().isEmpty());
        assertFalse(DraftPostRules.plan(22, List.of(expense, bank), true, null, null, null, "10020104").problems().isEmpty(), "the credit is the chosen bank");
        assertFalse(DraftPostRules.plan(22, List.of(expense, bank), false, null, null, null, "10020208").problems().isEmpty(), "it must balance");
        assertTrue(DraftPostRules.notPosted(25) != null && DraftPostRules.kindOf(25) == null, "types without an old example stay drafts");
    }

    // ---- posted at once ---------------------------------------------------------------------------------------------

    @Test
    void anOtherReceiptIsPostedLikeTheOldOneWithNoMemberAndThePayorNamed() {
        Teller t = tellers.get(0);
        long id = draft(t, 10, null, "ITEST PAYOR", List.of(line("10003", "150.00", "", null, null, null), line("4416", "", "150.00", null, null, null)));
        var plan = post.check(id, null, as(t.username()));
        assertTrue(plan.ready(), plan.problems().toString());
        assertEquals("OTHER_RECEIPT", plan.kind());
        var r = post.post(id, null, as(t.username()));
        assertEquals("POSTED", r.status());
        Map<String, Object> head = jdbc.queryForMap("SELECT TR_CLIENTID, CLIENTNAME, IS_FO FROM transsum WHERE TR_BRCODE = 1 AND TR_YEAR = ? AND TR_MODULE = 3 AND TR_CODE = 10 AND TR_CTLNO = ?", r.year(), r.controlNo());
        assertNull(col(head, "TR_CLIENTID"), "no member on an other receipt");
        assertEquals("ITEST PAYOR", col(head, "CLIENTNAME"));
        List<Map<String, Object>> dt = lines(r.year(), 3, 10, r.controlNo());
        assertEquals(2, dt.size());
        assertEquals("10003", col(dt.get(0), "DTACCT_CODE"));
        assertEquals(11, num(dt.get(0), "DTSLC_CODE"));
        assertEquals("4416", col(dt.get(1), "DTACCT_CODE"));
        assertNull(col(dt.get(1), "DTSLC_CODE"), "a plain ledger line has no sub-ledger");
        assertNull(col(dt.get(1), "ClientIDDT"));
        assertEquals(0, d("-150.00").compareTo((BigDecimal) col(dt.get(1), "DTAMT")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sldtl WHERE SL_BRCODE = 1 AND TR_CODE = 10 AND TR_CTLNO = ? AND TR_DATE = ?", Integer.class, r.controlNo(), java.sql.Date.valueOf(r.date())));
        if (db.canInsertInto("transck")) assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM transck WHERE CKTR_BRCODE = 1 AND CKTR_YEAR = ? AND CKTR_CODE = 10 AND CKTR_CTLNO = ?", Integer.class, r.year(), r.controlNo()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_fo_draft WHERE id = ?", Integer.class, id), "the draft is gone once posted");
        assertThrows(ResponseStatusException.class, () -> post.post(id, null, as(t.username())), "and cannot be posted twice");
    }

    @Test
    void aMixedPaymentPaysAPayableAndAPlainAccountOutOfTheDrawer() {
        Teller t = tellers.get(0);
        int ref = jdbc.queryForObject("SELECT MIN(OSLREF_NO) FROM osl WHERE OSLBR_CODE = 1 AND ClientIDOSL = ? AND OSLSLC_CODE = 29 AND OSLSLT_CODE = 1 AND OSLSTATUS = 15", Integer.class, payee);
        BigDecimal before = jdbc.queryForObject("SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 29 AND SLT_CODE = 1 AND REF_NO = ? AND SLE_CODE = 11",
            BigDecimal.class, payee, ref);
        poster.writeLedgerRow(1, payee, new MemberLedgerPoster.MemberLine(29, 1, ref, 11, 15, "2012305", d("-300.00")), LocalDate.now(), 21, 99999998L, "*9999998");
        cashIn(t, d("500.00"));
        long id = draft(t, 24, payee, null, List.of(line("2012305", "300.00", "", 29, 1, String.valueOf(ref)), line("30302", "200.00", "", null, null, null),
            line("10003", "", "500.00", null, null, null)));
        var plan = post.check(id, null, as(t.username()));
        assertTrue(plan.problems().isEmpty(), plan.problems().toString());
        var r = post.post(id, null, as(t.username()));
        List<Map<String, Object>> dt = lines(r.year(), 2, 24, r.controlNo());
        assertEquals(3, dt.size());
        assertEquals("2012305", col(dt.get(0), "DTACCT_CODE"));
        assertEquals(29, num(dt.get(0), "DTSLC_CODE"));
        assertEquals("30302", col(dt.get(1), "DTACCT_CODE"));
        assertEquals("10003", col(dt.get(2), "DTACCT_CODE"), "the cash goes out last");
        assertEquals(0, d("-500.00").compareTo((BigDecimal) col(dt.get(2), "DTAMT")));
        BigDecimal after = jdbc.queryForObject("SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 29 AND SLT_CODE = 1 AND REF_NO = ? AND SLE_CODE = 11",
            BigDecimal.class, payee, ref);
        assertEquals(0, before.compareTo(after), "the payable is back where it was");
    }

    // ---- waiting for approval -----------------------------------------------------------------------------------------

    @Test
    void aCashVoucherWaitsForASecondPersonIsPostedByAThirdAndThePayeeCanThenBePaid() {
        Teller a = tellers.get(0), b = tellers.get(1), c = tellers.get(2);
        long id = draft(a, 21, payee, null, List.of(line("30302", "500.00", "", null, null, null), line("2012305", "", "500.00", null, null, null)));
        var r = post.post(id, null, as(a.username()));
        assertEquals("WAITING FOR APPROVAL", r.status());
        List<Map<String, Object>> dt = lines(r.year(), 2, 21, r.controlNo());
        assertEquals(2, dt.size());
        assertTrue(dt.stream().allMatch((x) -> num(x, "DTUPD_TAG") == 2), "waiting lines are tag 2");
        assertEquals(29, num(dt.get(1), "DTSLC_CODE"));
        int ref = num(dt.get(1), "DTREF_NO");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM gltrans WHERE GLTransBR_CODE = 1 AND GLTransTR_CODE = 21 AND GLTransACCT_CODE = '30302' AND GLTransDR_AMT = 500.00 AND GLTransTR_DATE = ?",
            Integer.class, java.sql.Date.valueOf(r.date())), "nothing in the general ledger yet");
        assertTrue(vouchers.waiting(as(b.username())).stream().anyMatch((v) -> v.controlNo() == r.controlNo() && v.code() == 21));

        assertEquals(403, assertThrows(ResponseStatusException.class, () -> vouchers.approve(21, r.year(), r.controlNo(), as(a.username()))).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> vouchers.post(21, r.year(), r.controlNo(), as(c.username()))).getStatusCode().value(), "not before approval");
        assertEquals("APPROVED", vouchers.approve(21, r.year(), r.controlNo(), as(b.username())).voucher().status());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> vouchers.post(21, r.year(), r.controlNo(), as(b.username()))).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> vouchers.post(21, r.year(), r.controlNo(), as(a.username()))).getStatusCode().value());
        assertEquals("POSTED", vouchers.post(21, r.year(), r.controlNo(), as(c.username())).voucher().status());

        dt = lines(r.year(), 2, 21, r.controlNo());
        assertTrue(dt.stream().allMatch((x) -> num(x, "DTUPD_TAG") == 1 && num(x, "DTPOSTBY") == c.id() && num(x, "DTAPPRVBY") == b.id() && num(x, "DTENCDBY") == a.id()));
        Map<String, Object> row = jdbc.queryForMap("SELECT AMT, COCI_TYPE, ADJ_FLAG FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 29 AND SLT_CODE = 1 AND REF_NO = ? AND TR_CODE = 21",
            payee, ref);
        assertEquals(0, d("-500.00").compareTo((BigDecimal) col(row, "AMT")));
        assertNull(col(row, "COCI_TYPE"), "voucher rows have no tender, as in the old data");
        assertTrue(payouts.member(payee, as(a.username())).stream().anyMatch((p) -> p.refNo() == ref && p.balance().compareTo(d("500.00")) == 0), "the payee waits to be paid on Disburse");
    }

    @Test
    void aCheckVoucherIsPaidByTheCooperativesChequeWrittenWhenItIsPosted() {
        assumeTrue(db.tableExists("ecis_voucher_cheque"), "no voucher cheque table (V030 / V067)");
        assumeTrue(db.canInsertInto("transck"), "the database user may not write cheques");
        Teller a = tellers.get(0), b = tellers.get(1), c = tellers.get(2);
        var bank = banks.banks(as(a.username())).stream().findFirst().orElse(null);
        assumeTrue(bank != null, "no bank account set up");
        long id = draft(a, 22, null, "ITEST SUPPLIER", List.of(line("30302", "1000.00", "", null, null, null), line(bank.ledgerAccount(), "", "1000.00", null, null, null)));
        assertFalse(post.check(id, null, as(a.username())).problems().isEmpty(), "the cheque must be given");
        PostIn in = new PostIn(null, new OwnChequeIn(bank.bankId(), bank.ledgerAccount(), "987654321", LocalDate.now(), "ITEST SUPPLIER"), null, null);
        var plan = post.check(id, in, as(a.username()));
        assertTrue(plan.problems().isEmpty(), plan.problems().toString());
        var r = post.post(id, in, as(a.username()));
        assertEquals("WAITING FOR APPROVAL", r.status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM transck WHERE CKTR_BRCODE = 1 AND CKTR_YEAR = ? AND CKTR_CODE = 22 AND CKTR_CTLNO = ?", Integer.class, r.year(), r.controlNo()));
        var detail = vouchers.detail(22, r.year(), r.controlNo(), as(b.username()));
        assertEquals("000000987654321", detail.voucher().cheque().number());
        vouchers.approve(22, r.year(), r.controlNo(), as(b.username()));
        vouchers.post(22, r.year(), r.controlNo(), as(c.username()));
        Map<String, Object> ck = jdbc.queryForMap("SELECT * FROM transck WHERE CKTR_BRCODE = 1 AND CKTR_YEAR = ? AND CKTR_CODE = 22 AND CKTR_CTLNO = ?", r.year(), r.controlNo());
        assertEquals(2, num(ck, "CKCOCI_TYPE"));
        assertEquals(5, num(ck, "CKCHECKTYPE"), "the cooperative's own cheque is On Us, as on the old Check Vouchers");
        assertEquals(1, num(ck, "CKISCLEARED"));
        assertEquals(bank.bankId(), num(ck, "CKBANK_CODE"));
        assertEquals("ITEST SUPPLIER", col(ck, "CKPAYEE"));
        assertEquals(b.id(), num(ck, "CKAPPRVBY"));
        // the same cheque number of the same bank cannot be written again
        long again = draft(a, 22, null, "ITEST SUPPLIER", List.of(line("30302", "10.00", "", null, null, null), line(bank.ledgerAccount(), "", "10.00", null, null, null)));
        assertTrue(post.check(again, in, as(a.username())).problems().stream().anyMatch((p) -> p.contains("already on another voucher")));
    }

    // ---- returned cheques ---------------------------------------------------------------------------------------------

    @Test
    void aReturnedChequeThatPaidALoanPutsWhatItPaidBackOnTheLoan() {
        assumeTrue(db.canUpdateColumn("transck", "CKISCLEARED"), "V026_cheque_clearing.sql has not been run");
        List<Map<String, Object>> h = jdbc.queryForList("""
                SELECT AccessID, AccessUserName FROM access WHERE AccessBR_CODE = 1 AND COALESCE(AccessIsHeadCashier, 0) = 1 AND COALESCE(AccessIsDisabled, 0) = 0
                  AND COALESCE(AccessCanAccessFrontOffice, 0) = 1 AND COALESCE(AccessDepartment, 0) > 0 AND AccessLevel <> 'SUPERADMIN' ORDER BY AccessID LIMIT 1
                """);
        assumeTrue(!h.isEmpty(), "no head cashier in this database");
        Authentication head = as((String) h.get(0).get("AccessUserName"));
        Authentication boss = new TestingAuthenticationToken(tellers.get(1).username(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        List<String> numbers = jdbc.queryForList("""
                SELECT DISTINCT CHECKNO FROM sldtl WHERE SL_BRCODE = 1 AND SLC_CODE = 12 AND TR_CODE = 10 AND COCI_TYPE = 2 AND TR_DATE >= '2026-01-01' AND CHECKNO IS NOT NULL LIMIT 40
                """, String.class);
        ChequeReturnController.Candidate found = null;
        for (String n : numbers) {
            for (var x : returns.find(n, head)) if (x.problem() == null && !x.returned() && x.paid().stream().anyMatch((p) -> p.slc() == 12)) { found = x; break; }
            if (found != null) break;
        }
        assumeTrue(found != null, "no returnable loan cheque found");
        final var cheque = found;
        var principal = cheque.paid().stream().filter((p) -> p.slc() == 12 && p.event() == 11).findFirst().orElse(null);
        String owedSql = "SELECT COALESCE(SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 12 AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = 11";
        BigDecimal owedBefore = principal == null ? null : jdbc.queryForObject(owedSql, BigDecimal.class, cheque.clientId(), principal.typeCode(), principal.refNo());

        ChequeReturnController.Request req = new ChequeReturnController.Request(cheque.year(), cheque.ctlNo(), cheque.seq(), "VAULT", null, null, null);
        var check = returns.check(req, head);
        assumeTrue(check.problems().isEmpty(), "the vault cannot hold this cheque here: " + check.problems());
        var ask = returns.ask(req, head);
        overrides.decide(ask.id(), new OverrideController.Decision(true, "itest"), boss);
        var posted = returns.post(req, head);
        List<Map<String, Object>> dt = lines(posted.year(), 1, 1, posted.controlNo());
        assertEquals(cheque.paid().size() + 1, dt.size(), "a debit for each line the cheque paid and the credit where it is now");
        for (int i = 0; i < cheque.paid().size(); i++) {
            var p = cheque.paid().get(i);
            assertEquals(p.account(), col(dt.get(i), "DTACCT_CODE"));
            assertEquals(p.slc(), num(dt.get(i), "DTSLC_CODE"));
            assertEquals(p.event(), num(dt.get(i), "DTSLE_CODE"));
            assertEquals(cheque.clientId(), num(dt.get(i), "ClientIDDT"));
            assertEquals(0, p.amount().compareTo((BigDecimal) col(dt.get(i), "DTAMT")));
        }
        assertEquals("100111", col(dt.get(dt.size() - 1), "DTACCT_CODE"));
        assertEquals(0, dt.stream().map((x) -> (BigDecimal) col(x, "DTAMT")).reduce(BigDecimal.ZERO, BigDecimal::add).signum(), "it balances");
        if (principal != null) {
            BigDecimal owedAfter = jdbc.queryForObject(owedSql, BigDecimal.class, cheque.clientId(), principal.typeCode(), principal.refNo());
            assertEquals(0, owedBefore.add(principal.amount()).compareTo(owedAfter), "the principal the cheque paid is owed again");
        }
    }
}
