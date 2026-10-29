package ph.coop.core.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ph.coop.core.db.Db;

/**
 * Tamper evidence for the audit trail. A seal fingerprints a block of audit rows (SHA-256 over each row's fields, in order) and the seal before it, so the seals form a chain: changing a
 * sealed row, removing one, or removing or altering a seal shows up when the chain is checked. The application's own database user cannot change the log at all, so this is what protects
 * the trail against anyone who can reach the database some other way. A seal is added every night and whenever an administrator asks; it only covers rows older than a couple of
 * minutes, so a change still being committed is never caught half way.
 */
@Service
public class AuditSeals {

    private static final Logger LOG = LoggerFactory.getLogger(AuditSeals.class);
    static final String ZERO = "0".repeat(64);
    static final int MAX_BLOCK = 100_000;
    /** A row this young may belong to a change that has not committed yet, so it waits for the next seal. */
    static final Duration SETTLE = Duration.ofMinutes(2);

    public record Status(boolean installed, long seals, long lastSealedId, String lastSealedAt, long unsealedRows, boolean nightly) {}

    public record Sealed(long id, long fromId, long toId, long rows, String sealHash) {}

    public record Verification(boolean ok, long sealsChecked, long rowsChecked, long unsealedRows, List<String> problems) {}

    /** One audit row as it is fingerprinted. */
    record Row(long id, long atSecond, String user, String action, String entity, String entityId, String prev, String next, String reason, String ip, String info) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final boolean nightly;

    AuditSeals(JdbcTemplate jdbc, Db db, @Value("${coop.audit.seal.enabled:true}") boolean nightly) {
        this.jdbc = jdbc;
        this.db = db;
        this.nightly = nightly;
    }

    // ---- fingerprints (pure) ------------------------------------------------------------------------------------

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** The text of a row that is fingerprinted: every field, in a fixed order, separated by a character that cannot occur in the fields. */
    static String canonical(Row r) {
        char sep = '\u001F';
        return r.id() + sep + r.atSecond() + sep + nz(r.user()) + sep + nz(r.action()) + sep + nz(r.entity()) + sep + nz(r.entityId()) + sep + nz(r.prev()) + sep + nz(r.next())
            + sep + nz(r.reason()) + sep + nz(r.ip()) + sep + nz(r.info());
    }

    static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(MessageDigest d) {
        return HexFormat.of().formatHex(d.digest());
    }

    /** Folds one row into a running block fingerprint. */
    static void add(MessageDigest block, Row r) {
        block.update(canonical(r).getBytes(StandardCharsets.UTF_8));
        block.update((byte) '\n');
    }

    /** The fingerprint of a whole block of rows. */
    static String blockHash(List<Row> rows) {
        MessageDigest d = sha();
        for (Row r : rows) add(d, r);
        return hex(d);
    }

    static String sealHash(String prevSealHash, long fromId, long toId, long rowCount, String blockHash) {
        MessageDigest d = sha();
        d.update((prevSealHash + "|" + fromId + "|" + toId + "|" + rowCount + "|" + blockHash).getBytes(StandardCharsets.UTF_8));
        return hex(d);
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getLong(1), rs.getTimestamp(2).toInstant().getEpochSecond(), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
            rs.getString(9), rs.getString(10), rs.getString(11));
    }

    private static final String ROWS = "SELECT id, at, username, action, entity, entity_id, prev_value, new_value, reason, client_ip, client_info FROM ecis_audit_log";

    // ---- sealing ------------------------------------------------------------------------------------------------

    public boolean installed() {
        return db.allTablesExist("ecis_audit_seal") && db.columnExists("ecis_audit_log", "client_ip");
    }

    public Status status() {
        if (!installed()) return new Status(false, 0, 0, null, 0, nightly);
        Long seals = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_seal", Long.class);
        List<Object[]> last = jdbc.query("SELECT to_id, sealed_at FROM ecis_audit_seal ORDER BY id DESC LIMIT 1", (rs, i) -> new Object[] {rs.getLong(1), rs.getTimestamp(2)});
        long lastTo = last.isEmpty() ? 0 : (Long) last.get(0)[0];
        Long unsealed = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE id > ?", Long.class, lastTo);
        return new Status(true, seals == null ? 0 : seals, lastTo, last.isEmpty() ? null : last.get(0)[1].toString(), unsealed == null ? 0 : unsealed, nightly);
    }

    /** Seals the rows that have settled since the last seal; null when there are none. */
    public Sealed seal(String by) {
        return seal(by, SETTLE);
    }

    /** As {@link #seal(String)}, with the settling time given (tests use none). */
    public synchronized Sealed seal(String by, Duration settle) {
        if (!installed()) return null;
        List<String[]> last = jdbc.query("SELECT to_id, seal_hash FROM ecis_audit_seal ORDER BY id DESC LIMIT 1", (rs, i) -> new String[] {rs.getString(1), rs.getString(2)});
        long lastTo = last.isEmpty() ? 0 : Long.parseLong(last.get(0)[0]);
        String prev = last.isEmpty() ? ZERO : last.get(0)[1];
        // The database's own clock stamped the rows, so it is the one asked what time it is.
        Timestamp dbNow = jdbc.queryForObject("SELECT NOW()", Timestamp.class);
        Timestamp cutoff = Timestamp.from(dbNow.toInstant().minus(settle));
        Long toId = jdbc.queryForObject("SELECT MAX(id) FROM (SELECT id FROM ecis_audit_log WHERE id > ? AND at <= ? ORDER BY id LIMIT " + MAX_BLOCK + ") b", Long.class, lastTo, cutoff);
        if (toId == null) return null;
        MessageDigest block = sha();
        long[] counts = {0, Long.MAX_VALUE};
        jdbc.query(ROWS + " WHERE id > ? AND id <= ? ORDER BY id", (rs) -> {
            Row r = row(rs);
            add(block, r);
            counts[0]++;
            if (counts[1] == Long.MAX_VALUE) counts[1] = r.id();
        }, lastTo, toId);
        if (counts[0] == 0) return null;
        String blockHash = hex(block);
        String sealHash = sealHash(prev, counts[1], toId, counts[0], blockHash);
        jdbc.update("INSERT INTO ecis_audit_seal (from_id, to_id, row_count, block_hash, prev_seal_hash, seal_hash, sealed_by, sealed_at) VALUES (?, ?, ?, ?, ?, ?, ?, NOW())",
            counts[1], toId, counts[0], blockHash, prev, sealHash, by);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM ecis_audit_seal", Long.class);
        return new Sealed(id == null ? 0 : id, counts[1], toId, counts[0], sealHash);
    }

    /** The nightly seal. Does nothing when switched off or when the system is not migrated yet. */
    @Scheduled(cron = "${coop.audit.seal.cron:0 30 2 * * *}")
    void nightly() {
        if (!nightly) return;
        try {
            Sealed s = seal("system");
            if (s != null) LOG.info("Audit log sealed: {} rows (ids {} to {})", s.rows(), s.fromId(), s.toId());
        } catch (RuntimeException e) {
            LOG.error("The nightly audit seal failed", e);
        }
    }

    // ---- checking -----------------------------------------------------------------------------------------------

    /** Re-reads every sealed block and the chain of seals, and reports anything that does not match what was sealed. */
    public Verification verify() {
        if (!installed()) return new Verification(false, 0, 0, 0, List.of("The audit seals are not installed (V045)."));
        List<String> problems = new ArrayList<>();
        List<Object[]> seals = jdbc.query("SELECT id, from_id, to_id, row_count, block_hash, prev_seal_hash, seal_hash FROM ecis_audit_seal ORDER BY id",
            (rs, i) -> new Object[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7)});
        String prev = ZERO;
        long rowsChecked = 0, lastTo = 0;
        for (Object[] s : seals) {
            long id = (Long) s[0], from = (Long) s[1], to = (Long) s[2], count = (Long) s[3];
            String blockHash = (String) s[4], prevHash = (String) s[5], sealHash = (String) s[6];
            if (!prevHash.equals(prev)) problems.add("Seal #" + id + " does not follow the seal before it: a seal was removed or changed.");
            if (!sealHash(prevHash, from, to, count, blockHash).equals(sealHash)) problems.add("Seal #" + id + " has been altered.");
            if (from <= lastTo) problems.add("Seal #" + id + " overlaps the one before it.");
            MessageDigest d = sha();
            long[] n = {0};
            jdbc.query(ROWS + " WHERE id >= ? AND id <= ? ORDER BY id", (rs) -> { add(d, row(rs)); n[0]++; }, from, to);
            rowsChecked += n[0];
            if (n[0] < count) problems.add("Seal #" + id + ": " + (count - n[0]) + " audit row(s) between #" + from + " and #" + to + " have been removed.");
            else if (n[0] > count) problems.add("Seal #" + id + ": " + (n[0] - count) + " audit row(s) appear between #" + from + " and #" + to + " that were not there when it was sealed.");
            else if (!hex(d).equals(blockHash)) problems.add("Seal #" + id + ": at least one audit row between #" + from + " and #" + to + " has been changed.");
            prev = sealHash;
            lastTo = to;
        }
        Long unsealed = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE id > ?", Long.class, lastTo);
        return new Verification(problems.isEmpty(), seals.size(), rowsChecked, unsealed == null ? 0 : unsealed, problems);
    }
}
