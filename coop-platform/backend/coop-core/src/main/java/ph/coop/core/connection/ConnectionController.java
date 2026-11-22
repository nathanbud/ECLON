package ph.coop.core.connection;

import static ph.coop.core.web.ApiErrors.bad;
import static ph.coop.core.web.ApiErrors.conflict;
import static ph.coop.core.web.ApiErrors.notFound;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ph.coop.core.audit.AuditLog;

/**
 * Which database the server works on. Anyone can see the saved connections' names (the sign-in screen shows which database a person is signing in to); only a SUPERADMIN can
 * add, change, delete or switch them. The database user and password are the server's own settings and are used for every connection, so no secret is saved or sent.
 * A switch is tested first, audited, and clears everything the server remembers about the old database; people are asked to sign in again against the new one.
 */
@RestController
public class ConnectionController {

    private static final Logger log = LoggerFactory.getLogger(ConnectionController.class);

    /** What the sign-in screen may see: no host or port. */
    public record PublicConnection(String id, String name, String dbName, String branch, boolean active) {}

    public record PublicList(String active, List<PublicConnection> connections) {}

    /** A connection as the administrator sees it. */
    public record Row(String id, String name, String host, int port, String dbName, Integer branchId, String branchName, boolean isDefault, boolean active, boolean startup) {}

    public record In(String name, String host, Integer port, String dbName, Integer branchId, Boolean makeDefault) {}

    public record Place(String host, Integer port, String dbName) {}

    public record Branch(int id, String name) {}

    /** The outcome of trying a connection. {@code usable} is false when it is not the cooperative's database at all. */
    public record Probe(boolean reachable, boolean usable, List<String> warnings, String message) {}

    private final ConnectionStore store;
    private final SwitchableDataSource dataSource;
    private final List<OnConnectionSwitch> listeners;
    private final JdbcTemplate jdbc;
    private final String startupUrl;
    private final String user;
    private final String password;

    ConnectionController(ConnectionStore store, SwitchableDataSource dataSource, List<OnConnectionSwitch> listeners, JdbcTemplate jdbc,
                         @Value("${spring.datasource.url}") String startupUrl, @Value("${spring.datasource.username}") String user,
                         @Value("${spring.datasource.password}") String password) {
        this.store = store;
        this.dataSource = dataSource;
        this.listeners = listeners;
        this.jdbc = jdbc;
        this.startupUrl = startupUrl;
        this.user = user;
        this.password = password;
    }

    // ---- reading ------------------------------------------------------------------------------------------------

    @GetMapping("/api/public/connections")
    public PublicList publicList() {
        String active = dataSource.activeId();
        List<PublicConnection> out = new ArrayList<>();
        for (Row r : rows()) out.add(new PublicConnection(r.id(), r.name(), r.dbName(), r.branchName(), r.active()));
        return new PublicList(active, out);
    }

    @GetMapping("/api/admin/connections")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public List<Row> list() {
        return rows();
    }

    /** Saved connections, plus the server's own setting when nothing is saved or when it is the one in use. */
    private List<Row> rows() {
        String active = dataSource.activeId();
        List<Row> out = new ArrayList<>();
        List<DbConnection> saved = store.all();
        if (saved.isEmpty() || ConnectionStore.STARTUP_ID.equals(active)) {
            DbConnection s = ConnectionRules.fromStartupUrl(startupUrl);
            out.add(new Row(s.id(), s.name(), s.host(), s.port(), s.dbName(), null, null, saved.isEmpty(), s.id().equals(active), true));
        }
        for (DbConnection c : saved) out.add(new Row(c.id(), c.name(), c.host(), c.port(), c.dbName(), c.branchId(), c.branchName(), c.isDefault(), c.id().equals(active), false));
        return out;
    }

    // ---- looking at a server (before saving) --------------------------------------------------------------------

    /** The databases on a server (Load Databases). */
    @PostMapping("/api/admin/connections/databases")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public List<String> databases(@RequestBody Place in) {
        String host = ConnectionRules.host(in.host());
        int port = ConnectionRules.port(in.port());
        try (Connection c = DriverManager.getConnection(ConnectionRules.jdbcUrl(ConnectionRules.engineOf(startupUrl), host, port, "", true), user, password); ResultSet rs = c.getMetaData().getCatalogs()) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                String name = rs.getString(1);
                if (ph.coop.core.db.Db.SERVER_OWN_DATABASES.stream().noneMatch((s) -> s.equalsIgnoreCase(name))) out.add(name);
            }
            out.sort(String.CASE_INSENSITIVE_ORDER);
            return out;
        } catch (SQLException e) {
            throw bad("Could not reach the server " + host + ":" + port + ". " + reason(e));
        }
    }

    /** The branches and offices of a database (Get List). */
    @PostMapping("/api/admin/connections/branches")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public List<Branch> branches(@RequestBody Place in) {
        String host = ConnectionRules.host(in.host());
        int port = ConnectionRules.port(in.port());
        String database = ConnectionRules.database(in.dbName());
        return branchList(host, port, database);
    }

    /** Whether the server answers and the database looks like the cooperative's. */
    @PostMapping("/api/admin/connections/test")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public Probe test(@RequestBody Place in) {
        return probe(ConnectionRules.host(in.host()), ConnectionRules.port(in.port()), ConnectionRules.database(in.dbName()));
    }

    // ---- changing -----------------------------------------------------------------------------------------------

    @PostMapping("/api/admin/connections")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public Row create(@RequestBody In in, Authentication auth) {
        DbConnection checked = checked(in, null);
        DbConnection saved = store.add(checked);
        audit(auth, "connection.create", saved.id(), null, describe(saved), null);
        return row(saved);
    }

    @PutMapping("/api/admin/connections/{id}")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public Row update(@PathVariable String id, @RequestBody In in, Authentication auth) {
        if (ConnectionStore.STARTUP_ID.equals(id)) throw bad("The server's startup setting cannot be changed here. Save a connection of your own instead.");
        DbConnection before = store.find(id).orElseThrow(() -> notFound("That saved connection was not found; it may have been removed. Reload the list of connections and choose again."));
        DbConnection saved = store.update(id, checked(in, id));
        audit(auth, "connection.update", id, describe(before), describe(saved), null);
        return row(saved);
    }

    @DeleteMapping("/api/admin/connections/{id}")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public void delete(@PathVariable String id, Authentication auth) {
        if (ConnectionStore.STARTUP_ID.equals(id)) throw bad("The server's startup setting cannot be deleted.");
        DbConnection c = store.find(id).orElseThrow(() -> notFound("That saved connection was not found; it may have been removed. Reload the list of connections and choose again."));
        if (id.equals(dataSource.activeId())) throw conflict("This connection is in use now. Switch to another one first.");
        store.remove(id);
        audit(auth, "connection.delete", id, describe(c), null, null);
    }

    /** Moves the server to this connection. Everyone is asked to sign in again, because people are read from the database. */
    @PostMapping("/api/admin/connections/{id}/activate")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public PublicList activate(@PathVariable String id, Authentication auth) {
        DbConnection target = ConnectionStore.STARTUP_ID.equals(id) ? ConnectionRules.fromStartupUrl(startupUrl) : store.find(id).orElseThrow(() -> notFound("That saved connection was not found; it may have been removed. Reload the list of connections and choose again."));
        String before = describeActive();
        if (id.equals(dataSource.activeId())) return publicList();
        Probe p = probe(target.host(), target.port(), target.dbName());
        if (!p.reachable() || !p.usable()) throw bad(p.message());
        // recorded in the database being left, and again in the one being entered
        audit(auth, "connection.switch", id, before, describe(target), "Switched the database the server works on");
        String url = ConnectionStore.STARTUP_ID.equals(id) ? startupUrl : ConnectionRules.jdbcUrl(ConnectionRules.engineOf(startupUrl), target.host(), target.port(), target.dbName(), false);
        try {
            dataSource.switchTo(id, url);
        } catch (SQLException e) {
            throw bad("Could not switch: " + reason(e));
        }
        for (OnConnectionSwitch l : listeners) {
            try {
                l.connectionSwitched();
            } catch (RuntimeException e) {
                log.warn("{} could not reset after the database switch: {}", l.getClass().getSimpleName(), e.getMessage());
            }
        }
        AuditLog.forgetShape();
        try {
            audit(auth, "connection.switch", id, before, describe(target), "Switched the database the server works on");
        } catch (RuntimeException e) {
            log.info("The switch could not be recorded in the new database: {}", e.getMessage());
        }
        return publicList();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Checks what was typed and reads the branch's name from the database itself (so a saved connection is known to answer). */
    private DbConnection checked(In in, String selfId) {
        String name = ConnectionRules.name(in.name());
        String host = ConnectionRules.host(in.host());
        int port = ConnectionRules.port(in.port());
        String database = ConnectionRules.database(in.dbName());
        for (DbConnection c : store.all()) {
            if (c.id().equals(selfId)) continue;
            if (c.name().equalsIgnoreCase(name)) throw conflict("There is already a connection named \"" + c.name() + "\".");
            if (ConnectionRules.samePlace(c, host, port, database)) throw conflict("\"" + c.name() + "\" already points to this database.");
        }
        Integer branchId = in.branchId();
        String branchName = null;
        if (branchId != null) {
            List<Branch> list = branchList(host, port, database);
            Branch b = list.stream().filter((x) -> x.id() == branchId).findFirst().orElseThrow(() -> bad("That branch or office is not in this database."));
            branchName = b.name();
        } else {
            branchList(host, port, database); // proves the database answers and is the cooperative's
        }
        return new DbConnection(selfId, name, host, port, database, branchId, branchName, Boolean.TRUE.equals(in.makeDefault()));
    }

    private Row row(DbConnection c) {
        return new Row(c.id(), c.name(), c.host(), c.port(), c.dbName(), c.branchId(), c.branchName(), c.isDefault(), c.id().equals(dataSource.activeId()), false);
    }

    private List<Branch> branchList(String host, int port, String database) {
        try (Connection c = DriverManager.getConnection(ConnectionRules.jdbcUrl(ConnectionRules.engineOf(startupUrl), host, port, database, true), user, password); Statement st = c.createStatement()) {
            if (!hasTable(c, "branches")) throw bad("\"" + database + "\" has no list of branches, so it does not look like the cooperative's database.");
            List<Branch> out = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT BranchesID, BranchesDesc FROM branches ORDER BY BranchesID")) {
                while (rs.next()) out.add(new Branch(rs.getInt(1), rs.getString(2) == null ? "Branch " + rs.getInt(1) : rs.getString(2).trim()));
            }
            return out;
        } catch (SQLException e) {
            throw bad("Could not read \"" + database + "\" on " + host + ":" + port + ". " + reason(e));
        }
    }

    private Probe probe(String host, int port, String database) {
        try (Connection c = DriverManager.getConnection(ConnectionRules.jdbcUrl(ConnectionRules.engineOf(startupUrl), host, port, database, true), user, password)) {
            List<String> warnings = new ArrayList<>();
            boolean people = hasTable(c, "access");
            if (!people) return new Probe(true, false, warnings, "\"" + database + "\" is reachable but has no users table, so it is not the cooperative's database.");
            if (!hasTable(c, "ecis_user_credential")) warnings.add("The new system's own tables are not in this database yet, so only the bootstrap administrator can sign in until they are added.");
            if (!hasTable(c, "branches")) warnings.add("It has no list of branches.");
            return new Probe(true, true, warnings, "Connected.");
        } catch (SQLException e) {
            return new Probe(false, false, List.of(), "Could not connect to " + host + ":" + port + "/" + database + ". " + reason(e));
        }
    }

    private static boolean hasTable(Connection c, String table) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getTables(c.getCatalog(), c.getSchema(), "%", new String[] {"TABLE", "BASE TABLE"})) {
            while (rs.next()) if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) return true;
        }
        return false;
    }

    /** A short reason fit for a person: the database's own words without the stack of causes. */
    private static String reason(SQLException e) {
        String m = e.getMessage() == null ? "" : e.getMessage().replaceAll("\\s+", " ");
        if (m.toLowerCase().contains("access denied")) return "The server refused the application's database user.";
        if (m.toLowerCase().contains("unknown database")) return "There is no such database on that server.";
        if (m.toLowerCase().contains("communications link failure") || m.toLowerCase().contains("connect timed out") || m.toLowerCase().contains("connection refused")) return "The server did not answer.";
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    private String describeActive() {
        String id = dataSource.activeId();
        DbConnection c = ConnectionStore.STARTUP_ID.equals(id) ? ConnectionRules.fromStartupUrl(startupUrl) : store.find(id).orElse(null);
        return c == null ? id : describe(c);
    }

    private static String describe(DbConnection c) {
        return c.name() + " (" + c.host() + ":" + c.port() + "/" + c.dbName() + (c.branchName() == null ? "" : ", " + c.branchName()) + (c.isDefault() ? ", default" : "") + ")";
    }

    private void audit(Authentication auth, String action, String id, String prev, String next, String reason) {
        AuditLog.write(jdbc, auth.getName(), action, "database_connection", id, prev, next, reason);
    }
}
