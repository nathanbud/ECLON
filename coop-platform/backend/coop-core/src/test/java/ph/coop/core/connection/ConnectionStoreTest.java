package ph.coop.core.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

/** The saved connections file: kept between runs, one default, no password in it. */
class ConnectionStoreTest {

    @TempDir Path dir;

    private ConnectionStore store() {
        return new ConnectionStore(dir.resolve("connections.json").toString());
    }

    private static DbConnection conn(String name, String db, boolean isDefault) {
        return new DbConnection(null, name, "localhost", 3311, db, 1, "Main Office", isDefault);
    }

    @Test
    void nothingSavedMeansNoConnections() {
        assertTrue(store().all().isEmpty());
        assertTrue(store().defaultConnection().isEmpty());
    }

    @Test
    void theFirstOneSavedIsTheDefaultAndItSurvivesARestart() {
        DbConnection a = store().add(conn("local", "iaccs", false));
        assertTrue(a.isDefault());
        assertTrue(a.id().startsWith("c"));
        ConnectionStore again = store(); // a new object reading the same file
        assertEquals(1, again.all().size());
        assertEquals("local", again.defaultConnection().orElseThrow().name());
        assertEquals("Main Office", again.find(a.id()).orElseThrow().branchName());
    }

    @Test
    void thereIsOnlyOneDefault() {
        ConnectionStore s = store();
        DbConnection a = s.add(conn("a", "one", false));
        DbConnection b = s.add(conn("b", "two", true));
        assertFalse(s.find(a.id()).orElseThrow().isDefault());
        assertTrue(s.find(b.id()).orElseThrow().isDefault());
        // un-checking the only default does not leave none
        s.update(b.id(), conn("b", "two", false));
        assertTrue(s.find(b.id()).orElseThrow().isDefault());
        // making another the default moves it
        s.update(a.id(), conn("a", "one", true));
        assertTrue(s.find(a.id()).orElseThrow().isDefault());
        assertFalse(s.find(b.id()).orElseThrow().isDefault());
    }

    @Test
    void removingTheDefaultHandsItToTheFirstRemaining() {
        ConnectionStore s = store();
        DbConnection a = s.add(conn("a", "one", false));
        DbConnection b = s.add(conn("b", "two", false));
        s.remove(a.id());
        assertEquals(1, s.all().size());
        assertTrue(s.find(b.id()).orElseThrow().isDefault());
        s.remove(b.id());
        assertTrue(s.all().isEmpty());
    }

    @Test
    void anUnknownIdIsNotFound() {
        ConnectionStore s = store();
        assertThrows(ResponseStatusException.class, () -> s.remove("nope"));
        assertThrows(ResponseStatusException.class, () -> s.update("nope", conn("x", "y", false)));
    }

    @Test
    void theFileHoldsNoPasswordAndAnUnreadableFileMeansNone() throws Exception {
        ConnectionStore s = store();
        s.add(conn("a", "one", false));
        String text = Files.readString(dir.resolve("connections.json"));
        assertFalse(text.toLowerCase().contains("password"));
        assertFalse(Files.exists(dir.resolve("connections.json.tmp")));
        Files.writeString(dir.resolve("connections.json"), "{ not json");
        assertTrue(s.all().isEmpty());
    }
}
