package ph.coop.core.setup;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure rules of the starter setup: the catalog of standard lists ({@code starter/lists.txt}), their CSV files (as {@code psql \copy ... CSV HEADER}
 * writes them: an empty field without quotes is a missing value, {@code ""} is an empty text), the conversion of a field to its column's kind, and
 * how two keys are compared. No database here; {@link StarterSetupController} does the reading and adding.
 */
public final class StarterLists {

    private StarterLists() {}

    /** One standard list: its table, the group it is shown under, and what it is in words. */
    public record Entry(String table, String group, String label) {}

    /** The catalog, in load order. Lines starting with # and blank lines are skipped. */
    public static List<Entry> catalog(String text) {
        List<Entry> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String[] p = l.split("\\|", -1);
            if (p.length != 3 || p[0].isBlank()) throw new IllegalArgumentException("A catalog line must be table|group|label: " + l);
            out.add(new Entry(p[0].trim(), p[1].trim(), p[2].trim()));
        }
        return out;
    }

    /** The rows of a CSV file, the header first. A field is null when it was empty and unquoted. */
    public static List<List<String>> csv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false; // inside quotes
        boolean wasQuoted = false; // this field had quotes
        int i = 0;
        int n = text.length();
        if (n > 0 && text.charAt(0) == '﻿') i = 1;
        boolean any = false; // the current row has something
        for (; i < n; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else quoted = false;
                } else field.append(c);
                continue;
            }
            if (c == '"') { quoted = true; wasQuoted = true; any = true; }
            else if (c == ',') { row.add(value(field, wasQuoted)); field.setLength(0); wasQuoted = false; any = true; }
            else if (c == '\r') { /* part of \r\n */ }
            else if (c == '\n') {
                if (any || field.length() > 0) { row.add(value(field, wasQuoted)); rows.add(row); }
                row = new ArrayList<>(); field.setLength(0); wasQuoted = false; any = false;
            } else { field.append(c); any = true; }
        }
        if (quoted) throw new IllegalArgumentException("A quoted field is not closed.");
        if (any || field.length() > 0) { row.add(value(field, wasQuoted)); rows.add(row); }
        return rows;
    }

    private static String value(StringBuilder field, boolean wasQuoted) {
        return field.length() == 0 && !wasQuoted ? null : field.toString();
    }

    /** The field as the kind of value its column takes ({@link Types}); null stays null. */
    public static Object convert(String v, int sqlType) {
        if (v == null) return null;
        String t = v.trim();
        switch (sqlType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT:
                if (t.isEmpty()) return null;
                if (t.equalsIgnoreCase("t") || t.equalsIgnoreCase("true")) return 1L;
                if (t.equalsIgnoreCase("f") || t.equalsIgnoreCase("false")) return 0L;
                return new BigDecimal(t).longValueExact();
            case Types.DECIMAL, Types.NUMERIC, Types.REAL, Types.FLOAT, Types.DOUBLE:
                return t.isEmpty() ? null : new BigDecimal(t);
            case Types.BIT, Types.BOOLEAN:
                if (t.isEmpty()) return null;
                return t.equals("1") || t.equalsIgnoreCase("t") || t.equalsIgnoreCase("true");
            case Types.DATE:
                return t.isEmpty() ? null : Date.valueOf(t.length() > 10 ? t.substring(0, 10) : t);
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE:
                if (t.isEmpty()) return null;
                String s = t.replace('T', ' ');
                if (s.length() > 19) s = s.replaceFirst("(Z|[+-]\\d{2}(:?\\d{2})?)$", ""); // a time zone, as PostgreSQL writes one
                return Timestamp.valueOf(s.length() == 10 ? s + " 00:00:00" : s);
            case Types.TIME:
                return t.isEmpty() ? null : Time.valueOf(t);
            default:
                return v;
        }
    }

    /** One value as it is compared inside a key: numbers without trailing zeros, text trimmed and without regard to case, as the databases compare keys. */
    public static String keyPart(Object v) {
        if (v == null) return "\u0000";
        if (v instanceof BigDecimal b) return b.signum() == 0 ? "0" : b.stripTrailingZeros().toPlainString();
        if (v instanceof Number num) return new BigDecimal(num.toString()).stripTrailingZeros().toPlainString();
        if (v instanceof Boolean b) return b ? "1" : "0";
        return v.toString().trim().toLowerCase(Locale.ROOT);
    }

    /** A key of several values as one text. */
    public static String key(List<?> values) {
        StringBuilder sb = new StringBuilder();
        for (Object v : values) sb.append(keyPart(v)).append('\u0001');
        return sb.toString();
    }
}
