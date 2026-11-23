package ph.coop.core.accounting.close;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import ph.coop.core.accounting.close.YearEndCloseController.Close;
import ph.coop.core.accounting.close.YearEndCloseController.NewClose;
import ph.coop.core.accounting.close.YearEndCloseController.Preview;

/**
 * Closes 2026 on the real ledger through the real code, as if it were 20 January 2027, checks the Closing Entry against the old system's
 * (documentation/state/year-end-close.md), then rolls everything back. Needs 2025 closed and 2026 not, two enabled legacy users and V023.
 * Journals of 2026 that are not posted are moved into 2027 inside the rolled-back transaction, so they do not block the close.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@Import(YearEndCloseTest.FixedClock.class)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class YearEndCloseTest {

    static final int YEAR = 2026;
    static final LocalDate START = LocalDate.of(YEAR, 1, 1), END = LocalDate.of(YEAR, 12, 31);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(LocalDateTime.of(2027, 1, 20, 9, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }
    }

    @Autowired YearEndCloseController closes;
    @Autowired ClosedBooks books;
    @Autowired CloseReviews reviews;
    @Autowired JdbcTemplate jdbc;

    private final int branch = 1;
    private String maker, checker;
    private int makerId, checkerId;

    @BeforeEach
    void setUp() {
        List<Map<String, Object>> u = jdbc.queryForList("""
                SELECT AccessID, AccessUserName FROM access
                WHERE COALESCE(AccessIsDisabled, 0) = 0 AND AccessLevel <> 'SUPERADMIN' AND AccessBR_CODE = 1 AND COALESCE(AccessDepartment, 0) > 0
                ORDER BY AccessID LIMIT 2
                """);
        assumeTrue(u.size() == 2, "need two enabled legacy users in branch 1");
        makerId = ((Number) u.get(0).get("AccessID")).intValue();
        maker = (String) u.get(0).get("AccessUserName");
        checkerId = ((Number) u.get(1).get("AccessID")).intValue();
        checker = (String) u.get(1).get("AccessUserName");
        assumeTrue(Integer.valueOf(YEAR - 1).equals(books.lastClosedYear(branch)), "needs 2025 closed and 2026 not");
        jdbc.update("UPDATE ecis_journal SET journal_date = ? WHERE branch_code = ? AND status <> 'POSTED' AND journal_date BETWEEN ? AND ?",
                LocalDate.of(YEAR + 1, 1, 5), branch, START, END);
        as(maker);
        assumeTrue(reviews.stored(), "the closing reviews change (V042) is not applied here");
        completeChecklist();
    }

    /** What an accountant does before the close: confirms each item of the year-end checklist, explaining what the application found wanting. */
    private void completeChecklist() {
        for (var item : reviews.items(branch, CloseChecklist.YEAR, String.valueOf(YEAR))) {
            if (!item.settled()) reviews.tick(branch, CloseChecklist.YEAR, String.valueOf(YEAR), item.key(), "Checked for the year-end close test", maker);
        }
    }

    private static Authentication as(String name) {
        Authentication a = new TestingAuthenticationToken(name, null, "ROLE_ACCOUNTANT");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    @org.junit.jupiter.api.AfterEach
    void signOut() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private BigDecimal sum(String sql, Object... args) {
        BigDecimal v = jdbc.queryForObject(sql, BigDecimal.class, args);
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal incomeAndExpenseNet() {
        return sum("""
                SELECT SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT) FROM gltrans WHERE GLTransBR_CODE = ? AND GLTransTR_DATE BETWEEN ? AND ? AND GLTransTR_CODE <> 98
                  AND (GLTransACCT_CODE LIKE '4%' OR GLTransACCT_CODE LIKE '5%')
                """, branch, START, END);
    }

    @Test
    void theCloseWaitsForTheYearEndChecklist() {
        reviews.untick(branch, CloseChecklist.YEAR, String.valueOf(YEAR), "ALLOWANCE");
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> closes.prepare(new NewClose(branch, YEAR), as(maker)));
        assertTrue(e.getReason().contains("year-end checklist"), e.getReason());
        completeChecklist();
        assertEquals("PREPARED", closes.prepare(new NewClose(branch, YEAR), as(maker)).status());
    }

    @Test
    void closingAFinishedYearWritesTheOldSystemsClosingEntryAndClosesTheYear() {
        BigDecimal before = incomeAndExpenseNet();
        assumeTrue(before.signum() != 0, "no income or expense in 2026");
        Preview p = closes.preview(branch, YEAR);
        assertEquals(List.of(), p.problems());
        assertEquals(0, before.negate().compareTo(p.surplus()), "the surplus is income less expenses");
        assertEquals(0, p.income().subtract(p.expenses()).compareTo(p.surplus()));

        Close c = closes.prepare(new NewClose(branch, YEAR), as(maker));
        assertEquals("PREPARED", c.status());
        assertEquals(0, before.compareTo(incomeAndExpenseNet()), "preparing writes nothing to the ledger");
        ResponseStatusException own = assertThrows(ResponseStatusException.class, () -> closes.post(c.id(), as(maker)));
        assertTrue(own.getReason().contains("cannot post it"));
        assertThrows(ResponseStatusException.class, () -> closes.prepare(new NewClose(branch, YEAR), as(checker)), "one closing at a time");

        Close posted = closes.post(c.id(), as(checker));
        assertEquals("POSTED", posted.status());
        assertNotNull(posted.controlNo());
        long ctl = posted.controlNo();

        Map<String, Object> h = jdbc.queryForMap("SELECT TR_DATE, TR_DOCNO, BATCH_NO, EXPLANATION, IS_FO FROM transsum WHERE TR_BRCODE = ? AND TR_YEAR = ? AND TR_MODULE = 1 AND TR_CODE = 99 AND TR_CTLNO = ?",
                branch, YEAR, ctl);
        assertEquals(END, ((java.sql.Date) h.get(h.keySet().iterator().next())).toLocalDate());
        assertEquals(String.format("%08d", ctl), h.values().toArray()[1]);
        assertEquals("20261231", String.valueOf(h.values().toArray()[2]).trim());
        assertEquals("Closing Entries (Computer Generated) for Date Ending: 12/31/2026", h.values().toArray()[3]);

        assertEquals(0, sum("SELECT SUM(DTAMT) FROM transdt WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = 1 AND DTTR_CODE = 99 AND DTTR_CTLNO = ?", branch, YEAR, ctl).signum(),
                "the closing entry balances");
        assertEquals(p.lineCount(), jdbc.queryForObject("SELECT COUNT(*) FROM transdt WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = 1 AND DTTR_CODE = 99 AND DTTR_CTLNO = ?",
                Integer.class, branch, YEAR, ctl));
        List<Map<String, Object>> people = jdbc.queryForList("SELECT DISTINCT DTENCDBY, DTAPPRVBY, DTPOSTBY, DTADJ_FLAG, DTUPD_TAG FROM transdt WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = 1 AND DTTR_CODE = 99 AND DTTR_CTLNO = ?",
                branch, YEAR, ctl);
        assertEquals(1, people.size());
        assertEquals(List.of(makerId, checkerId, checkerId, 1, 1), people.get(0).values().stream().map(v -> ((Number) v).intValue()).toList());

        assertEquals(0, incomeAndExpenseNet().signum(), "every income and expense account is at zero");
        List<Map<String, Object>> left = jdbc.queryForList("""
                SELECT GLTransDEPT, GLTransACCT_CODE FROM gltrans WHERE GLTransBR_CODE = ? AND GLTransTR_DATE BETWEEN ? AND ? AND GLTransTR_CODE <> 98
                  AND (GLTransACCT_CODE LIKE '4%' OR GLTransACCT_CODE LIKE '5%') GROUP BY GLTransDEPT, GLTransACCT_CODE HAVING SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT) <> 0
                """, branch, START, END);
        assertEquals(List.of(), left, "per department too");
        assertEquals(0, p.surplus().negate().compareTo(sum("SELECT SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT) FROM gltrans WHERE GLTransBR_CODE = ? AND GLTransTR_DATE = ? AND GLTransTR_CODE = 99 AND GLTransACCT_CODE = '30198'",
                branch, END)), "the surplus is credited to 30198");
        String pl = books.profitLossAccount(branch);
        assertEquals(0, sum("SELECT SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT) FROM gltrans WHERE GLTransBR_CODE = ? AND GLTransTR_DATE BETWEEN ? AND ? AND GLTransTR_CODE IN (98, 99) AND GLTransACCT_CODE = ?",
                branch, START, END, pl).signum(), "the running net surplus for the period ends the year at zero");

        assertTrue(books.isClosed(branch, YEAR));
        assertEquals(List.of(), jdbc.queryForList("SELECT status FROM ecis_accounting_period WHERE branch_code = ? AND start_date >= ? AND end_date <= ? AND status IN ('OPEN', 'SOFT_CLOSED')",
                String.class, branch, START, END), "the year's periods are closed");
        assertTrue(closes.preview(branch, YEAR).problems().get(0).contains("already closed"));
        Integer audits = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action IN ('yearend.prepare', 'yearend.post') AND username IN (?, ?)", Integer.class, maker, checker);
        assertTrue(audits != null && audits >= 2);
    }

    /** Characterization: the format of every row the Closing Entry writes (documentation/design/posting-engine.md). */
    @Test
    void theClosingEntryWritesRowsOfExactlyThisShape() {
        assumeTrue(incomeAndExpenseNet().signum() != 0, "no income or expense in 2026");
        Close posted = closes.post(closes.prepare(new NewClose(branch, YEAR), as(maker)).id(), as(checker));
        long ctl = posted.controlNo();
        String snap = ph.coop.core.accounting.ledger.LedgerSnapshot.shape(jdbc,
            new ph.coop.core.accounting.ledger.LedgerSnapshot.Where(branch, YEAR, 1, 99, ctl, END, 99, List.of()),
            List.of(ph.coop.core.accounting.ledger.LedgerSnapshot.ph("ctl", ctl, "tr_ctlno", "dttr_ctlno"),
                ph.coop.core.accounting.ledger.LedgerSnapshot.ph("docNo", String.format("%08d", ctl), "tr_docno"),
                ph.coop.core.accounting.ledger.LedgerSnapshot.ph("preparer", makerId, "dtencdby"),
                ph.coop.core.accounting.ledger.LedgerSnapshot.ph("checker", checkerId, "dtapprvby", "dtpostby")));
        ph.coop.core.accounting.ledger.LedgerSnapshot.assertMatches("year-end-close", snap);
    }

    @Test
    void aClosingIsRefusedWhenTheLedgerChangedAfterItWasPrepared() {
        Preview p = closes.preview(branch, YEAR);
        assumeTrue(p.problems().isEmpty(), "2026 cannot be closed here: " + p.problems());
        Close c = closes.prepare(new NewClose(branch, YEAR), as(maker));
        jdbc.update("""
                INSERT INTO gltrans (GLTransBR_CODE, GLTransACCT_CODE, GLTransTR_DATE, GLTransTR_CODE, GLTransDEPT, GLTransDR_AMT, GLTransCR_AMT,
                  GLTransDR_BALB4, GLTransCR_BALB4, GLTransSUM_BALB4, GLTransSYNC, GLTransLASTUPDATED)
                VALUES (?, '41100', ?, 1, 2, 0, -1.00, 0, 0, 0, 1, ?)
                """, branch, LocalDate.of(YEAR, 12, 30), LocalDateTime.now());
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> closes.post(c.id(), as(checker)));
        assertTrue(e.getReason().contains("changed after the closing was prepared"), e.getReason());
        assertThrows(ResponseStatusException.class, () -> closes.cancel(c.id(), new YearEndCloseController.Reason(" "), as(checker)), "a reason is required");
        assertEquals("CANCELLED", closes.cancel(c.id(), new YearEndCloseController.Reason("Late income booked"), as(checker)).status());
        assertEquals("PREPARED", closes.prepare(new NewClose(branch, YEAR), as(maker)).status(), "prepared again after the cancel");
    }

    @Test
    void aYearThatHasNotEndedCannotBeClosed() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> closes.prepare(new NewClose(branch, YEAR + 1), as(maker)));
        assertTrue(e.getReason().contains("has not ended"), e.getReason());
    }
}
