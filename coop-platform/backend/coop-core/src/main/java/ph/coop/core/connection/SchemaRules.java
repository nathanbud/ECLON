package ph.coop.core.connection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Compares the database changes this build of the application carries (database/postgres/migrations, packaged into it) with what the connected
 * database has recorded in legacy.ecis_pg_migration (written by scripts/migrate-postgres.ps1). Pure: no database, so it is tested on its own.
 * <ul>
 *   <li>A change the build has and the database has not recorded is <b>missing</b>: the screens that need it fail there until it is applied.
 *       Changes numbered below the first one the database recorded predate its log and are taken as applied (coop_bgempc's log starts at V020).</li>
 *   <li>A change the database recorded that the build does not know is <b>newer</b>: the database was updated by a newer version of the application.</li>
 *   <li>A change recorded with another fingerprint than the build's file was edited after it was applied (never do that); it is reported, not counted as missing.
 *       V009 to V011 are regenerated from MySQL by the setup script, and BASELINE rows carry no real fingerprint, so neither is compared.</li>
 * </ul>
 */
public final class SchemaRules {

    private SchemaRules() {}

    static final Set<String> REGENERATED = Set.of("V009", "V010", "V011");

    public enum State { UP_TO_DATE, BEHIND, NEWER, NO_LOG, NOT_CHECKED }

    /** A recorded change: its fingerprint and how it was recorded (APPLIED or BASELINE). */
    public record Recorded(String checksum, String how) {}

    public record Result(State state, List<String> missing, List<String> newer, List<String> changed, String lastExpected, String lastRecorded) {}

    /** version ("V033") to its number (33); -1 when it is not a version. */
    static int number(String version) {
        if (version == null || !version.matches("V\\d+")) return -1;
        return Integer.parseInt(version.substring(1));
    }

    /**
     * @param expected the build's changes: version to file name and fingerprint (SHA-256, upper case hex, as scripts/migrate-postgres.ps1 records it)
     * @param recorded the database's log: version to fingerprint and how; null when the database has no log table at all
     */
    public static Result compare(Map<String, String[]> expected, Map<String, Recorded> recorded) {
        TreeMap<Integer, String> want = new TreeMap<>();
        for (String v : expected.keySet()) if (number(v) >= 0) want.put(number(v), v);
        String lastExpected = want.isEmpty() ? null : want.lastEntry().getValue();
        if (recorded == null) return new Result(State.NO_LOG, List.copyOf(want.values()), List.of(), List.of(), lastExpected, null);

        TreeMap<Integer, String> have = new TreeMap<>();
        for (String v : recorded.keySet()) if (number(v) >= 0) have.put(number(v), v);
        String lastRecorded = have.isEmpty() ? null : have.lastEntry().getValue();
        int floor = have.isEmpty() ? Integer.MAX_VALUE : have.firstKey();

        List<String> missing = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (var e : want.entrySet()) {
            String v = e.getValue();
            Recorded r = recorded.get(v);
            if (r == null) {
                if (e.getKey() >= floor) missing.add(v + " " + expected.get(v)[0]);
            } else if (!"BASELINE".equalsIgnoreCase(r.how()) && !REGENERATED.contains(v) && r.checksum() != null
                       && !r.checksum().trim().equalsIgnoreCase(expected.get(v)[1])) {
                changed.add(v + " " + expected.get(v)[0]);
            }
        }
        if (have.isEmpty()) missing = new ArrayList<>(want.values().stream().map((v) -> v + " " + expected.get(v)[0]).toList());

        List<String> newer = new ArrayList<>();
        for (String v : have.values()) if (!expected.containsKey(v)) newer.add(v);

        State state = !missing.isEmpty() ? State.BEHIND : !newer.isEmpty() ? State.NEWER : State.UP_TO_DATE;
        return new Result(state, List.copyOf(missing), List.copyOf(newer), List.copyOf(changed), lastExpected, lastRecorded);
    }

    /** The sentence for the log and the screens. */
    public static String message(Result r, String database) {
        String db = database == null ? "The database" : "The database " + database;
        return switch (r.state()) {
            case UP_TO_DATE -> db + " has every database change this version of the application needs (up to " + r.lastExpected() + ").";
            case BEHIND -> db + " is missing " + r.missing().size() + " database change" + (r.missing().size() == 1 ? "" : "s") + " this version of the application needs ("
                + String.join(", ", r.missing().stream().map((m) -> m.split(" ")[0]).toList()) + "). Screens that use them will fail here. A super administrator can apply them in " + DatabaseChanges.WHERE + " (Apply them now).";
            case NEWER -> db + " has database changes this version of the application does not know (" + String.join(", ", r.newer())
                + "). It was updated by a newer version; update this server's application too.";
            case NO_LOG -> db + " has no record of which database changes it has (no legacy.ecis_pg_migration), so it cannot be checked. Run scripts/migrate-postgres.ps1 -DryRun to see.";
            case NOT_CHECKED -> db + " is the old MySQL database; its changes are not checked.";
        };
    }
}
