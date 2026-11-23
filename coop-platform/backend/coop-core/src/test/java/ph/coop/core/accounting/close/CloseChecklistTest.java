package ph.coop.core.accounting.close;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ph.coop.core.accounting.close.CloseChecklist.Facts;
import ph.coop.core.accounting.close.CloseChecklist.Item;
import ph.coop.core.accounting.close.CloseChecklist.Tick;

class CloseChecklistTest {

    private static final Facts CLEAN = new Facts(true, "0.00", 0, 2, 2, 5, 5, 0, null, 12);

    private static Item item(List<Item> items, String key) {
        return items.stream().filter((i) -> i.key().equals(key)).findFirst().orElseThrow();
    }

    @Test void whatTheApplicationChecksIsOkAndWhatPeopleConfirmIsOpen() {
        List<Item> month = CloseChecklist.evaluate(CloseChecklist.MONTH, CLEAN, Map.of());
        assertEquals("OK", item(month, "BOOKS_BALANCE").status());
        assertEquals("OK", item(month, "BANKS_RECONCILED").status());
        assertEquals("OPEN", item(month, "SL_RECONCILED").status());
        assertEquals("OPEN", item(month, "MATERIAL_ADJUSTMENTS").status());
        assertFalse(CloseChecklist.complete(month));
        assertEquals(3, CloseChecklist.open(month)); // SL, cash count, material adjustments
    }

    @Test void aMonthIsCompleteOnceEachManualItemIsConfirmed() {
        Map<String, Tick> ticks = new HashMap<>();
        for (String k : List.of("SL_RECONCILED", "CASH_COUNTED", "MATERIAL_ADJUSTMENTS")) ticks.put(k, new Tick(null, "acct", "2026-04-02T10:00"));
        assertTrue(CloseChecklist.complete(CloseChecklist.evaluate(CloseChecklist.MONTH, CLEAN, ticks)));
    }

    @Test void aDifferenceTheApplicationFindsHoldsTheCloseUntilExplained() {
        Facts f = new Facts(false, "1,110,895,150.00", 2, 3, 1, 4, 0, 1, null, 12);
        List<Item> items = CloseChecklist.evaluate(CloseChecklist.MONTH, f, Map.of());
        assertEquals("ATTENTION", item(items, "BOOKS_BALANCE").status());
        assertTrue(item(items, "BOOKS_BALANCE").detail().contains("1,110,895,150.00"));
        assertEquals("2 entries are not posted yet.", item(items, "ENTRIES_POSTED").detail());
        assertEquals("2 of 3 bank accounts have no finished reconciliation.", item(items, "BANKS_RECONCILED").detail());
        assertEquals("1 business day is still open.", item(items, "BUSINESS_DAYS_ENDED").detail());
        var explained = CloseChecklist.evaluate(CloseChecklist.MONTH, f, Map.of("BOOKS_BALANCE", new Tick("Old General Journal 2336; correction is with the board", "acct", "")));
        assertEquals("EXPLAINED", item(explained, "BOOKS_BALANCE").status());
        assertNotNull(CloseChecklist.tickProblem(CloseChecklist.MONTH, "BOOKS_BALANCE", "ok", true), "an explanation needs 10 characters");
        assertNull(CloseChecklist.tickProblem(CloseChecklist.MONTH, "CASH_COUNTED", null, false), "a manual tick needs no note");
        assertNotNull(CloseChecklist.tickProblem(CloseChecklist.MONTH, "ALLOWANCE", "x", false), "the allowance is a year-end item");
    }

    @Test void theYearAsksForEveryMonthOnlyWhenMonthlyClosingIsRequired() {
        assertEquals("NOT_APPLICABLE", item(CloseChecklist.evaluate(CloseChecklist.YEAR, CLEAN, Map.of()), "MONTHS_REVIEWED").status());
        Facts required = new Facts(true, "0.00", 0, 0, 0, 0, 0, 0, 10, 12);
        var months = item(CloseChecklist.evaluate(CloseChecklist.YEAR, required, Map.of()), "MONTHS_REVIEWED");
        assertEquals("ATTENTION", months.status());
        assertEquals("2 of 12 months are not signed off.", months.detail());
        // the year-end adjustments are on the year's list
        var keys = CloseChecklist.definitions(CloseChecklist.YEAR).stream().map(CloseChecklist.Definition::key).toList();
        assertTrue(keys.containsAll(List.of("ACCRUALS", "ALLOWANCE", "PREPAYMENTS", "FIXED_ASSETS", "INVENTORY")));
        assertEquals(LocalDate.of(2026, 4, 30), CloseChecklist.dueDate(2025, 120));
    }
}
