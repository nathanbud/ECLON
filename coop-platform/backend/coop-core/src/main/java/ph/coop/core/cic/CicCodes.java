package ph.coop.core.cic;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The CIC's code lists (titles, civil statuses, identification types, contract types and so on), read once from {@code cic/csdf-codes.tsv}. That file is made from the CIC's workbook
 * by {@code scripts/gen-cic-codes.py}; when the CIC sends a new workbook, run the script and replace the file. Used to check that a value in a record is one the CIC knows.
 */
final class CicCodes {

    private static volatile CicCodes instance;

    private final Map<String, Map<String, String>> lists = new HashMap<>();

    private CicCodes() {}

    static CicCodes get() {
        CicCodes c = instance;
        if (c == null) {
            synchronized (CicCodes.class) {
                if (instance == null) instance = load();
                c = instance;
            }
        }
        return c;
    }

    private static CicCodes load() {
        CicCodes c = new CicCodes();
        try (InputStream in = CicCodes.class.getResourceAsStream("/cic/csdf-codes.tsv")) {
            if (in == null) throw new IllegalStateException("The CIC code lists (cic/csdf-codes.tsv) are missing from the application.");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split("\t", -1);
                    if (p.length < 4) continue;
                    c.lists.computeIfAbsent(p[0] + "/" + p[1], (k) -> new HashMap<>()).put(p[2], p[3]);
                    // "1-Single": the file writes it as 1, the workbook as 1-Single; accept both
                    int dash = p[2].indexOf('-');
                    if (dash > 0 && p[2].substring(0, dash).chars().allMatch(Character::isDigit)) c.lists.get(p[0] + "/" + p[1]).putIfAbsent(p[2].substring(0, dash), p[3]);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("The CIC code lists could not be read.", e);
        }
        return c;
    }

    /** Whether the list (named by the record type of its sheet and its name in the workbook, such as {@code ID/IdentificationTypeDomain}) holds the value. */
    boolean has(String list, String value) {
        Map<String, String> l = lists.get(list);
        return l != null && l.containsKey(value);
    }

    String describe(String list, String value) {
        Map<String, String> l = lists.get(list);
        return l == null ? null : l.get(value);
    }

    Set<String> values(String list) {
        Map<String, String> l = lists.get(list);
        return l == null ? Set.of() : new HashSet<>(l.keySet());
    }

    int size(String list) {
        Map<String, String> l = lists.get(list);
        return l == null ? 0 : l.size();
    }
}
