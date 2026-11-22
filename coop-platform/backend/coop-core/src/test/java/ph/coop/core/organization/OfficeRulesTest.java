package ph.coop.core.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ph.coop.core.organization.OfficeRules.Office;

class OfficeRulesTest {

    private static final List<Office> OFFICES = List.of(new Office(1, "MAIN OFFICE"), new Office(2, "BAUKO SATELLITE"), new Office(5, "BRENTWOOD"));

    @Test
    void aNewOfficeNeedsAFreeNumberAUniqueNameAndAReason() {
        assertEquals(List.of(), OfficeRules.addProblems(6, "Buguias Satellite", OFFICES, 200, "New branch opened"));
        assertTrue(OfficeRules.addProblems(2, "Other", OFFICES, 200, "New branch").get(0).contains("already used by BAUKO SATELLITE"));
        assertTrue(OfficeRules.addProblems(2, "Other", OFFICES, 200, "New branch").get(0).contains("next free one is 6"));
        assertTrue(OfficeRules.addProblems(0, "Other", OFFICES, 200, "New branch").get(0).contains("from 1 to 32767"));
        assertTrue(OfficeRules.addProblems(null, "Other", OFFICES, 200, "New branch").get(0).contains("from 1 to 32767"));
        assertTrue(OfficeRules.addProblems(6, "  main office ", OFFICES, 200, "New branch").get(0).contains("already called MAIN OFFICE"));
        assertTrue(OfficeRules.addProblems(6, " ", OFFICES, 200, "New branch").get(0).startsWith("Give the office a name"));
        assertTrue(OfficeRules.addProblems(6, "X".repeat(201), OFFICES, 200, "New branch").get(0).contains("at most 200"));
        assertTrue(OfficeRules.addProblems(6, "Other", OFFICES, 200, "x").get(0).startsWith("Give a reason"));
    }

    @Test
    void theFirstOfficeOfAnEmptyDatabaseIsNumberOne() {
        assertEquals(1, OfficeRules.nextNumber(List.of()));
        assertEquals(6, OfficeRules.nextNumber(OFFICES));
        assertEquals(List.of(), OfficeRules.addProblems(1, "MAIN OFFICE", List.of(), 200, "First office of this cooperative"));
    }

    @Test
    void renamingKeepsTheNumberAndRefusesTheSameOrATakenName() {
        assertEquals(List.of(), OfficeRules.renameProblems(5, "Brentwood Satellite", OFFICES, 200, "Renamed by the board"));
        assertEquals(List.of(), OfficeRules.renameProblems(5, "brentwood", OFFICES, 200, "Case only"), "a change of case is a change");
        assertTrue(OfficeRules.renameProblems(5, "BRENTWOOD", OFFICES, 200, "Same").get(0).contains("same as before"));
        assertTrue(OfficeRules.renameProblems(5, "Main Office", OFFICES, 200, "Clash").get(0).contains("already called MAIN OFFICE"));
        assertTrue(OfficeRules.renameProblems(9, "Nine", OFFICES, 200, "Gone").get(0).contains("not on this database any more"));
    }

    @Test
    void onlyAnUnusedOfficeOtherThanOneMayBeDeleted() {
        Office brentwood = new Office(5, "BRENTWOOD");
        assertEquals(List.of(), OfficeRules.deleteProblems(brentwood, 0, 0, "Added by mistake"));
        String used = OfficeRules.deleteProblems(brentwood, 3, 1, "Closing").get(0);
        assertTrue(used.contains("3 members and 1 user still belong to it"), used);
        assertTrue(OfficeRules.deleteProblems(new Office(1, "MAIN OFFICE"), 0, 0, "Closing").get(0).contains("office 1"));
        assertTrue(OfficeRules.deleteProblems(brentwood, 0, 0, "").get(0).startsWith("Give a reason"));
    }

    @Test
    void readsAUsersOfficeList() {
        assertEquals(Set.of(1, 2, 3, 4), OfficeRules.officesOf("003,002,004,001"));
        assertEquals(Set.of(5, 1), OfficeRules.officesOf("005, 001"));
        assertEquals(Set.of(), OfficeRules.officesOf(null));
        assertEquals(Set.of(1), OfficeRules.officesOf("x,001,"));
    }
}
