package ph.coop.core.biometric;

/**
 * Compares two fingerprint templates. Real matching belongs to the scanner maker's SDK or a certified ISO/IEC 19794-2 matcher; the application only depends on this interface, so
 * installing one is a new implementation and nothing else changes. {@link SimulatedMatcher} is the stand-in until then.
 */
public interface FingerprintMatcher {

    /** Whether this matcher understands templates in the given format. */
    boolean supports(String format);

    /** The similarity of two templates of the same format, from 0 (different) to 1 (the same finger). */
    double score(byte[] a, byte[] b);

    /** True for the stand-in, so the screens can say that no real scanner is connected. */
    default boolean simulated() {
        return false;
    }
}
