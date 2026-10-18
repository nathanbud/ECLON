package ph.coop.core.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Exercises the portable helpers against the real database, then rolls back. Runs only when DB_PASSWORD is set. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class DbPostingTest {

    @Autowired Db db;
    @Autowired JdbcTemplate jdbc;

    @Test
    void knowsWhatExists() {
        assertTrue(db.tableExists("ecis_audit_log"));
        assertTrue(db.tableExists("ECIS_AUDIT_LOG"), "names are matched without regard to case");
        assertFalse(db.tableExists("no_such_table_here"));
        assertTrue(db.allTablesExist("ecis_audit_log", "ecis_journal"));
        assertTrue(db.columnExists("ecis_journal", "tr_ctlno"));
        assertFalse(db.columnExists("ecis_journal", "no_such_column"));
        assertTrue(db.textColumnLengths("client").containsKey("LName"));
        assertTrue(db.canInsertInto("ecis_audit_log", "transsum"));
        assertFalse(db.canInsertInto("client"), "the app may update clients but never insert them");
    }

    @Test
    void insertReturningIdGivesTheNewKey() {
        long a = db.insertReturningId("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id) VALUES (NOW(), ?, ?, ?, ?)", "id", "itest", "db.test", "x", "1");
        long b = db.insertReturningId("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id) VALUES (NOW(), ?, ?, ?, ?)", "id", "itest", "db.test", "x", "2");
        assertTrue(a > 0 && b > a);
        assertEquals("2", jdbc.queryForObject("SELECT entity_id FROM ecis_audit_log WHERE id = ?", String.class, b));
    }

    @Test
    void upsertInsertsThenUpdatesTheSameRow() {
        Map<String, Object> key = new LinkedHashMap<>(Map.of("username", "itest_upsert"));
        var now = new java.sql.Timestamp(System.currentTimeMillis());
        db.upsert("ecis_user_credential", key, new LinkedHashMap<>(Map.of("password_hash", "first", "set_by", "itest", "set_at", now)));
        db.upsert("ecis_user_credential", key, new LinkedHashMap<>(Map.of("password_hash", "second", "set_by", "itest", "set_at", now)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_user_credential WHERE username = 'itest_upsert'", Integer.class));
        assertEquals("second", jdbc.queryForObject("SELECT password_hash FROM ecis_user_credential WHERE username = 'itest_upsert'", String.class));
    }
}
