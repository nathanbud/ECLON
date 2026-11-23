package ph.coop.core.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CashSetupRulesTest {

    @Test
    void anEmptyDatabaseNeedsTheCashTypesAndTheVaultAndChequeAccounts() {
        List<String> m = CashSetupRules.missing(Set.of(), Set.of());
        assertEquals(6, m.size(), m.toString());
        assertTrue(m.get(0).contains("Cash-on-Hand (11/1)"));
        assertTrue(m.contains("the chart account for cash in the vault"));
        assertTrue(m.contains("the chart account for cheques and other cash items on hand"));
    }

    @Test
    void readyWithTheTypesAndTheRequiredLinksAtmIsOptional() {
        assertEquals(List.of(), CashSetupRules.missing(Set.of(1, 2, 3, 97), Set.of("1/50", "3/51")));
    }

    @Test
    void theFirstDrawerOfAKindNeedsItsCashAndChequeAccounts() {
        assertEquals(List.of("Cash-on-Hand (11/1)", "COCI (11/3)"), CashSetupRules.drawerAccountsMissing(Set.of(1, 2, 3, 97), Set.of(), null));
        assertEquals(List.of(), CashSetupRules.drawerAccountsMissing(Set.of(1, 2, 3, 97), Set.of(), Map.of(1, "10003", 3, "10004")));
        assertEquals(List.of(), CashSetupRules.drawerAccountsMissing(Set.of(1, 3, 97), Set.of(1, 3, 97), null), "later drawers take the others' accounts");
    }
}
