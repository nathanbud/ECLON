package ph.coop.core.deposit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** When a savings account is due to be marked dormant. No database. */
class DormancyRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);
    private static final LocalDate OPENED = LocalDate.of(2015, 3, 1);

    private static LocalDate d(String s) { return LocalDate.parse(s); }

    @Test void anAccountIsDueWhenTheMemberHasNotMovedItForThePeriod() {
        assertEquals(d("2024-11-12"), DormancyRules.cutoff(TODAY, 24));
        assertTrue(DormancyRules.due(15, OPENED, d("2024-11-12"), null, TODAY, 24), "exactly the period ago");
        assertTrue(DormancyRules.due(15, OPENED, d("2020-01-05"), null, TODAY, 24));
        assertFalse(DormancyRules.due(15, OPENED, d("2024-11-13"), null, TODAY, 24), "one day short");
        assertFalse(DormancyRules.due(15, OPENED, d("2026-10-01"), null, TODAY, 24));
    }

    @Test void anAccountThatWasNeverUsedCountsFromTheDayItWasOpened() {
        assertTrue(DormancyRules.due(15, OPENED, null, null, TODAY, 24));
        assertFalse(DormancyRules.due(15, d("2025-06-01"), null, null, TODAY, 24), "opened too recently");
        assertFalse(DormancyRules.due(15, null, null, null, TODAY, 24), "nothing says how old it is");
        assertFalse(DormancyRules.due(15, d("2026-01-10"), d("2020-01-05"), null, TODAY, 24), "an opening date after the last entry wins");
    }

    @Test void aTypeWithoutAPeriodNeverGoesDormantAndOnlyActiveAccountsAreListed() {
        assertFalse(DormancyRules.due(15, OPENED, d("2010-01-05"), null, TODAY, null));
        assertFalse(DormancyRules.due(15, OPENED, d("2010-01-05"), null, TODAY, 0));
        assertFalse(DormancyRules.due(16, OPENED, d("2010-01-05"), null, TODAY, 24), "already dormant");
        assertFalse(DormancyRules.due(17, OPENED, d("2010-01-05"), null, TODAY, 24), "closed");
    }

    @Test void aReactivatedAccountStartsCountingAgain() {
        assertFalse(DormancyRules.due(15, OPENED, d("2018-01-05"), d("2026-11-01"), TODAY, 24));
        assertTrue(DormancyRules.due(15, OPENED, d("2018-01-05"), d("2023-11-01"), TODAY, 24), "reactivated, then quiet for the whole period again");
    }

    @Test void interestAndDividendsAreNotTheMembersActivity() {
        assertTrue(DormancyRules.SYSTEM_POSTINGS.contains(53));
        assertTrue(DormancyRules.SYSTEM_POSTINGS.contains(54));
        assertFalse(DormancyRules.SYSTEM_POSTINGS.contains(10), "a cash receipt is the member's");
        assertFalse(DormancyRules.SYSTEM_POSTINGS.contains(23), "a withdrawal is the member's");
    }
}
