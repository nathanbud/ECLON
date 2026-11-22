package ph.coop.core.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FeatureRulesTest {

    private static String keyOf(String path) {
        FeatureRules.Feature f = FeatureRules.of(path);
        return f == null ? null : f.key();
    }

    @Test
    void eachAddressBelongsToItsArea() {
        assertEquals("savings", keyOf("/api/front-office/savings/deposits"));
        assertEquals("savings", keyOf("/api/members/1/33189/savings"));
        assertEquals("savings", keyOf("/api/members/1/33189/savings/22/45805"));
        assertEquals("savings", keyOf("/api/savings-dormancy"));
        assertEquals("timedeposits", keyOf("/api/members/1/5/time-deposits"));
        assertEquals("loans", keyOf("/api/members/1/5/loans/1/77/worksheet/pdf"));
        assertEquals("loans", keyOf("/api/loan-applications/pending"));
        assertEquals("loans", keyOf("/api/admin/loan-setup/types/3/deductions"));
        assertEquals("passbook", keyOf("/api/front-office/passbooks/1"));
        assertEquals("pos", keyOf("/api/pos/sales"));
        assertEquals("procurement", keyOf("/api/procurement/receipts"));
    }

    @Test
    void membershipShareCapitalTheBooksAndCashAreAlwaysOn() {
        assertNull(keyOf("/api/members/1/5/shares"));
        assertNull(keyOf("/api/front-office/share-payments"));
        assertNull(keyOf("/api/journals"));
        assertNull(keyOf("/api/front-office/handover"));
        assertNull(keyOf("/api/admin/features"));
        assertNull(keyOf("/api/members/1/5"));
        assertNull(keyOf("/api/front-office/savingsx"), "a longer word is not the savings area");
    }

    @Test
    void accountsOfASwitchedOffAreaDoNotOpen() {
        assertFalse(FeatureRules.classOn(22, Map.of("savings", false)));
        assertTrue(FeatureRules.classOn(31, Map.of("savings", false)), "share capital always opens");
        assertTrue(FeatureRules.classOn(22, Map.of()), "an area not switched off is on");
        assertFalse(FeatureRules.classOn(12, Map.of("loans", false)));
    }

    @Test
    void theRefusalSaysWhoCanSwitchItOn() {
        String r = FeatureRules.refusal(FeatureRules.find("savings"));
        assertTrue(r.startsWith("Savings is switched off for this cooperative") && r.contains("Settings > System > Features"), r);
        assertTrue(FeatureRules.problems(Map.of("nonsense", true), "Because").get(0).contains("nonsense is not an area"));
        assertTrue(FeatureRules.problems(Map.of("savings", false), "").get(0).startsWith("Give a reason"));
    }
}
