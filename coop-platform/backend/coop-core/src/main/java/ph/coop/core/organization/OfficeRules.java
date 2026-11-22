package ph.coop.core.organization;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The rules for the cooperative's offices (the old table {@code restrik}), kept apart from the database so they can be tested alone. An office has a number
 * (1 to 32767, never changed once given) and a name (required, unique ignoring case). It may be deleted only while no member and no user points to it.
 */
final class OfficeRules {

    private OfficeRules() {}

    static final int MAX_NUMBER = 32767;
    static final int MIN_REASON = 3;

    record Office(int id, String name) {}

    static List<String> addProblems(Integer id, String name, List<Office> existing, int maxName, String reason) {
        List<String> out = new ArrayList<>();
        if (id == null || id < 1 || id > MAX_NUMBER) out.add("Give the office a number from 1 to " + MAX_NUMBER + ". The number stays with the office for good.");
        else existing.stream().filter((o) -> o.id() == id).findFirst()
            .ifPresent((o) -> out.add("Office number " + id + " is already used by " + o.name() + ". Choose another number; the next free one is " + nextNumber(existing) + "."));
        out.addAll(nameProblems(name, id == null ? -1 : id, existing, maxName));
        out.addAll(reasonProblems(reason));
        return out;
    }

    static List<String> renameProblems(int id, String name, List<Office> existing, int maxName, String reason) {
        List<String> out = new ArrayList<>();
        Office o = existing.stream().filter((x) -> x.id() == id).findFirst().orElse(null);
        if (o == null) {
            out.add("Office number " + id + " is not on this database any more, so it cannot be renamed. Reload the screen to see the current offices.");
            return out;
        }
        out.addAll(nameProblems(name, id, existing, maxName));
        if (name != null && name.trim().equals(o.name())) out.add("The name is the same as before, so there is nothing to save. Type the new name, or cancel.");
        out.addAll(reasonProblems(reason));
        return out;
    }

    static List<String> deleteProblems(Office o, int members, int users, String reason) {
        List<String> out = new ArrayList<>();
        if (members > 0 || users > 0) {
            List<String> who = new ArrayList<>();
            if (members > 0) who.add(members + (members == 1 ? " member" : " members"));
            if (users > 0) who.add(users + (users == 1 ? " user" : " users"));
            out.add(o.name() + " cannot be deleted because " + String.join(" and ", who) + " still belong to it, and their records would point to an office that"
                + " no longer exists. Nothing was deleted. Rename the office instead, or move them to another office first.");
        }
        if (o.id() == 1) out.add(o.name() + " is office 1, which a new member is given when no office is chosen, so it cannot be deleted. Rename it instead.");
        out.addAll(reasonProblems(reason));
        return out;
    }

    private static List<String> nameProblems(String name, int id, List<Office> existing, int maxName) {
        List<String> out = new ArrayList<>();
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) out.add("Give the office a name, such as MAIN OFFICE or the town of a satellite office.");
        else if (n.length() > maxName) out.add("The office name is " + n.length() + " characters long; this database keeps at most " + maxName + ". Shorten it.");
        else existing.stream().filter((o) -> o.id() != id && o.name().trim().equalsIgnoreCase(n)).findFirst()
            .ifPresent((o) -> out.add("Office " + o.id() + " is already called " + o.name() + ". Give this office a different name so the two can be told apart."));
        return out;
    }

    private static List<String> reasonProblems(String reason) {
        if (reason != null && reason.trim().length() >= MIN_REASON) return List.of();
        return List.of("Give a reason for the change (at least " + MIN_REASON + " characters); it is kept in the audit log.");
    }

    /** One more than the highest number in use, or 1 for the first office. */
    static int nextNumber(List<Office> existing) {
        int top = existing.stream().mapToInt(Office::id).max().orElse(0);
        return Math.min(top + 1, MAX_NUMBER);
    }

    /** The office numbers in a user's office list ({@code access.AccessRestrik}, such as "003,002,004,001"); anything not a number is skipped. */
    static Set<Integer> officesOf(String accessRestrik) {
        Set<Integer> out = new LinkedHashSet<>();
        if (accessRestrik == null) return out;
        for (String part : accessRestrik.split("[,;\\s]+")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            try {
                out.add(Integer.parseInt(p));
            } catch (NumberFormatException e) {
                // not an office number: left out
            }
        }
        return out;
    }
}
