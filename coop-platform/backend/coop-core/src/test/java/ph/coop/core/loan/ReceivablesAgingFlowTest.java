package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.loan.ReceivablesAging.Line;
import ph.coop.core.loan.ReceivablesAgingController.Request;

/**
 * The aging of receivables against the real data on the last day of the old data: the loans alone agree with the loan PAR report, the receivable classes add up, groups
 * add up to the total, only administrators and accountants may run it, and a run is audited. Rolled back.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class ReceivablesAgingFlowTest {

    static final LocalDate CUT = LocalDate.of(2026, 4, 24);

    @Autowired ReceivablesAgingController aging;
    @Autowired JdbcTemplate jdbc;

    private static Authentication accountant() {
        return new TestingAuthenticationToken("tester", "x", "ROLE_ACCOUNTANT");
    }

    private static Request req(List<Integer> classes, String basis, int days, List<String> groupBy, boolean totalsOnly) {
        return new Request(CUT, basis, days, classes, List.of(), null, null, null, null, null, null, null, null, null, null, groupBy, "name", totalsOnly);
    }

    private static Line total(List<Line> lines) {
        Line t = lines.get(lines.size() - 1);
        assertEquals("total", t.kind());
        return t;
    }

    @Test
    void loansAloneAgreeWithTheLedgerAndAccountsReceivableAddOn() {
        var loans = aging.run(req(List.of(12), "PAR", 30, List.of(), true), accountant());
        BigDecimal ledger = jdbc.queryForObject("SELECT COALESCE(SUM(b), 0) FROM (SELECT SUM(AMT) b FROM sldtl WHERE SL_BRCODE = 1 AND SLC_CODE = 12 AND SLE_CODE = 11 AND TR_DATE <= ?"
            + " GROUP BY SL_CLIENTID, SLT_CODE, REF_NO HAVING SUM(AMT) > 0.004) x", BigDecimal.class, CUT);
        assertEquals(0, ledger.compareTo(total(loans.lines()).outstanding()), "the loans' outstanding is the ledger's");
        BigDecimal buckets = total(loans.lines()).buckets().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, buckets.compareTo(total(loans.lines()).outstanding()), "the buckets add up to the outstanding");

        var both = aging.run(req(List.of(12, 13), "PAR", 30, List.of("slclass"), true), accountant());
        List<Line> heads = both.lines().stream().filter((l) -> l.kind().equals("group")).toList();
        BigDecimal sum = heads.stream().map(Line::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(total(both.lines()).outstanding()), "the classes add up to the total");
        assertTrue(total(both.lines()).outstanding().compareTo(total(loans.lines()).outstanding()) >= 0);
    }

    @Test
    void nestedGroupsAddUpAndAccountsAreListed() {
        var r = aging.run(req(List.of(12, 13), "MATURITY", 1, List.of("sltype", "aging"), false), accountant());
        Line t = total(r.lines());
        long accounts = r.lines().stream().filter((l) -> l.kind().equals("account")).count();
        assertEquals(t.accounts(), accounts);
        BigDecimal outer = r.lines().stream().filter((l) -> l.kind().equals("group") && l.level() == 0).map(Line::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, outer.compareTo(t.outstanding()));
    }

    @Test
    void filtersNarrowTheList() {
        var all = total(aging.run(req(List.of(12), "PAR", 30, List.of(), true), accountant()).lines());
        var late = total(aging.run(new Request(CUT, "PAR", 30, List.of(12), List.of(), null, null, null, null, null, null, null, null, 31L, null, List.of(), "name", true), accountant()).lines());
        assertTrue(late.accounts() < all.accounts());
        assertEquals(0, late.atRisk().compareTo(late.outstanding()), "every account late over 30 days is at risk at PAR 30");
        assertThrows(ResponseStatusException.class, () -> aging.run(new Request(CUT, "PAR", 30, List.of(12), List.of(), CUT, CUT.minusDays(1), null, null, null, null, null, null, null, null, List.of(), "name", true), accountant()));
        assertThrows(ResponseStatusException.class, () -> aging.run(req(List.of(22), "PAR", 30, List.of(), true), accountant()), "savings is not a receivable");
    }

    @Test
    void accruedInterestMatchesTheConfirmedWorksheetAndAddsUp() {
        // loan 1221-00003212 of member 28711: the old system printed interest due 6,809.59 on 2026-04-24 (pics/intwork.pdf)
        var r = aging.run(new Request(CUT, "PAR", 30, List.of(12), List.of("12:21"), null, null, null, null, null, null, null, null, null, null, List.of(), "name", false,
            true, true, false), accountant());
        assertTrue(r.accrual() && r.penalty());
        Line loan = r.lines().stream().filter((l) -> "account".equals(l.kind()) && Integer.valueOf(28711).equals(l.client()) && Integer.valueOf(3212).equals(l.ref())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("6809.59").compareTo(loan.interestDue()));
        Line t = total(r.lines());
        BigDecimal interest = r.lines().stream().filter((l) -> "account".equals(l.kind())).map(Line::interestDue).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, interest.compareTo(t.interestDue()), "the total is the sum of the accounts");
        assertEquals(0, t.outstanding().add(t.interestDue()).add(t.penaltyDue()).compareTo(t.totalDue()));

        var owing = aging.run(new Request(CUT, "PAR", 30, List.of(12), List.of("12:21"), null, null, null, null, null, null, null, null, null, null, List.of(), "name", true,
            true, false, true), accountant());
        assertTrue(total(owing.lines()).accounts() <= t.accounts());
        assertEquals(0, total(owing.lines()).penaltyDue().signum(), "penalty left out when not asked");
        assertThrows(ResponseStatusException.class, () -> aging.run(new Request(CUT, "PAR", 30, List.of(12), List.of(), null, null, null, null, null, null, null, null, null, null,
            List.of(), "name", true, false, false, true), accountant()), "owing only needs the interest");
    }

    @Test
    void excelAndPdfOfTheLastRunAreLoggedAndAudited() throws Exception {
        Request r = new Request(CUT, "PAR", 30, List.of(12, 13), List.of(), null, null, null, null, null, null, null, null, null, null, List.of("slclass", "aging"), "name", false,
            true, true, false);
        aging.run(r, accountant());
        String count = "SELECT COUNT(*) FROM ecis_audit_log WHERE entity_id = 'receivables-aging' AND action = ?";
        Integer runs = jdbc.queryForObject(count, Integer.class, "report.run");
        Integer exportsAudited = jdbc.queryForObject(count, Integer.class, "report.export");
        Integer exportsLogged = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_data_export WHERE subject = ?", Integer.class, "Aging of receivables as of " + CUT);
        long t0 = System.currentTimeMillis();
        var xlsx = aging.download("xlsx", r, accountant());
        var pdf = aging.download("pdf", r, accountant());
        long ms = System.currentTimeMillis() - t0;
        assertEquals(runs, jdbc.queryForObject(count, Integer.class, "report.run"), "the files reuse the run on screen instead of reading the ledgers again");
        assertEquals(exportsAudited + 2, jdbc.queryForObject(count, Integer.class, "report.export"));
        assertEquals(exportsLogged + 2, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_data_export WHERE subject = ?", Integer.class, "Aging of receivables as of " + CUT));
        assertTrue(xlsx.getHeaders().getFirst("Content-Disposition").contains("aging-of-receivables-" + CUT + ".xlsx"));
        assertEquals("application/pdf", pdf.getHeaders().getContentType().toString());
        assertTrue(new String(pdf.getBody(), 0, 5).startsWith("%PDF"));
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx.getBody()))) {
            assertEquals("Aging", book.getSheetName(0));
        }
        String keep = System.getenv("AGING_SAMPLE_DIR");
        if (keep != null) {
            java.nio.file.Files.write(java.nio.file.Path.of(keep, "aging.xlsx"), xlsx.getBody());
            java.nio.file.Files.write(java.nio.file.Path.of(keep, "aging.pdf"), pdf.getBody());
            System.out.println("SAMPLE files written in " + ms + " ms");
        }
        // other choices run again
        Request other = new Request(CUT, "MATURITY", 1, List.of(13), List.of(), null, null, null, null, null, null, null, null, null, null, List.of(), "name", true);
        aging.download("pdf", other, accountant());
        assertEquals(runs + 1, jdbc.queryForObject(count, Integer.class, "report.run"));
        Authentication teller = new TestingAuthenticationToken("teller", "x", "ROLE_TELLER");
        assertThrows(ResponseStatusException.class, () -> aging.download("pdf", r, teller));
    }

    @Test
    void onlyAdministratorsAndAccountantsAndEveryRunIsAudited() {
        Authentication teller = new TestingAuthenticationToken("teller", "x", "ROLE_TELLER");
        assertThrows(ResponseStatusException.class, () -> aging.options(teller));
        assertThrows(ResponseStatusException.class, () -> aging.run(req(List.of(12), "PAR", 30, List.of(), true), teller));
        var options = aging.options(accountant());
        assertEquals(List.of(12, 13, 25), options.classes().stream().map((c) -> c.code()).toList());
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'report.run' AND entity_id = 'receivables-aging'", Integer.class);
        aging.run(req(List.of(13), "PAR", 30, List.of(), true), accountant());
        Integer after = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'report.run' AND entity_id = 'receivables-aging'", Integer.class);
        assertEquals(before + 1, after);
    }
}
