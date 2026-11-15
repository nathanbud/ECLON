package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.BeforeEach;
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
import ph.coop.core.db.Db;
import ph.coop.core.frontoffice.ChequeRules.Input;
import ph.coop.core.frontoffice.SavingsPostingController.Receipt;
import ph.coop.core.frontoffice.SavingsPostingController.Request;

/**
 * Posts savings deposits paid by cheque and by cash and cheque through the real code at a fixed date, compares the rows with the old
 * system's Cash Receipt (documentation/cheque-receipt-posting-map.md), then rolls everything back. Needs V024 (INSERT on transck) and the
 * same teller and account as the other posting tests; skips when they are missing.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@Import(ChequeReceiptPostingTest.FixedClock.class)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class ChequeReceiptPostingTest {

    static final LocalDate TODAY = LocalDate.of(2026, 4, 25);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(LocalDateTime.of(TODAY, java.time.LocalTime.of(10, 0)).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }
    }

    @Autowired SavingsPostingController savings;
    @Autowired JdbcTemplate jdbc;
    @Autowired Db db;

    private String teller;
    private int tellerId, tellerNumber, dept;
    private int[] account;

    @BeforeEach
    void setUp() {
        assumeTrue(db.canInsertInto("transck"), "needs V024_cheque_receipts.sql");
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
                SELECT s.ClientIDSD AS c, s.SDSLT_CODE AS t, s.SDREF_NO AS r FROM sd s JOIN client k ON k.ClientIDBrCode = s.SDBR_CODE AND k.ClientID = s.ClientIDSD AND k.AccountStatus = 1
                WHERE s.SDBR_CODE = 1 AND s.SDSTATUS = 15 ORDER BY s.SDREF_NO DESC LIMIT 1
                """);
        assumeTrue(!a.isEmpty(), "no active savings account");
        account = new int[] {((Number) a.get(0).get("c")).intValue(), ((Number) a.get(0).get("t")).intValue(), ((Number) a.get(0).get("r")).intValue()};
    }

    private Authentication as(String name) { return new TestingAuthenticationToken(name, null); }

    private static long counter = 900_000_000_000L + System.nanoTime() % 90_000_000_000L;

    private static String fresh() { return String.valueOf(counter++); }

    private Input cheque(String number, int kind, String amount) {
        return new Input(number, 1, TODAY.minusDays(5), kind, new BigDecimal(amount));
    }

    private Request req(String amount, List<Input> cheques) {
        return new Request(account[0], account[1], account[2], new BigDecimal(amount), "itest", null, true, cheques);
    }

    @Test
    void aChequeOnlyDepositWritesTheRowsTheOldCashReceiptWrote() {
        String n1 = fresh(), n2 = fresh();
        Receipt r = savings.deposit(req("3858.00", List.of(cheque(n1, 1, "3300.00"), cheque(n2, 2, "558.00"))), as(teller));

        List<Map<String, Object>> dt = jdbc.queryForList("SELECT * FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 3 AND DTTR_CODE = 10 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO", r.year(), r.controlNo());
        assertEquals(3, dt.size(), "two cheques and the member line, no cash line");
        for (int i = 0; i < 2; i++) {
            assertEquals("100113", dt.get(i).get("DTACCT_CODE"));
            assertEquals(3, ((Number) dt.get(i).get("DTSLT_CODE")).intValue(), "cheques sit in sub-ledger type 3");
            assertEquals(53, ((Number) dt.get(i).get("DTSLE_CODE")).intValue());
            assertEquals(tellerNumber, ((Number) dt.get(i).get("DTCTS_NUM")).intValue());
            assertEquals(2, ((Number) dt.get(i).get("DTCOCI_TYPE")).intValue());
        }
        assertEquals(0, new BigDecimal("3300.00").compareTo((BigDecimal) dt.get(0).get("DTAMT")));
        assertEquals(0, new BigDecimal("558.00").compareTo((BigDecimal) dt.get(1).get("DTAMT")));
        assertEquals(0, new BigDecimal("-3858.00").compareTo((BigDecimal) dt.get(2).get("DTAMT")));
        assertEquals(2, ((Number) dt.get(2).get("DTCOCI_TYPE")).intValue(), "the member line is marked cheque when any cheque is in the receipt");
        assertEquals(0, dt.stream().map((x) -> (BigDecimal) x.get("DTAMT")).reduce(BigDecimal.ZERO, BigDecimal::add).signum(), "the entries cancel out");
        for (Map<String, Object> x : dt) {
            assertEquals(tellerId, ((Number) x.get("DTENCDBY")).intValue());
            assertEquals(dept, ((Number) x.get("DTDEPT")).intValue());
        }

        List<Map<String, Object>> sl = jdbc.queryForList("SELECT AMT, COCI_TYPE, CLRG_DAYS, CHECKNO, BANK_CODE FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 22 AND SLT_CODE = ? AND REF_NO = ? AND TR_CTLNO = ? AND TR_CODE = 10 ORDER BY SEQNO",
            account[0], account[1], account[2], r.controlNo());
        assertEquals(2, sl.size(), "one ledger row for each cheque");
        assertEquals(0, new BigDecimal("-3300.00").compareTo((BigDecimal) sl.get(0).get("AMT")));
        assertEquals(2, ((Number) sl.get(0).get("COCI_TYPE")).intValue());
        assertEquals(5, ((Number) sl.get(0).get("CLRG_DAYS")).intValue(), "a local cheque: 3 days and 2");
        assertEquals(9, ((Number) sl.get(1).get("CLRG_DAYS")).intValue(), "a regional cheque: 7 days and 2");
        assertEquals(ChequeRules.number(n1), sl.get(0).get("CHECKNO"));
        assertEquals(1, ((Number) sl.get(0).get("BANK_CODE")).intValue());

        List<Map<String, Object>> ck = jdbc.queryForList("SELECT * FROM transck WHERE CKTR_BRCODE = 1 AND CKTR_YEAR = ? AND CKTR_MODULE = 3 AND CKTR_CODE = 10 AND CKTR_CTLNO = ? ORDER BY CKSEQ_NO", r.year(), r.controlNo());
        assertEquals(2, ck.size());
        assertEquals(0, ((Number) ck.get(0).get("CKSEQ_NO")).intValue());
        assertEquals(2, ((Number) ck.get(0).get("CKCOCI_TYPE")).intValue());
        assertEquals(1, ((Number) ck.get(0).get("CKCHECKTYPE")).intValue());
        assertEquals(3, ((Number) ck.get(0).get("CKCLRG_DAYS")).intValue(), "the days of the kind itself");
        assertEquals(7, ((Number) ck.get(1).get("CKCLRG_DAYS")).intValue());
        assertEquals(0, ((Number) ck.get(0).get("CKISCLEARED")).intValue());
        assertEquals(1, ((Number) ck.get(0).get("CKUPD_TAG")).intValue());
        assertEquals(tellerId, ((Number) ck.get(0).get("CKENCDBY")).intValue());
        assertEquals(tellerId, ((Number) ck.get(0).get("CKPOSTBY")).intValue());
        assertEquals(TODAY.minusDays(5).toString(), ck.get(0).get("CKCHECKDATE").toString());

        // the general ledger: a debit for each cheque on the cheque account
        Integer gl = jdbc.queryForObject("SELECT COUNT(*) FROM gltrans WHERE GLTransBR_CODE = 1 AND GLTransTR_DATE = ? AND GLTransTR_CODE = 10 AND GLTransLASTUPDATED >= ? AND GLTransACCT_CODE = '100113' AND GLTransDR_AMT IN (3300.00, 558.00)",
            Integer.class, TODAY.toString(), TODAY.atStartOfDay());
        assertTrue(gl >= 2);
    }

    @Test
    void aMixedDepositWritesTheChequeLinesFirstThenTheCashAndTheCashRowOfTransck() {
        long gl0 = ph.coop.core.accounting.ledger.LedgerSnapshot.glMark(jdbc);
        String chequeNo = fresh();
        Receipt r = savings.deposit(req("12000.00", List.of(cheque(chequeNo, 2, "2810.66"))), as(teller));
        ph.coop.core.accounting.ledger.LedgerSnapshot.assertMatches("savings-deposit-cheque-and-cash", ph.coop.core.accounting.ledger.LedgerSnapshot.voucherShape(jdbc, 1, r.year(), 3, 10, r.controlNo(), gl0,
            java.util.stream.Stream.concat(ph.coop.core.accounting.ledger.LedgerSnapshot.teller(r.controlNo(), r.documentNo(), tellerId, tellerNumber, dept, account[0], account[1], account[2]).stream(),
                java.util.stream.Stream.of(ph.coop.core.accounting.ledger.LedgerSnapshot.ph("chequeNo", ChequeRules.number(chequeNo), "checkno", "ckcheckno"))).toList()));
        List<Map<String, Object>> dt = jdbc.queryForList("SELECT DTACCT_CODE, DTSLT_CODE, DTCOCI_TYPE, DTAMT FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 3 AND DTTR_CODE = 10 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO", r.year(), r.controlNo());
        assertEquals(3, dt.size());
        assertEquals("100113", dt.get(0).get("DTACCT_CODE"));
        assertEquals("10003", dt.get(1).get("DTACCT_CODE"));
        assertEquals(1, ((Number) dt.get(1).get("DTCOCI_TYPE")).intValue());
        assertEquals(0, new BigDecimal("9189.34").compareTo((BigDecimal) dt.get(1).get("DTAMT")), "the cash is what the cheques do not cover");
        assertEquals(2, ((Number) dt.get(2).get("DTCOCI_TYPE")).intValue());

        List<Map<String, Object>> sl = jdbc.queryForList("SELECT AMT, COCI_TYPE, CLRG_DAYS FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 22 AND SLT_CODE = ? AND REF_NO = ? AND TR_CTLNO = ? AND TR_CODE = 10 ORDER BY SEQNO",
            account[0], account[1], account[2], r.controlNo());
        assertEquals(2, sl.size());
        assertEquals(1, ((Number) sl.get(0).get("COCI_TYPE")).intValue(), "the cash row comes first");
        assertEquals(0, ((Number) sl.get(0).get("CLRG_DAYS")).intValue());
        assertEquals(2, ((Number) sl.get(1).get("COCI_TYPE")).intValue());
        assertEquals(9, ((Number) sl.get(1).get("CLRG_DAYS")).intValue());

        List<Map<String, Object>> ck = jdbc.queryForList("SELECT CKSEQ_NO, CKCOCI_TYPE, CKAMT, CKCHECKTYPE, CKCHECKNO FROM transck WHERE CKTR_BRCODE = 1 AND CKTR_YEAR = ? AND CKTR_MODULE = 3 AND CKTR_CODE = 10 AND CKTR_CTLNO = ? ORDER BY CKSEQ_NO", r.year(), r.controlNo());
        assertEquals(2, ck.size());
        assertEquals(1, ((Number) ck.get(0).get("CKCOCI_TYPE")).intValue(), "the cash row is seq 0");
        assertEquals(0, new BigDecimal("9189.34").compareTo((BigDecimal) ck.get(0).get("CKAMT")));
        assertEquals(null, ck.get(0).get("CKCHECKTYPE"));
        assertEquals("", ck.get(0).get("CKCHECKNO"));
        assertEquals(2, ((Number) ck.get(1).get("CKCOCI_TYPE")).intValue());
    }

    @Test
    void aCashOnlyReceiptNowAlsoWritesItsCashRowInTransck() {
        Receipt r = savings.deposit(req("800.00", null), as(teller));
        List<Map<String, Object>> ck = jdbc.queryForList("SELECT CKSEQ_NO, CKCOCI_TYPE, CKAMT, CKISCLEARED, CKDEPT FROM transck WHERE CKTR_BRCODE = 1 AND CKTR_YEAR = ? AND CKTR_MODULE = 3 AND CKTR_CODE = 10 AND CKTR_CTLNO = ?", r.year(), r.controlNo());
        assertEquals(1, ck.size());
        assertEquals(1, ((Number) ck.get(0).get("CKCOCI_TYPE")).intValue());
        assertEquals(0, new BigDecimal("800.00").compareTo((BigDecimal) ck.get(0).get("CKAMT")));
        assertEquals(dept, ((Number) ck.get(0).get("CKDEPT")).intValue());
    }

    @Test
    void badChequesAreRefusedWithTheReasons() {
        Input stale = new Input(fresh(), 1, TODAY.minusDays(200), 1, new BigDecimal("100.00"));
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> savings.deposit(req("100.00", List.of(stale)), as(teller)));
        assertTrue(e.getReason().contains("stale"), e.getReason());
        Input noBank = new Input(fresh(), 99999, TODAY, 1, new BigDecimal("100.00"));
        e = assertThrows(ResponseStatusException.class, () -> savings.deposit(req("100.00", List.of(noBank)), as(teller)));
        assertTrue(e.getReason().contains("bank"), e.getReason());
        e = assertThrows(ResponseStatusException.class, () -> savings.deposit(req("100.00", List.of(cheque(fresh(), 1, "150.00"))), as(teller)));
        assertTrue(e.getReason().contains("more than the payment"), e.getReason());
    }

    @Test
    void theSameChequeAgainNeedsAConfirmation() {
        String n = fresh();
        savings.deposit(req("100.00", List.of(cheque(n, 1, "100.00"))), as(teller));
        Request again = new Request(account[0], account[1], account[2], new BigDecimal("100.00"), "itest", null, false, List.of(cheque(n, 1, "100.00")));
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> savings.deposit(again, as(teller)));
        assertTrue(e.getReason().contains("confirm it"), e.getReason());
    }
}
