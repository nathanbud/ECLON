package ph.coop.core.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ph.coop.core.accounting.journal.Journal;
import ph.coop.core.accounting.journal.JournalLine;

class AssetRulesTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    void straightLineSpreadsTheDepreciableAmountOverTheLife() {
        BigDecimal dep = AssetRules.depreciable(d("120000.00"), d("12000.00"));
        assertEquals(d("108000.00"), dep);
        LocalDate inService = LocalDate.of(2026, 1, 15);
        // the month the asset goes into service is not depreciated; February is the first month
        assertEquals(0, AssetRules.monthsThrough(inService, LocalDate.of(2026, 1, 31)));
        assertEquals(1, AssetRules.monthsThrough(inService, LocalDate.of(2026, 2, 28)));
        assertEquals(12, AssetRules.monthsThrough(inService, LocalDate.of(2027, 1, 31)));
        assertEquals(d("1800.00"), AssetRules.monthAmount(dep, 60, inService, LocalDate.of(2026, 2, 28)));
        assertEquals(0, AssetRules.monthAmount(dep, 60, inService, LocalDate.of(2026, 1, 31)).signum(), "nothing in the month of service");
        assertEquals(d("108000.00"), AssetRules.cumulative(dep, 60, 60));
        assertEquals(d("108000.00"), AssetRules.cumulative(dep, 60, 200), "never more than the depreciable amount");
        assertEquals(0, AssetRules.monthAmount(dep, 60, inService, LocalDate.of(2031, 3, 31)).signum(), "month 62 is past the life");
    }

    @Test
    void roundingNeverDriftsAndTheLastMonthLandsOnTheExactTotal() {
        BigDecimal dep = d("100000.00");
        LocalDate inService = LocalDate.of(2026, 1, 1);
        BigDecimal sum = BigDecimal.ZERO;
        for (int m = 1; m <= 36; m++) sum = sum.add(AssetRules.monthAmount(dep, 36, inService, LocalDate.of(2026, 1, 1).plusMonths(m).withDayOfMonth(1).plusMonths(1).minusDays(1)));
        assertEquals(0, d("100000.00").compareTo(sum));
        assertEquals(d("2777.78"), AssetRules.monthAmount(dep, 36, inService, LocalDate.of(2026, 2, 28)));
        assertEquals(d("2777.78"), AssetRules.monthAmount(dep, 36, inService, LocalDate.of(2026, 3, 31)));
        assertEquals(d("2777.77"), AssetRules.monthAmount(dep, 36, inService, LocalDate.of(2026, 4, 30)), "the cumulative figure rounds, so a month can be a centavo less");
    }

    @Test
    void monthEndsRunFromTheMonthAfterServiceThroughThePeriod() {
        List<LocalDate> m = AssetRules.monthEnds(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 4, 30));
        assertEquals(List.of(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30)), m);
        assertTrue(AssetRules.monthEnds(LocalDate.of(2026, 4, 10), LocalDate.of(2026, 4, 30)).isEmpty());
        assertTrue(AssetRules.isMonthEnd(LocalDate.of(2028, 2, 29)));
        assertFalse(AssetRules.isMonthEnd(LocalDate.of(2026, 2, 27)));
    }

    @Test
    void anAssetNeedsSensibleFigures() {
        LocalDate today = LocalDate.of(2026, 10, 28);
        assertTrue(AssetRules.assetProblems(d("1000"), d("100"), 60, today.minusDays(5), today.minusDays(2), today).isEmpty());
        assertFalse(AssetRules.assetProblems(d("0"), d("0"), 60, today, today, today).isEmpty());
        assertFalse(AssetRules.assetProblems(d("1000"), d("1000.01"), 60, today, today, today).isEmpty(), "salvage over cost");
        assertFalse(AssetRules.assetProblems(d("1000"), d("0"), 0, today, today, today).isEmpty());
        assertFalse(AssetRules.assetProblems(d("1000"), d("0"), 601, today, today, today).isEmpty());
        assertFalse(AssetRules.assetProblems(d("1000"), d("0"), 12, today.plusDays(1), today.plusDays(1), today).isEmpty(), "future");
        assertFalse(AssetRules.assetProblems(d("1000"), d("0"), 12, today, today.minusDays(1), today).isEmpty(), "service before acquisition");
        assertFalse(AssetRules.assetProblems(d("1000.001"), d("0"), 12, today, today, today).isEmpty());
        assertTrue(AssetRules.assetProblems(d("1000"), d("1000"), 12, today, today, today).isEmpty(), "nothing to depreciate is allowed");
    }

    @Test
    void aDisposalJournalAlwaysBalancesWhateverTheOutcome() {
        BigDecimal cost = d("120000.00"), accumulated = d("45000.00");
        BigDecimal book = cost.subtract(accumulated);
        // sold at a loss, at book value, and at a gain; scrapped for nothing
        for (String proceedsText : new String[] {"50000.00", "75000.00", "90000.00", "0.00"}) {
            BigDecimal proceeds = d(proceedsText);
            BigDecimal gl = AssetRules.gainLoss(proceeds, book);
            List<JournalLine> lines = AssetRules.disposalLines("1160111", "1160112", cost, accumulated, proceeds, "10001", gl, "6004106", AssetRules.LOSS_ACCOUNT);
            Journal j = new Journal("AD-TEST", "u", lines);
            assertTrue(j.isBalanced(), "proceeds " + proceedsText + " gave " + lines);
        }
        var loss = AssetRules.disposalLines("1160111", "1160112", cost, accumulated, d("50000.00"), "10001", d("-25000.00"), null, AssetRules.LOSS_ACCOUNT);
        assertTrue(loss.stream().anyMatch((l) -> l.accountCode().equals("6004108") && l.debit().compareTo(d("25000.00")) == 0));
        var gain = AssetRules.disposalLines("1160111", "1160112", cost, accumulated, d("90000.00"), "10001", d("15000.00"), "6004106", AssetRules.LOSS_ACCOUNT);
        assertTrue(gain.stream().anyMatch((l) -> l.accountCode().equals("6004106") && l.credit().compareTo(d("15000.00")) == 0));
        // an asset that was never depreciated has no accumulated line
        assertEquals(2, AssetRules.disposalLines("1160111", "1160112", cost, BigDecimal.ZERO, BigDecimal.ZERO, null, cost.negate(), null, AssetRules.LOSS_ACCOUNT).size());
    }

    @Test
    void disposalRequestsAreChecked() {
        assertTrue(AssetRules.disposalProblems("SALE", d("50000"), "10001", null, d("-25000")).isEmpty());
        assertTrue(AssetRules.disposalProblems("SCRAP", d("0"), null, null, d("-75000")).isEmpty());
        assertFalse(AssetRules.disposalProblems("SALE", d("50000"), null, null, d("-25000")).isEmpty(), "proceeds need an account");
        assertFalse(AssetRules.disposalProblems("SALE", d("90000"), "10001", null, d("15000")).isEmpty(), "a gain needs an account");
        assertFalse(AssetRules.disposalProblems("SCRAP", d("100"), "10001", null, d("-74900")).isEmpty(), "only a sale brings proceeds");
        assertFalse(AssetRules.disposalProblems("GIFT", d("0"), null, null, d("-1")).isEmpty());
        assertFalse(AssetRules.disposalProblems("SALE", d("-1"), "10001", null, d("-1")).isEmpty());
        assertEquals("DEP-2026-10", AssetRules.depreciationReference(LocalDate.of(2026, 10, 31)));
    }
}
