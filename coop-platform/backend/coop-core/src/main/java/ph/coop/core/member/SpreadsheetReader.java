package ph.coop.core.member;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads a list of members from a CSV or Excel (.xlsx) file into rows keyed by the header names. Deliberately small and dependency free: the first sheet only, values as text,
 * Excel date serials left as numbers (the importer turns them into dates for date columns). Pure logic, no database access.
 */
final class SpreadsheetReader {

    private SpreadsheetReader() {}

    static final int MAX_ROWS = 5000;
    static final int MAX_BYTES = 5 * 1024 * 1024;

    /** One data row: its line number in the file (the header is line 1) and its values by header name. */
    record Row(int line, Map<String, String> values) {}

    static List<Row> read(String fileName, byte[] data) {
        if (data == null || data.length == 0) throw new IllegalArgumentException("The file is empty.");
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("The file is larger than 5 MB.");
        List<List<String>> grid = fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx") ? xlsx(data) : csv(new String(strip(data), StandardCharsets.UTF_8));
        return rows(grid);
    }

    private static byte[] strip(byte[] d) {
        // a UTF-8 byte order mark would stick to the first header name
        return d.length >= 3 && (d[0] & 0xFF) == 0xEF && (d[1] & 0xFF) == 0xBB && (d[2] & 0xFF) == 0xBF ? java.util.Arrays.copyOfRange(d, 3, d.length) : d;
    }

    static List<Row> rows(List<List<String>> grid) {
        if (grid.isEmpty()) throw new IllegalArgumentException("The file has no rows.");
        List<String> header = new ArrayList<>();
        for (String h : grid.get(0)) header.add(h == null ? "" : h.trim());
        if (header.stream().allMatch(String::isBlank)) throw new IllegalArgumentException("The first row must be the column names.");
        List<Row> out = new ArrayList<>();
        for (int i = 1; i < grid.size(); i++) {
            List<String> cells = grid.get(i);
            Map<String, String> values = new LinkedHashMap<>();
            boolean any = false;
            for (int c = 0; c < header.size(); c++) {
                String name = header.get(c);
                String v = c < cells.size() && cells.get(c) != null ? cells.get(c).trim() : "";
                if (!name.isBlank() && !v.isEmpty()) { values.put(name, v); any = true; }
            }
            if (!any) continue; // a blank line
            if (out.size() >= MAX_ROWS) throw new IllegalArgumentException("More than " + MAX_ROWS + " rows: split the list into smaller files.");
            out.add(new Row(i + 1, values));
        }
        return out;
    }

    /** CSV with quotes ("" inside quotes is a quote), commas or semicolons, and line breaks inside quoted values. */
    static List<List<String>> csv(String text) {
        char delimiter = text.lines().findFirst().map((l) -> l.chars().filter((c) -> c == ';').count() > l.chars().filter((c) -> c == ',').count() ? ';' : ',').orElse(',');
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; } else quoted = false;
                } else cell.append(ch);
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == delimiter) {
                row.add(cell.toString()); cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0);
                out.add(row); row = new ArrayList<>();
            } else cell.append(ch);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); out.add(row); }
        return out;
    }

    // ---- xlsx ---------------------------------------------------------------------------------------------------

    private static List<List<String>> xlsx(byte[] data) {
        try {
            List<String> shared = new ArrayList<>();
            byte[] sheet = null;
            String sheetName = null;
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
                ZipEntry e;
                long total = 0;
                while ((e = zip.getNextEntry()) != null) {
                    String n = e.getName();
                    if (!n.equals("xl/sharedStrings.xml") && !n.startsWith("xl/worksheets/sheet")) continue;
                    byte[] bytes = zip.readNBytes(20 * 1024 * 1024);
                    total += bytes.length;
                    if (total > 40L * 1024 * 1024) throw new IllegalArgumentException("The spreadsheet is too large when unpacked.");
                    if (n.equals("xl/sharedStrings.xml")) shared = sharedStrings(bytes);
                    else if (sheet == null || n.compareTo(sheetName) < 0) { sheet = bytes; sheetName = n; }
                }
            }
            if (sheet == null) throw new IllegalArgumentException("No worksheet was found in the file.");
            return sheetRows(sheet, shared);
        } catch (IOException | XMLStreamException e) {
            throw new IllegalArgumentException("The file could not be read as an Excel workbook (.xlsx).");
        }
    }

    private static XMLInputFactory factory() {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f;
    }

    private static List<String> sharedStrings(byte[] xml) throws XMLStreamException {
        List<String> out = new ArrayList<>();
        XMLStreamReader r = factory().createXMLStreamReader(new ByteArrayInputStream(xml));
        StringBuilder cur = null;
        boolean inText = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String n = r.getLocalName();
                if (n.equals("si")) cur = new StringBuilder();
                else if (n.equals("t") && cur != null) inText = true;
            } else if (ev == XMLStreamConstants.CHARACTERS && inText && cur != null) {
                cur.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String n = r.getLocalName();
                if (n.equals("t")) inText = false;
                else if (n.equals("si") && cur != null) { out.add(cur.toString()); cur = null; }
            }
        }
        return out;
    }

    private static int column(String ref) {
        int c = 0;
        for (int i = 0; i < ref.length() && Character.isLetter(ref.charAt(i)); i++) c = c * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
        return c - 1;
    }

    private static List<List<String>> sheetRows(byte[] xml, List<String> shared) throws XMLStreamException {
        List<List<String>> out = new ArrayList<>();
        XMLStreamReader r = factory().createXMLStreamReader(new ByteArrayInputStream(xml));
        List<String> row = null;
        String type = null;
        int col = 0;
        StringBuilder value = null;
        boolean inValue = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "row" -> row = new ArrayList<>();
                    case "c" -> {
                        String ref = r.getAttributeValue(null, "r");
                        col = ref == null ? (row == null ? 0 : row.size()) : column(ref);
                        type = r.getAttributeValue(null, "t");
                        value = new StringBuilder();
                    }
                    case "v", "t" -> inValue = value != null;
                    default -> { }
                }
            } else if (ev == XMLStreamConstants.CHARACTERS && inValue && value != null) {
                value.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                switch (r.getLocalName()) {
                    case "v", "t" -> inValue = false;
                    case "c" -> {
                        if (row != null && value != null) {
                            while (row.size() < col) row.add("");
                            String v = value.toString();
                            if ("s".equals(type)) {
                                try { v = shared.get(Integer.parseInt(v.trim())); } catch (RuntimeException ignored) { v = ""; }
                            }
                            row.add(v);
                        }
                        value = null;
                    }
                    case "row" -> { if (row != null) out.add(row); row = null; }
                    default -> { }
                }
            }
        }
        return out;
    }

    /** An Excel date serial (days since 1899-12-30) as an ISO date, or null when the text is not a plausible serial. */
    static String excelDate(String text) {
        try {
            double d = Double.parseDouble(text);
            if (d < 1 || d > 80000) return null;
            return LocalDate.of(1899, 12, 30).plusDays((long) Math.floor(d)).toString();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
