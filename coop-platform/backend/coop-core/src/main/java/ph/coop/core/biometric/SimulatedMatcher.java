package ph.coop.core.biometric;

import org.springframework.stereotype.Component;

/**
 * Stand-in matcher for development and demonstration until a real scanner and matcher are installed. Its "SIM1" template is a 128-byte feature vector; the score is the share of
 * bits two vectors agree on, so a re-scan of the same simulated finger (a few flipped bits) matches and two different fingers (about half the bits differ) do not. It proves the
 * flow end to end but is not biometrics: it must never be trusted to identify a real person.
 */
@Component
public class SimulatedMatcher implements FingerprintMatcher {

    public static final String FORMAT = "SIM1";
    public static final int LENGTH = 128;

    @Override
    public boolean supports(String format) {
        return FORMAT.equals(format);
    }

    @Override
    public double score(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != LENGTH || b.length != LENGTH) return 0;
        int differing = 0;
        for (int i = 0; i < LENGTH; i++) differing += Integer.bitCount((a[i] ^ b[i]) & 0xFF);
        return 1.0 - differing / (double) (LENGTH * 8);
    }

    @Override
    public boolean simulated() {
        return true;
    }
}
