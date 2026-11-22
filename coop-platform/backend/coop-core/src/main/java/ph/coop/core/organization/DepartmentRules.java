package ph.coop.core.organization;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules for the cooperative's departments (the old table {@code dept}), apart from the database so they are tested alone. A department has a number
 * (1 to 32767, never changed), a name (required, unique ignoring case), optionally a parent department (no loops), a TIN and an address. One that was ever
 * used is only switched off; delete is for one nothing points to, and never department 2, which a new member is given when none is chosen.
 */
final class DepartmentRules {

    private DepartmentRules() {}

    static final int MAX_NUMBER = 32767;
    static final int DEFAULT_FOR_MEMBERS = 2;
    static final int MIN_REASON = 3;

    record Dept(int id, String name, Integer parent, String tin, String address, boolean disabled) {}

    /** What is typed for a department: the number only when adding. */
    record Input(Integer id, String name, Integer parent, String tin, String address) {}

    static List<String> addProblems(Input in, List<Dept> existing, Map<String, Integer> lengths, String reason) {
        List<String> out = new ArrayList<>();
        Integer id = in.id();
        if (id == null || id < 1 || id > MAX_NUMBER) out.add("Give the department a number from 1 to " + MAX_NUMBER + ". The number stays with the department for good.");
        else existing.stream().filter((d) -> d.id() == id).findFirst()
            .ifPresent((d) -> out.add("Department number " + id + " is already used by " + d.name() + ". Choose another number; the next free one is " + nextNumber(existing) + "."));
        out.addAll(fieldProblems(in, id == null ? -1 : id, existing, lengths));
        out.addAll(reasonProblems(reason));
        return out;
    }

    static List<String> changeProblems(int id, Input in, List<Dept> existing, Map<String, Integer> lengths, String reason) {
        List<String> out = new ArrayList<>();
        Dept d = find(existing, id);
        if (d == null) {
            out.add("Department number " + id + " is not on this database any more, so it cannot be changed. Reload the screen to see the current departments.");
            return out;
        }
        out.addAll(fieldProblems(in, id, existing, lengths));
        if (in.parent() != null && loops(id, in.parent(), existing))
            out.add(d.name() + " cannot be placed under that department, because that department is itself under " + d.name() + ". Choose another parent, or none.");
        if (out.isEmpty() && same(d, in)) out.add("Nothing was changed, so there is nothing to save. Change a field, or cancel.");
        out.addAll(reasonProblems(reason));
        return out;
    }

    static List<String> switchProblems(Dept d, boolean off, String reason) {
        List<String> out = new ArrayList<>();
        if (d.disabled() == off) out.add(d.name() + " is already switched " + (off ? "off" : "on") + ", so there is nothing to change. Reload the screen to see it.");
        out.addAll(reasonProblems(reason));
        return out;
    }

    /**
     * @param members members of the department; users users given it; children departments under it
     * @param usedIn  the records of the books that point to it, in words ("ledger entries"), empty when none
     */
    static List<String> deleteProblems(Dept d, int members, int users, int children, List<String> usedIn, String reason) {
        List<String> out = new ArrayList<>();
        if (d.id() == DEFAULT_FOR_MEMBERS)
            out.add(d.name() + " is department " + DEFAULT_FOR_MEMBERS + ", which a new member is given when no department is chosen, so it cannot be deleted. Rename it instead.");
        List<String> who = new ArrayList<>();
        if (members > 0) who.add(members + (members == 1 ? " member" : " members"));
        if (users > 0) who.add(users + (users == 1 ? " user" : " users"));
        if (children > 0) who.add(children + (children == 1 ? " department under it" : " departments under it"));
        who.addAll(usedIn);
        if (!who.isEmpty())
            out.add(d.name() + " cannot be deleted because " + String.join(", ", who) + " still point to it, and they would point to a department that no longer"
                + " exists. Nothing was deleted. Switch it off instead, so it is no longer chosen.");
        out.addAll(reasonProblems(reason));
        return out;
    }

    private static List<String> fieldProblems(Input in, int id, List<Dept> existing, Map<String, Integer> lengths) {
        List<String> out = new ArrayList<>();
        String n = in.name() == null ? "" : in.name().trim();
        int maxName = lengths.getOrDefault("DeptDesc", 30);
        if (n.isEmpty()) out.add("Give the department a name, such as MAIN OFFICE or the name of a satellite office.");
        else if (n.length() > maxName) out.add("The department name is " + n.length() + " characters long; this database keeps at most " + maxName + ". Shorten it.");
        else existing.stream().filter((d) -> d.id() != id && d.name().trim().equalsIgnoreCase(n)).findFirst()
            .ifPresent((d) -> out.add("Department " + d.id() + " is already called " + d.name() + ". Give this one a different name so the two can be told apart."));
        if (in.parent() != null) {
            if (in.parent() == id) out.add("A department cannot be under itself. Choose another parent, or none.");
            else if (find(existing, in.parent()) == null) out.add("The parent department " + in.parent() + " is not on this database. Choose one from the list, or none.");
        }
        int maxTin = lengths.getOrDefault("DeptTIN", 200);
        if (in.tin() != null && in.tin().trim().length() > maxTin) out.add("The TIN is longer than the " + maxTin + " characters this database keeps. Shorten it.");
        int maxAddress = lengths.getOrDefault("DeptAddress", 200);
        if (in.address() != null && in.address().trim().length() > maxAddress) out.add("The address is longer than the " + maxAddress + " characters this database keeps. Shorten it.");
        return out;
    }

    /** True when putting {@code id} under {@code parent} would make a loop (the parent's chain reaches {@code id}). */
    static boolean loops(int id, int parent, List<Dept> existing) {
        Set<Integer> seen = new HashSet<>();
        Integer at = parent;
        while (at != null && seen.add(at)) {
            if (at == id) return true;
            Dept p = find(existing, at);
            at = p == null ? null : p.parent();
        }
        return false;
    }

    private static boolean same(Dept d, Input in) {
        return d.name().equals(trim(in.name())) && java.util.Objects.equals(d.parent(), in.parent())
            && java.util.Objects.equals(blank(d.tin()), trim(in.tin())) && java.util.Objects.equals(blank(d.address()), trim(in.address()));
    }

    static String trim(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static String blank(String s) {
        return trim(s);
    }

    private static Dept find(List<Dept> all, int id) {
        return all.stream().filter((d) -> d.id() == id).findFirst().orElse(null);
    }

    private static List<String> reasonProblems(String reason) {
        if (reason != null && reason.trim().length() >= MIN_REASON) return List.of();
        return List.of("Give a reason for the change (at least " + MIN_REASON + " characters); it is kept in the audit log.");
    }

    static int nextNumber(List<Dept> existing) {
        return Math.min(existing.stream().mapToInt(Dept::id).max().orElse(0) + 1, MAX_NUMBER);
    }
}
