package ph.coop.core.organization;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The cooperative's units: the old dept table read as a tree (each department optionally under a consolidating one), with what each unit is. A branch and a
 * satellite office are different kinds of unit (a branch is a full operating unit; a satellite office offers part of the services under the main office or a
 * branch), so each department records its kind; one not chosen yet gets a kind suggested from its name and place in the tree. A statement for a unit covers it
 * and everything under it; a consolidating statement shows each unit directly under it side by side, plus the entries posted to the unit itself. Pure logic.
 */
public final class UnitRules {

    private UnitRules() {}

    public static final String MAIN_OFFICE = "MAIN_OFFICE";
    public static final String BRANCH = "BRANCH";
    public static final String SATELLITE_OFFICE = "SATELLITE_OFFICE";
    public static final String CONSOLIDATION = "CONSOLIDATION";
    public static final String OTHER = "OTHER";

    /** The kinds in the order they are offered, with their words. */
    public static final Map<String, String> KINDS = new java.util.LinkedHashMap<>();
    static {
        KINDS.put(MAIN_OFFICE, "Main office");
        KINDS.put(BRANCH, "Branch");
        KINDS.put(SATELLITE_OFFICE, "Satellite office");
        KINDS.put(CONSOLIDATION, "Consolidation");
        KINDS.put(OTHER, "Other unit");
    }

    /** A department as the tree needs it. */
    public record Dept(int id, String name, Integer parent) {}

    public static String kindName(String kind) {
        return KINDS.getOrDefault(kind, "Other unit");
    }

    /**
     * The kind a department most likely is, from its name, when none was chosen: a consolidation (CONSOLIDATED MAIN, CONSO LABCOOP), a satellite office, a
     * branch, a building or project (another unit), a main office, and otherwise a consolidation when other units sit under it, else another unit.
     */
    public static String suggest(String name, boolean hasChildren) {
        String n = name == null ? "" : name.toUpperCase(Locale.ROOT);
        if (n.contains("CONSOL")) return CONSOLIDATION;
        if (n.contains("SATELLITE")) return SATELLITE_OFFICE;
        if (n.contains("BRANCH")) return BRANCH;
        if (n.contains("BUILDING") || n.contains("BUIILDING") || n.contains("PROJECT") || n.contains("STORE")) return OTHER;
        if (n.contains("MAIN") || n.contains("HEAD OFFICE")) return MAIN_OFFICE;
        return hasChildren ? CONSOLIDATION : OTHER;
    }

    /** The unit and every unit under it, at any depth; a loop in the old data is cut where it repeats. */
    public static Set<Integer> subtree(int root, List<Dept> all) {
        Set<Integer> out = new LinkedHashSet<>();
        List<Integer> todo = new ArrayList<>(List.of(root));
        while (!todo.isEmpty()) {
            int id = todo.remove(0);
            if (!out.add(id)) continue;
            for (Dept d : all) if (d.parent() != null && d.parent() == id && d.id() != id) todo.add(d.id());
        }
        return out;
    }

    /** The units directly under one, in number order. */
    public static List<Dept> children(int root, List<Dept> all) {
        return all.stream().filter((d) -> d.parent() != null && d.parent() == root && d.id() != root).sorted((a, b) -> a.id() - b.id()).toList();
    }

    /** The top units: those under no unit, or under one that does not exist. */
    public static List<Dept> roots(List<Dept> all) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Dept d : all) ids.add(d.id());
        return all.stream().filter((d) -> d.parent() == null || !ids.contains(d.parent()) || d.parent() == d.id()).toList();
    }

    /** Why a kind cannot be saved; empty when it can. */
    public static List<String> kindProblems(String kind, String reason) {
        List<String> p = new ArrayList<>();
        if (kind == null || !KINDS.containsKey(kind)) p.add("Choose what the department is: main office, branch, satellite office, consolidation or another unit.");
        if (reason == null || reason.trim().length() < 3) p.add("Give the reason for the change (at least 3 characters); it is kept in the audit log.");
        return p;
    }
}
