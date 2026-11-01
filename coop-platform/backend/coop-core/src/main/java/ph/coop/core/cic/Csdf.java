package ph.coop.core.cic;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * The shape of a CSDF line (the CIC's standard data format): fields separated by a pipe, the first field the record type, columns named by spreadsheet letters (A is the type,
 * B the provider code, and so on) as the CIC's workbook names them. {@link Rec} builds one line; the helpers write dates and numbers the way the CIC file does.
 */
final class Csdf {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("ddMMyyyy");

    private Csdf() {}

    /** The number of a column letter: A is 1, Z is 26, AA is 27. */
    static int col(String letters) {
        int n = 0;
        for (char c : letters.toCharArray()) n = n * 26 + (c - 'A' + 1);
        return n;
    }

    /** The letters of a column number, the other way round. */
    static String letters(int n) {
        StringBuilder s = new StringBuilder();
        while (n > 0) {
            int r = (n - 1) % 26;
            s.insert(0, (char) ('A' + r));
            n = (n - 1) / 26;
        }
        return s.toString();
    }

    /** A value as it goes into a field: no pipe or line break (they would break the line), no space at either end. Null is an empty field. */
    static String clean(String v) {
        if (v == null) return "";
        return v.replace('|', ' ').replace('\r', ' ').replace('\n', ' ').trim();
    }

    static String date(LocalDate d) {
        return d == null ? "" : d.format(DATE);
    }

    /** Digits only: phone and identification numbers are written without spaces, hyphens or brackets. */
    static String digits(String v) {
        return v == null ? "" : v.replaceAll("[^0-9]", "");
    }

    /** One record: a fixed number of fields, the first being its type. */
    static final class Rec {
        final String type;
        final String[] fields;

        Rec(String type, int width) {
            this.type = type;
            this.fields = new String[width];
            java.util.Arrays.fill(fields, "");
            fields[0] = type;
        }

        Rec set(String letters, String value) {
            int i = col(letters) - 1;
            if (i < 0 || i >= fields.length) throw new IllegalArgumentException("column " + letters + " is outside a " + type + " record of " + fields.length + " columns");
            fields[i] = clean(value);
            return this;
        }

        String get(String letters) {
            return fields[col(letters) - 1];
        }

        String line() {
            return String.join("|", fields);
        }
    }

    /** The fields of a stored line, empty fields kept. */
    static String[] parse(String line) {
        return line.split("\\|", -1);
    }
}
