package ph.coop.core.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** What a saved connection may contain: nothing that could carry extra options into the database address. */
class ConnectionRulesTest {

    @Test
    void aNameIsTrimmedAndMustBeThereAndNotTooLong() {
        assertEquals("local", ConnectionRules.name("  local "));
        assertThrows(ResponseStatusException.class, () -> ConnectionRules.name("   "));
        assertThrows(ResponseStatusException.class, () -> ConnectionRules.name(null));
        assertThrows(ResponseStatusException.class, () -> ConnectionRules.name("x".repeat(ConnectionRules.NAME_MAX + 1)));
    }

    @Test
    void aHostIsANameOrAnAddressAndNothingElse() {
        assertEquals("localhost", ConnectionRules.host("localhost"));
        assertEquals("192.168.1.20", ConnectionRules.host(" 192.168.1.20 "));
        assertEquals("db-1.coop.example", ConnectionRules.host("db-1.coop.example"));
        for (String bad : new String[] {"", "  ", "host/db", "host?useSSL=false", "host:3306", "a b", "host;drop", "-leading", "user@host", "host&x=1"}) {
            assertThrows(ResponseStatusException.class, () -> ConnectionRules.host(bad), bad);
        }
    }

    @Test
    void aPortIsFromOneTo65535() {
        assertEquals(3311, ConnectionRules.port(3311));
        assertThrows(ResponseStatusException.class, () -> ConnectionRules.port(null));
        assertThrows(ResponseStatusException.class, () -> ConnectionRules.port(0));
        assertThrows(ResponseStatusException.class, () -> ConnectionRules.port(65536));
    }

    @Test
    void aDatabaseNameIsLettersDigitsUnderscoresAndDollars() {
        assertEquals("iaccsbsucmpc01", ConnectionRules.database("iaccsbsucmpc01"));
        for (String bad : new String[] {"", " ", "db name", "db/other", "db?x=1", "db;", "db`", "a".repeat(65)}) {
            assertThrows(ResponseStatusException.class, () -> ConnectionRules.database(bad), bad);
        }
    }

    @Test
    void theAddressUsesTheSameOptionsAsTheServersOwnSetting() {
        String url = ConnectionRules.jdbcUrl("localhost", 3311, "iaccsbsucmpc01", false);
        assertEquals("jdbc:mysql://localhost:3311/iaccsbsucmpc01?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Manila&zeroDateTimeBehavior=CONVERT_TO_NULL", url);
        assertTrue(ConnectionRules.jdbcUrl("h", 1, "d", true).contains("connectTimeout=4000"));
        assertFalse(ConnectionRules.jdbcUrl("h", 1, "d", false).contains("connectTimeout"));
        assertTrue(ConnectionRules.jdbcUrl("h", 1, "", true).startsWith("jdbc:mysql://h:1/?"));
    }

    @Test
    void aPostgresqlServerGetsPostgresqlAddressesInTheSchemaOfTheOldAndNewTables() {
        assertEquals("postgresql", ConnectionRules.engineOf("jdbc:postgresql://localhost:5440/coop?currentSchema=legacy"));
        assertEquals("mysql", ConnectionRules.engineOf("jdbc:mysql://localhost:3311/x"));
        assertEquals("mysql", ConnectionRules.engineOf(null));
        String url = ConnectionRules.jdbcUrl("postgresql", "localhost", 5440, "coop", false);
        assertTrue(url.startsWith("jdbc:postgresql://localhost:5440/coop?currentSchema=legacy"));
        assertTrue(url.contains("stringtype=unspecified"));
        assertFalse(url.contains("connectTimeout"));
        assertTrue(ConnectionRules.jdbcUrl("postgresql", "h", 1, "d", true).contains("connectTimeout=4"));
        assertTrue(ConnectionRules.jdbcUrl("postgresql", "h", 1, "", true).startsWith("jdbc:postgresql://h:1/postgres?"), "a probe of the server itself opens the maintenance database");
        DbConnection c = ConnectionRules.fromStartupUrl("jdbc:postgresql://Localhost:5440/coop?currentSchema=legacy");
        assertEquals("localhost", c.host());
        assertEquals(5440, c.port());
        assertEquals("coop", c.dbName());
        assertEquals(5432, ConnectionRules.fromStartupUrl("jdbc:postgresql://db/x").port());
    }

    @Test
    void theStartupSettingIsReadBackIntoItsParts() {
        DbConnection c = ConnectionRules.fromStartupUrl("jdbc:mysql://Localhost:3311/iaccsbsucmpc01?useSSL=false");
        assertEquals("localhost", c.host());
        assertEquals(3311, c.port());
        assertEquals("iaccsbsucmpc01", c.dbName());
        assertEquals(ConnectionStore.STARTUP_ID, c.id());
        assertEquals(3306, ConnectionRules.fromStartupUrl("jdbc:mysql://db/x").port());
        assertEquals("?", ConnectionRules.fromStartupUrl("not a url").host());
    }

    @Test
    void twoConnectionsToTheSamePlaceAreRecognisedWhateverTheCase() {
        DbConnection a = new DbConnection("c1", "One", "LocalHost", 3311, "IACCS", null, null, false);
        assertTrue(ConnectionRules.samePlace(a, "localhost", 3311, "iaccs"));
        assertFalse(ConnectionRules.samePlace(a, "localhost", 3312, "iaccs"));
        assertFalse(ConnectionRules.samePlace(a, "localhost", 3311, "other"));
    }
}
