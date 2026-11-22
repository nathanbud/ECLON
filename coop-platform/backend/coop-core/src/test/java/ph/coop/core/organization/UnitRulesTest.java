package ph.coop.core.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ph.coop.core.organization.UnitRules.Dept;

class UnitRulesTest {

    /** The shape of coop's own department tree. */
    private static final List<Dept> TREE = List.of(
        new Dept(1, "CONSOLIDATED ALL", null),
        new Dept(15, "CONSOLIDATED MAIN", 1),
        new Dept(2, "MAIN OFFICE", 15),
        new Dept(17, "BUILDING - MAIN", 2),
        new Dept(3, "BAUKO SATELLITE OFFICE", 15),
        new Dept(4, "BAGUIO SATELLITE OFFICE", 15),
        new Dept(22, "BUILDING - BONIFACIO", 4),
        new Dept(10, "CONSO LABCOOP", 1),
        new Dept(11, "LABCOOP BUGUIAS", 10));

    @Test void aKindIsSuggestedFromTheNameAndThePlaceInTheTree() {
        assertEquals(UnitRules.CONSOLIDATION, UnitRules.suggest("CONSOLIDATED MAIN", true));
        assertEquals(UnitRules.CONSOLIDATION, UnitRules.suggest("CONSO LABCOOP", true));
        assertEquals(UnitRules.SATELLITE_OFFICE, UnitRules.suggest("BAUKO SATELLITE OFFICE", false));
        assertEquals(UnitRules.BRANCH, UnitRules.suggest("La Trinidad Branch", false));
        assertEquals(UnitRules.MAIN_OFFICE, UnitRules.suggest("MAIN OFFICE", true));
        // a building of the main office is not the main office
        assertEquals(UnitRules.OTHER, UnitRules.suggest("BUILDING - MAIN", false));
        assertEquals(UnitRules.OTHER, UnitRules.suggest("BRENTWOOD", false));
        assertEquals(UnitRules.CONSOLIDATION, UnitRules.suggest("NORTH", true));
    }

    @Test void aUnitCoversEverythingUnderItAtAnyDepth() {
        assertEquals(Set.of(15, 2, 17, 3, 4, 22), UnitRules.subtree(15, TREE));
        assertEquals(Set.of(3), UnitRules.subtree(3, TREE));
        assertEquals(9, UnitRules.subtree(1, TREE).size());
    }

    @Test void aLoopInTheOldDataDoesNotRunForever() {
        List<Dept> loop = List.of(new Dept(1, "A", 2), new Dept(2, "B", 1));
        assertEquals(Set.of(1, 2), UnitRules.subtree(1, loop));
    }

    @Test void theUnitsDirectlyUnderOneAndTheTopOnes() {
        assertEquals(List.of(2, 3, 4), UnitRules.children(15, TREE).stream().map(Dept::id).toList());
        assertEquals(List.of(1), UnitRules.roots(TREE).stream().map(Dept::id).toList());
    }

    @Test void aKindNeedsAKnownKindAndAReason() {
        assertTrue(UnitRules.kindProblems(UnitRules.BRANCH, "Registered with the CDA as a branch").isEmpty());
        assertEquals(2, UnitRules.kindProblems("SATELLITE_BRANCH", "").size());
        assertEquals("Satellite office", UnitRules.kindName(UnitRules.SATELLITE_OFFICE));
    }
}
