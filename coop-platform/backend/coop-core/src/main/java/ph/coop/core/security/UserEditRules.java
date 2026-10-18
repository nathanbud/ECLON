package ph.coop.core.security;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Checks for editing a person's legacy account setup. Pure logic, no database access. */
final class UserEditRules {

    private UserEditRules() {}

    static final Set<String> LEVELS = Set.of("USER", "ADMIN", "SUPERADMIN");

    private static boolean elevated(String level) {
        return level != null && (level.equalsIgnoreCase("ADMIN") || level.equalsIgnoreCase("SUPERADMIN"));
    }

    /**
     * Why this edit is not allowed, or null when it is.
     * Nobody edits their own account (so nobody can grant themselves more access), and changing an administrator's
     * account, or anyone's level, needs a super administrator.
     */
    static String problem(String caller, String callerLevel, String target, String targetLevel, boolean changesLevel) {
        if (caller.equalsIgnoreCase(target)) return "You cannot change your own account. Ask another administrator.";
        boolean superAdmin = "SUPERADMIN".equalsIgnoreCase(callerLevel);
        if ((elevated(targetLevel) || changesLevel) && !superAdmin) {
            return "Only a super administrator can change an administrator's account or anyone's level.";
        }
        return null;
    }

    /**
     * Returns the value in the form to store (null clears it), or throws {@link IllegalArgumentException} with a
     * message fit to show the user.
     *
     * @param allowed   the permitted values for a choice or a list, otherwise null
     * @param multi     true when the field is a list of values stored comma separated
     * @param maxLength the column's character limit when known, otherwise null
     */
    static String normalize(String label, String kind, Set<String> allowed, boolean multi, Integer maxLength, String raw) {
        String v = raw == null ? "" : raw.trim();

        if (kind.equals("bool")) {
            if (v.equals("1") || v.equalsIgnoreCase("true")) return "1";
            if (v.equals("0") || v.equalsIgnoreCase("false") || v.isEmpty()) return "0";
            throw new IllegalArgumentException(label + " must be on or off.");
        }
        if (v.isEmpty()) return null;

        if (multi) {
            Set<String> chosen = new LinkedHashSet<>();
            for (String part : v.split(",")) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                if (allowed != null && !allowed.contains(p)) throw new IllegalArgumentException(label + " includes a choice that is not allowed: " + p + ".");
                chosen.add(p);
            }
            return chosen.isEmpty() ? null : String.join(",", new ArrayList<>(chosen));
        }
        if (allowed != null) {
            if (!allowed.contains(v)) throw new IllegalArgumentException(label + " is not one of the allowed choices.");
            return v;
        }

        switch (kind) {
            case "number" -> {
                try {
                    long n = Long.parseLong(v);
                    if (n < 0 || n > Integer.MAX_VALUE) throw new IllegalArgumentException(label + " is out of range.");
                    return Long.toString(n);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(label + " must be a whole number.");
                }
            }
            case "money" -> {
                try {
                    BigDecimal amount = new BigDecimal(v.replace(",", ""));
                    if (amount.scale() > 2) throw new IllegalArgumentException(label + " can have at most two decimal places.");
                    if (amount.signum() < 0 || amount.compareTo(new BigDecimal("9999999999999")) > 0) throw new IllegalArgumentException(label + " is out of range.");
                    return amount.toPlainString();
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(label + " must be a number.");
                }
            }
            case "date" -> {
                try {
                    return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v).toString();
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException(label + " must be a date.");
                }
            }
            default -> {
                if (maxLength != null && v.length() > maxLength) throw new IllegalArgumentException(label + " is too long (at most " + maxLength + " characters).");
                return v;
            }
        }
    }

    /** Whether a stored value and a new one are the same, so an unchanged field is not rewritten or audited. */
    static boolean same(String kind, String stored, String updated) {
        String a = stored == null || stored.isBlank() ? null : stored.trim();
        String b = updated == null || updated.isBlank() ? null : updated.trim();
        if (a == null || b == null) return a == null && b == null;
        try {
            switch (kind) {
                case "bool", "number" -> { return Long.parseLong(a) == Long.parseLong(b); }
                case "money" -> { return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0; }
                case "date" -> { return a.substring(0, Math.min(10, a.length())).equals(b.substring(0, Math.min(10, b.length()))); }
                default -> { return a.equals(b); }
            }
        } catch (NumberFormatException e) {
            return a.equals(b);
        }
    }

    /** A list stored as "003,002" compares by its members, not their order. */
    static boolean sameList(String stored, String updated) {
        return members(stored).equals(members(updated));
    }

    private static Set<String> members(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s != null) for (String p : s.split(",")) if (!p.isBlank()) out.add(p.trim());
        return Set.copyOf(out);
    }

    static List<String> levels() { return List.of("USER", "ADMIN", "SUPERADMIN"); }
}
