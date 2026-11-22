package ph.coop.core.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The database changes packaged into the build match the main database's log byte for byte (the same fingerprints scripts/migrate-postgres.ps1 recorded),
 * so the main database reads as up to date with nothing edited. Run with the PostgreSQL profile on the main database (see testing.md).
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class SchemaCheckTest {

    @Autowired SchemaCheck check;

    @Test void theBuildCarriesEveryChangeFile() throws Exception {
        var expected = SchemaCheck.expected();
        assertTrue(expected.containsKey("V001") && expected.containsKey("V033"), "packaged from database/postgres/migrations");
        assertTrue(expected.get("V033")[1].matches("[0-9A-F]{64}"));
    }

    @Test void theMainDatabaseIsUpToDateAndNothingWasEdited() {
        var s = check.check();
        assertEquals("UP_TO_DATE", s.state(), s.message());
        assertTrue(s.changed().isEmpty(), "edited after applied: " + s.changed());
        assertEquals(s.lastExpected(), s.lastRecorded());
    }
}
