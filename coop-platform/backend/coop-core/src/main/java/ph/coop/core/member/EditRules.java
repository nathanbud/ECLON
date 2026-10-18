package ph.coop.core.member;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** Checks and normalises one edited value before it is written. Pure logic, no database access. */
final class EditRules {

    private EditRules() {}

    static final int MIN_YEAR = 1900;
    static final int MAX_YEAR = 2100;

    /**
     * Returns the value in the form to store (or null to clear the field), or throws
     * {@link IllegalArgumentException} with a message fit to show the user.
     *
     * @param kind      text, date, money or year
     * @param lookup    the allowed codes when the field is coded, otherwise null
     * @param maxLength the column's character limit when known, otherwise null
     */
    static String normalize(String label, String kind, Map<Integer, String> lookup, Integer maxLength, String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) return null;

        if (lookup != null) {
            try {
                int code = Integer.parseInt(v);
                if (!lookup.containsKey(code)) throw new IllegalArgumentException(label + " is not one of the allowed choices.");
                return Integer.toString(code);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(label + " is not one of the allowed choices.");
            }
        }

        switch (kind) {
            case "date" -> {
                try {
                    return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v).toString();
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException(label + " must be a date.");
                }
            }
            case "year" -> {
                try {
                    int year = Integer.parseInt(v);
                    if (year < MIN_YEAR || year > MAX_YEAR) throw new IllegalArgumentException(label + " must be a year between " + MIN_YEAR + " and " + MAX_YEAR + ".");
                    return Integer.toString(year);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(label + " must be a year.");
                }
            }
            case "money" -> {
                try {
                    BigDecimal amount = new BigDecimal(v.replace(",", ""));
                    if (amount.scale() > 2) throw new IllegalArgumentException(label + " can have at most two decimal places.");
                    if (amount.abs().compareTo(new BigDecimal("9999999999999")) > 0) throw new IllegalArgumentException(label + " is too large.");
                    return amount.toPlainString();
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(label + " must be a number.");
                }
            }
            default -> {
                if (maxLength != null && v.length() > maxLength) {
                    throw new IllegalArgumentException(label + " is too long (at most " + maxLength + " characters).");
                }
                return v;
            }
        }
    }

    /** Whether the stored value and the new value are the same, so an unchanged field is not rewritten or audited. */
    static boolean same(String kind, boolean coded, String stored, String updated) {
        String a = stored == null || stored.isBlank() ? null : stored.trim();
        String b = updated;
        if (a == null || b == null) return a == null && b == null;
        try {
            if (coded || kind.equals("year")) return Integer.parseInt(a) == Integer.parseInt(b);
            if (kind.equals("money")) return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0;
        } catch (NumberFormatException e) {
            return a.equals(b);
        }
        if (kind.equals("date")) return a.substring(0, Math.min(10, a.length())).equals(b);
        return a.equals(b);
    }

    /** Legacy datetimes come back as "2023-09-12 00:00:00.0"; clients send "2023-09-12T00:00:00". Make them comparable. */
    static String stamp(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim().replace('T', ' ');
        int dot = v.indexOf('.');
        return dot > 0 ? v.substring(0, dot) : v;
    }
}
