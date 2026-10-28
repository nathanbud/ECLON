package ph.coop.core.biometric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class BiometricRulesTest {

    static byte[] random(long seed) {
        byte[] b = new byte[SimulatedMatcher.LENGTH];
        new Random(seed).nextBytes(b);
        return b;
    }

    static byte[] noisy(byte[] t, int bits, long seed) {
        byte[] b = t.clone();
        Random r = new Random(seed);
        for (int i = 0; i < bits; i++) b[r.nextInt(b.length)] ^= (byte) (1 << r.nextInt(8));
        return b;
    }

    @Test
    void goodCapturesPassAndBadOnesSayWhy() {
        assertTrue(BiometricRules.captureProblems(1, 80, "SIM1", random(1)).isEmpty());
        assertEquals(1, BiometricRules.captureProblems(11, 80, "SIM1", random(1)).size());
        assertTrue(BiometricRules.captureProblems(1, 30, "SIM1", random(1)).get(0).contains("quality"));
        assertEquals(1, BiometricRules.captureProblems(1, 80, "SIM1", new byte[0]).size());
        assertEquals(1, BiometricRules.captureProblems(1, 80, "", random(1)).size());
    }

    @Test
    void theSimulatedMatcherSeparatesRescansFromOtherFingers() {
        SimulatedMatcher m = new SimulatedMatcher();
        byte[] a = random(7);
        assertEquals(1.0, m.score(a, a), 1e-9);
        assertTrue(m.score(a, noisy(a, 40, 3)) >= BiometricRules.MATCH_THRESHOLD);
        assertTrue(m.score(a, random(8)) < BiometricRules.MATCH_THRESHOLD);
        assertEquals(0, m.score(a, new byte[5]), 0);
    }

    @Test
    void theVaultRoundTripsAndBindsTheTemplateToItsFinger() {
        BiometricVault v = new BiometricVault("", "target/test-biometric.key");
        byte[] t = random(5);
        byte[] sealed = v.seal(t, 1, 100, 2);
        assertTrue(sealed.length > t.length);
        assertEquals(java.util.Arrays.toString(t), java.util.Arrays.toString(v.open(sealed, 1, 100, 2)));
        assertThrows(IllegalStateException.class, () -> v.open(sealed, 1, 100, 3));
        assertThrows(IllegalStateException.class, () -> v.open(sealed, 1, 101, 2));
    }
}
