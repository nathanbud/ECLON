package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.frontoffice.PenaltyWaiverController.Decision;
import ph.coop.core.frontoffice.PenaltyWaiverController.Request;

/**
 * The waived-penalties report from audit rows made for the test, and the waiver journal through the real code (ask, a second person approves, post)
 * compared with the old journal (loan-posting-map.md), then rolled back. Runs only when DB_PASSWORD is set.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class PenaltyWaiverTest {

    @Autowired PenaltyWaiverController waivers;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    /** The controller checks the role on every call, so the signed-in person is set first. */
    private static Authentication as(Authentication a) {
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    private static Authentication accountant(String name) {
        return new TestingAuthenticationToken(name, null, List.of(new SimpleGrantedAuthority("ROLE_ACCOUNTANT")));
    }

    @Test
    void theReportListsOnlyPaymentsWhereLessThanTheSuggestedPenaltyWasCharged() {
        Timestamp at = Timestamp.valueOf(LocalDateTime.now().minusMinutes(5));
        String sql = "INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value) VALUES (?, 'itest-cashier', 'loan.payment.post', 'transsum', ?, ?)";
        jdbc.update(sql, at, "1/2026/3/10/990001", "client 4505 loan 30/1198 amount 500.00 penalty 100.00 (suggested 150.00) interest 10.00 principal 390.00");
        jdbc.update(sql, at, "1/2026/3/10/990002", "client 4505 loan 30/1198 amount 500.00 penalty 150.00 (suggested 150.00) interest 10.00 principal 340.00");
        jdbc.update(sql, at, "1/2026/3/10/990003", "client 4505 loan 30/1198 amount 500.00 penalty 0.00 (no suggestion) interest 10.00 principal 490.00");
        as(accountant("itest-acct"));
        var r = waivers.report(null, null);
        var mine = r.rows().stream().filter((w) -> w.receipt().startsWith("1/2026/3/10/99000")).toList();
        assertEquals(1, mine.size(), "only the payment charged below the suggestion");
        assertEquals(0, new BigDecimal("50.00").compareTo(mine.get(0).waived()));
        assertEquals(30, mine.get(0).typeCode());
        assertEquals(1198, mine.get(0).refNo());
        assertNull(mine.get(0).allowedBy(), "no override was spent for this made-up payment");
        assertTrue(r.total().compareTo(new BigDecimal("50.00")) >= 0);
        assertThrows(ResponseStatusException.class, () -> waivers.report("2026-05-01", "2026-04-01"));
    }

    @Test
    void aWaiverJournalDebitsTheExpenseAndCreditsTheLoanAsEvent42WithAnotherPersonApproving() {
        List<Map<String, Object>> people = jdbc.queryForList("""
                SELECT AccessID, AccessUserName FROM access WHERE AccessBR_CODE = 1 AND COALESCE(AccessIsDisabled, 0) = 0 AND COALESCE(AccessDepartment, 0) > 0 ORDER BY AccessID LIMIT 2
                """);
        assumeTrue(people.size() == 2, "need two enabled users with a department");
        Authentication maker = accountant((String) people.get(0).get("AccessUserName"));
        Authentication checker = accountant((String) people.get(1).get("AccessUserName"));
        int makerId = ((Number) people.get(0).get("AccessID")).intValue();
        as(maker);
        List<PenaltyWaiverController.Expense> expense = waivers.expenseAccounts();
        assumeTrue(!expense.isEmpty(), "no penalty waived expense account in the chart");
        List<Map<String, Object>> loan = jdbc.queryForList("""
                SELECT l.ClientIDLoan, l.LoanSLT_CODE, l.LoanREF_NO FROM loan l JOIN glcontrol g ON g.GLControlBR_CODE = l.LoanBR_CODE AND g.GLControlSLC_CODE = 12
                  AND g.GLControlSLT_CODE = l.LoanSLT_CODE AND g.GLControlSLE_CODE = 42 AND g.GLControlCTS_NUM = 99
                WHERE l.LoanBR_CODE = 1 AND l.LoanSLC_CODE = 12 ORDER BY l.LoanTR_DATE DESC LIMIT 1
                """);
        assumeTrue(!loan.isEmpty(), "no loan whose type has a waived penalties account");
        int client = ((Number) loan.get(0).get("ClientIDLoan")).intValue(), type = ((Number) loan.get(0).get("LoanSLT_CODE")).intValue(), ref = ((Number) loan.get(0).get("LoanREF_NO")).intValue();
        Request req = new Request(client, type, ref, new BigDecimal("123.45"), expense.get(0).code(), "50% penalty under BOD approval (itest)");

        assertEquals("NONE", waivers.check(req, as(maker)).approval());
        assertThrows(ResponseStatusException.class, () -> waivers.post(req, as(maker)), "nothing posts without an approval");
        assertFalse(waivers.check(new Request(client, type, ref, new BigDecimal("0"), expense.get(0).code(), "x"), as(maker)).problems().isEmpty(), "a zero amount and no reason are problems");
        waivers.ask(req, as(maker));
        assertEquals("PENDING", waivers.check(req, as(maker)).approval());
        long id = jdbc.queryForObject("SELECT MAX(id) FROM ecis_fo_override WHERE kind = 'PENALTY_WAIVER'", Long.class);
        assertThrows(ResponseStatusException.class, () -> waivers.decide(id, new Decision(true, null), as(maker)), "nobody approves their own request");
        assertEquals(1, waivers.pending(as(checker)).size());
        waivers.decide(id, new Decision(true, "agreed"), as(checker));
        assertTrue(waivers.check(req, as(maker)).ready());

        var posted = waivers.post(req, as(maker));
        assertEquals(0, new BigDecimal("123.45").compareTo(posted.amount()));
        List<Map<String, Object>> dt = jdbc.queryForList("SELECT * FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 1 AND DTTR_CODE = 1 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO",
            posted.year(), posted.controlNo());
        assertEquals(2, dt.size());
        assertEquals(expense.get(0).code(), dt.get(0).get("DTACCT_CODE"));
        assertNull(dt.get(0).get("DTSLC_CODE"), "the expense line has no sub-ledger");
        assertEquals(0, new BigDecimal("123.45").compareTo((BigDecimal) dt.get(0).get("DTAMT")));
        assertEquals(42, ((Number) dt.get(1).get("DTSLE_CODE")).intValue());
        assertEquals(99, ((Number) dt.get(1).get("DTCTS_NUM")).intValue());
        assertEquals(0, new BigDecimal("-123.45").compareTo((BigDecimal) dt.get(1).get("DTAMT")));
        assertEquals(makerId, ((Number) dt.get(1).get("DTPOSTBY")).intValue());
        assertTrue(((Number) dt.get(1).get("DTAPPRVBY")).intValue() != makerId, "a different person approved it");
        Map<String, Object> sl = jdbc.queryForMap("SELECT AMT, SLE_CODE, ADJ_FLAG, COCI_TYPE FROM sldtl WHERE SL_BRCODE = 1 AND TR_CODE = 1 AND TR_CTLNO = ? AND SL_CLIENTID = ? AND SLE_CODE = 42", posted.controlNo(), client);
        assertEquals(0, new BigDecimal("-123.45").compareTo((BigDecimal) sl.get("AMT")));
        assertNull(sl.get("COCI_TYPE"));
        // the loan balance (event 11) is not touched
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sldtl WHERE SL_BRCODE = 1 AND TR_CODE = 1 AND TR_CTLNO = ? AND SL_CLIENTID = ? AND SLE_CODE = 11", Integer.class, posted.controlNo(), client));
        // the approval is spent once
        assertThrows(ResponseStatusException.class, () -> waivers.post(req, as(maker)));
    }
}
