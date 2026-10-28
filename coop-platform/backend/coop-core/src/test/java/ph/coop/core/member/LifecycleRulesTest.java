package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LifecycleRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    @Test
    void checkDigitMatchesRealMembers() {
        assertEquals(4, LifecycleRules.checkDigit(100691));
        assertEquals(7, LifecycleRules.checkDigit(1));
    }

    @Test
    void registrationNeedsTheBasics() {
        assertEquals(4, LifecycleRules.registrationProblems(1, Map.of("LName", "Reyes"), TODAY).size());
        assertTrue(LifecycleRules.registrationProblems(1, Map.of("LName", "R", "FName", "A", "DateOfBirth", "1990-01-01", "Gender", "2", "CivilStatus", "1"), TODAY).isEmpty());
    }

    @Test
    void minorsAndUnknownTypesAreRefused() {
        Map<String, String> minor = Map.of("LName", "R", "FName", "A", "DateOfBirth", "2012-01-01", "Gender", "2", "CivilStatus", "1");
        assertTrue(LifecycleRules.registrationProblems(1, minor, TODAY).get(0).contains("18"));
        Map<String, String> adult = Map.of("LName", "R", "FName", "A", "DateOfBirth", "1990-01-01", "Gender", "2", "CivilStatus", "1");
        assertTrue(LifecycleRules.registrationProblems(4, adult, TODAY).get(0).contains("Only"));
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
}
