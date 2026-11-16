package ph.coop.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** The fingerprints are sensitive to every field and to the order of the chain; sealing, checking and the "where" of a request work against the database; the app's user cannot touch the log. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.audit.seal.enabled=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class AuditSealsTest {

    @Autowired AuditSeals seals;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private static AuditSeals.Row row(long id, String reason) {
        return new AuditSeals.Row(id, 1_700_000_000_000L + id * 1000, "ana", "member.update", "client", "1-100", "{\"a\":1}", "{\"a\":2}", reason, "10.0.0.5", "Mozilla");
    }

    private static String hash(List<AuditSeals.Row> rows) {
        return AuditSeals.blockHash(rows, AuditSeals.TO_THE_MILLISECOND);
    }

    // ---- pure ---------------------------------------------------------------------------------------------------

    @Test
    void aMillisecondSealSeesTheMillisecondsAndASecondSealDoesNot() {
        var at = new AuditSeals.Row(1, 1_700_000_000_123L, "ana", "member.update", "client", "1-100", "{\"a\":1}", "{\"a\":2}", "r", "10.0.0.5", "Mozilla");
        var moved = new AuditSeals.Row(1, 1_700_000_000_456L, "ana", "member.update", "client", "1-100", "{\"a\":1}", "{\"a\":2}", "r", "10.0.0.5", "Mozilla");
        assertNotEquals(AuditSeals.blockHash(List.of(at), AuditSeals.TO_THE_MILLISECOND), AuditSeals.blockHash(List.of(moved), AuditSeals.TO_THE_MILLISECOND), "a time moved by a third of a second");
        // a seal made before milliseconds were kept fingerprinted whole seconds, and still checks out the same way
        assertEquals(AuditSeals.blockHash(List.of(at), AuditSeals.TO_THE_SECOND), AuditSeals.blockHash(List.of(moved), AuditSeals.TO_THE_SECOND));
        assertTrue(AuditSeals.canonical(at, AuditSeals.TO_THE_MILLISECOND).startsWith("1\u001F1700000000123\u001Fana\u001F"), "every field written as text, side by side");
        // the first seals' formula is kept as it was: the id, the separator (31) and the time in seconds came out as one sum
        assertTrue(AuditSeals.canonical(at, AuditSeals.TO_THE_SECOND).startsWith((1 + 31 + 1_700_000_000L + 31) + "ana"));
    }

    @Test
    void theBlockFingerprintChangesWithAnyFieldTheOrderAndTheCount() {
        List<AuditSeals.Row> rows = List.of(row(1, "a"), row(2, "b"), row(3, "c"));
        String base = hash(rows);
        assertEquals(base, hash(List.of(row(1, "a"), row(2, "b"), row(3, "c"))), "the same rows give the same fingerprint");
        assertNotEquals(base, hash(List.of(row(1, "a"), row(2, "changed"), row(3, "c"))), "a changed field");
        assertNotEquals(base, hash(List.of(row(1, "a"), row(3, "c"))), "a removed row");
        assertNotEquals(base, hash(List.of(row(1, "a"), row(3, "c"), row(2, "b"))), "a different order");
        var moved = new AuditSeals.Row(2, 1_700_000_002_000L, "ana", "member.update", "client", "1-100", "{\"a\":1}", "{\"a\":2}", "b", "10.0.0.99", "Mozilla");
        assertNotEquals(base, hash(List.of(row(1, "a"), moved, row(3, "c"))), "even where the request came from is protected");
    }

    @Test
    void aSealDependsOnTheOneBeforeIt() {
        String block = hash(List.of(row(1, "a")));
        String first = AuditSeals.sealHash(AuditSeals.ZERO, 1, 1, 1, block);
        assertNotEquals(first, AuditSeals.sealHash("f".repeat(64), 1, 1, 1, block));
        assertNotEquals(first, AuditSeals.sealHash(AuditSeals.ZERO, 1, 2, 1, block));
        assertEquals(64, first.length());
    }

    // ---- against the database -----------------------------------------------------------------------------------

    @BeforeEach
    void ready() {
        assumeTrue(seals.installed(), "V045 has not been applied");
    }

    @Test
    void anAuditRowRecordsWhereTheRequestCameFrom() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("User-Agent", "CoopPlatform/0.1 Test");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            AuditLog.write(jdbc, "tester", "test.where", "test", "w1", null, "x", "check");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
        AuditLog.write(jdbc, "tester", "test.where", "test", "w2", null, "x", "no request");
        assertEquals("203.0.113.9", jdbc.queryForObject("SELECT client_ip FROM ecis_audit_log WHERE entity = 'test' AND entity_id = 'w1'", String.class));
        assertEquals("CoopPlatform/0.1 Test", jdbc.queryForObject("SELECT client_info FROM ecis_audit_log WHERE entity = 'test' AND entity_id = 'w1'", String.class));
        assertEquals(null, jdbc.queryForObject("SELECT client_ip FROM ecis_audit_log WHERE entity = 'test' AND entity_id = 'w2'", String.class));
    }

    @Test
    void sealingCoversTheRowsAndTheChainChecksOut() {
        AuditLog.write(jdbc, "tester", "test.seal", "test", "s1", null, "one", "first");
        var first = seals.seal("tester", Duration.ZERO);
        assertTrue(first.rows() >= 1);
        var v1 = seals.verify();
        assertTrue(v1.ok(), v1.problems().toString());
        assertEquals(0, v1.unsealedRows());

        AuditLog.write(jdbc, "tester", "test.seal", "test", "s2", null, "two", "second");
        assertEquals(1, seals.status().unsealedRows());
        var second = seals.seal("tester", Duration.ZERO);
        assertEquals(1, second.rows());
        var v2 = seals.verify();
        assertTrue(v2.ok(), v2.problems().toString());
        assertEquals(seals.status().seals(), v2.sealsChecked());
        assertEquals(null, seals.seal("tester", Duration.ZERO), "nothing new, nothing to seal");
    }

    @Test
    void aBlockThatDoesNotMatchWhatWasSealedIsReported() {
        AuditLog.write(jdbc, "tester", "test.tamper", "test", "t1", null, "real", "row");
        long id = jdbc.queryForObject("SELECT MAX(id) FROM ecis_audit_log", Long.class);
        // A seal that claims a different fingerprint for the block: exactly what a changed row would produce.
        String wrongBlock = "a".repeat(64);
        List<String[]> last = jdbc.query("SELECT seal_hash FROM ecis_audit_seal ORDER BY id DESC LIMIT 1", (rs, i) -> new String[] {rs.getString(1)});
        String prev = last.isEmpty() ? AuditSeals.ZERO : last.get(0)[0];
        jdbc.update("INSERT INTO ecis_audit_seal (from_id, to_id, row_count, block_hash, prev_seal_hash, seal_hash, sealed_by, sealed_at) VALUES (?, ?, 1, ?, ?, ?, 'attacker', NOW())",
            id, id, wrongBlock, prev, AuditSeals.sealHash(prev, id, id, 1, wrongBlock));
        var v = seals.verify();
        assertTrue(!v.ok());
        assertTrue(v.problems().stream().anyMatch((p) -> p.contains("has been changed")), v.problems().toString());
    }

    @Test
    void aSealThatIsAlteredOrOutOfChainIsReported() {
        AuditLog.write(jdbc, "tester", "test.tamper", "test", "t2", null, "real", "row");
        seals.seal("tester", Duration.ZERO);
        AuditLog.write(jdbc, "tester", "test.tamper", "test", "t3", null, "real", "row");
        long id = jdbc.queryForObject("SELECT MAX(id) FROM ecis_audit_log", Long.class);
        jdbc.update("INSERT INTO ecis_audit_seal (from_id, to_id, row_count, block_hash, prev_seal_hash, seal_hash, sealed_by, sealed_at) VALUES (?, ?, 1, ?, ?, ?, 'attacker', NOW())",
            id, id, "b".repeat(64), "c".repeat(64), "d".repeat(64));
        var v = seals.verify();
        assertTrue(!v.ok());
        assertTrue(v.problems().stream().anyMatch((p) -> p.contains("does not follow") || p.contains("altered")), v.problems().toString());
    }

    @Test
    void theApplicationsDatabaseUserCannotChangeOrRemoveAnAuditRowOrASeal() {
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE ecis_audit_log SET reason = 'x' WHERE id = -1"));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM ecis_audit_log WHERE id = -1"));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE ecis_audit_seal SET sealed_by = 'x' WHERE id = -1"));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM ecis_audit_seal WHERE id = -1"));
    }
}
