package ph.coop.core.web;

import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an unexpected server failure into what happened and what to do about it, in plain words, instead of "Something went wrong". Pure, so it is
 * tested on its own.
 * <p>
 * Database failures are recognised by their SQLState (the standard code PostgreSQL and MySQL both give) and then by their wording; anything else is
 * named by its kind and first line. Never shown: the values in a row or the SQL itself, only the names of tables, columns and constraints, which tell
 * an administrator where to look. The full details go to the server's log under the reference ({@link ServerErrors}).
 */
public final class ErrorExplainer {

    private ErrorExplainer() {}

    /**
     * status: the HTTP status to answer with. code: a short name for the kind of failure. problem and fix: the technical cause and remedy, with table and
     * column names, for system administrators only (Settings > System > Error log). What the person using the screen is told is {@link #forUser}.
     */
    public record Explained(int status, String code, String problem, String fix) {}

    /**
     * What the person using the screen is told: what could not be done and why, in everyday words, whether anything was kept, and what they can do.
     * No table, column or error names; the reference (added by {@link ServerErrors}) lets an administrator find the technical details.
     */
    public static String forUser(String code) {
        return switch (code) {
            case "VALUE_TOO_LONG" -> "This was not saved because one of the entries is longer than the system allows. Shorten what you typed and try again. "
                + "If you did not type anything long, tell your administrator.";
            case "DUPLICATE" -> "This was not saved because the same record already exists. It was probably saved a moment ago: reload the screen and check before trying again.";
            case "LINKED_RECORD" -> "This could not be done because it refers to something that no longer exists, or something else still depends on it. "
                + "Reload the screen, check your choices, and try again.";
            case "MISSING_VALUE" -> "This was not saved because a required entry is missing. Fill in every required field and try again. "
                + "If every field on the screen is filled in, tell your administrator.";
            case "NO_DATABASE_PERMISSION", "MISSING_TABLE", "MISSING_COLUMN" -> "This could not be done because this part of the system is not fully set up yet on the database you are using. "
                + "Your administrator needs to finish the setup; until then, this action is not available.";
            case "DATABASE_UNREACHABLE" -> "The system cannot reach its database right now, so this could not be done. Try again in a few minutes. "
                + "If it keeps happening, tell your administrator.";
            case "BUSY" -> "Someone else was changing the same records at the same moment, so this was not saved. Try again.";
            case "TOO_SLOW" -> "This took too long and was stopped. Try again with a smaller selection, such as a shorter period or fewer records.";
            case "BAD_REQUEST_BODY" -> "Some entries are not in the form the system expects, such as a date or an amount. Check them and try again. "
                + "If it keeps happening, close and reopen the window.";
            default -> "This could not be completed because of a problem in the system. Check whether it went through before trying again. "
                + "If it happens again, tell your administrator.";
        };
    }

    private static final Pattern TOO_LONG = Pattern.compile("(?i)value too long for type character varying\\((\\d+)\\)|Data too long for column '([^']+)'");
    private static final Pattern COLUMN = Pattern.compile("(?i)column \"?([A-Za-z0-9_.]+)\"?");
    private static final Pattern RELATION = Pattern.compile("(?i)relation \"([A-Za-z0-9_.]+)\"|table \"?([A-Za-z0-9_.]+)\"?|Table '([^']+)'");
    private static final Pattern CONSTRAINT = Pattern.compile("(?i)constraint \"([A-Za-z0-9_]+)\"|key '([A-Za-z0-9_.]+)'");
    private static final Pattern PERMISSION = Pattern.compile("(?i)permission denied for (?:table|relation|sequence|schema) ([A-Za-z0-9_.]+)|command denied to user .* for table '([^']+)'");

    public static Explained explain(Throwable error) {
        SQLException sql = sqlCause(error);
        String state = sql == null ? null : sql.getSQLState();
        String text = sql != null && sql.getMessage() != null ? sql.getMessage() : allMessages(error);

        if (state != null) {
            Explained e = bySqlState(state, text);
            if (e != null) return e;
        }
        Explained byWords = byWording(error, text);
        if (byWords != null) return byWords;

        Throwable root = root(error);
        String first = firstLine(root.getMessage());
        return new Explained(500, "UNEXPECTED",
            "The server could not finish this because of an unexpected error (" + root.getClass().getSimpleName() + (first.isEmpty() ? "" : ": " + first) + ").",
            "Try again. If it happens again, give your administrator the reference below; the server's log (logs/backend.log) has the details under it.");
    }

    private static Explained bySqlState(String state, String text) {
        switch (state) {
            case "22001": {
                Matcher m = TOO_LONG.matcher(text);
                String limit = m.find() ? (m.group(1) != null ? m.group(1) + " characters" : "the column " + m.group(2)) : "the limit";
                return new Explained(400, "VALUE_TOO_LONG", "A value is longer than the database can keep (" + limit + ").",
                    "Shorten what you typed and try again. If it is not something you typed, tell your administrator: the database column needs to be wider.");
            }
            case "23505":
                return new Explained(409, "DUPLICATE", "This would make a second record with the same key" + named(CONSTRAINT, text, " (") + ".",
                    "It was probably saved already: reload the screen and check before trying again.");
            case "23503":
                return new Explained(409, "LINKED_RECORD", "This refers to a record that does not exist, or another record still refers to this one" + named(CONSTRAINT, text, " (") + ".",
                    "Check that what you chose still exists, or remove what refers to it first, then try again.");
            case "23502":
                return new Explained(400, "MISSING_VALUE", "A required value was not given" + named(COLUMN, text, " (column ") + ".",
                    "Fill in every required field and try again. If the field is not on the screen, tell your administrator.");
            case "42501":
                return new Explained(500, "NO_DATABASE_PERMISSION", "The application's database account is not allowed to do this" + named(PERMISSION, text, " (on ") + ".",
                    "A super administrator can usually give it that permission by applying the missing database changes in Settings > System > Database connection (Apply them now).");
            case "42P01":
            case "42S02":
                return new Explained(500, "MISSING_TABLE", "The database has no table this needs" + named(RELATION, text, " (") + ": a database change has not been applied to it.",
                    "A super administrator should apply the missing database changes in Settings > System > Database connection (Apply them now), which lists them.");
            case "42703":
            case "42S22":
                return new Explained(500, "MISSING_COLUMN", "The database has no column this needs" + named(COLUMN, text, " (") + ": a database change has not been applied to it.",
                    "A super administrator should apply the missing database changes in Settings > System > Database connection (Apply them now), which lists them.");
            case "40001":
            case "40P01":
                return new Explained(409, "BUSY", "Someone else was changing the same records at the same moment, so this was undone to keep the data right.",
                    "Nothing was saved. Try again.");
            case "57014":
                return new Explained(503, "TOO_SLOW", "The database took too long to answer, so it was stopped.",
                    "Try again with a narrower choice (a shorter period, fewer records). If it keeps happening, tell your administrator.");
            default:
                if (state.startsWith("08")) return databaseUnreachable();
                return null;
        }
    }

    private static Explained byWording(Throwable error, String text) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String name = t.getClass().getName();
            if (name.endsWith("CannotGetJdbcConnectionException") || name.endsWith("CannotCreateTransactionException") || name.contains("ConnectException")) return databaseUnreachable();
            if (name.endsWith("QueryTimeoutException")) return bySqlState("57014", text);
            if (name.endsWith("DuplicateKeyException")) return bySqlState("23505", text);
            if (name.endsWith("DataIntegrityViolationException") && TOO_LONG.matcher(text).find()) return bySqlState("22001", text);
            if (name.endsWith("HttpMessageNotReadableException"))
                return new Explained(400, "BAD_REQUEST_BODY", "The server could not read what the screen sent (a field has the wrong kind of value).",
                    "Check the fields (dates like 2026-11-21, amounts as numbers) and try again. If it keeps happening, the screen and server may be different versions: reload the window.");
        }
        return null;
    }

    private static Explained databaseUnreachable() {
        return new Explained(503, "DATABASE_UNREACHABLE", "The server cannot reach its database.",
            "Check that the database is running (Docker Desktop and the eclon-postgres container, or the PostgreSQL service for a database on this computer), then try again.");
    }

    /** " (name)" for the first name the pattern finds in the text, or "" when there is none. */
    private static String named(Pattern p, String text, String open) {
        Matcher m = p.matcher(text == null ? "" : text);
        if (!m.find()) return "";
        for (int i = 1; i <= m.groupCount(); i++) if (m.group(i) != null) return open + m.group(i) + ")";
        return "";
    }

    private static SQLException sqlCause(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) if (c instanceof SQLException s) return s;
        return null;
    }

    private static Throwable root(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r;
    }

    private static String allMessages(Throwable t) {
        StringBuilder b = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) if (c.getMessage() != null) b.append(c.getMessage()).append('\n');
        return b.toString();
    }

    /** The first line of a message, without SQL or values: cut at the first line break, at a bracketed SQL text, and to 160 characters. */
    static String firstLine(String message) {
        if (message == null) return "";
        String s = message.split("\\R", 2)[0];
        int sql = s.indexOf(" SQL [");
        if (sql >= 0) s = s.substring(0, sql);
        int detail = s.indexOf("Detail:");
        if (detail >= 0) s = s.substring(0, detail);
        s = s.trim();
        return s.length() > 160 ? s.substring(0, 157) + "…" : s;
    }
}
