package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import ph.coop.core.frontoffice.MemoController.LineIn;
import ph.coop.core.frontoffice.MemoController.NewMemo;

/**
 * Posts a real debit/credit memo through the real code and checks what it writes into the legacy ledger (transsum module 1 type 2, transdt, the members' sldtl rows and gltrans),
 * then rolls everything back. Uses three existing enabled legacy users as maker, approver and poster without changing them, and an open period made up for the year 1999 (also
 * rolled back). Runs only when DB_PASSWORD is set; needs V051 and the ledger grants.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.audit.seal.enabled=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class MemoFlowTest {

    private static final LocalDate DAY = LocalDate.of(1999, 1, 15);
    private static final int YEAR = 1999;

    @Autowired MemoController memos;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private String maker, approver, poster;
    private int makerId, approverId, posterId;
    private final int branch = 1;
    /** Two savings accounts of different members: from has money to give, to is any other. */
    private int fromClient, fromType, fromRef, toClient, toType, toRef;
    private String ledgerAccount;

    @BeforeEach
    void setUp() {
        assumeTrue(db.tableExists("ecis_memo"), "V051 has not been applied");
        List<Map<String, Object>> u = jdbc.queryForList("""
                SELECT AccessID, AccessUserName FROM access
                WHERE COALESCE(AccessIsDisabled, 0) = 0 AND AccessLevel <> 'SUPERADMIN' AND AccessBR_CODE = 1 AND COALESCE(AccessDepartment, 0) > 0
                ORDER BY AccessID LIMIT 3
                """);
        assumeTrue(u.size() == 3, "need three enabled legacy users in branch 1");
        makerId = ((Number) u.get(0).get("AccessID")).intValue();
        maker = (String) u.get(0).get("AccessUserName");
        approverId = ((Number) u.get(1).get("AccessID")).intValue();
        approver = (String) u.get(1).get("AccessUserName");
        posterId = ((Number) u.get(2).get("AccessID")).intValue();
        poster = (String) u.get(2).get("AccessUserName");

        List<Map<String, Object>> rich = jdbc.queryForList("""
                SELECT s.ClientIDSD c, s.SDSLT_CODE t, s.SDREF_NO r FROM sd s
                WHERE s.SDBR_CODE = 1 AND s.SDSLC_CODE = 22 AND s.SDSTATUS = 15
                  AND (SELECT COALESCE(-SUM(d.AMT), 0) FROM sldtl d WHERE d.SL_BRCODE = s.SDBR_CODE AND d.SL_CLIENTID = s.ClientIDSD AND d.SLC_CODE = 22 AND d.SLT_CODE = s.SDSLT_CODE AND d.REF_NO = s.SDREF_NO AND d.SLE_CODE = 11) >= 50
                ORDER BY s.SDREF_NO DESC LIMIT 1
                """);
        assumeTrue(!rich.isEmpty(), "no savings account with at least 50");
        fromClient = ((Number) rich.get(0).get("c")).intValue();
        fromType = ((Number) rich.get(0).get("t")).intValue();
        fromRef = ((Number) rich.get(0).get("r")).intValue();
        List<Map<String, Object>> other = jdbc.queryForList("SELECT ClientIDSD c, SDSLT_CODE t, SDREF_NO r FROM sd WHERE SDBR_CODE = 1 AND SDSLC_CODE = 22 AND SDSTATUS = 15 AND ClientIDSD <> ? ORDER BY SDREF_NO LIMIT 1", fromClient);
        assumeTrue(!other.isEmpty(), "no second savings account");
        toClient = ((Number) other.get(0).get("c")).intValue();
        toType = ((Number) other.get(0).get("t")).intValue();
        toRef = ((Number) other.get(0).get("r")).intValue();
        List<String> gl = jdbc.queryForList("SELECT COAID FROM coa WHERE COABR_CODE = 1 AND COALESCE(COAacct_tag, '') <> 'S' AND COALESCE(COAisdisabled, 0) = 0 AND COAID LIKE '44%' ORDER BY COAID LIMIT 1", String.class);
        assumeTrue(!gl.isEmpty(), "no income account to post a fee to");
        ledgerAccount = gl.get(0);
        jdbc.update("INSERT INTO ecis_accounting_period (branch_code, name, start_date, end_date, status, updated_by, updated_at) VALUES (1, 'ITEST 1999-01', ?, ?, 'OPEN', 'itest', NOW())", LocalDate.of(YEAR, 1, 1), LocalDate.of(YEAR, 1, 31));
    }

    private static Authentication as(String name) {
        Authentication a = new TestingAuthenticationToken(name, null, "ROLE_ACCOUNTANT");
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    private LineIn from(String amount) { return new LineIn("SAVINGS", fromClient, fromType, fromRef, null, new BigDecimal(amount), BigDecimal.ZERO, null); }

    private LineIn to(String amount) { return new LineIn("SAVINGS", toClient, toType, toRef, null, BigDecimal.ZERO, new BigDecimal(amount), null); }

    private LineIn fee(String amount) { return new LineIn("LEDGER", null, null, null, ledgerAccount, BigDecimal.ZERO, new BigDecimal(amount), null); }

    private long create(String date, LineIn... lines) {
        return memos.create(new NewMemo(branch, date, "Transfer to beneficiary, authorised by the family (itest)", List.of(lines)), as(maker)).id();
    }

    private BigDecimal balance(int client, int type, int ref) {
        return jdbc.queryForObject("SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 22 AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = 11", BigDecimal.class, client, type, ref);
    }

    private long approved(long id) {
        memos.submit(id, as(maker));
        memos.approve(id, as(approver));
        return id;
    }

    @Test
    void aTransferBetweenTwoMembersIsPostedTheWayTheOldSystemWroteIt() {
        BigDecimal before = balance(fromClient, fromType, fromRef), toBefore = balance(toClient, toType, toRef);
        long id = approved(create(DAY.toString(), from("5.00"), to("5.00")));
        var posted = memos.post(id, as(poster));
        assertEquals("POSTED", posted.status());
        assertEquals(poster, posted.postedBy());
        assertNotNull(posted.documentNo());
        assertTrue(posted.documentNo().matches("\\*\\d{7}"), posted.documentNo());

        long ctl = Long.parseLong(posted.documentNo().substring(1));
        Map<String, Object> head = jdbc.queryForMap("SELECT TR_MODULE, TR_CODE, TR_CLIENTID, BATCH_NO, EXPLANATION FROM transsum WHERE TR_BRCODE = 1 AND TR_YEAR = ? AND TR_CODE = 2 AND TR_CTLNO = ?", YEAR, ctl);
        assertEquals(1, ((Number) head.get("TR_MODULE")).intValue());
        assertEquals(fromClient, ((Number) head.get("TR_CLIENTID")).intValue());
        assertEquals("19990115", head.get("BATCH_NO"));

        List<Map<String, Object>> lines = jdbc.queryForList("SELECT DTSEQ_NO, DTACCT_CODE, ClientIDDT, DTSLC_CODE, DTSLE_CODE, DTADJ_FLAG, DTAMT, DTENCDBY, DTAPPRVBY, DTPOSTBY, DTUPD_TAG FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_CODE = 2 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO", YEAR, ctl);
        assertEquals(2, lines.size());
        assertEquals(0, new BigDecimal("5.00").compareTo((BigDecimal) lines.get(0).get("DTAMT")), "the debit is positive");
        assertEquals(0, new BigDecimal("-5.00").compareTo((BigDecimal) lines.get(1).get("DTAMT")), "the credit is negative");
        assertEquals(makerId, ((Number) lines.get(0).get("DTENCDBY")).intValue());
        assertEquals(approverId, ((Number) lines.get(0).get("DTAPPRVBY")).intValue());
        assertEquals(posterId, ((Number) lines.get(0).get("DTPOSTBY")).intValue());
        assertEquals(1, ((Number) lines.get(0).get("DTADJ_FLAG")).intValue());
        assertEquals(22, ((Number) lines.get(0).get("DTSLC_CODE")).intValue());
        assertEquals(11, ((Number) lines.get(0).get("DTSLE_CODE")).intValue());

        List<Map<String, Object>> sl = jdbc.queryForList("SELECT SL_CLIENTID, AMT, SLE_CODE, TR_DOCNO, ADJ_FLAG FROM sldtl WHERE TR_CODE = 2 AND TR_CTLNO = ? AND TR_DATE = ? ORDER BY AMT DESC", ctl, DAY.toString());
        assertEquals(2, sl.size());
        assertEquals(fromClient, ((Number) sl.get(0).get("SL_CLIENTID")).intValue());
        assertEquals(0, new BigDecimal("5.00").compareTo((BigDecimal) sl.get(0).get("AMT")));
        assertEquals(posted.documentNo(), sl.get(0).get("TR_DOCNO"));

        BigDecimal net = jdbc.queryForObject("SELECT COALESCE(SUM(GLTransDR_AMT + GLTransCR_AMT), 0) FROM gltrans WHERE GLTransBR_CODE = 1 AND GLTransTR_DATE = ? AND GLTransTR_CODE = 2", BigDecimal.class, DAY.toString());
        assertEquals(0, net.compareTo(BigDecimal.ZERO), "the general ledger lines balance");
        assertEquals(0, before.subtract(new BigDecimal("5.00")).compareTo(balance(fromClient, fromType, fromRef)), "the giving account is down by the amount");
        assertEquals(0, toBefore.add(new BigDecimal("5.00")).compareTo(balance(toClient, toType, toRef)), "the receiving account is up by the amount");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'MEMO_POST' AND entity_id = ?", Integer.class, String.valueOf(id)));
    }

    @Test
    void aMemoCanMoveMoneyBetweenAMemberAndALedgerAccount() {
        long id = approved(create(DAY.toString(), from("2.50"), fee("2.50")));
        var posted = memos.post(id, as(poster));
        long ctl = Long.parseLong(posted.documentNo().substring(1));
        List<Map<String, Object>> lines = jdbc.queryForList("SELECT DTACCT_CODE, ClientIDDT, DTSLC_CODE, DTAMT FROM transdt WHERE DTTR_YEAR = ? AND DTTR_CODE = 2 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO", YEAR, ctl);
        assertEquals(2, lines.size());
        assertEquals(ledgerAccount, lines.get(1).get("DTACCT_CODE"));
        assertEquals(null, lines.get(1).get("ClientIDDT"), "a ledger line has no member");
        assertEquals(null, lines.get(1).get("DTSLC_CODE"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sldtl WHERE TR_CODE = 2 AND TR_CTLNO = ? AND TR_DATE = ?", Integer.class, ctl, DAY.toString()), "only the member line has a ledger row");
    }

    @Test
    void theThreePeopleMustBeDifferentAndEachStepIsInOrder() {
        long id = create(DAY.toString(), from("1.00"), to("1.00"));
        assertThrows(ResponseStatusException.class, () -> memos.approve(id, as(approver)), "not sent for review yet");
        memos.submit(id, as(maker));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> memos.approve(id, as(maker))).getStatusCode(), "the maker cannot approve");
        assertThrows(ResponseStatusException.class, () -> memos.post(id, as(poster)), "not approved yet");
        memos.approve(id, as(approver));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> memos.post(id, as(maker))).getStatusCode(), "the maker cannot post");
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> memos.post(id, as(approver))).getStatusCode(), "the approver cannot post");
        assertEquals("POSTED", memos.post(id, as(poster)).status());
        assertThrows(ResponseStatusException.class, () -> memos.post(id, as(poster)), "posted once only");
    }

    @Test
    void aMemoThatWouldOverdrawAnAccountOrNeedsAClosedPeriodIsRefused() {
        BigDecimal balance = balance(fromClient, fromType, fromRef);
        String tooMuch = balance.add(BigDecimal.ONE).toPlainString();
        assertThrows(ResponseStatusException.class, () -> create(DAY.toString(), from(tooMuch), to(tooMuch)), "overdrawn");
        long id = approved(create("1998-06-15", from("1.00"), to("1.00")));
        assertThrows(ResponseStatusException.class, () -> memos.post(id, as(poster)), "no accounting period covers 1998");
        assertEquals("APPROVED", memos.get(id).summary().status(), "nothing was written");
        assertThrows(ResponseStatusException.class, () -> create(DAY.toString(), from("1.00"), new LineIn("SAVINGS", toClient, toType, 999999999, null, BigDecimal.ZERO, BigDecimal.ONE, null)), "no such account");
        assertThrows(ResponseStatusException.class, () -> create(DAY.toString(), from("1.00"), new LineIn("LEDGER", null, null, null, "NOSUCH", BigDecimal.ZERO, BigDecimal.ONE, null)), "no such ledger account");
    }

    @Test
    void aPostedMemoIsReversedByANewMemoAndADraftCanBeCancelled() {
        // the reversal is dated today, so an open period for today is needed too (rolled back with the rest)
        jdbc.update("INSERT INTO ecis_accounting_period (branch_code, name, start_date, end_date, status, updated_by, updated_at) VALUES (1, 'ITEST today', CURRENT_DATE, CURRENT_DATE, 'OPEN', 'itest', NOW())");
        BigDecimal before = balance(fromClient, fromType, fromRef);
        long id = approved(create(DAY.toString(), from("4.00"), to("4.00")));
        memos.post(id, as(poster));
        assertThrows(ResponseStatusException.class, () -> memos.cancel(id, new MemoController.Reason("too late"), as(maker)), "a posted memo cannot be cancelled");
        assertThrows(ResponseStatusException.class, () -> memos.reverse(id, new MemoController.Reason(""), as(maker)), "a reason is needed");

        var rev = memos.reverse(id, new MemoController.Reason("Wrong beneficiary"), as(maker));
        assertEquals("DRAFT", rev.status());
        assertEquals(id, rev.reverses());
        var detail = memos.get(rev.id());
        assertEquals(0, new BigDecimal("4.00").compareTo(detail.lines().get(0).credit()), "debits and credits are swapped");
        assertEquals(0, new BigDecimal("4.00").compareTo(detail.lines().get(1).debit()));
        assertThrows(ResponseStatusException.class, () -> memos.reverse(id, new MemoController.Reason("again"), as(maker)), "one reversal only");

        memos.post(approved(rev.id()), as(poster));
        assertEquals(0, before.compareTo(balance(fromClient, fromType, fromRef)), "the reversal restores the balance");

        long draft = create(DAY.toString(), from("1.00"), to("1.00"));
        assertThrows(ResponseStatusException.class, () -> memos.cancel(draft, new MemoController.Reason("x"), as(maker)), "a reason is needed");
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> memos.cancel(draft, new MemoController.Reason("Made by mistake"), as(approver))).getStatusCode(), "only the maker or an administrator");
        assertEquals("CANCELLED", memos.cancel(draft, new MemoController.Reason("Made by mistake"), as(maker)).status());
        assertEquals("Made by mistake", memos.get(draft).cancelReason());
    }

    @Test
    void aMembersAccountsCanBeFoundForTheForm() {
        as(maker);
        var a = memos.accounts(branch, fromClient);
        assertEquals(fromClient, a.clientId());
        assertTrue(a.accounts().stream().anyMatch((x) -> x.kind().equals("SAVINGS") && x.typeCode() == fromType && x.refNo() == fromRef));
    }
}
