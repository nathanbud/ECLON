package ph.coop.core.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ChartRulesTest {

    @Test void theNormalBalanceFollowsTheType() {
        assertEquals("D", ChartRules.normalBalance(1));
        assertEquals("C", ChartRules.normalBalance(2));
        assertEquals("C", ChartRules.normalBalance(3));
        assertEquals("C", ChartRules.normalBalance(4));
        assertEquals("D", ChartRules.normalBalance(5));
        assertEquals("C", ChartRules.normalBalance(6));
    }

    @Test void onlyAContraAccountsBalanceIsStored() {
        assertNull(ChartRules.stored(1, "D"));
        assertEquals("C", ChartRules.stored(1, "c"));       // an allowance for losses under assets
        assertNull(ChartRules.stored(2, "C"));
        assertNull(ChartRules.stored(5, null));
        assertThrows(IllegalArgumentException.class, () -> ChartRules.stored(1, "X"));
    }

    @Test void aDescriptionIsTrimmedEmptyClearsItAndItHasALimit() {
        assertEquals("For petty expenses", ChartRules.description("  For petty expenses "));
        assertNull(ChartRules.description("   "));
        assertThrows(IllegalArgumentException.class, () -> ChartRules.description("x".repeat(251)));
    }
}
