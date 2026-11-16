package ph.coop.core.security;

import java.security.SecureRandom;
import java.util.List;
import java.util.Set;

/**
 * Rules for passwords.
 * <p>
 * A temporary password is made by the system, never typed by the administrator, so nobody picks a guessable one: 12 characters from an alphabet with
 * no look-alikes (no 0/O, 1/l/I), about 69 bits. It is single-purpose: the person must replace it at first sign-in, and it stops working after
 * 1 hour, 4 hours, 24 hours (the default) or 3 days, whichever the administrator chose. That follows common practice (OWASP and NIST SP 800-63B:
 * a short life, forced replacement, no reuse as the permanent password).
 * A password a person chooses needs at least 8 characters (NIST minimum) with both letters and numbers (symbols and spaces are allowed too), at most 128, and may not be their username, the temporary one, or a
 * well-known password.
 */
final class PasswordPolicy {

    static final int MIN_LENGTH = 8;
    static final int MAX_LENGTH = 128;
    static final int TEMP_LENGTH = 12;
    static final int DEFAULT_HOURS = 24;
    static final List<Integer> VALIDITY_HOURS = List.of(1, 4, 24, 72);

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> COMMON = Set.of("password", "password1", "password123", "12345678", "123456789", "1234567890", "qwertyui", "qwerty123",
        "iloveyou", "admin123", "welcome1", "letmein1", "abc12345", "11111111", "00000000");

    private PasswordPolicy() {}

    static String generateTemporary() {
        StringBuilder b = new StringBuilder(TEMP_LENGTH);
        for (int i = 0; i < TEMP_LENGTH; i++) b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return b.toString();
    }

    /** The validity chosen, or the default when none was given; null when it is not one of the allowed choices. */
    static Integer validity(Integer hours) {
        if (hours == null) return DEFAULT_HOURS;
        return VALIDITY_HOURS.contains(hours) ? hours : null;
    }

    /** Why a new password is not acceptable, or null when it is. */
    static String problem(String next, String username, String current) {
        if (next == null || next.length() < MIN_LENGTH) return "The new password must be at least " + MIN_LENGTH + " characters.";
        if (next.length() > MAX_LENGTH) return "The new password can be at most " + MAX_LENGTH + " characters.";
        if (next.equals(current)) return "Choose a password different from the temporary one.";
        if (username != null && next.equalsIgnoreCase(username)) return "The password cannot be your username.";
        if (COMMON.contains(next.toLowerCase())) return "That password is too common. Choose another.";
        if (next.chars().noneMatch(Character::isLetter) || next.chars().noneMatch(Character::isDigit)) return "Use both letters and numbers.";
        if (next.chars().distinct().count() < 4) return "Use a mix of different characters.";
        return null;
    }
}
