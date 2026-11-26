package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.frontoffice.OpeningBalanceController.BatchIn;
import ph.coop.core.frontoffice.OpeningBalanceController.LineIn;

/**
 * Posts real opening balances through the real code and checks what they write into the ledger (one General Journal, module 1 type 1: transsum, transdt with the
 * member's account on the control account's line, the member's sldtl row and gltrans), then rolls everything back. Uses three existing enabled users as maker,
 * approver and poster without changing them, a savings account that has no entries yet, and an open period made up for January 1999 (also rolled back). Runs only
 * when DB_PASSWORD is set; needs PostgreSQL V044 (MySQL V081).
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.audit.seal.enabled=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class OpeningBalanceFlowTest {

    private static final LocalDate DAY = LocalDate.of(1999, 1, 1);
    private static final String WHY = "Opening balances as of 1 January 1999 (itest)";

    @Autowired OpeningBalanceController batches;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private String maker, approver, poster;
    private int makerId;
    private final int branch = 1;
    /** A savings account with no entries at all, and one that has entries. */
    private int emptyClient, emptyType, emptyRef, usedClient, usedType;
    private String cash, control;

    @BeforeEach
    void setUp() {
        assumeTrue(db.tableExists("ecis_opening_batch"), "V044 (MySQL V081) has not been applied");
        List<Map<String, Object>> u = jdbc.queryForList("""
                SELECT AccessID, AccessUserName FROM access
                WHERE COALESCE(AccessIsDisabled, 0) = 0 AND AccessLevel <> 'SUPERADMIN' AND AccessBR_CODE = 1 AND COALESCE(AccessDepartment, 0) > 0
                ORDER BY AccessID LIMIT 3
                """);
        assumeTrue(u.size() == 3, "need three enabled users in branch 1");
        makerId = ((Number) u.get(0).get("AccessID")).intValue();
        maker = (String) u.get(0).get("AccessUserName");
        approver = (String) u.get(1).get("AccessUserName");
        poster = (String) u.get(2).get("AccessUserName");

        List<Map<String, Object>> empty = jdbc.queryForList("""
                SELECT s.ClientIDSD c, s.SDSLT_CODE t, s.SDREF_NO r FROM sd s
                WHERE s.SDBR_CODE = 1 AND s.SDSLC_CODE = 22 AND s.SDSTATUS = 15
                  AND NOT EXISTS (SELECT 1 FROM sldtl d WHERE d.SL_BRCODE = s.SDBR_CODE AND d.SL_CLIENTID = s.ClientIDSD AND d.SLC_CODE = 22 AND d.SLT_CODE = s.SDSLT_CODE AND d.REF_NO = s.SDREF_NO)
                  AND (SELECT COUNT(*) FROM sd o WHERE o.SDBR_CODE = s.SDBR_CODE AND o.ClientIDSD = s.ClientIDSD AND o.SDSLC_CODE = 22 AND o.SDSLT_CODE = s.SDSLT_CODE) = 1
                  AND EXISTS (SELECT 1 FROM glcontrol g WHERE g.GLControlBR_CODE = 1 AND g.GLControlSLC_CODE = 22 AND g.GLControlSLT_CODE = s.SDSLT_CODE AND g.GLControlSLE_CODE = 11 AND g.GLControlCTS_NUM = 15)
                ORDER BY s.SDREF_NO DESC LIMIT 1
                """);
        assumeTrue(!empty.isEmpty(), "no savings account without entries");
        emptyClient = ((Number) empty.get(0).get("c")).intValue();
        emptyType = ((Number) empty.get(0).get("t")).intValue();
        emptyRef = ((Number) empty.get(0).get("r")).intValue();
        control = jdbc.queryForObject("SELECT GLControlACCT_CODE FROM glcontrol WHERE GLControlBR_CODE = 1 AND GLControlSLC_CODE = 22 AND GLControlSLT_CODE = ? AND GLControlSLE_CODE = 11 AND GLControlCTS_NUM = 15",
            String.class, emptyType).trim();

        List<Map<String, Object>> used = jdbc.queryForList("""
                SELECT s.ClientIDSD c, s.SDSLT_CODE t FROM sd s
                WHERE s.SDBR_CODE = 1 AND s.SDSLC_CODE = 22 AND s.SDSTATUS = 15
                  AND EXISTS (SELECT 1 FROM sldtl d WHERE d.SL_BRCODE = s.SDBR_CODE AND d.SL_CLIENTID = s.ClientIDSD AND d.SLC_CODE = 22 AND d.SLT_CODE = s.SDSLT_CODE AND d.REF_NO = s.SDREF_NO)
                  AND (SELECT COUNT(*) FROM sd o WHERE o.SDBR_CODE = s.SDBR_CODE AND o.ClientIDSD = s.ClientIDSD AND o.SDSLC_CODE = 22 AND o.SDSLT_CODE = s.SDSLT_CODE) = 1
                ORDER BY s.SDREF_NO DESC LIMIT 1
                """);
        assumeTrue(!used.isEmpty(), "no savings account with entries");
        usedClient = ((Number) used.get(0).get("c")).intValue();
        usedType = ((Number) used.get(0).get("t")).intValue();

        // a detail asset account that is no member account's control account, to stand for cash on hand
        List<String> gl = jdbc.queryForList("""
                SELECT c.COAID FROM coa c WHERE c.COABR_CODE = 1 AND COALESCE(c.COAacct_tag, '') <> 'S' AND COALESCE(c.COAisdisabled, 0) = 0 AND c.COAacct_type = 1
                  AND NOT EXISTS (SELECT 1 FROM glcontrol g WHERE g.GLControlBR_CODE = 1 AND g.GLControlSLE_CODE = 11 AND g.GLControlACCT_CODE = c.COAID)
                ORDER BY c.COAID LIMIT 1
                """, String.class);
        assumeTrue(!gl.isEmpty(), "no asset account to stand for cash");
        cash = gl.get(0).trim();
        jdbc.update("INSERT INTO ecis_accounting_period (branch_code, name, start_date, end_date, status, updated_by, updated_at) VALUES (1, 'ITEST 1999-01', ?, ?, 'OPEN', 'itest', NOW())",
            DAY, LocalDate.of(1999, 1, 31));
    }

    private static Authentication as(String name) {
        Authentication a = new TestingAuthenticationToken(name, null, "ROLE_ACCOUNTANT");
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    private LineIn cash(String debit) { return new LineIn("GL", null, null, null, cash, new BigDecimal(debit), BigDecimal.ZERO, null); }

    private LineIn savings(int client, int type, Integer ref, String credit) { return new LineIn("SAVINGS", client, type, ref, null, BigDecimal.ZERO, new BigDecimal(credit), null); }

    private long create(LineIn... lines) {
        return batches.create(new BatchIn(branch, DAY.toString(), WHY, List.of(lines)), as(maker)).id();
    }

    @Test
    void openingBalancesArePostedAsOneGeneralJournalWithTheMembersOnTheirControlAccount() {
        // the account number is left out: the member's only savings account of the type is found
        long id = create(cash("123.45"), savings(emptyClient, emptyType, null, "123.45"));
        var detail = batches.get(id);
        assertEquals(List.of(), detail.problems());
        assertEquals(control, detail.lines().get(1).accountCode(), "the member line goes to the control account");
        assertTrue(detail.trialBalance().stream().anyMatch((t) -> t.account().equals(control) && t.memberAccounts() == 1 && t.control()));

        batches.submit(id, as(maker));
        batches.approve(id, as(approver));
        var posted = batches.post(id, as(poster));
        assertEquals("POSTED", posted.status());
        assertTrue(posted.documentNo().matches("\\*\\d{7}"), posted.documentNo());
        long ctl = Long.parseLong(posted.documentNo().substring(1));

        Map<String, Object> head = jdbc.queryForMap("SELECT TR_MODULE, TR_CLIENTID, EXPLANATION FROM transsum WHERE TR_BRCODE = 1 AND TR_YEAR = 1999 AND TR_MODULE = 1 AND TR_CODE = 1 AND TR_CTLNO = ?", ctl);
        assertEquals(emptyClient, ((Number) head.get("TR_CLIENTID")).intValue());
        assertEquals(WHY, head.get("EXPLANATION"));
        List<Map<String, Object>> lines = jdbc.queryForList("SELECT DTACCT_CODE, ClientIDDT, DTSLC_CODE, DTREF_NO, DTSLE_CODE, DTAMT, DTENCDBY FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = 1999 AND DTTR_MODULE = 1 AND DTTR_CODE = 1 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO", ctl);
        assertEquals(2, lines.size());
        assertNull(lines.get(0).get("ClientIDDT"), "the cash line has no member");
        assertEquals(control, ((String) lines.get(1).get("DTACCT_CODE")).trim());
        assertEquals(emptyClient, ((Number) lines.get(1).get("ClientIDDT")).intValue());
        assertEquals(22, ((Number) lines.get(1).get("DTSLC_CODE")).intValue());
        assertEquals(emptyRef, ((Number) lines.get(1).get("DTREF_NO")).intValue());
        assertEquals(0, new BigDecimal("-123.45").compareTo((BigDecimal) lines.get(1).get("DTAMT")));
        assertEquals(makerId, ((Number) lines.get(1).get("DTENCDBY")).intValue());

        BigDecimal balance = jdbc.queryForObject("SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 22 AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = 11",
            BigDecimal.class, emptyClient, emptyType, emptyRef);
        assertEquals(0, new BigDecimal("123.45").compareTo(balance), "the member's savings now hold the opening balance");
        BigDecimal net = jdbc.queryForObject("SELECT COALESCE(SUM(GLTransDR_AMT + GLTransCR_AMT), 0) FROM gltrans WHERE GLTransBR_CODE = 1 AND GLTransTR_DATE = ? AND GLTransTR_CODE = 1", BigDecimal.class, DAY.toString());
        assertEquals(0, net.compareTo(BigDecimal.ZERO), "the general ledger lines balance");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'opening.balance.post' AND entity_id = ?", Integer.class, String.valueOf(id)));

        assertThrows(ResponseStatusException.class, () -> batches.post(id, as(poster)), "posted once only");
        assertThrows(ResponseStatusException.class, () -> batches.cancel(id, new OpeningBalanceController.Reason("too late"), as(maker)), "a posted batch is not cancelled");
    }

    @Test
    void aLoanStillBeingPaidAndATimeDepositStillRunningAreOpenedWithTheirBalances() {
        // a monthly diminishing-balance loan type and a certificate type that have their control accounts (principal; accrued interest for the certificate)
        List<Integer> loanTypes = jdbc.queryForList("""
                SELECT t.SLTypeSLT_CODE FROM sltype t WHERE t.SLTypeBR_CODE = 1 AND t.SLTypeSLC_CODE = 12 AND COALESCE(t.SLTypeIsDisabled, 0) = 0 AND t.SLTypeAMORTYPE = 1 AND t.SLTypePPMT_MODE = 4
                  AND EXISTS (SELECT 1 FROM glcontrol g WHERE g.GLControlBR_CODE = 1 AND g.GLControlSLC_CODE = 12 AND g.GLControlSLT_CODE = t.SLTypeSLT_CODE AND g.GLControlSLE_CODE = 11 AND g.GLControlCTS_NUM = 11)
                ORDER BY t.SLTypeSLT_CODE LIMIT 1
                """, Integer.class);
        List<Integer> tdTypes = jdbc.queryForList("""
                SELECT t.SLTypeSLT_CODE FROM sltype t WHERE t.SLTypeBR_CODE = 1 AND t.SLTypeSLC_CODE = 24 AND COALESCE(t.SLTypeIsDisabled, 0) = 0
                  AND EXISTS (SELECT 1 FROM glcontrol g WHERE g.GLControlBR_CODE = 1 AND g.GLControlSLC_CODE = 24 AND g.GLControlSLT_CODE = t.SLTypeSLT_CODE AND g.GLControlSLE_CODE = 11 AND g.GLControlCTS_NUM = 15)
                  AND EXISTS (SELECT 1 FROM glcontrol g WHERE g.GLControlBR_CODE = 1 AND g.GLControlSLC_CODE = 24 AND g.GLControlSLT_CODE = t.SLTypeSLT_CODE AND g.GLControlSLE_CODE = 71 AND g.GLControlCTS_NUM = 99)
                ORDER BY t.SLTypeSLT_CODE LIMIT 1
                """, Integer.class);
        assumeTrue(!loanTypes.isEmpty() && !tdTypes.isEmpty(), "no loan or time deposit type with its control accounts");
        int loanType = loanTypes.get(0), tdType = tdTypes.get(0);
        as(maker);
        var offered = batches.types(branch);
        assertTrue(offered.stream().anyMatch((o) -> o.kind().equals("LOAN") && o.typeCode() == loanType && o.termMonths() != null), "the loan type is offered with its term");
        assertTrue(offered.stream().anyMatch((o) -> o.kind().equals("TD") && o.typeCode() == tdType), "the time deposit type is offered");
        LineIn loan = new LineIn("LOAN", emptyClient, loanType, null, null, new BigDecimal("12000.00"), BigDecimal.ZERO, "Appliance loan", null, new BigDecimal("12"), 12,
            "1999-01-15", new BigDecimal("20000.00"), "1998-07-15", null);
        LineIn td = new LineIn("TD", emptyClient, tdType, null, null, BigDecimal.ZERO, new BigDecimal("100000.00"), "Old certificate 0457", null, new BigDecimal("5"), 360, "1998-10-01",
            null, null, new BigDecimal("1250.00"));
        // cash: the certificate and its interest came in, the loan went out
        long id = create(cash("89250.00"), loan, td);
        var detail = batches.get(id);
        assertEquals(List.of(), detail.problems());
        assertEquals(LocalDate.of(1999, 9, 26), detail.lines().get(2).maturity());
        assertTrue(detail.lines().get(2).accrualAccount() != null, "the accrued interest has its own control account");
        assertEquals(4, detail.trialBalance().size(), "cash, the loans control, the certificates control and accrued interest: " + detail.trialBalance());

        batches.submit(id, as(maker));
        batches.approve(id, as(approver));
        assertEquals("POSTED", batches.post(id, as(poster)).status());
        var after = batches.get(id);
        int loanRef = after.lines().get(1).refNo(), tdRef = after.lines().get(2).refNo();

        Map<String, Object> l = jdbc.queryForMap("SELECT LoanPAMT, LoanSTATUS, LoanINT_RATE, LoanTERMS, LoanFID_DATE, LoanMAT_DATE, LoanRemarks FROM loan WHERE LoanBR_CODE = 1 AND LoanSLC_CODE = 12 AND LoanSLT_CODE = ? AND LoanREF_NO = ? AND ClientIDLoan = ?",
            loanType, loanRef, emptyClient);
        assertEquals(0, new BigDecimal("12000.00").compareTo((BigDecimal) l.get("LoanPAMT")), "the loan is opened for what is still owed");
        assertEquals(11, ((Number) l.get("LoanSTATUS")).intValue());
        assertEquals(12, ((Number) l.get("LoanTERMS")).intValue());
        assertEquals(LocalDate.of(1999, 1, 15), LocalDate.parse(l.get("LoanFID_DATE").toString().substring(0, 10)));
        assertEquals(LocalDate.of(1999, 12, 15), LocalDate.parse(l.get("LoanMAT_DATE").toString().substring(0, 10)));
        assertTrue(((String) l.get("LoanRemarks")).contains("granted 20000.00 on 1998-07-15"), (String) l.get("LoanRemarks"));
        assertEquals(13, jdbc.queryForObject("SELECT COUNT(*) FROM amortsched WHERE BR_CODE = 1 AND CLIENTID = ? AND SLC_CODE = 12 AND SLT_CODE = ? AND REF_NO = ?", Integer.class, emptyClient, loanType, loanRef));
        BigDecimal owed = jdbc.queryForObject("SELECT SUM(AMT) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 12 AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = 11",
            BigDecimal.class, emptyClient, loanType, loanRef);
        assertEquals(0, new BigDecimal("12000.00").compareTo(owed), "the loan's ledger holds the balance as its principal");

        Map<String, Object> t = jdbc.queryForMap("SELECT TDPAMT, TDTR_DATE, TDMAT_DATE, TDINT_RATE, TDTERMS, TDCERT_NUM, TDSTATUS FROM td WHERE TDBR_CODE = 1 AND TDSLC_CODE = 24 AND TDSLT_CODE = ? AND TDREF_NO = ?", tdType, tdRef);
        assertEquals("1998-10-01", t.get("TDTR_DATE").toString().substring(0, 10), "the certificate keeps its placement date");
        assertEquals("1999-09-26", t.get("TDMAT_DATE").toString().substring(0, 10));
        assertEquals(String.valueOf(tdRef), ((String) t.get("TDCERT_NUM")).trim(), "the certificate number is its account number");
        assertEquals(15, ((Number) t.get("TDSTATUS")).intValue());
        BigDecimal principal = jdbc.queryForObject("SELECT -SUM(AMT) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 24 AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = 11",
            BigDecimal.class, emptyClient, tdType, tdRef);
        BigDecimal accrued = jdbc.queryForObject("SELECT -SUM(AMT) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 24 AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = 71",
            BigDecimal.class, emptyClient, tdType, tdRef);
        assertEquals(0, new BigDecimal("100000.00").compareTo(principal));
        assertEquals(0, new BigDecimal("1250.00").compareTo(accrued), "the interest earned before the opening is the certificate's accrued interest");
        BigDecimal net = jdbc.queryForObject("SELECT COALESCE(SUM(GLTransDR_AMT + GLTransCR_AMT), 0) FROM gltrans WHERE GLTransBR_CODE = 1 AND GLTransTR_DATE = ? AND GLTransTR_CODE = 1", BigDecimal.class, DAY.toString());
        assertEquals(0, net.compareTo(BigDecimal.ZERO), "the general ledger lines balance");
        assertTrue(jdbc.queryForObject("SELECT reason FROM ecis_audit_log WHERE action = 'opening.balance.post' AND entity_id = ?", String.class, String.valueOf(id)).contains("1 loan opened, 1 time deposit opened"));
    }

    @Test
    void aLoanWhoseNextPaymentIsNotAfterTheOpeningOrOfATypeThatDoesNotExistIsRefused() {
        LineIn late = new LineIn("LOAN", emptyClient, 10, null, null, new BigDecimal("100.00"), BigDecimal.ZERO, null, null, null, 12, DAY.toString(), null, null, null);
        long id = create(late, new LineIn("GL", null, null, null, cash, BigDecimal.ZERO, new BigDecimal("100.00"), null));
        assertTrue(String.join(" ", batches.get(id).problems()).contains("next payment must be due after the opening date"));
        LineIn none = new LineIn("TD", emptyClient, 9999, null, null, BigDecimal.ZERO, new BigDecimal("100.00"), null, null, new BigDecimal("5"), 90, "1998-12-01", null, null, null);
        long t = create(cash("100.00"), none);
        assertTrue(String.join(" ", batches.get(t).problems()).contains("there is no time deposit type 9999"), batches.get(t).problems().toString());
    }

    @Test
    void aControlAccountTypedAsALedgerLineOrAnAccountWithEntriesIsRefused() {
        long id = create(cash("10.00"), new LineIn("GL", null, null, null, control, BigDecimal.ZERO, new BigDecimal("10.00"), null));
        assertTrue(String.join(" ", batches.get(id).problems()).contains("control account of members' accounts"));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> batches.submit(id, as(maker))).getStatusCode());

        long used = create(cash("10.00"), savings(usedClient, usedType, null, "10.00"));
        assertTrue(String.join(" ", batches.get(used).problems()).contains("an opening balance is an account's first entry"));
        assertThrows(ResponseStatusException.class, () -> batches.submit(used, as(maker)));
    }

    @Test
    void theSameAccountInTwoBatchesIsRefused() {
        create(cash("5.00"), savings(emptyClient, emptyType, emptyRef, "5.00"));
        long second = create(cash("5.00"), savings(emptyClient, emptyType, emptyRef, "5.00"));
        assertTrue(String.join(" ", batches.get(second).problems()).contains("also in another batch"));
    }

    @Test
    void theThreePeopleMustBeDifferentAndADraftCanBeChangedSentBackAndCancelled() {
        long id = create(cash("1.00"), savings(emptyClient, emptyType, null, "2.00"));
        assertTrue(String.join(" ", batches.get(id).problems()).contains("does not balance"));
        assertThrows(ResponseStatusException.class, () -> batches.submit(id, as(maker)), "an unbalanced batch is not sent");
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> batches.update(id, new BatchIn(branch, DAY.toString(), WHY, List.of(cash("2.00"), savings(emptyClient, emptyType, null, "2.00"))), as(approver))).getStatusCode(),
            "only the maker changes a draft");
        batches.update(id, new BatchIn(branch, DAY.toString(), WHY, List.of(cash("2.00"), savings(emptyClient, emptyType, null, "2.00"))), as(maker));
        assertEquals(List.of(), batches.get(id).problems());

        batches.submit(id, as(maker));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> batches.approve(id, as(maker))).getStatusCode(), "the maker cannot approve");
        assertEquals("DRAFT", batches.sendBack(id, new OpeningBalanceController.Reason("Cash count differs"), as(approver)).status(), "a reviewer sends it back");
        batches.submit(id, as(maker));
        batches.approve(id, as(approver));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> batches.post(id, as(maker))).getStatusCode(), "the maker cannot post");
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> batches.post(id, as(approver))).getStatusCode(), "the approver cannot post");
        assertEquals("CANCELLED", batches.cancel(id, new OpeningBalanceController.Reason("Prepared again"), as(maker)).status());
        assertEquals("Prepared again", batches.get(id).cancelReason());
    }
}
