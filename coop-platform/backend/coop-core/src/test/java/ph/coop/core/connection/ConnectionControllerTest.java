package ph.coop.core.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Which database the server works on, against the real server: only a SUPERADMIN changes it, a saved connection is checked against the database, and a switch moves the
 * application (and what it remembers) to the other database and back.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.connections.file=target/test-data/connections-it.json"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class ConnectionControllerTest {

    private static final Path FILE = Path.of("target", "test-data", "connections-it.json");

    @Autowired ConnectionController controller;
    @Autowired SwitchableDataSource dataSource;
    @Autowired ConnectionStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private static Authentication as(String... roles) {
        Authentication a = new TestingAuthenticationToken("boss", "x", roles);
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    private static Authentication superadmin() {
        return as("ROLE_ADMIN", "ROLE_SUPERADMIN");
    }

    private ConnectionController.Row startup() {
        superadmin();
        return controller.list().stream().filter(ConnectionController.Row::startup).findFirst().orElseThrow();
    }

    @BeforeEach
    void clean() throws Exception {
        Files.deleteIfExists(FILE);
    }

    @AfterEach
    void putBack() throws Exception {
        // whatever a test did, the application ends on the database it started with, and nothing is left in the file
        if (!ConnectionStore.STARTUP_ID.equals(dataSource.activeId())) controller.activate(ConnectionStore.STARTUP_ID, superadmin());
        Files.deleteIfExists(FILE);
        SecurityContextHolder.clearContext();
    }

    @Test
    void onlyASuperadminMayLookAtOrChangeConnections() {
        as("ROLE_ADMIN");
        var place = new ConnectionController.Place("localhost", 3311, "x");
        assertThrows(AccessDeniedException.class, () -> controller.list());
        assertThrows(AccessDeniedException.class, () -> controller.databases(place));
        assertThrows(AccessDeniedException.class, () -> controller.test(place));
        assertThrows(AccessDeniedException.class, () -> controller.create(new ConnectionController.In("a", "localhost", 3311, "x", null, null), as("ROLE_ADMIN")));
        assertThrows(AccessDeniedException.class, () -> controller.activate(ConnectionStore.STARTUP_ID, as("ROLE_ADMIN")));
    }

    @Test
    void anyoneSeesWhichDatabaseTheyAreSigningInToButNotWhereItIs() {
        SecurityContextHolder.clearContext();
        var list = controller.publicList();
        assertEquals(ConnectionStore.STARTUP_ID, list.active());
        assertEquals(1, list.connections().size());
        assertTrue(list.connections().get(0).active());
        assertNotNull(list.connections().get(0).dbName());
        var parts = java.util.Arrays.stream(ConnectionController.PublicConnection.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
        assertFalse(parts.contains("host") || parts.contains("port"), "where the server is stays private");
    }

    @Test
    @Transactional
    void loadDatabasesLeavesOutTheServersOwn() {
        superadmin();
        var s = startup();
        var names = controller.databases(new ConnectionController.Place(s.host(), s.port(), null));
        assertTrue(names.stream().anyMatch((n) -> n.equalsIgnoreCase(s.dbName())), "the database in use is listed");
        assertFalse(names.stream().anyMatch((n) -> n.equalsIgnoreCase("mysql") || n.equalsIgnoreCase("information_schema")));
    }

    @Test
    @Transactional
    void aSavedConnectionIsCheckedAgainstTheDatabaseAndKeepsTheBranchsName() {
        var auth = superadmin();
        var s = startup();
        var branches = controller.branches(new ConnectionController.Place(s.host(), s.port(), s.dbName()));
        assertFalse(branches.isEmpty(), "the database has at least one branch or office");
        var first = branches.get(0);

        var saved = controller.create(new ConnectionController.In("  Main  ", s.host(), s.port(), s.dbName(), first.id(), null), auth);
        assertEquals("Main", saved.name());
        assertEquals(first.name(), saved.branchName());
        assertTrue(saved.isDefault(), "the first one saved is the default");
        assertFalse(saved.active(), "saving does not switch");

        // the same name or the same place twice is refused
        assertThrows(ResponseStatusException.class, () -> controller.create(new ConnectionController.In("main", s.host(), s.port(), "other_db", null, null), auth));
        assertThrows(ResponseStatusException.class, () -> controller.create(new ConnectionController.In("Other", s.host(), s.port(), s.dbName(), null, null), auth));
        // a branch the database does not have, and a database that is not there, are refused
        assertThrows(ResponseStatusException.class, () -> controller.create(new ConnectionController.In("Odd", s.host(), s.port(), "x_" + s.dbName(), null, null), auth));
        assertThrows(ResponseStatusException.class, () -> controller.update(saved.id(), new ConnectionController.In("Main", s.host(), s.port(), s.dbName(), -12345, null), auth));

        var renamed = controller.update(saved.id(), new ConnectionController.In("Head office", s.host(), s.port(), s.dbName(), first.id(), null), auth);
        assertEquals("Head office", renamed.name());
        assertEquals(1, store.all().size());

        // the audit trail says who saved it
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'connection.create' AND entity_id = ?", Integer.class, saved.id());
        assertEquals(1, rows);

        // the startup setting cannot be edited or deleted, and what is in use cannot be deleted
        assertThrows(ResponseStatusException.class, () -> controller.update(ConnectionStore.STARTUP_ID, new ConnectionController.In("x", "h", 1, "d", null, null), auth));
        assertThrows(ResponseStatusException.class, () -> controller.delete(ConnectionStore.STARTUP_ID, auth));
        controller.delete(saved.id(), auth);
        assertTrue(store.all().isEmpty());
    }

    @Test
    @Transactional
    void aDatabaseThatIsNotTheCooperativesIsNotUsable() {
        superadmin();
        var s = startup();
        var probe = controller.test(new ConnectionController.Place(s.host(), s.port(), s.dbName()));
        assertTrue(probe.reachable());
        assertTrue(probe.usable());
        var nowhere = controller.test(new ConnectionController.Place("127.0.0.1", 1, "x"));
        assertFalse(nowhere.reachable());
        assertFalse(nowhere.message().isBlank());
    }

    @Test
    void switchingMovesTheApplicationToTheOtherDatabaseAndBack() {
        var auth = superadmin();
        var s = startup();
        var others = controller.databases(new ConnectionController.Place(s.host(), s.port(), null)).stream().filter((n) -> !n.equalsIgnoreCase(s.dbName())).toList();
        String other = others.stream().filter((n) -> controller.test(new ConnectionController.Place(s.host(), s.port(), n)).usable()).findFirst().orElse(null);
        assumeTrue(other != null, "no second cooperative database on this server to switch to");

        var saved = controller.create(new ConnectionController.In("Second", s.host(), s.port(), other, null, false), auth);
        assertEquals(s.dbName().toLowerCase(), currentDatabase().toLowerCase());
        db.tableExists("access"); // remembered for the old database

        var after = controller.activate(saved.id(), auth);
        assertEquals(saved.id(), after.active());
        assertEquals(saved.id(), dataSource.activeId());
        assertEquals(1, after.connections().stream().filter(ConnectionController.PublicConnection::active).count());
        assertEquals(other.toLowerCase(), currentDatabase().toLowerCase());
        assertTrue(db.tableExists("access"), "the new database is asked again, not answered from memory");

        // the one in use cannot be deleted; going back works
        assertThrows(ResponseStatusException.class, () -> controller.delete(saved.id(), auth));
        controller.activate(ConnectionStore.STARTUP_ID, auth);
        assertEquals(s.dbName().toLowerCase(), currentDatabase().toLowerCase());
        // the switch was recorded in the database that was left
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'connection.switch' AND entity_id = ?", Integer.class, saved.id());
        assertTrue(rows != null && rows >= 1);
        controller.delete(saved.id(), auth);
    }

    private String currentDatabase() {
        return jdbc.execute((java.sql.Connection c) -> c.getCatalog());
    }
}
