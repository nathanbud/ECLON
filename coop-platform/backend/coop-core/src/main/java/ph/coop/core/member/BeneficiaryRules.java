package ph.coop.core.member;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What a beneficiary request must satisfy, apart from the database: a name, a known relationship, a sensible birth date, and a kind (Principal or Secondary). */
final class BeneficiaryRules {

    static final Set<String> ACTIONS = Set.of("ADD", "EDIT", "REMOVE");
    /** The kinds a person can choose; the old data also holds 99 (not applicable), which is shown but not offered. */
    static final Map<Integer, String> TYPES = Map.of(1, "Principal", 2, "Secondary");
    static final int NAME_MAX = 50;
    static final int OCCUPATION_MAX = 35;

    private BeneficiaryRules() {}

    /** The name as it is kept: trimmed, with runs of spaces made one. */
    static String name(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    static String occupation(String s) {
        String t = name(s);
        return t.isEmpty() ? null : t;
    }

    /** The birth date, or null when none was given; a bad date is reported through {@link #problems}. */
    static LocalDate birthDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Problems with a request to add, change or remove a beneficiary; empty when it is fine. {@code relations} are the relationship codes the cooperative uses. */
    static List<String> problems(String action, String name, Integer relation, Set<Integer> relations, String birth, String occupation, Integer type, String note, LocalDate today) {
        List<String> out = new ArrayList<>();
        if (action == null || !ACTIONS.contains(action)) {
            out.add("Say whether to add, change or remove a beneficiary.");
            return out;
        }
        if (action.equals("REMOVE")) {
            if (note == null || note.trim().length() < 3) out.add("Give the reason for removing this beneficiary.");
            return out;
        }
        String n = name(name);
        if (n.length() < 2) out.add("Give the beneficiary's name.");
        else if (n.length() > NAME_MAX) out.add("The name can be at most " + NAME_MAX + " characters.");
        else if (!n.matches("[\\p{L}][\\p{L} .,'\\-]*")) out.add("The name can only have letters, spaces, commas, periods, hyphens and apostrophes.");
        if (relation == null || !relations.contains(relation)) out.add("Choose the relationship to the member.");
        if (birth != null && !birth.isBlank()) {
            LocalDate d = birthDate(birth);
            if (d == null) out.add("The birth date must look like 1990-04-24.");
            else if (d.isAfter(today)) out.add("The birth date cannot be in the future.");
            else if (d.getYear() < 1900) out.add("The birth date is not plausible.");
        }
        if (occupation != null && occupation.trim().length() > OCCUPATION_MAX) out.add("The occupation can be at most " + OCCUPATION_MAX + " characters.");
        if (type == null || !TYPES.containsKey(type)) out.add("Choose Principal or Secondary.");
        return out;
    }
}
