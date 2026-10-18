package ph.coop.core.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

/**
 * The only place that knows how MySQL and PostgreSQL differ, so the rest of the code can stay in portable SQL.
 * Everything else must avoid: LAST_INSERT_ID(), ON DUPLICATE KEY, INSERT IGNORE, GROUP_CONCAT, YEAR() and friends,
 * information_schema with DATABASE(), and backticks. The PostgreSQL branches are written from the documentation and
 * have not been run against a real PostgreSQL yet.
 */
@Component
public class Db {

    private final JdbcTemplate jdbc;
    /** Tables and columns that exist cannot stop existing, so a positive answer is kept. */
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    /** Privileges are checked often (the handover window polls), so a yes is kept for a minute. */
    private final Map<String, Long> grantsOkUntil = new ConcurrentHashMap<>();

    public Db(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- what exists --------------------------------------------------------------------------------------------

    public boolean tableExists(String table) {
        String key = "t:" + table.toLowerCase();
        if (seen.contains(key)) return true;
        boolean found = jdbc.execute((ConnectionCallback<Boolean>) (c) -> {
            try (ResultSet rs = c.getMetaData().getTables(c.getCatalog(), c.getSchema(), "%", new String[]{"TABLE", "BASE TABLE"})) {
                while (rs.next()) if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) return true;
            }
            return false;
        });
        if (found) seen.add(key);
        return found;
    }

    public boolean allTablesExist(String... tables) {
        for (String t : tables) if (!tableExists(t)) return false;
        return true;
    }

    public boolean columnExists(String table, String column) {
        String key = "c:" + table.toLowerCase() + "." + column.toLowerCase();
        if (seen.contains(key)) return true;
        boolean found = jdbc.execute((ConnectionCallback<Boolean>) (c) -> {
            try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), c.getSchema(), "%", "%")) {
                while (rs.next()) {
                    if (table.equalsIgnoreCase(rs.getString("TABLE_NAME")) && column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) return true;
                }
            }
            return false;
        });
        if (found) seen.add(key);
        return found;
    }

    /** Declared length of each text column of a table (column name as the database spells it, in table order). */
    public Map<String, Integer> textColumnLengths(String table) {
        return jdbc.execute((ConnectionCallback<Map<String, Integer>>) (c) -> {
            Map<String, Integer> out = new LinkedHashMap<>();
            try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), c.getSchema(), "%", "%")) {
                while (rs.next()) {
                    if (!table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) continue;
                    int type = rs.getInt("DATA_TYPE");
                    boolean text = type == java.sql.Types.CHAR || type == java.sql.Types.VARCHAR || type == java.sql.Types.LONGVARCHAR || type == java.sql.Types.NCHAR || type == java.sql.Types.NVARCHAR;
                    if (text) out.put(rs.getString("COLUMN_NAME"), rs.getInt("COLUMN_SIZE"));
                }
            }
            return out;
        });
    }

    /** True when the signed-in database user may INSERT into every one of the tables. */
    public boolean canInsertInto(String... tables) {
        String key = String.join(",", tables);
        Long until = grantsOkUntil.get(key);
        if (until != null && until > System.currentTimeMillis()) return true;
        boolean postgres = isPostgres();
        for (String t : tables) {
            Integer n = postgres
                ? jdbc.queryForObject("SELECT CASE WHEN has_table_privilege(?, 'INSERT') THEN 1 ELSE 0 END", Integer.class, t)
                : jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_PRIVILEGES WHERE TABLE_SCHEMA = DATABASE() AND PRIVILEGE_TYPE = 'INSERT' AND TABLE_NAME = ?"
                    + " AND REPLACE(GRANTEE, '''', '') = CURRENT_USER()", Integer.class, t);
            if (n == null || n == 0) return false;
        }
        grantsOkUntil.put(key, System.currentTimeMillis() + 60_000);
        return true;
    }

    private boolean isPostgres() {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) (c) -> c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres")));
    }

    // ---- writing ------------------------------------------------------------------------------------------------

    /**
     * The next control number for a legacy transaction type (they run on across years). Reads the highest number of the year
     * with a row lock, so two people cannot take the same one and only one row is locked; the first posting of a new year
     * falls back to the highest of earlier years. Must run inside the posting transaction. Uses ORDER BY ... LIMIT 1 FOR UPDATE
     * (valid on MySQL and PostgreSQL) instead of MAX(...) FOR UPDATE, which PostgreSQL refuses.
     */
    public long nextControlNumber(int branch, int module, int code, int year) {
        List<Long> top = jdbc.queryForList(
            "SELECT TR_CTLNO FROM transsum WHERE TR_BRCODE = ? AND TR_YEAR = ? AND TR_MODULE = ? AND TR_CODE = ? ORDER BY TR_CTLNO DESC LIMIT 1 FOR UPDATE",
            Long.class, branch, year, module, code);
        if (!top.isEmpty()) return top.get(0) + 1;
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(TR_CTLNO), 0) FROM transsum WHERE TR_BRCODE = ? AND TR_MODULE = ? AND TR_CODE = ?", Long.class, branch, module, code);
        return (max == null ? 0 : max) + 1;
    }

    /** Runs an INSERT and returns the generated value of {@code idColumn} (works with MySQL and PostgreSQL). */
    public long insertReturningId(String sql, String idColumn, Object... args) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update((Connection c) -> {
            PreparedStatement ps = c.prepareStatement(sql, new String[]{idColumn});
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keys);
        Number k = keys.getKey();
        if (k == null) throw new IllegalStateException("The insert returned no key");
        return k.longValue();
    }

    /** Updates the row with these key values, or inserts it when there is none (replaces ON DUPLICATE KEY UPDATE). */
    public void upsert(String table, Map<String, Object> key, Map<String, Object> values) {
        if (!update(table, key, values)) {
            try {
                insert(table, key, values);
            } catch (DuplicateKeyException raced) {
                update(table, key, values); // someone else inserted it between our update and insert
            }
        }
    }

    private boolean update(String table, Map<String, Object> key, Map<String, Object> values) {
        List<Object> args = new ArrayList<>(values.values());
        args.addAll(key.values());
        String set = String.join(", ", values.keySet().stream().map((k) -> k + " = ?").toList());
        String where = String.join(" AND ", key.keySet().stream().map((k) -> k + " = ?").toList());
        return jdbc.update("UPDATE " + table + " SET " + set + " WHERE " + where, args.toArray()) > 0;
    }

    private void insert(String table, Map<String, Object> key, Map<String, Object> values) {
        Map<String, Object> all = new LinkedHashMap<>(key);
        all.putAll(values);
        String cols = String.join(", ", all.keySet());
        String marks = String.join(", ", all.keySet().stream().map((k) -> "?").toList());
        jdbc.update("INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ")", all.values().toArray());
    }
}
