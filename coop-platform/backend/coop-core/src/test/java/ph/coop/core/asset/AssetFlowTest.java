package ph.coop.core.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.asset.AssetCategoryController.CategoryIn;
import ph.coop.core.asset.AssetController.MaintIn;
import ph.coop.core.asset.AssetController.NewAsset;
import ph.coop.core.asset.AssetController.TransferIn;
import ph.coop.core.asset.AssetDisposalController.Decision;
import ph.coop.core.asset.AssetDisposalController.DisposalIn;
import ph.coop.core.asset.DepreciationController.Request;

/**
 * Fixed assets end to end against the database, rolled back: a category with its ledger accounts, an asset, the monthly depreciation run that leaves draft journals,
 * a transfer a different accountant approves, maintenance that falls due, and a disposal whose journal balances. Uses the old chart's own accounts.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class AssetFlowTest {

    @Autowired AssetCategoryController categories;
    @Autowired AssetController assets;
    @Autowired DepreciationController depreciation;
    @Autowired AssetDisposalController disposals;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private long category;

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private Authentication as(String user, String... roles) {
        Authentication a = new TestingAuthenticationToken(user, "x", java.util.Arrays.stream(roles).map((r) -> "ROLE_" + r).toArray(String[]::new));
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    @BeforeEach
    void ready() {
        assumeTrue(db.tableExists("ecis_asset_disposal") && db.tableExists("ecis_journal"), "V034 has not been applied");
        try {
            jdbc.update("UPDATE ecis_asset SET status = status WHERE id = -1");
        } catch (RuntimeException e) {
            assumeTrue(false, "the grants of V034 are missing");
        }
        for (String a : new String[] {"1160111", "1160112", "50328", "10001", "6004108"})
            assumeTrue(!jdbc.queryForList("SELECT 1 FROM coa WHERE COABR_CODE = 1 AND COAID = ? AND COAacct_tag = 'D' AND COALESCE(COAisdisabled, 0) = 0", a).isEmpty(), "account " + a + " is not usable here");
        category = categories.create(new CategoryIn("TST Office equipment", "1160111", "1160112", "50328", 60, d("10"), null), as("acct", "ACCOUNTANT")).id();
    }

    private LocalDate lastMonthEnd() {
        return LocalDate.now().withDayOfMonth(1).minusDays(1);
    }

    private long asset(String tag, int monthsAgo) {
        LocalDate day = LocalDate.now().minusMonths(monthsAgo);
        return assets.register(new NewAsset(tag, "Test cash counting machine " + tag, category, "SN-" + tag, "Head office", day, day, d("120000.00"), null, null, null, "PO-TEST"), as("keeper", "ASSET_CUSTODIAN")).id();
    }

    @Test
    void anAssetTakesTheCategoryDefaultsAndIsDepreciatedMonthByMonthIntoDraftJournals() {
        long id = asset("TST-1", 4);
        var detail = assets.get(id);
        assertEquals(0, d("12000.00").compareTo(detail.summary().salvageValue()), "10% of the cost");
        assertEquals(60, detail.summary().lifeMonths());
        assertTrue(detail.summary().assetNo().matches("FA-\\d{4}-\\d{4}"));
        assertThrows(ResponseStatusException.class, () -> assets.register(new NewAsset("tst-1", "Duplicate tag", category, null, "Head office", LocalDate.now(), null, d("100"), null, null, null, null), as("keeper", "ASSET_CUSTODIAN")));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> assets.register(new NewAsset("TST-X", "No rights", category, null, "Head office", LocalDate.now(), null, d("100"), null, null, null, null), as("clerk", "PURCHASER")));

        as("acct", "ACCOUNTANT");
        var preview = depreciation.preview(new Request(lastMonthEnd()), as("acct", "ACCOUNTANT"));
        assertEquals(3, preview.periods().size(), "four months ago went into service, so three month ends are due");
        assertEquals(0, d("5400.00").compareTo(preview.total()));
        assertThrows(ResponseStatusException.class, () -> depreciation.preview(new Request(LocalDate.now().withDayOfMonth(1)), as("acct", "ACCOUNTANT")), "not a month end");
        assertThrows(ResponseStatusException.class, () -> depreciation.preview(new Request(LocalDate.now().plusMonths(1).withDayOfMonth(1).minusDays(1)), as("acct", "ACCOUNTANT")), "month not over");
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> depreciation.run(new Request(lastMonthEnd()), as("keeper", "ASSET_CUSTODIAN")), "custodians do not run it");

        var done = depreciation.run(new Request(lastMonthEnd()), as("acct", "ACCOUNTANT"));
        assertEquals(3, done.size());
        for (var r : done) {
            assertEquals(0, d("1800.00").compareTo(r.total()));
            List<Map<String, Object>> lines = jdbc.queryForList("SELECT account_code, debit, credit FROM ecis_journal_line WHERE journal_id = ? ORDER BY line_no", r.journalId());
            assertEquals(2, lines.size());
            assertEquals("50328", lines.get(0).get("account_code"));
            assertEquals("1160112", lines.get(1).get("account_code"));
            assertEquals("DRAFT", jdbc.queryForObject("SELECT status FROM ecis_journal WHERE id = ?", String.class, r.journalId()));
        }
        var after = assets.get(id);
        assertEquals(3, after.depreciation().size());
        assertEquals(0, d("5400.00").compareTo(after.summary().booked()));
        assertEquals(0, BigDecimal.ZERO.compareTo(after.summary().posted()), "nothing is posted until the journals are");
        assertEquals(0, d("114600.00").compareTo(after.summary().netBookValue()));
        assertThrows(ResponseStatusException.class, () -> depreciation.run(new Request(lastMonthEnd()), as("acct", "ACCOUNTANT")), "never twice for the same month");
        assertEquals(3, depreciation.runs().size());
    }

    @Test
    void aTransferMovesTheAssetOnlyWhenADifferentAccountantApprovesIt() {
        long id = asset("TST-2", 1);
        Authentication keeper = as("keeper", "ASSET_CUSTODIAN");
        assertThrows(ResponseStatusException.class, () -> assets.askTransfer(id, new TransferIn("head office", "same place"), keeper));
        var t = assets.askTransfer(id, new TransferIn("Baguio branch", "needed at the branch"), keeper);
        assertEquals("PENDING", t.status());
        assertEquals("Head office", assets.get(id).summary().location());
        assertThrows(ResponseStatusException.class, () -> assets.askTransfer(id, new TransferIn("Buguias", "again"), as("keeper", "ASSET_CUSTODIAN")), "one waiting at a time");
        assertThrows(ResponseStatusException.class, () -> assets.approveTransfer(t.id(), null, as("keeper", "ASSET_CUSTODIAN", "ACCOUNTANT")), "not the one who asked");
        assertThrows(ResponseStatusException.class, () -> assets.approveTransfer(t.id(), null, as("keeper2", "ASSET_CUSTODIAN")), "a custodian cannot approve");
        assertEquals("APPROVED", assets.approveTransfer(t.id(), new AssetController.Decision("ok"), as("acct", "ACCOUNTANT")).status());
        assertEquals("Baguio branch", assets.get(id).summary().location());
    }

    @Test
    void maintenanceIsRecordedAndFallsDue() {
        long id = asset("TST-3", 2);
        Authentication keeper = as("keeper", "ASSET_CUSTODIAN");
        assertThrows(ResponseStatusException.class, () -> assets.maintain(id, new MaintIn(LocalDate.now().plusDays(1), "future", d("0"), null, null), keeper));
        assertThrows(ResponseStatusException.class, () -> assets.maintain(id, new MaintIn(LocalDate.now(), "next due before the work", d("0"), null, LocalDate.now().minusDays(1)), keeper));
        assets.maintain(id, new MaintIn(LocalDate.now().minusDays(3), "Replaced the belt", d("1500.00"), "Fix-It Co.", LocalDate.now().plusDays(10)), keeper);
        assertEquals(1, assets.get(id).maintenance().size());
        assertTrue(assets.due(30).stream().anyMatch((x) -> x.assetId() == id));
        assertTrue(assets.due(5).stream().noneMatch((x) -> x.assetId() == id), "not within 5 days");
        // a later job that says nothing more is due clears it
        assets.maintain(id, new MaintIn(LocalDate.now(), "Serviced", d("0"), null, null), as("keeper", "ASSET_CUSTODIAN"));
        assertTrue(assets.due(30).stream().noneMatch((x) -> x.assetId() == id));
    }

    @Test
    void aDisposalNeedsDepreciationUpToDateAndLeavesABalancedDraftJournalOnceApproved() {
        long id = asset("TST-4", 5);
        Authentication keeper = as("keeper", "ASSET_CUSTODIAN");
        DisposalIn sale = new DisposalIn(LocalDate.now(), "SALE", d("50000.00"), "10001", null, "sold to a member");
        assertThrows(ResponseStatusException.class, () -> disposals.ask(id, sale, keeper), "depreciation is behind");
        depreciation.run(new Request(lastMonthEnd()), as("acct", "ACCOUNTANT"));
        BigDecimal book = assets.get(id).summary().netBookValue();
        assertTrue(book.compareTo(d("120000.00")) < 0);

        assertThrows(ResponseStatusException.class, () -> disposals.ask(id, new DisposalIn(LocalDate.now(), "SALE", d("50000.00"), null, null, "no account"), as("keeper", "ASSET_CUSTODIAN")));
        var ask = disposals.ask(id, sale, as("keeper", "ASSET_CUSTODIAN"));
        assertEquals("PENDING", ask.status());
        assertEquals(0, book.compareTo(ask.bookValue()));
        assertEquals(0, d("50000.00").subtract(book).compareTo(ask.gainLoss()));
        assertThrows(ResponseStatusException.class, () -> disposals.ask(id, sale, as("keeper", "ASSET_CUSTODIAN")), "one waiting at a time");
        assertThrows(ResponseStatusException.class, () -> disposals.approve(ask.id(), null, as("keeper", "ASSET_CUSTODIAN", "ACCOUNTANT")), "not the one who asked");
        assertThrows(ResponseStatusException.class, () -> disposals.reject(ask.id(), new Decision(" "), as("acct", "ACCOUNTANT")));

        var ok = disposals.approve(ask.id(), new Decision("approved"), as("acct", "ACCOUNTANT"));
        assertEquals("APPROVED", ok.status());
        assertEquals(ask.disposalNo(), ok.journal());
        var a = assets.get(id).summary();
        assertEquals("DISPOSED", a.status());
        BigDecimal debit = jdbc.queryForObject("SELECT SUM(l.debit) FROM ecis_journal_line l JOIN ecis_journal j ON j.id = l.journal_id WHERE j.reference = ?", BigDecimal.class, ask.disposalNo());
        BigDecimal credit = jdbc.queryForObject("SELECT SUM(l.credit) FROM ecis_journal_line l JOIN ecis_journal j ON j.id = l.journal_id WHERE j.reference = ?", BigDecimal.class, ask.disposalNo());
        assertEquals(0, debit.compareTo(credit), "the disposal journal balances");
        assertEquals("DRAFT", jdbc.queryForObject("SELECT status FROM ecis_journal WHERE reference = ?", String.class, ask.disposalNo()));
        // a disposed asset takes no more depreciation, no transfer and no second disposal
        assertTrue(depreciation.preview(new Request(lastMonthEnd()), as("acct", "ACCOUNTANT")).periods().isEmpty());
        assertThrows(ResponseStatusException.class, () -> assets.askTransfer(id, new TransferIn("Elsewhere", "gone"), as("keeper", "ASSET_CUSTODIAN")));
        assertThrows(ResponseStatusException.class, () -> disposals.ask(id, sale, as("keeper", "ASSET_CUSTODIAN")));
    }

    @Test
    void categoriesNeedRealDistinctAccounts() {
        Authentication acct = as("acct", "ACCOUNTANT");
        assertThrows(ResponseStatusException.class, () -> categories.create(new CategoryIn("TST bad", "9999999", "1160112", "50328", 12, d("0"), null), acct), "unknown account");
        assertThrows(ResponseStatusException.class, () -> categories.create(new CategoryIn("TST same", "1160111", "1160111", "50328", 12, d("0"), null), acct), "same account twice");
        assertThrows(ResponseStatusException.class, () -> categories.create(new CategoryIn("TST life", "1160111", "1160112", "50328", 0, d("0"), null), acct));
        assertThrows(ResponseStatusException.class, () -> categories.create(new CategoryIn("TST Office equipment", "1160111", "1160112", "50328", 12, d("0"), null), acct), "name taken");
        assertThrows(ResponseStatusException.class, () -> categories.create(new CategoryIn("TST nobody", "1160111", "1160112", "50328", 12, d("0"), null), as("keeper", "ASSET_CUSTODIAN")));
    }
}
