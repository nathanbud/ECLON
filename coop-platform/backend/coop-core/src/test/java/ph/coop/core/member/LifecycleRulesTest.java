package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LifecycleRulesTest {

    /** The member types the first cooperative registers. */
    private static final Map<Integer, String> TYPES = Map.of(1, "Regular Individual", 2, "Associate");

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    @Test
    void checkDigitMatchesRealMembers() {
        assertEquals(4, LifecycleRules.checkDigit(100691));
        assertEquals(7, LifecycleRules.checkDigit(1));
    }

    @Test
    void registrationNeedsTheBasics() {
        assertEquals(4, LifecycleRules.registrationProblems(1, Map.of("LName", "Reyes"), TODAY, TYPES).size());
        assertTrue(LifecycleRules.registrationProblems(1, Map.of("LName", "R", "FName", "A", "DateOfBirth", "1990-01-01", "Gender", "2", "CivilStatus", "1"), TODAY, TYPES).isEmpty());
    }

    @Test
    void minorsAndUnknownTypesAreRefused() {
        Map<String, String> minor = Map.of("LName", "R", "FName", "A", "DateOfBirth", "2012-01-01", "Gender", "2", "CivilStatus", "1");
        assertTrue(LifecycleRules.registrationProblems(1, minor, TODAY, TYPES).get(0).contains("18"));
        Map<String, String> adult = Map.of("LName", "R", "FName", "A", "DateOfBirth", "1990-01-01", "Gender", "2", "CivilStatus", "1");
        assertTrue(LifecycleRules.registrationProblems(4, adult, TODAY, TYPES).get(0).contains("those are the member types this cooperative registers"));
    }

    @Test
    void closingNeedsReasonAndDeathDate() {
        assertTrue(LifecycleRules.closeProblems(1, 1, null, TODAY).isEmpty());
        assertEquals(1, LifecycleRules.closeProblems(1, 6, null, TODAY).size());
        assertEquals(1, LifecycleRules.closeProblems(1, 1, LocalDate.of(2026, 1, 1), TODAY).size());
        assertEquals(1, LifecycleRules.closeProblems(3, 1, null, TODAY).size());
        assertEquals(1, LifecycleRules.closeProblems(1, 9, null, TODAY).size());
    }

    @Test
    void closingNeedsZeroBalancesExceptWrittenOffLoans() {
        Map<Integer, BigDecimal> b = Map.of(31, new BigDecimal("-1000.00"), 12, new BigDecimal("500.00"), 22, BigDecimal.ZERO);
        assertEquals(2, LifecycleRules.balanceProblems(b, 1).size());
        assertEquals(1, LifecycleRules.balanceProblems(b, 3).size());
    }

    @Test
    void aMissingAccountTypeSaysWhoWhyThatNothingWasSavedAndTheTwoWaysToFinish() {
        String m = LifecycleRules.missingAccountType("Budikey, Nathaniel", "registered", "Regular Individual", 22, 1);
        assertTrue(m.startsWith("Budikey, Nathaniel cannot be registered yet."), m);
        assertTrue(m.contains("gets a savings account (type 22/1)"), m);
        assertTrue(m.contains("nothing was saved"), m);
        assertTrue(m.contains("1. set up the savings account type in Settings > Products"), m);
        assertTrue(m.contains("2. if Regular Individual members should not get one, switch it off in Settings > Products > Account opening (SL Controls)"), m);
        assertTrue(LifecycleRules.missingAccountType("Cruz, Ana", "reactivated", "Associate", 31, 3).contains("cannot be reactivated yet. Every Associate member gets a share capital account"));
    }

    @Test
    void aCooperativeWithNoMemberTypesChosenSaysWhereToChooseThem() {
        assertTrue(LifecycleRules.registrationProblems(1, Map.of(), TODAY, Map.of()).get(0).contains("Settings > Membership > Member defaults"));
        assertTrue(LifecycleRules.noAccountsChosen("Cruz, Ana", "registered", "Regular Individual").contains("Account opening (SL Controls)"));
        assertTrue(LifecycleRules.noDefault("Cruz, Ana", "department").contains("has not chosen the department a new member is given"));
    }

    @Test
    void aMissingListEntryIsNamedWithWhereToAddIt() {
        String m = LifecycleRules.missingLinks("Budikey, Nathaniel", java.util.List.of(
            new LifecycleRules.Missing("department", "2", true, LifecycleRules.whereToAdd("ClientDEPT")),
            new LifecycleRules.Missing("gender", "1", false, LifecycleRules.whereToAdd("Gender"))));
        assertTrue(m.startsWith("Budikey, Nathaniel cannot be registered yet. The member's record points to 2 things this database does not have, so nothing was saved."), m);
        assertTrue(m.contains("1. a new member is given department 2 when none is chosen, but this database has no department with that number: add it in Settings > Organization > Departments"), m);
        assertTrue(m.contains("2. the registration names gender 1, but this database has no gender with that number: add it in Settings > System > Starter setup"), m);
        assertEquals("Settings > Organization > Offices", LifecycleRules.whereToAdd("RestrikID"));
    }
}
