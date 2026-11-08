package ph.coop.core.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The pool swap itself, against two real databases on the test server: a switch moves new connections over, and one that fails changes nothing. */
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class SwitchableDataSourceTest {

    private static final String HOST = System.getenv().getOrDefault("DB_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("DB_PORT", "3311"));
    private static final String FIRST = System.getenv().getOrDefault("DB_NAME", "iaccsbsucmpc01");
    /** A second database the application user can open; "test" exists on a standard MySQL install and is not the cooperative's. */
    private static final String SECOND = "test";

    private static String url(String database) {
        return ConnectionRules.jdbcUrl(HOST, PORT, database, true);
    }

    private static String catalog(SwitchableDataSource ds) throws SQLException {
        try (Connection c = ds.getConnection()) {
            return c.getCatalog();
        }
    }

    @Test
    void aSwitchMovesNewConnectionsToTheOtherDatabaseAndBack() throws Exception {
        SwitchableDataSource ds = new SwitchableDataSource("one", url(FIRST), System.getenv("DB_USER"), System.getenv("DB_PASSWORD"), 2);
        try {
            assertEquals("one", ds.activeId());
            assertEquals(FIRST, catalog(ds).toLowerCase());
            ds.switchTo("two", url(SECOND));
            assertEquals("two", ds.activeId());
            assertEquals(SECOND, catalog(ds).toLowerCase());
            ds.switchTo("one", url(FIRST));
            assertEquals(FIRST, catalog(ds).toLowerCase());
        } finally {
            ds.close();
        }
    }

    @Test
    void aSwitchToADatabaseThatCannotBeOpenedLeavesTheApplicationWhereItWas() throws Exception {
        SwitchableDataSource ds = new SwitchableDataSource("one", url(FIRST), System.getenv("DB_USER"), System.getenv("DB_PASSWORD"), 2);
        try {
            assertThrows(SQLException.class, () -> ds.switchTo("gone", url("no_such_database_" + System.nanoTime())));
            assertEquals("one", ds.activeId());
            assertEquals(FIRST, catalog(ds).toLowerCase());
        } finally {
            ds.close();
        }
    }
}
