package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DrawerRulesTest {

    @Test
    void ownLimitBeatsTheDefault() {
        Map<Integer, BigDecimal> m = Map.of(0, new BigDecimal("500000"), 91, new BigDecimal("200000"));
        assertEquals(0, new BigDecimal("200000").compareTo(DrawerRules.limitFor(m, 91)));
        assertEquals(0, new BigDecimal("500000").compareTo(DrawerRules.limitFor(m, 92)));
        assertNull(DrawerRules.limitFor(Map.of(), 92));
    }

    @Test
    void excessOnlyWhenOverTheLimit() {
        assertEquals(0, new BigDecimal("50.00").compareTo(DrawerRules.excess(new BigDecimal("250.00"), new BigDecimal("200"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(DrawerRules.excess(new BigDecimal("200"), new BigDecimal("200"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(DrawerRules.excess(new BigDecimal("999999"), null)));
    }

    @Test
    void varianceIsCountedMinusExpected() {
        assertEquals(0, new BigDecimal("-50.00").compareTo(DrawerRules.variance(new BigDecimal("1000"), new BigDecimal("950"))));
        assertEquals(0, new BigDecimal("0.25").compareTo(DrawerRules.variance(new BigDecimal("1000"), new BigDecimal("1000.25"))));
    }

    @Test
    void closingIsBlockedUntilEverythingIsSettledAndCounted() {
        assertNull(DrawerRules.notReadyToClose(0, List.of()));
        String m = DrawerRules.notReadyToClose(2, List.of("Teller 91", "Teller 92"));
        assertTrue(m.startsWith(DrawerRules.NOT_READY_PREFIX));
        assertTrue(m.contains("2 hand-offs"));
        assertTrue(m.contains("Teller 91, Teller 92 have not counted"));
        assertTrue(DrawerRules.notReadyToClose(0, List.of("Teller 91")).contains("Teller 91 has not counted"));
    }
}
