package ph.coop.core.connection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The rules for applying missing database changes from the application ({@link SchemaApply}), apart from the database so they are tested alone: only a
 * PostgreSQL database that is BEHIND, with a database administrator's name and password (used once, never kept) and a reason; the changes in number order.
 */
final class SchemaApplyRules {

    private SchemaApplyRules() {}

    static final int MIN_REASON = 3;
    static final String DEFAULT_ADMIN = "coop_admin";

    static List<String> problems(String state, String database, String adminUser, String adminPassword, String reason) {
        List<String> out = new ArrayList<>();
        String db = database == null ? "This database" : "The database " + database;
        switch (state == null ? "" : state) {
            case "BEHIND" -> { }
            case "UP_TO_DATE" -> out.add(db + " already has every database change this version needs, so there is nothing to apply. Reload the screen to see its state.");
            case "NEWER" -> out.add(db + " has changes newer than this server knows. Nothing was applied: update this server's application instead.");
            case "NOT_CHECKED" -> out.add(db + " is not a PostgreSQL database, so its changes are applied with scripts/migrate.ps1 on the server, not from here.");
            default -> out.add(db + " has no record of its database changes, so the application cannot tell which to apply. Ask the person who looks after the"
                + " server to run scripts/migrate-postgres.ps1 -DryRun there first.");
        }
        if (adminUser == null || adminUser.isBlank()) out.add("Give the database administrator's user name (on this system usually " + DEFAULT_ADMIN + ").");
        if (adminPassword == null || adminPassword.isEmpty()) out.add("Give the database administrator's password. It is used for these changes only and is not kept.");
        if (reason == null || reason.trim().length() < MIN_REASON) out.add("Give a reason (at least " + MIN_REASON + " characters); it is kept in the audit log with each change.");
        return out;
    }

    /** The missing changes ("V035 V035__offices.sql") as file names, lowest number first. */
    static List<String> order(List<String> missing) {
        return missing.stream()
            .map((m) -> m.substring(m.indexOf(' ') + 1).trim())
            .sorted(Comparator.comparingInt((String f) -> SchemaRules.number(f.substring(0, f.indexOf('_')))))
            .toList();
    }

    /** What to tell the person when the administrator's sign-in is refused or the change fails, by SQLState; never the password or the SQL. */
    static String failure(String sqlState, String file, int applied) {
        String done = applied == 0 ? "No change was applied." : applied + (applied == 1 ? " change was" : " changes were") + " applied before it and stay applied.";
        if ("28P01".equals(sqlState) || "28000".equals(sqlState))
            return "The database refused that administrator's user name or password, so nothing was applied. Check them and try again.";
        if ("42501".equals(sqlState))
            return "That database user is not allowed to make these changes (" + file + "), so it was rolled back. " + done + " Use the database's administrator account, such as " + DEFAULT_ADMIN + ".";
        if (sqlState != null && sqlState.startsWith("08"))
            return "The database could not be reached with those details, so nothing was applied. Check that the database server is running, then try again.";
        return file + " could not be applied, so it was rolled back and nothing after it was tried. " + done
            + " The cause is in the server log (logs/backend.log); ask the person who looks after the server to apply it with scripts/migrate-postgres.ps1.";
    }
}
