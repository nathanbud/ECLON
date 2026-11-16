package ph.coop.core.connection;

import static ph.coop.core.web.ApiErrors.bad;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What a saved connection may contain, and the JDBC address built from it. Plain rules, no database, so they are tested on their own. */
public final class ConnectionRules {

    private ConnectionRules() {}

    public static final int NAME_MAX = 60;
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,252}");
    private static final Pattern DATABASE = Pattern.compile("[A-Za-z0-9_$]{1,64}");
    private static final Pattern STARTUP_URL = Pattern.compile("jdbc:(?:mysql|postgresql)://([^:/?]+)(?::(\\d+))?/([^?]*)");

    /** The name as saved (trimmed); refuses an empty or over-long one. */
    public static String name(String raw) {
        String n = raw == null ? "" : raw.trim();
        if (n.isEmpty()) throw bad("Give the connection a name.");
        if (n.length() > NAME_MAX) throw bad("The connection name is longer than " + NAME_MAX + " characters.");
        return n;
    }

    /** The host as saved; letters, digits, dots, dashes and underscores only, so it cannot carry anything into the address. */
    public static String host(String raw) {
        String h = raw == null ? "" : raw.trim();
        if (h.isEmpty()) throw bad("Enter the server host.");
        if (!HOST.matcher(h).matches()) throw bad("The server host may only contain letters, digits, dots, dashes and underscores.");
        return h;
    }

    public static int port(Integer raw) {
        if (raw == null || raw < 1 || raw > 65535) throw bad("The port must be a number from 1 to 65535.");
        return raw;
    }

    public static String database(String raw) {
        String d = raw == null ? "" : raw.trim();
        if (d.isEmpty()) throw bad("Choose the database.");
        if (!DATABASE.matcher(d).matches()) throw bad("The database name may only contain letters, digits, underscores and dollar signs.");
        return d;
    }

    /** True when the two describe the same place, whatever the case of the host or name. */
    public static boolean samePlace(DbConnection a, String host, int port, String database) {
        return a.host().equalsIgnoreCase(host) && a.port() == port && a.dbName().equalsIgnoreCase(database);
    }

    /** The database engine the server was started on, from its address: "postgresql" or (the default) "mysql". Saved connections use the same engine. */
    public static String engineOf(String startupUrl) {
        return startupUrl != null && startupUrl.startsWith("jdbc:postgresql:") ? "postgresql" : "mysql";
    }

    /** The MySQL address the application uses (same options as {@code application.yml}). */
    public static String jdbcUrl(String host, int port, String database, boolean probe) {
        return jdbcUrl("mysql", host, port, database, probe);
    }

    /**
     * The address the application uses for the engine (same options as {@code application.yml} or {@code application-postgres.yml}). A probe has short time limits so a wrong
     * host answers quickly. {@code database} may be empty only for a probe of the server itself. On PostgreSQL the old and the new tables are in schema {@code legacy}.
     */
    public static String jdbcUrl(String engine, String host, int port, String database, boolean probe) {
        if ("postgresql".equals(engine)) {
            return "jdbc:postgresql://" + host + ":" + port + "/" + (database == null || database.isEmpty() ? "postgres" : database)
                + "?currentSchema=legacy&stringtype=unspecified&options=-c%20TimeZone%3DAsia/Manila"
                + (probe ? "&connectTimeout=4&socketTimeout=10" : "");
        }
        return "jdbc:mysql://" + host + ":" + port + "/" + (database == null ? "" : database)
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Manila&zeroDateTimeBehavior=CONVERT_TO_NULL"
            + (probe ? "&connectTimeout=4000&socketTimeout=10000" : "");
    }

    /** Host, port and database of the address the server was started with, for showing it in the list. Unknown parts come back as "?" and 0. */
    public static DbConnection fromStartupUrl(String url) {
        Matcher m = STARTUP_URL.matcher(url == null ? "" : url);
        if (!m.lookingAt()) return new DbConnection(ConnectionStore.STARTUP_ID, ConnectionStore.STARTUP_NAME, "?", 0, "?", null, null, false);
        return new DbConnection(ConnectionStore.STARTUP_ID, ConnectionStore.STARTUP_NAME, m.group(1).toLowerCase(Locale.ROOT), m.group(2) == null ? (url.startsWith("jdbc:postgresql:") ? 5432 : 3306) : Integer.parseInt(m.group(2)), m.group(3), null, null, false);
    }
}
