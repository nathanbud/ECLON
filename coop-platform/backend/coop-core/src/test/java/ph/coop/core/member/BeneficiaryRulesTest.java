package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BeneficiaryRulesTest {

    private static final Set<Integer> RELATIONS = Set.of(35, 36, 99);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

    private static java.util.List<String> add(String name, Integer relation, String birth, String occupation, Integer type) {
        return BeneficiaryRules.problems("ADD", name, relation, RELATIONS, birth, occupation, type, null, TODAY);
    }

    @Test
    void aCompleteBeneficiaryHasNoProblems() {
        assertTrue(add("Ana Reyes", 36, "2010-05-04", "Student", 1).isEmpty());
        assertTrue(add("reyes, ana", 35, null, null, 2).isEmpty(), "birth date and occupation are optional");
        assertTrue(add("Mary-Ann O'Brien Jr.", 36, "", "", 1).isEmpty());
    }

    @Test
    void aMissingOrWrongPartIsReported() {
        assertEquals(1, add("", 36, null, null, 1).size(), "name");
        assertEquals(1, add("A", 36, null, null, 1).size(), "a one-letter name");
        assertEquals(1, add("Ana 2", 36, null, null, 1).size(), "digits in a name");
        assertEquals(1, add("x".repeat(51), 36, null, null, 1).size(), "a long name");
        assertEquals(1, add("Ana Reyes", null, null, null, 1).size(), "relationship");
        assertEquals(1, add("Ana Reyes", 7, null, null, 1).size(), "a relationship the cooperative does not use");
        assertEquals(1, add("Ana Reyes", 36, "04/24/2010", null, 1).size(), "date format");
        assertEquals(1, add("Ana Reyes", 36, "2026-11-02", null, 1).size(), "birth date in the future");
        assertEquals(1, add("Ana Reyes", 36, "1850-01-01", null, 1).size(), "an implausible birth date");
        assertEquals(1, add("Ana Reyes", 36, null, "x".repeat(36), 1).size(), "a long occupation");
        assertEquals(1, add("Ana Reyes", 36, null, null, 99).size(), "not applicable cannot be chosen");
        assertEquals(1, add("Ana Reyes", 36, null, null, null).size(), "kind");
    }

    @Test
    void removingNeedsAReasonAndNothingElse() {
        assertEquals(1, BeneficiaryRules.problems("REMOVE", null, null, RELATIONS, null, null, null, "", TODAY).size());
        assertTrue(BeneficiaryRules.problems("REMOVE", null, null, RELATIONS, null, null, null, "Died in October 2026", TODAY).isEmpty());
    }

    @Test
    void anUnknownActionIsRefused() {
        assertEquals(1, BeneficiaryRules.problems("DROP", "Ana", 36, RELATIONS, null, null, 1, null, TODAY).size());
        assertEquals(1, BeneficiaryRules.problems(null, "Ana", 36, RELATIONS, null, null, 1, null, TODAY).size());
    }

    @Test
    void namesAreKeptTidy() {
        assertEquals("Ana Maria Reyes", BeneficiaryRules.name("  Ana   Maria  Reyes "));
        assertEquals("", BeneficiaryRules.name(null));
        assertNull(BeneficiaryRules.occupation("   "));
        assertEquals(LocalDate.of(2010, 5, 4), BeneficiaryRules.birthDate("2010-05-04"));
        assertNull(BeneficiaryRules.birthDate("nonsense"));
    }
}
