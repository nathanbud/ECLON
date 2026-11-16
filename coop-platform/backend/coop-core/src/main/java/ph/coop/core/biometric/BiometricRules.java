package ph.coop.core.biometric;

import java.util.List;
import java.util.Map;

/** Rules for fingerprint enrolment that need no database or scanner: finger numbering, the quality floor and the match threshold. */
public final class BiometricRules {

    private BiometricRules() {}

    /** Finger positions as in ISO/IEC 19794: 1 right thumb to 5 right little, 6 left thumb to 10 left little. */
    public static final Map<Integer, String> FINGERS = Map.ofEntries(
        Map.entry(1, "Right thumb"), Map.entry(2, "Right index"), Map.entry(3, "Right middle"), Map.entry(4, "Right ring"), Map.entry(5, "Right little"),
        Map.entry(6, "Left thumb"), Map.entry(7, "Left index"), Map.entry(8, "Left middle"), Map.entry(9, "Left ring"), Map.entry(10, "Left little"));

    /** A capture below this quality (0 to 100, as an NFIQ2-style score) is refused and the finger is scanned again. */
    public static final int MIN_QUALITY = 50;

    /** Two templates scoring at least this (0 to 1) are the same finger. */
    public static final double MATCH_THRESHOLD = 0.80;

    /** The consent text version a member agrees to; a new wording gets a new version. */
    public static final String CONSENT_VERSION = "2026-1";

    /** Enough fingers that one injured finger does not lock a member out: the app asks for at least two. */
    public static final int RECOMMENDED_FINGERS = 2;

    public static List<String> captureProblems(int finger, int quality, String format, byte[] template) {
        List<String> out = new java.util.ArrayList<>();
        if (!FINGERS.containsKey(finger)) out.add("Choose one of the ten fingers.");
        if (quality < MIN_QUALITY) out.add("The scan quality is " + quality + " and at least " + MIN_QUALITY + " is needed. Clean the finger and the glass and scan again.");
        if (format == null || format.isBlank()) out.add("The scanner did not say which template format it produced.");
        if (template == null || template.length == 0) out.add("The scan has no fingerprint data.");
        else if (template.length > 16 * 1024) out.add("The fingerprint data is too large.");
        return out;
    }
}
