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
public class Db implements ph.coop.core.connection.OnConnectionSwitch {

    /** Databases that belong to the database server itself (MySQL and PostgreSQL), never offered as the cooperative's own. */
    public static final List<String> SERVER_OWN_DATABASES = List.of("information_schema", "mysql", "performance_schema", "sys", "postgres", "template0", "template1");

    private final JdbcTemplate jdbc;
    /** Tables and columns that exist cannot stop existing, so a positive answer is kept. */
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    /** Privileges are checked often (the handover window polls), so a yes is kept for a minute. */
    private final Map<String, Long> grantsOkUntil = new ConcurrentHashMap<>();

    /** A table that exists in one database may not exist in another, so what was learned is forgotten when the database is switched. */
    @Override
    public void connectionSwitched() {
        seen.clear();
        grantsOkUntil.clear();
    }

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
            // PostgreSQL spells every column in lowercase while the code asks for LName, FName ...: look up without regard to case, in table order
            Map<String, Integer> out = new org.springframework.util.LinkedCaseInsensitiveMap<>();
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

    public record ColumnInfo(String name, int sqlType) {}

    public record ForeignKey(List<String> columns, String parent, List<String> parentColumns) {}

    public record TableLayout(String name, List<ColumnInfo> columns, List<String> key, List<ForeignKey> links) {}

    /**
     * The columns (in order, with their kind as {@link java.sql.Types}), primary key and links of the tables, keyed by the table's name in lowercase;
     * a table that is not there is left out. Three queries in all, however many tables (the driver's metadata calls take one or more per table).
     */
    public Map<String, TableLayout> tableLayouts(java.util.Collection<String> tables) {
        Map<String, TableLayout> out = new LinkedHashMap<>();
        if (tables.isEmpty()) return out;
        List<String> names = tables.stream().map((t) -> t.toLowerCase(java.util.Locale.ROOT)).distinct().toList();
        String in = String.join(", ", names.stream().map((t) -> "?").toList());
        Object[] args = names.toArray();
        boolean postgres = isPostgres();
        String schema = postgres ? "current_schema()" : "DATABASE()";
        Map<String, String> exact = new LinkedHashMap<>();
        Map<String, List<ColumnInfo>> cols = new LinkedHashMap<>();
        jdbc.query("SELECT table_name, column_name, data_type FROM information_schema.columns WHERE table_schema = " + schema + " AND LOWER(table_name) IN (" + in + ")"
            + " ORDER BY table_name, ordinal_position", (org.springframework.jdbc.core.RowCallbackHandler) (rs) -> {
                String t = rs.getString(1);
                exact.put(t.toLowerCase(java.util.Locale.ROOT), t);
                cols.computeIfAbsent(t.toLowerCase(java.util.Locale.ROOT), (x) -> new ArrayList<>()).add(new ColumnInfo(rs.getString(2), sqlType(rs.getString(3))));
            }, args);
        Map<String, List<String>> keys = new LinkedHashMap<>();
        jdbc.query("SELECT k.table_name, k.column_name FROM information_schema.table_constraints c JOIN information_schema.key_column_usage k"
            + " ON k.constraint_name = c.constraint_name AND k.table_schema = c.table_schema AND k.table_name = c.table_name"
            + " WHERE c.constraint_type = 'PRIMARY KEY' AND c.table_schema = " + schema + " AND LOWER(c.table_name) IN (" + in + ") ORDER BY k.table_name, k.ordinal_position",
            (org.springframework.jdbc.core.RowCallbackHandler) (rs) -> keys.computeIfAbsent(rs.getString(1).toLowerCase(java.util.Locale.ROOT), (x) -> new ArrayList<>()).add(rs.getString(2)), args);
        Map<String, Map<String, ForeignKey>> links = new LinkedHashMap<>();
        String fkSql = postgres
            ? "SELECT c.conname, cl.relname, pc.relname, a.attname, pa.attname FROM pg_constraint c JOIN pg_class cl ON cl.oid = c.conrelid"
                + " JOIN pg_namespace n ON n.oid = cl.relnamespace JOIN pg_class pc ON pc.oid = c.confrelid"
                + " CROSS JOIN LATERAL unnest(c.conkey, c.confkey) WITH ORDINALITY AS k(ck, pk, ord)"
                + " JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.ck JOIN pg_attribute pa ON pa.attrelid = c.confrelid AND pa.attnum = k.pk"
                + " WHERE c.contype = 'f' AND n.nspname = current_schema() AND cl.relname IN (" + in + ") ORDER BY 2, 1, k.ord"
            : "SELECT CONSTRAINT_NAME, TABLE_NAME, REFERENCED_TABLE_NAME, COLUMN_NAME, REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE"
                + " WHERE TABLE_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL AND LOWER(TABLE_NAME) IN (" + in + ") ORDER BY 2, 1, ORDINAL_POSITION";
        jdbc.query(fkSql, (org.springframework.jdbc.core.RowCallbackHandler) (rs) -> {
            Map<String, ForeignKey> byName = links.computeIfAbsent(rs.getString(2).toLowerCase(java.util.Locale.ROOT), (x) -> new LinkedHashMap<>());
            ForeignKey fk = byName.computeIfAbsent(rs.getString(1), (x) -> {
                try {
                    return new ForeignKey(new ArrayList<>(), rs.getString(3), new ArrayList<>());
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            fk.columns().add(rs.getString(4));
            fk.parentColumns().add(rs.getString(5));
        }, args);
        for (Map.Entry<String, String> e : exact.entrySet()) {
            String t = e.getKey();
            out.put(t, new TableLayout(e.getValue(), cols.get(t), keys.getOrDefault(t, List.of()), new ArrayList<>(links.getOrDefault(t, Map.of()).values())));
        }
        return out;
    }

    /** A column's kind as {@link java.sql.Types}, from the data_type name information_schema gives (PostgreSQL and MySQL spellings). */
    static int sqlType(String dataType) {
        String d = dataType == null ? "" : dataType.toLowerCase(java.util.Locale.ROOT);
        if (d.equals("boolean") || d.equals("bit")) return java.sql.Types.BOOLEAN;
        if (d.contains("int")) return java.sql.Types.BIGINT;
        if (d.equals("numeric") || d.equals("decimal") || d.equals("real") || d.startsWith("double") || d.equals("float")) return java.sql.Types.NUMERIC;
        if (d.equals("date")) return java.sql.Types.DATE;
        if (d.startsWith("timestamp") || d.equals("datetime")) return java.sql.Types.TIMESTAMP;
        if (d.startsWith("time")) return java.sql.Types.TIME;
        return java.sql.Types.VARCHAR;
    }

    /** True when the signed-in database user may INSERT into every one of the tables. */
    public boolean canInsertInto(String... tables) {
        String key = String.join(",", tables);
        Long until = grantsOkUntil.get(key);
        if (until != null && until > System.currentTimeMillis()) return true;
        boolean postgres = isPostgres();
        for (String t : tables) {
            Integer n = postgres
                ? jdbc.queryForObject("SELECT CASE WHEN to_regclass(?) IS NULL THEN 0 WHEN has_table_privilege(?, 'INSERT') THEN 1 ELSE 0 END", Integer.class, t.toLowerCase(), t.toLowerCase())
                : jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_PRIVILEGES WHERE TABLE_SCHEMA = DATABASE() AND PRIVILEGE_TYPE = 'INSERT' AND TABLE_NAME = ?"
                    + " AND REPLACE(GRANTEE, '''', '') = CURRENT_USER()", Integer.class, t);
            if (n == null || n == 0) return false;
        }
        grantsOkUntil.put(key, System.currentTimeMillis() + 60_000);
        return true;
    }

    /** True when the signed-in database user may UPDATE the column (a column grant or a grant on the whole table). */
    public boolean canUpdateColumn(String table, String column) {
        if (isPostgres()) {
            Integer n = jdbc.queryForObject("SELECT CASE WHEN to_regclass(?) IS NULL THEN 0 WHEN has_column_privilege(?, ?, 'UPDATE') THEN 1 ELSE 0 END", Integer.class, table.toLowerCase(), table.toLowerCase(), column.toLowerCase());
            return n != null && n > 0;
        }
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMN_PRIVILEGES WHERE TABLE_SCHEMA = DATABASE() AND PRIVILEGE_TYPE = 'UPDATE' AND TABLE_NAME = ? AND COLUMN_NAME = ?"
            + " AND REPLACE(GRANTEE, '''', '') = CURRENT_USER()", Integer.class, table, column);
        Integer t = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_PRIVILEGES WHERE TABLE_SCHEMA = DATABASE() AND PRIVILEGE_TYPE = 'UPDATE' AND TABLE_NAME = ?"
            + " AND REPLACE(GRANTEE, '''', '') = CURRENT_USER()", Integer.class, table);
        return (c != null && c > 0) || (t != null && t > 0);
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
        String column = isPostgres() ? idColumn.toLowerCase() : idColumn; // PostgreSQL takes a quoted-as-given name literally, and its columns are lowercase
        jdbc.update((Connection c) -> {
            PreparedStatement ps = c.prepareStatement(sql, new String[]{column});
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
