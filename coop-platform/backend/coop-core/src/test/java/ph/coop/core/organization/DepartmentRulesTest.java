package ph.coop.core.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ph.coop.core.organization.DepartmentRules.Dept;
import ph.coop.core.organization.DepartmentRules.Input;

class DepartmentRulesTest {

    private static final List<Dept> DEPTS = List.of(
        new Dept(1, "CONSOLIDATED ALL", null, null, null, false),
        new Dept(2, "MAIN OFFICE", 15, "004-034-232-000", "BSU Compound", false),
        new Dept(3, "BAUKO SATELLITE OFFICE", 15, null, null, false),
        new Dept(15, "CONSOLIDATED MAIN", 1, null, null, false));
    private static final Map<String, Integer> LEN = Map.of("DeptDesc", 30, "DeptTIN", 200, "DeptAddress", 200);

    @Test
    void addingNeedsAFreeNumberAUniqueNameAnExistingParentAndAReason() {
        assertEquals(List.of(), DepartmentRules.addProblems(new Input(16, "BRENTWOOD", 15, null, null), DEPTS, LEN, "New office"));
        assertTrue(DepartmentRules.addProblems(new Input(2, "X", null, null, null), DEPTS, LEN, "New").get(0).contains("next free one is 16"));
        assertTrue(DepartmentRules.addProblems(new Input(16, "main office", null, null, null), DEPTS, LEN, "New").get(0).contains("already called MAIN OFFICE"));
        assertTrue(DepartmentRules.addProblems(new Input(16, "X".repeat(31), null, null, null), DEPTS, LEN, "New").get(0).contains("at most 30"));
        assertTrue(DepartmentRules.addProblems(new Input(16, "X", 99, null, null), DEPTS, LEN, "New").get(0).contains("parent department 99"));
        assertTrue(DepartmentRules.addProblems(new Input(16, "X", null, null, null), DEPTS, LEN, "").get(0).startsWith("Give a reason"));
        assertEquals(List.of(), DepartmentRules.addProblems(new Input(2, "MAIN OFFICE", null, null, null), List.of(), LEN, "First department"));
    }

    @Test
    void aDepartmentCannotEndUpUnderItself() {
        assertTrue(DepartmentRules.loops(15, 2, DEPTS), "15 under 2 would loop: 2 is under 15");
        assertFalse(DepartmentRules.loops(3, 1, DEPTS));
        assertTrue(DepartmentRules.changeProblems(15, new Input(15, "CONSOLIDATED MAIN", 2, null, null), DEPTS, LEN, "Move").get(0).contains("is itself under"));
        assertTrue(DepartmentRules.changeProblems(3, new Input(3, "BAUKO SATELLITE OFFICE", 3, null, null), DEPTS, LEN, "Move").get(0).contains("under itself"));
    }

    @Test
    void changingRefusesNoChangeAndAcceptsANewAddress() {
        assertTrue(DepartmentRules.changeProblems(2, new Input(2, "MAIN OFFICE", 15, "004-034-232-000", "BSU Compound"), DEPTS, LEN, "Same").get(0).startsWith("Nothing was changed"));
        assertEquals(List.of(), DepartmentRules.changeProblems(2, new Input(2, "MAIN OFFICE", 15, "004-034-232-000", "Balili, La Trinidad"), DEPTS, LEN, "Moved"));
        assertTrue(DepartmentRules.changeProblems(9, new Input(9, "X", null, null, null), DEPTS, LEN, "Gone").get(0).contains("not on this database any more"));
    }

    @Test
    void onlyAnUnusedDepartmentOtherThanTwoMayBeDeleted() {
        Dept bauko = DEPTS.get(2);
        assertEquals(List.of(), DepartmentRules.deleteProblems(bauko, 0, 0, 0, List.of(), "Added by mistake"));
        String used = DepartmentRules.deleteProblems(bauko, 3, 0, 0, List.of("ledger entries"), "Closing").get(0);
        assertTrue(used.contains("3 members, ledger entries still point to it") && used.contains("Switch it off instead"), used);
        assertTrue(DepartmentRules.deleteProblems(DEPTS.get(1), 0, 0, 0, List.of(), "Closing").get(0).contains("department 2"));
        assertTrue(DepartmentRules.deleteProblems(DEPTS.get(3), 0, 0, 2, List.of(), "Closing").get(0).contains("2 departments under it"));
    }

    @Test
    void switchingOffTwiceSaysSo() {
        assertTrue(DepartmentRules.switchProblems(new Dept(3, "BAUKO", null, null, null, true), true, "Closed").get(0).contains("already switched off"));
        assertEquals(List.of(), DepartmentRules.switchProblems(DEPTS.get(2), true, "Closed"));
    }
}
