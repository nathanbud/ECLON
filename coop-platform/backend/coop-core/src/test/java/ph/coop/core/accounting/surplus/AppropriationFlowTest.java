package ph.coop.core.accounting.surplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import ph.coop.core.accounting.close.ClosedBooks;
import ph.coop.core.accounting.ledger.Ledger;
import ph.coop.core.accounting.ledger.Money;
import ph.coop.core.accounting.ledger.Voucher;
import ph.coop.core.accounting.surplus.AppropriationController.AppropriationIn;
import ph.coop.core.accounting.surplus.AppropriationController.LineIn;

/**
 * Appropriates a made-up year's net surplus through the real code and checks the General Journal it writes, then rolls everything back. The year 1998 is closed
 * here with a Closing Entry written by the ledger (an income account to the undivided net surplus), the entry is dated in an open period made up for March 1999,
 * and three existing enabled users are maker, approver and poster without being changed. Runs only when DB_PASSWORD is set; needs PostgreSQL V045 (MySQL V082).
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.audit.seal.enabled=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class AppropriationFlowTest {

    private static final int YEAR = 1998;
    private static final LocalDate ENTRY = LocalDate.of(1999, 3, 15);
    private static final BigDecimal NET = new BigDecimal("100000.00");
    private static final String GA = "GA Resolution No. 7, s. 1999 (itest)";

    @Autowired AppropriationController appropriations;
    @Autowired Appropriations books;
    @Autowired Ledger ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private String maker, approver, poster;
    private int makerId;
    private List<String> funds;
    private String payout, source;

    @BeforeEach
    void setUp() {
        assumeTrue(db.tableExists("ecis_appropriation"), "V045 (MySQL V082) has not been applied");
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
        source = books.surplusAccount();
        payout = ph.coop.core.deposit.DividendRun.FUND_ACCOUNT;
        assumeTrue(jdbc.queryForObject("SELECT COUNT(*) FROM coa WHERE COABR_CODE = 1 AND COAID IN (?, ?) AND COAacct_tag = 'D'", Integer.class, source, payout) == 2,
            "the undivided net surplus and the dividend payout accounts must be in the chart");
        assumeTrue(!new ClosedBooksProbe(jdbc).closed(YEAR), YEAR + " is already closed in this database");

        // four equity or liability detail accounts to stand for the funds
        funds = jdbc.queryForList("""
                SELECT c.COAID FROM coa c WHERE c.COABR_CODE = 1 AND c.COAacct_tag = 'D' AND COALESCE(c.COAisdisabled, 0) = 0 AND c.COAacct_type IN (2, 3)
                  AND c.COAID NOT LIKE '4%' AND c.COAID NOT LIKE '5%' AND c.COAID NOT IN (?, ?, '30199')
                  AND NOT EXISTS (SELECT 1 FROM glcontrol g WHERE g.GLControlBR_CODE = 1 AND g.GLControlSLE_CODE = 11 AND g.GLControlACCT_CODE = c.COAID)
                ORDER BY c.COAID LIMIT 4
                """, String.class, source, payout).stream().map(String::trim).toList();
        assumeTrue(funds.size() == 4, "need four accounts to stand for the funds");
        String income = jdbc.queryForObject("SELECT MIN(COAID) FROM coa WHERE COABR_CODE = 1 AND COAacct_tag = 'D' AND COAID LIKE '4%'", String.class).trim();

        // close 1998: the income account to the undivided net surplus, as the year-end close writes it
        ledger.write(new Voucher("itest-close-" + YEAR, maker, 1, 1, ClosedBooks.CLOSING_ENTRY, LocalDate.of(YEAR, 12, 31), "Closing Entries (itest)", Voucher.Style.CLOSING_ENTRY,
            new Voucher.People(makerId, makerId, makerId), List.of(new Voucher.Line(income, 2, Money.of(NET)), new Voucher.Line(source, 2, Money.of(NET.negate()))), List.of()));
        jdbc.update("INSERT INTO ecis_accounting_period (branch_code, name, start_date, end_date, status, updated_by, updated_at) VALUES (1, 'ITEST 1999-03', ?, ?, 'OPEN', 'itest', NOW())",
            LocalDate.of(1999, 3, 1), LocalDate.of(1999, 3, 31));
    }

    /** Whether a year is closed, read before the test closes 1998. */
    private record ClosedBooksProbe(JdbcTemplate jdbc) {
        boolean closed(int year) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM transsum WHERE TR_BRCODE = 1 AND TR_YEAR = ? AND TR_MODULE = 1 AND TR_CODE = 99", Integer.class, year) > 0;
        }
    }

    private static Authentication as(String name) {
        Authentication a = new TestingAuthenticationToken(name, null, "ROLE_ACCOUNTANT");
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    private List<LineIn> lines(String reservePct, String isc, String pr) {
        return List.of(new LineIn("RESERVE", "Reserve fund", new BigDecimal(reservePct), null, funds.get(0)),
            new LineIn("EDUCATION", "Education and training fund", new BigDecimal("10"), null, funds.get(1)),
            new LineIn("COMMUNITY", "Community development fund", new BigDecimal("3"), null, funds.get(2)),
            new LineIn("OPTIONAL", "Optional fund", new BigDecimal("7"), null, funds.get(3)),
            new LineIn("ISC", "Interest on share capital", null, new BigDecimal(isc), null),
            new LineIn("PR", "Patronage refund", null, new BigDecimal(pr), null));
    }

    private long create(List<LineIn> lines) {
        return appropriations.create(new AppropriationIn(1, YEAR, ENTRY.toString(), GA, lines), as(maker)).id();
    }

    @Test
    void theNetSurplusIsSetAsideAndAllocatedInOneGeneralJournal() {
        assertEquals(0, NET.compareTo(books.closedSurplus(1, YEAR)), "the net surplus is what the closing put in the undivided net surplus");
        var start = appropriations.start(1, YEAR, as(maker));
        assertEquals(List.of(), start.problems());

        long id = create(lines("10", "40000.00", "25000.00"));
        var d = appropriations.get(id);
        assertEquals(List.of(), d.problems());
        assertEquals(0, new BigDecimal("30000.00").compareTo(d.totals().funds()));
        assertEquals(0, new BigDecimal("5000.00").compareTo(d.totals().undivided()), "what is not allocated stays undivided");
        assertEquals(payout, d.lines().get(4).account(), "interest goes to the account the dividend vouchers pay from");

        appropriations.submit(id, as(maker));
        appropriations.approve(id, as(approver));
        var posted = appropriations.post(id, as(poster));
        assertEquals("POSTED", posted.status());
        long ctl = Long.parseLong(posted.documentNo().substring(1));

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT DTACCT_CODE, DTAMT, DTENCDBY FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = 1999 AND DTTR_MODULE = 1 AND DTTR_CODE = 1 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO", ctl);
        assertEquals(6, rows.size(), "one debit and five credits (interest and refund on one account)");
        assertEquals(source, ((String) rows.get(0).get("DTACCT_CODE")).trim());
        assertEquals(0, new BigDecimal("95000.00").compareTo((BigDecimal) rows.get(0).get("DTAMT")));
        assertEquals(0, new BigDecimal("-65000.00").compareTo((BigDecimal) rows.get(5).get("DTAMT")));
        assertEquals(makerId, ((Number) rows.get(0).get("DTENCDBY")).intValue());
        BigDecimal net = jdbc.queryForObject("SELECT COALESCE(SUM(GLTransDR_AMT + GLTransCR_AMT), 0) FROM gltrans WHERE GLTransBR_CODE = 1 AND GLTransTR_DATE = ? AND GLTransTR_CODE = 1", BigDecimal.class, ENTRY);
        assertEquals(0, net.signum(), "the general ledger lines balance");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'accounting.appropriation.post' AND entity_id = ?", Integer.class, String.valueOf(id)));

        // the dividend distribution of the year then pays members no more than was appropriated
        Appropriations.Posted p = books.posted(1, YEAR);
        assertNotNull(p);
        assertEquals(4, p.funds().size());
        assertNull(books.distributionProblem(1, YEAR, true, NET, new BigDecimal("40000.00"), new BigDecimal("25000.00"), 100));
        assertTrue(books.distributionProblem(1, YEAR, false, null, new BigDecimal("40001.00"), BigDecimal.ZERO, 100).contains("more than"));
        assertTrue(books.distributionProblem(1, YEAR, true, new BigDecimal("99999.00"), BigDecimal.ZERO, BigDecimal.ZERO, 100).contains("not the one appropriated"));

        assertThrows(ResponseStatusException.class, () -> appropriations.post(id, as(poster)), "posted once only");
        assertThrows(ResponseStatusException.class, () -> appropriations.cancel(id, new AppropriationController.Reason("too late"), as(maker)), "a posted one is not cancelled");
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> create(lines("10", "1.00", "1.00"))).getStatusCode(), "one appropriation a year");
    }

    @Test
    void theLawsLimitsAndTheThreePeopleAreEnforced() {
        long id = create(lines("9", "40000.00", "25000.00"));
        assertTrue(String.join(" ", appropriations.get(id).problems()).contains("at least 10%"));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> appropriations.submit(id, as(maker))).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> appropriations.update(id, new AppropriationIn(1, YEAR, ENTRY.toString(), GA, lines("10", "40000.00", "25000.00")), as(approver))).getStatusCode(), "only the maker changes a draft");
        appropriations.update(id, new AppropriationIn(1, YEAR, ENTRY.toString(), GA, lines("10", "40000.00", "25000.00")), as(maker));
        assertEquals(List.of(), appropriations.get(id).problems());

        appropriations.submit(id, as(maker));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> appropriations.approve(id, as(maker))).getStatusCode(), "the maker cannot approve");
        assertEquals("DRAFT", appropriations.sendBack(id, new AppropriationController.Reason("Check the resolution"), as(approver)).status());
        appropriations.submit(id, as(maker));
        appropriations.approve(id, as(approver));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> appropriations.post(id, as(approver))).getStatusCode(), "the approver cannot post");
        assertEquals("CANCELLED", appropriations.cancel(id, new AppropriationController.Reason("Prepared again"), as(maker)).status());
        assertEquals("Prepared again", appropriations.get(id).cancelReason());
        create(lines("10", "1.00", "1.00"));
    }

    @Test
    void anOpenYearHasNoNetSurplusToAppropriate() {
        assertNull(books.closedSurplus(1, 1997 - 100));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> appropriations.create(new AppropriationIn(1, 2001, "2002-03-01", GA, lines("10", "1.00", "1.00")), as(maker))).getStatusCode());
    }
}
