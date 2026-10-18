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
import ph.coop.core.frontoffice.LoanPaymentController.Breakdown;
import ph.coop.core.frontoffice.LoanPaymentController.Receipt;
import ph.coop.core.frontoffice.LoanPaymentController.Request;
import ph.coop.core.loan.LoanBook;
import ph.coop.core.loan.LoanRules;

/**
 * Posts a real loan payment through the real code at a fixed date (the old data stops on 24 April 2026), compares the rows with the old
 * system's Cash Receipt (documentation/loan-posting-map.md), then rolls everything back. Uses an existing teller who may add cash receipts
 * and existing loans; skips when the database has none. Runs only when DB_PASSWORD is set; needs V006 and V011.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@Import(LoanPaymentPostingTest.FixedClock.class)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class LoanPaymentPostingTest {

    static final LocalDate TODAY = LocalDate.of(2026, 4, 25);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(LocalDateTime.of(TODAY, java.time.LocalTime.of(10, 0)).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }
    }

    @Autowired LoanPaymentController payments;
    @Autowired LoanBook book;
    @Autowired JdbcTemplate jdbc;

    private String teller;
    private int tellerId, tellerNumber, dept;

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
    }

    private Authentication as(String name) { return new TestingAuthenticationToken(name, null); }

    /** A loan that is current, has a balance, owes some interest and is not late. */
    private int[] loan(boolean late) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT l.ClientIDLoan AS c, l.LoanSLT_CODE AS t, l.LoanREF_NO AS r FROM loan l JOIN client k ON k.ClientIDBrCode = l.LoanBR_CODE AND k.ClientID = l.ClientIDLoan AND k.AccountStatus = 1
                WHERE l.LoanBR_CODE = 1 AND l.LoanSTATUS IN (11, 12) AND l.LoanTR_DATE BETWEEN ? AND ? AND l.LoanPAMT > 5000 ORDER BY l.LoanREF_NO DESC LIMIT 400
                """, late ? "2024-06-01" : "2025-10-01", late ? "2025-06-01" : "2026-03-01");
        for (Map<String, Object> r : rows) {
            int c = ((Number) r.get("c")).intValue(), t = ((Number) r.get("t")).intValue(), ref = ((Number) r.get("r")).intValue();
            var terms = book.terms(1, c, t, ref).orElse(null);
            if (terms == null || terms.outstanding().compareTo(new BigDecimal("3000")) < 0 || terms.status() != 11) continue;
            var q = book.quote(1, c, t, ref, terms, TODAY);
            if (q.interestDue().signum() <= 0) continue;
            boolean isLate = q.pastDuePrincipal().signum() > 0 && q.daysPastDue() > terms.graceDays();
            if (isLate == late) return new int[] {c, t, ref};
        }
        assumeTrue(false, late ? "no current loan that is late beyond its grace period" : "no current loan with interest due that is not late");
        return null;
    }

    private BigDecimal outstanding(int c, int t, int ref) {
        return jdbc.queryForObject("SELECT SUM(AMT) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 12 AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = 11", BigDecimal.class, c, t, ref);
    }

    @Test
    void aPaymentIsSplitInterestFirstAndPostsTheRowsTheOldCashReceiptWrote() {
        int[] l = loan(false);
        var terms = book.terms(1, l[0], l[1], l[2]).orElseThrow();
        // the interest the old system would book, worked out here from the raw ledger and loan rows
        BigDecimal interest = LoanRules.interest(outstanding(l[0], l[1], l[2]), terms.ratePercent(), terms.interestPaidTo(), TODAY);
        BigDecimal amount = interest.add(new BigDecimal("1000.00"));
        Integer seqBefore = jdbc.queryForObject("SELECT COALESCE(MAX(SEQNO), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 12 AND SLT_CODE = ? AND REF_NO = ?", Integer.class, l[0], l[1], l[2]);
        BigDecimal before = outstanding(l[0], l[1], l[2]);

        Request req = new Request(l[0], l[1], l[2], amount, "itest", null, false);
        Breakdown b = payments.check(req, as(teller));
        assertTrue(b.ready(), String.valueOf(b.problems()));
        assertEquals(0, interest.compareTo(b.interest()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(b.principal()));
        assertEquals(0, before.subtract(new BigDecimal("1000.00")).compareTo(b.balanceAfter()));

        Receipt r = payments.post(req, as(teller));
        assertEquals(0, interest.compareTo(r.interest()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(r.principal()));
        assertEquals(0, before.subtract(new BigDecimal("1000.00")).compareTo(outstanding(l[0], l[1], l[2])));

        List<Map<String, Object>> dt = jdbc.queryForList("SELECT * FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 3 AND DTTR_CODE = 10 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO", r.year(), r.controlNo());
        assertEquals(3, dt.size());
        assertEquals("10003", dt.get(0).get("DTACCT_CODE"));
        assertEquals(53, ((Number) dt.get(0).get("DTSLE_CODE")).intValue());
        assertEquals(tellerNumber, ((Number) dt.get(0).get("DTCTS_NUM")).intValue());
        assertEquals(0, amount.compareTo((BigDecimal) dt.get(0).get("DTAMT")));
        assertEquals(12, ((Number) dt.get(1).get("DTSLC_CODE")).intValue());
        assertEquals(11, ((Number) dt.get(1).get("DTSLE_CODE")).intValue());
        assertEquals(11, ((Number) dt.get(1).get("DTCTS_NUM")).intValue(), "the loan's status");
        assertEquals(0, new BigDecimal("-1000.00").compareTo((BigDecimal) dt.get(1).get("DTAMT")));
        assertEquals(12, ((Number) dt.get(2).get("DTSLC_CODE")).intValue());
        assertEquals(23, ((Number) dt.get(2).get("DTSLE_CODE")).intValue());
        assertEquals(99, ((Number) dt.get(2).get("DTCTS_NUM")).intValue());
        assertEquals(0, interest.negate().compareTo((BigDecimal) dt.get(2).get("DTAMT")));
        assertEquals(0, dt.stream().map((x) -> (BigDecimal) x.get("DTAMT")).reduce(BigDecimal.ZERO, BigDecimal::add).signum(), "the entries cancel out");
        for (Map<String, Object> x : dt) {
            assertEquals(tellerId, ((Number) x.get("DTENCDBY")).intValue());
            assertEquals(dept, ((Number) x.get("DTDEPT")).intValue());
        }

        List<Map<String, Object>> sl = jdbc.queryForList("SELECT SEQNO, SLE_CODE, AMT FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 12 AND SLT_CODE = ? AND REF_NO = ? AND TR_CTLNO = ? AND TR_CODE = 10 ORDER BY SEQNO",
            l[0], l[1], l[2], r.controlNo());
        assertEquals(2, sl.size());
        assertEquals(seqBefore + 1, ((Number) sl.get(0).get("SEQNO")).intValue());
        assertEquals(seqBefore + 2, ((Number) sl.get(1).get("SEQNO")).intValue(), "the ledger sequence runs on across events");
        assertEquals(11, ((Number) sl.get(0).get("SLE_CODE")).intValue());
        assertEquals(23, ((Number) sl.get(1).get("SLE_CODE")).intValue());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM gltrans WHERE GLTransBR_CODE = 1 AND GLTransTR_DATE = ? AND GLTransTR_CODE = 10 AND GLTransLASTUPDATED >= ? AND (GLTransACCT_CODE = '10003' AND GLTransDR_AMT = ? OR GLTransCR_AMT = ? OR GLTransCR_AMT = ?)",
            Integer.class, TODAY.toString(), TODAY.atStartOfDay(), amount, new BigDecimal("-1000.00"), interest.negate()));
    }

    @Test
    void aSmallPaymentIsAllInterestAndWritesOnlyTwoLines() {
        int[] l = loan(false);
        var terms = book.terms(1, l[0], l[1], l[2]).orElseThrow();
        BigDecimal interest = LoanRules.interest(outstanding(l[0], l[1], l[2]), terms.ratePercent(), terms.interestPaidTo(), TODAY);
        assumeTrue(interest.compareTo(new BigDecimal("1.00")) > 0);
        BigDecimal amount = new BigDecimal("1.00");
        Receipt r = payments.post(new Request(l[0], l[1], l[2], amount, null, null, false), as(teller));
        assertEquals(0, amount.compareTo(r.interest()));
        assertEquals(0, BigDecimal.ZERO.compareTo(r.principal()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 3 AND DTTR_CODE = 10 AND DTTR_CTLNO = ?", Integer.class, r.year(), r.controlNo()));
    }

    @Test
    void refusesMoreThanThePayoffAndBadAmounts() {
        int[] l = loan(false);
        Breakdown b = payments.check(new Request(l[0], l[1], l[2], new BigDecimal("1000.00"), null, null, false), as(teller));
        BigDecimal tooMuch = b.payoff().add(new BigDecimal("0.01"));
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> payments.post(new Request(l[0], l[1], l[2], tooMuch, null, null, false), as(teller)));
        assertTrue(e.getReason().contains("most that can be paid"));
        assertFalse(payments.check(new Request(l[0], l[1], l[2], BigDecimal.ZERO, null, null, false), as(teller)).ready());
        assertThrows(ResponseStatusException.class, () -> payments.post(new Request(l[0], l[1], l[2], new BigDecimal("1.005"), null, null, false), as(teller)));
        assertThrows(ResponseStatusException.class, () -> payments.post(new Request(l[0], l[1], 99999999, BigDecimal.ONE, null, null, false), as(teller)));
        // paying exactly the payoff clears the loan
        Receipt r = payments.post(new Request(l[0], l[1], l[2], b.payoff(), null, null, false), as(teller));
        assertEquals(0, BigDecimal.ZERO.compareTo(r.balanceAfter()));
        assertEquals(0, BigDecimal.ZERO.compareTo(outstanding(l[0], l[1], l[2])));
    }

    @Test
    void refusesALoanThatIsLateBeyondItsGracePeriodBecauseThePenaltyIsNotKnown() {
        int[] l = loan(true);
        Breakdown b = payments.check(new Request(l[0], l[1], l[2], new BigDecimal("1000.00"), null, null, false), as(teller));
        assertFalse(b.ready());
        assertTrue(b.problems().stream().anyMatch((p) -> p.contains("past due") && p.contains("penalty")));
        assertThrows(ResponseStatusException.class, () -> payments.post(new Request(l[0], l[1], l[2], new BigDecimal("1000.00"), null, null, false), as(teller)));
    }

    @Test
    void refusesPeopleWhoMayNotTakeCash() {
        int[] l = loan(false);
        List<String> other = jdbc.queryForList("SELECT a.AccessUserName FROM access a WHERE COALESCE(a.AccessIsDisabled, 0) = 0 AND a.AccessLevel <> 'SUPERADMIN' AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 0 LIMIT 1", String.class);
        if (!other.isEmpty()) {
            assertThrows(ResponseStatusException.class, () -> payments.post(new Request(l[0], l[1], l[2], BigDecimal.ONE, null, null, false), as(other.get(0))));
        }
    }
}
