package ph.coop.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.report.Reports.Table;

/**
 * Runs the reports against the database: every report must run, its rows must be as wide as its columns, who may see what is enforced, and the figures must agree with
 * each other and with the tables they come from. The loan reports are run on the last day of the old data (2026-04-24). Rolled back.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class ReportsTest {

    static final String OLD_DATA_END = "2026-04-24";

    @Autowired ReportController reports;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private Authentication admin() {
        return new TestingAuthenticationToken("tester", "x", "ROLE_ADMIN");
    }

    private Table run(String id, String... kv) {
        Map<String, String> p = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) p.put(kv[i], kv[i + 1]);
        return reports.run(id, p, admin()).table();
    }

    private static void wellFormed(Table t) {
        for (List<Object> r : t.rows()) assertEquals(t.columns().size(), r.size(), "a row is as wide as the columns");
        if (t.totals() != null) assertEquals(t.columns().size(), t.totals().size(), "the totals row is as wide as the columns");
    }

    private static BigDecimal money(Object o) {
        return o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    @Test
    void everyReportIsListedForAnAdministratorAndRunsWithItsDefaults() {
        var infos = reports.list(admin());
        assertTrue(infos.size() >= 20, "found " + infos.size());
        for (var info : infos) {
            if (info.group().equals("Loans")) continue; // run apart below, they scan the whole loan ledger
            Map<String, String> defaults = new HashMap<>();
            for (var p : info.params()) if (!p.defaultValue().isBlank()) defaults.put(p.name(), p.defaultValue());
            if (info.id().equals("general-ledger")) defaults.put("account", "10001");
            wellFormed(reports.run(info.id(), defaults, admin()).table());
        }
    }

    @Test
    void whoMayRunWhatIsEnforced() {
        Authentication keeper = new TestingAuthenticationToken("keeper", "x", "ROLE_STOREKEEPER");
        Authentication officer = new TestingAuthenticationToken("officer", "x", "ROLE_MEMBERSHIP_OFFICER");
        Authentication nobody = new TestingAuthenticationToken("nobody", "x", "ROLE_PURCHASER");
        var forKeeper = reports.list(keeper).stream().map((i) -> i.group()).distinct().toList();
        assertEquals(List.of("Inventory"), forKeeper);
        assertEquals(List.of("Membership"), reports.list(officer).stream().map((i) -> i.group()).distinct().toList());
        assertThrows(ResponseStatusException.class, () -> reports.run("loan-par", Map.of(), keeper));
        assertThrows(ResponseStatusException.class, () -> reports.run("members-status", Map.of(), nobody));
        assertThrows(ResponseStatusException.class, () -> reports.run("no-such-report", Map.of(), admin()));
        wellFormed(reports.run("stock-balance", Map.of(), keeper).table());
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'report.run' AND username = 'keeper'", Integer.class) >= 1, "runs are audited");
    }

    @Test
    void anAuditorReadsTheAccountingReportsAndTheJournalListingBalances() {
        Authentication auditor = new TestingAuthenticationToken("auditor", "x", "ROLE_AUDITOR");
        var ids = reports.list(auditor).stream().map((i) -> i.id()).toList();
        assertTrue(ids.containsAll(List.of("general-ledger", "journal-listing", "fixed-assets-vs-ledger")), ids.toString());
        Table t = reports.run("journal-listing", Map.of("from", "2025-03-03", "to", "2025-03-03"), auditor).table();
        wellFormed(t);
        assertTrue(t.rows().size() > 0, "coop has entries on 3 March 2025");
        if (!t.note().contains("Only the first")) {
            assertEquals(0, money(t.totals().get(5)).compareTo(money(t.totals().get(6))), "a day of whole entries balances: " + t.totals());
        }
        Table fa = reports.run("fixed-assets-vs-ledger", Map.of("asOf", "2026-11-23"), auditor).table();
        wellFormed(fa);
    }

    @Test
    void badParametersAreRefused() {
        assertThrows(ResponseStatusException.class, () -> run("members-growth", "from", "2026-02-30"));
        assertThrows(ResponseStatusException.class, () -> run("daily-sales", "from", "2026-05-01", "to", "2026-04-01"));
        assertThrows(ResponseStatusException.class, () -> run("daily-sales", "from", "2000-01-01", "to", "2026-04-01"), "too long a period");
        assertThrows(ResponseStatusException.class, () -> run("general-ledger"), "needs an account");
        assertThrows(ResponseStatusException.class, () -> run("general-ledger", "account", "0000000"), "no such account");
        assertThrows(ResponseStatusException.class, () -> run("stock-movement", "warehouseId", "abc"));
    }

    @Test
    void membersByStatusAddUpToTheMemberMaster() {
        Table t = run("members-status");
        wellFormed(t);
        long total = ((Number) t.totals().get(1)).longValue();
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM client WHERE ClientIDBrCode = 1", Long.class), total);
        assertTrue(t.rows().stream().anyMatch((r) -> r.get(0).equals("Active")), "uses the status names of the old lookup");
        Table g = run("members-growth", "from", "2025-01-01", "to", "2025-12-31");
        assertEquals(12, g.rows().size());
        long joined = ((Number) g.totals().get(1)).longValue();
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM client WHERE ClientIDBrCode = 1 AND AccountStatus IN (1, 2, 3) AND DateOpened >= '2025-01-01' AND DateOpened <= '2025-12-31'", Long.class), joined);
    }

    @Test
    void theGeneralLedgerRunsTheOpeningBalanceForwardToTheClosing() {
        Table t = run("general-ledger", "account", "10001", "from", "2026-03-01", "to", "2026-03-31");
        wellFormed(t);
        BigDecimal opening = money(t.rows().get(0).get(5));
        BigDecimal closing = money(t.totals().get(5));
        BigDecimal movement = BigDecimal.ZERO;
        for (int i = 1; i < t.rows().size(); i++) {
            Object dr = t.rows().get(i).get(3), cr = t.rows().get(i).get(4);
            if (dr != null) movement = movement.add(money(dr));
            if (cr != null) movement = movement.subtract(money(cr));
        }
        assertEquals(0, opening.add(movement).compareTo(closing), "opening plus the postings is the closing balance");
        BigDecimal fromLedger = jdbc.queryForObject("SELECT COALESCE(SUM(GLTransDR_AMT + GLTransCR_AMT), 0) FROM gltrans WHERE GLTransBR_CODE = 1 AND GLTransACCT_CODE = '10001' AND GLTransTR_DATE <= '2026-03-31'", BigDecimal.class);
        assertEquals(0, fromLedger.compareTo(closing), "and it equals the ledger balance at that date");
    }

    @Test
    void theLoanReportsAgreeWithEachOther() {
        Table portfolio = run("loan-portfolio", "asOf", OLD_DATA_END);
        wellFormed(portfolio);
        assertTrue(((Number) portfolio.totals().get(1)).longValue() > 1000, "thousands of loans have a balance");
        BigDecimal total = money(portfolio.totals().get(2));
        BigDecimal byRows = portfolio.rows().stream().map((r) -> money(r.get(2))).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, total.compareTo(byRows));
        Table status = run("loan-status", "asOf", OLD_DATA_END);
        wellFormed(status);
        assertEquals(0, total.compareTo(money(status.totals().get(2))), "status totals match the portfolio");

        Table par = run("loan-par", "asOf", OLD_DATA_END);
        wellFormed(par);
        assertEquals(7, par.rows().size());
        assertEquals(0, total.compareTo(money(par.totals().get(2))), "the buckets add up to the portfolio");
        assertEquals(((Number) portfolio.totals().get(1)).longValue(), ((Number) par.totals().get(1)).longValue());
        assertTrue(par.note().startsWith("PAR over 30 days"));

        Table late = run("loan-delinquent", "asOf", OLD_DATA_END, "minDays", "31");
        wellFormed(late);
        BigDecimal lateBalance = money(late.totals().get(4));
        BigDecimal parOver30 = BigDecimal.ZERO;
        for (int i = 2; i < par.rows().size(); i++) parOver30 = parOver30.add(money(par.rows().get(i).get(2)));
        assertEquals(0, parOver30.compareTo(lateBalance), "loans more than 30 days late are the PAR over 30 days");
        long days = -1;
        for (List<Object> r : late.rows()) {
            long d = ((Number) r.get(6)).longValue();
            assertTrue(d >= 31, "listed loans are at least 31 days late");
            assertTrue(days == -1 || d <= days, "worst first");
            days = d;
        }
        System.out.println("LOAN REPORTS at " + OLD_DATA_END + ": " + portfolio.totals().get(1) + " loans, " + total + " outstanding; " + late.totals().get(0) + "; " + par.note());
    }

    @Test
    void theLoanFlowReportsRunOverAQuarter() {
        for (String id : new String[] {"loan-releases", "loan-payments", "loan-collection"}) {
            Table t = run(id, "from", "2026-01-01", "to", "2026-03-31");
            wellFormed(t);
            assertFalse(t.rows().isEmpty(), id + " has activity in the first quarter of 2026");
        }
        Table maturing = run("loan-maturity", "from", "2026-05-01", "to", "2026-07-31");
        wellFormed(maturing);
    }

    @Test
    void inventoryAndPosReportsReadWhatWasWritten() {
        assumeTrue(db.tableExists("ecis_sale") && db.tableExists("ecis_inventory_balance"), "V031 or V033 has not been applied");
        long wh = db.insertReturningId("INSERT INTO ecis_warehouse (branch_code, code, name, active) VALUES (1, 'RPT-T', 'Report test store', 1)", "id");
        long product = db.insertReturningId("INSERT INTO ecis_product (branch_code, sku, name, unit, cost, reorder_level, track_batch, track_expiry, active, created_by, created_at) VALUES (1, 'RPT-P', 'Report test item', 'pc', 25.5, 10, 0, 0, 1, 'test', NOW())", "id");
        jdbc.update("INSERT INTO ecis_inventory_balance (product_id, warehouse_id, batch_id, quantity) VALUES (?, ?, 0, 8)", product, wh);
        Table bal = run("stock-balance", "warehouseId", String.valueOf(wh));
        wellFormed(bal);
        assertEquals(1, bal.rows().size());
        assertEquals(0, new BigDecimal("204.00").compareTo(money(bal.totals().get(8))), "8 at 25.50");
        assertTrue(run("low-stock").rows().stream().anyMatch((r) -> r.get(0).equals("RPT-P")), "8 on hand is under the reorder level of 10");
        assertTrue(run("stock-valuation").rows().stream().anyMatch((r) -> r.get(1).equals("Report test store")));

        long sale = db.insertReturningId("""
                INSERT INTO ecis_sale (branch_code, sale_no, sale_date, sale_time, warehouse_id, cashier, subtotal, discount_total, total, paid, change_given, cost_total, returned_total)
                VALUES (1, 'SL-9999-0001', CURRENT_DATE, NOW(), ?, 'tester', 200.00, 20.00, 180.00, 200.00, 20.00, 100.00, 0)
                """, "id", wh);
        jdbc.update("INSERT INTO ecis_sale_line (sale_id, line_no, product_id, batch_id, quantity, unit_price, promo_off, line_total, unit_cost) VALUES (?, 1, ?, 0, 4, 50.00, 0, 200.00, 25.00)", sale, product);
        String today = LocalDate.now().toString();
        Table daily = run("daily-sales", "from", today, "to", today);
        wellFormed(daily);
        assertEquals(1, daily.rows().size());
        assertEquals(0, new BigDecimal("180.00").compareTo(money(daily.totals().get(4))));
        assertEquals(0, new BigDecimal("80.00").compareTo(money(daily.totals().get(8))), "margin is 180 less the cost of 100");
        Table byProduct = run("sales-by-product", "from", today, "to", today);
        assertEquals(0, new BigDecimal("100.00").compareTo(money(byProduct.rows().get(0).get(5))), "4 units at a cost of 25.00");
        assertEquals(0, new BigDecimal("20.00").compareTo(money(run("pos-discounts", "from", today, "to", today).totals().get(2))));
        assertEquals(0, new BigDecimal("180.00").compareTo(money(run("sales-by-store", "from", today, "to", today).totals().get(2))));
    }
}
