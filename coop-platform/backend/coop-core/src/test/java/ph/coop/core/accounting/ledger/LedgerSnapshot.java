package ph.coop.core.accounting.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Characterization ("golden") snapshots of what a posting writes into the old system's ledger: every column of every row of the voucher's header
 * ({@code transsum}), lines ({@code transdt}), subsidiary ledger ({@code sldtl}) and general ledger ({@code gltrans}), as text. Columns that change on
 * every run (time stamps, generated row ids) are left out, and values that differ per run (the control number, people's ids) are written as
 * placeholders. A flow moved onto the posting engine must give exactly the snapshot it gave before (see documentation/design/posting-engine.md).
 *
 * <p>The first run writes {@code src/test/resources/ledger-golden/<name>.txt} and fails so it can be read and committed; later runs compare.
 */
public final class LedgerSnapshot {

    private static final Set<String> VOLATILE = Set.of("tr_time", "datetimeadded", "gltranslastupdated", "gltransid", "id", "sldtl_id", "transdt_id", "dtupd_datetime");

    private LedgerSnapshot() {}

    /** What identifies the rows of one posting. The general ledger has no voucher number, so it is found by branch, date, transaction code and accounts. */
    public record Where(int branch, int year, int module, int code, long ctl, java.time.LocalDate glDate, int glCode, List<String> glAccounts) {}

    /** A value written as {name} where it appears in one of these columns (anywhere else, or with another value, it is written as it is). */
    public record Placeholder(String name, Object value, Set<String> columns) {}

    public static Placeholder ph(String name, Object value, String... columns) {
        Set<String> c = new java.util.HashSet<>();
        for (String x : columns) c.add(x.toLowerCase());
        return new Placeholder(name, value, c);
    }

    public static String take(JdbcTemplate jdbc, Where w, List<Placeholder> placeholders) {
        StringBuilder out = new StringBuilder();
        section(out, "transsum", jdbc.queryForList("SELECT * FROM transsum WHERE TR_BRCODE = ? AND TR_YEAR = ? AND TR_MODULE = ? AND TR_CODE = ? AND TR_CTLNO = ?",
            w.branch(), w.year(), w.module(), w.code(), w.ctl()), placeholders);
        section(out, "transdt", jdbc.queryForList("SELECT * FROM transdt WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = ? AND DTTR_CODE = ? AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO",
            w.branch(), w.year(), w.module(), w.code(), w.ctl()), placeholders);
        section(out, "sldtl", jdbc.queryForList("SELECT * FROM sldtl WHERE SL_BRCODE = ? AND TR_DATE = ? AND TR_CODE = ? AND TR_CTLNO = ? ORDER BY SLC_CODE, SLT_CODE, REF_NO, SLE_CODE, SEQNO",
            w.branch(), w.glDate(), w.code(), w.ctl()), placeholders);
        List<Map<String, Object>> gl = new ArrayList<>();
        for (String acct : w.glAccounts()) {
            gl.addAll(jdbc.queryForList("SELECT * FROM gltrans WHERE GLTransBR_CODE = ? AND GLTransTR_DATE = ? AND GLTransTR_CODE = ? AND GLTransACCT_CODE = ? ORDER BY GLTransDEPT, GLTransDR_AMT, GLTransCR_AMT",
                w.branch(), w.glDate(), w.glCode(), acct));
        }
        section(out, "gltrans", gl, placeholders);
        return out.toString();
    }

    private static void section(StringBuilder out, String table, List<Map<String, Object>> rows, List<Placeholder> placeholders) {
        out.append("## ").append(table).append(" (").append(rows.size()).append(" rows)\n");
        for (Map<String, Object> r : rows) {
            Map<String, Object> sorted = new TreeMap<>();
            r.forEach((k, v) -> { if (!VOLATILE.contains(k.toLowerCase())) sorted.put(k.toLowerCase(), v); });
            List<String> cells = new ArrayList<>();
            sorted.forEach((k, v) -> cells.add(k + "=" + text(k, v, placeholders)));
            out.append(String.join(" | ", cells)).append('\n');
        }
    }

    private static String text(String column, Object v, List<Placeholder> placeholders) {
        if (v == null) return "null";
        String s = v instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : v.toString().trim();
        for (Placeholder p : placeholders) if (p.columns().contains(column) && String.valueOf(p.value()).equals(s)) return "{" + p.name() + "}";
        return s;
    }

    /** Columns that carry the data rather than the format: left out of a {@link #shape}. */
    private static final Set<String> DATA = Set.of("dtacct_code", "dtdept", "dtamt", "dtseq_no", "gltransacct_code", "gltransdept", "gltransdr_amt", "gltranscr_amt",
        "explanation", "tr_date", "dtsl_date", "gltranstr_date", "batch_no", "tr_year", "dttr_year");

    /**
     * The format of a posting whose figures depend on live data (the year-end close of a real year): every distinct row once, without the
     * account, department, amount, sequence and dates, and for a general-ledger row whether it is a debit (D), a credit (C) or zero (0).
     */
    public static String shape(JdbcTemplate jdbc, Where w, List<Placeholder> placeholders) {
        StringBuilder out = new StringBuilder();
        shapeSection(out, "transsum", jdbc.queryForList("SELECT * FROM transsum WHERE TR_BRCODE = ? AND TR_YEAR = ? AND TR_MODULE = ? AND TR_CODE = ? AND TR_CTLNO = ?",
            w.branch(), w.year(), w.module(), w.code(), w.ctl()), placeholders);
        shapeSection(out, "transdt", jdbc.queryForList("SELECT * FROM transdt WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = ? AND DTTR_CODE = ? AND DTTR_CTLNO = ?",
            w.branch(), w.year(), w.module(), w.code(), w.ctl()), placeholders);
        shapeSection(out, "gltrans", jdbc.queryForList("SELECT * FROM gltrans WHERE GLTransBR_CODE = ? AND GLTransTR_DATE = ? AND GLTransTR_CODE = ?",
            w.branch(), w.glDate(), w.glCode()), placeholders);
        return out.toString();
    }

    private static void shapeSection(StringBuilder out, String table, List<Map<String, Object>> rows, List<Placeholder> placeholders) {
        java.util.TreeSet<String> distinct = new java.util.TreeSet<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> sorted = new TreeMap<>();
            r.forEach((k, v) -> { String c = k.toLowerCase(); if (!VOLATILE.contains(c) && !DATA.contains(c)) sorted.put(c, v); });
            if (table.equals("gltrans")) {
                BigDecimal dr = (BigDecimal) r.get("gltransdr_amt"), cr = (BigDecimal) r.get("gltranscr_amt");
                sorted.put("side", dr != null && dr.signum() > 0 ? "D" : cr != null && cr.signum() < 0 ? "C" : "0");
            }
            List<String> cells = new ArrayList<>();
            sorted.forEach((k, v) -> cells.add(k + "=" + text(k, v, placeholders)));
            distinct.add(String.join(" | ", cells));
        }
        out.append("## ").append(table).append(rows.isEmpty() ? " (none)" : "").append('\n');
        distinct.forEach((d) -> out.append(d).append('\n'));
    }

    /** Compares with the golden file, writing it on the first run. */
    public static void assertMatches(String name, String snapshot) {
        Path file = Path.of("src/test/resources/ledger-golden", name + ".txt");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, snapshot, StandardCharsets.UTF_8);
                fail("Wrote the golden snapshot " + file + " from the current code. Read it, commit it, and run again.");
            }
            assertEquals(Files.readString(file, StandardCharsets.UTF_8), snapshot, "the rows written differ from " + file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

}
