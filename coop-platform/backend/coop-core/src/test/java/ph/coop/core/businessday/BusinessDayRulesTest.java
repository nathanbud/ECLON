package ph.coop.core.businessday;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ph.coop.core.businessday.BusinessDayRules.Check;

class BusinessDayRulesTest {

    // Monday 16 November 2026; Sunday 15 November; Monday 30 November 2026 is Bonifacio Day
    private static final LocalDate MON = LocalDate.of(2026, 11, 16);
    private static final Map<LocalDate, String> NONE = Map.of();

    @Test
    void suggestsTodayOnAWorkingDayAndTheLatestWorkingDayOtherwise() {
        assertEquals(MON, BusinessDayRules.suggested(LocalDate.of(2026, 11, 13), MON, NONE));
        // on a Sunday the latest working day after the last closed one: Saturday
        assertEquals(LocalDate.of(2026, 11, 14), BusinessDayRules.suggested(LocalDate.of(2026, 11, 13), MON.minusDays(1), NONE));
        // a holiday today and Saturday already closed: nothing working left, so today (to be confirmed)
        Map<LocalDate, String> holiday = Map.of(LocalDate.of(2026, 11, 30), "Bonifacio Day");
        assertEquals(LocalDate.of(2026, 11, 28), BusinessDayRules.suggested(LocalDate.of(2026, 11, 27), LocalDate.of(2026, 11, 30), holiday));
        assertEquals(LocalDate.of(2026, 11, 30), BusinessDayRules.suggested(LocalDate.of(2026, 11, 29), LocalDate.of(2026, 11, 30), holiday));
        // the first day ever: today
        assertEquals(MON, BusinessDayRules.suggested(null, MON, NONE));
    }

    @Test
    void opensOnlyAfterTheLastClosedDayNeverInTheFutureAndOneAtATime() {
        assertNull(BusinessDayRules.startProblem(null, LocalDate.of(2026, 11, 13), MON, MON));
        assertNull(BusinessDayRules.startProblem(null, null, MON.minusDays(3), MON), "a past date after the last closed day may be opened");
        assertTrue(BusinessDayRules.startProblem(null, null, MON.plusDays(1), MON).contains("cannot be later than today"));
        assertTrue(BusinessDayRules.startProblem(null, MON, MON, MON).contains("must come after the last closed day"));
        assertTrue(BusinessDayRules.startProblem(null, MON, MON.minusDays(1), MON).contains("must come after the last closed day"));
        assertTrue(BusinessDayRules.startProblem(MON.minusDays(1), null, MON, MON).contains("is still open"));
        assertNotNull(BusinessDayRules.startProblem(null, null, null, MON));
    }

    @Test
    void warnsAboutRestDaysPastDatesAndSkippedWorkingDays() {
        assertTrue(BusinessDayRules.startWarnings(LocalDate.of(2026, 11, 14), MON, MON, NONE).isEmpty(), "Saturday closed, Monday opened: Sunday is not a gap (the cooperative works Saturdays)");
        assertEquals(1, BusinessDayRules.startWarnings(LocalDate.of(2026, 11, 13), MON, MON, NONE).size(), "Friday closed, Monday opened: Saturday was a working day");
        List<String> sunday = BusinessDayRules.startWarnings(null, MON.minusDays(1), MON, NONE);
        assertTrue(sunday.get(0).contains("is a Sunday"), sunday.toString());
        assertTrue(sunday.get(1).contains("1 day before today"), sunday.toString());
        List<String> holiday = BusinessDayRules.startWarnings(null, LocalDate.of(2026, 11, 30), LocalDate.of(2026, 11, 30), Map.of(LocalDate.of(2026, 11, 30), "Bonifacio Day"));
        assertEquals(List.of("Monday, 30 November 2026 is a holiday (Bonifacio Day)."), holiday);
        List<String> gap = BusinessDayRules.startWarnings(LocalDate.of(2026, 11, 10), MON, MON, NONE);
        assertEquals(List.of("4 working days since the last closed day (Tuesday, 10 November 2026) will have no business day (5 days in all)."), gap);
    }

    @Test
    void endsWhenEveryCheckThatMustPassPassesOrWithAReason() {
        Check ok = new Check("drawers", "Drawers", true, "fine", true);
        Check review = new Check("drafts", "Drafts", false, "2 drafts", false);
        Check bad = new Check("handoffs", "Hand-offs", false, "1 hand-off is waiting.", true);
        assertNull(BusinessDayRules.endProblem(List.of(ok, review), null), "a review item does not stop End Day");
        String why = BusinessDayRules.endProblem(List.of(ok, bad), null);
        assertTrue(why.contains("1 hand-off is waiting.") && why.contains("give the reason"), why);
        assertNotNull(BusinessDayRules.endProblem(List.of(bad), "too short"));
        assertNull(BusinessDayRules.endProblem(List.of(bad), "Teller 3 counted on paper, system down"));
        assertEquals(List.of(bad), BusinessDayRules.failing(List.of(ok, review, bad)));
        assertEquals("Hand-offs: 1 hand-off is waiting.", BusinessDayRules.summary(List.of(bad)));
    }

    @Test
    void reopensOnlyTheLastClosedDayWhileNoneIsOpenWithAReason() {
        assertNull(BusinessDayRules.reopenProblem(null, MON, "ended before the last deposit"));
        assertTrue(BusinessDayRules.reopenProblem(MON, MON.minusDays(1), "ended before the last deposit").contains("is open"));
        assertTrue(BusinessDayRules.reopenProblem(null, null, "ended before the last deposit").contains("No business day has been closed"));
        assertTrue(BusinessDayRules.reopenProblem(null, MON, "oops").contains("reason"));
    }
}
