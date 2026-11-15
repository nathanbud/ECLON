package ph.coop.core.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Reads the stored "before" and "after" of an audit row as a list of changes: which field, what it was, what it became. Modules store the values in a few shapes, and each is read:
 * <ul>
 *   <li>a JSON object of field to value on both sides: only the fields whose value differs are listed;</li>
 *   <li>a JSON object on one side only (something added or removed): every field, with the other side empty;</li>
 *   <li>an {@code UPDATE:<column>} row (a member's profile change): that one column, before and after as stored;</li>
 *   <li>plain text: one change named "Value".</li>
 * </ul>
 * Nothing is lost: when the values cannot be read as changes the raw text is still shown beside them. Field names are shown in words where they are known. Pure logic, no database.
 */
public final class AuditChanges {

    private AuditChanges() {}

    /** One changed field. {@code field} is the stored name, {@code label} the name in words; a null side means there was no value. */
    public record Change(String field, String label, String before, String after) {}

    /** Decimals are read exactly as written (1.00 stays 1.00), not as floating point. */
    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));

    /** Names in words for fields of the old tables and of the application's own records that show up in the audit trail. */
    private static final Map<String, String> KNOWN = Map.ofEntries(
        // account and loan types (sltype)
        Map.entry("sltypem_desc", "Name"), Map.entry("sltypem_desc2", "Full name"), Map.entry("sltypeint_rate", "Interest rate"), Map.entry("sltypemin_bal", "Minimum balance"),
        Map.entry("sltypemaxbal", "Maximum balance"), Map.entry("sltypedorm_term", "Dormant after"), Map.entry("sltypedorm_perd", "Dormancy counted in"),
        Map.entry("sltypepriority", "Order in lists"), Map.entry("sltypeisdisabled", "Switched off"), Map.entry("sltypepen_rate", "Penalty rate"), Map.entry("sltypesf_rate", "Service fee rate"),
        Map.entry("sltypeterms", "Term"), Map.entry("sltypeterm_perd", "Term counted in"), Map.entry("sltypeppmt_mode", "Principal paid"), Map.entry("sltypeipmt_mode", "Interest paid"),
        Map.entry("sltypeamortype", "Amortization"), Map.entry("sltypemincomkr", "Minimum co-makers"), Map.entry("sltyperequirecollateral", "Needs collateral"),
        Map.entry("sltypendays_1year_intloan", "Days in a year"), Map.entry("sltypend_flag", "Interest days"),
        // chart of accounts (coa)
        Map.entry("coadesc", "Account name"), Map.entry("coaacct_sum", "Under heading"), Map.entry("coacanglonly", "Takes journal entries"), Map.entry("coaisdisabled", "Switched off"),
        // loan deductions (loansldeduc)
        Map.entry("descr", "Description"), Map.entry("deducorder", "Order"), Map.entry("dslc_code", "Member account class"), Map.entry("dslt_code", "Member account type"),
        Map.entry("dsle_code", "Posting"), Map.entry("dacct_code", "Chart account"), Map.entry("slbehavior", "Kind of line"), Map.entry("ishideprinting", "Left off the printed slip"),
        Map.entry("formula", "Formula"),
        // common short names
        Map.entry("glonly", "Takes journal entries"), Map.entry("parent", "Under heading"), Map.entry("summary", "Heading"), Map.entry("disabled", "Switched off"),
        Map.entry("status", "Status"), Map.entry("reason", "Reason"), Map.entry("copiedfrom", "Copied from"), Map.entry("event", "Posting"), Map.entry("number", "Status or drawer no."),
        Map.entry("account", "Account"), Map.entry("lastactivity", "Last deposit or withdrawal"), Map.entry("balanceaccount", "Balance posts to"));

    /** The field's name in words: from {@code extra} (for example the member profile's own labels), then the known names, then the name taken apart. */
    static String label(String field, Function<String, String> extra) {
        if (field == null || field.isEmpty()) return "Value";
        String own = extra == null ? null : extra.apply(field);
        if (own != null) return own;
        String known = KNOWN.get(field.toLowerCase());
        if (known != null) return known;
        // camelCase or snake_case taken apart: minBalance -> Min balance, client_type -> Client type
        String words = field.replace('_', ' ').replaceAll("([a-z0-9])([A-Z])", "$1 $2").trim().toLowerCase();
        return words.isEmpty() ? field : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static JsonNode object(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (!t.startsWith("{")) return null;
        try {
            JsonNode n = JSON.readTree(t);
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** A value as it is shown: text and numbers as they were stored, true and false as Yes and No, anything nested as compact JSON. */
    static String show(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        if (n.isTextual()) return n.asText();
        if (n.isBoolean()) return n.asBoolean() ? "Yes" : "No";
        if (n.isNumber()) return n.decimalValue().toPlainString();
        return n.toString();
    }

    private static boolean same(JsonNode a, JsonNode b) {
        if (a == null || a.isNull()) return b == null || b.isNull();
        if (b == null || b.isNull()) return false;
        if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue()) == 0;
        if (a.isNumber() || b.isNumber()) {
            // 1 and "1" are the same value stored two ways
            try {
                return new BigDecimal(a.asText()).compareTo(new BigDecimal(b.asText())) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return a.equals(b);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** The changes recorded in one audit row. Empty when there is nothing before or after. */
    public static List<Change> of(String action, String prev, String next, Function<String, String> extra) {
        List<Change> out = new ArrayList<>();
        String before = blankToNull(prev), after = blankToNull(next);
        if (before == null && after == null) return out;
        if (action != null && action.startsWith("UPDATE:")) {
            String field = action.substring("UPDATE:".length());
            out.add(new Change(field, label(field, extra), before, after));
            return out;
        }
        JsonNode a = object(before), b = object(after);
        boolean aOk = a != null || before == null, bOk = b != null || after == null;
        if (aOk && bOk) {
            Set<String> fields = new LinkedHashSet<>();
            if (b != null) for (Iterator<String> it = b.fieldNames(); it.hasNext(); ) fields.add(it.next());
            if (a != null) for (Iterator<String> it = a.fieldNames(); it.hasNext(); ) fields.add(it.next());
            boolean both = a != null && b != null;
            for (String f : fields) {
                JsonNode was = a == null ? null : a.get(f), now = b == null ? null : b.get(f);
                if (both && same(was, now)) continue;
                out.add(new Change(f, label(f, extra), show(was), show(now)));
            }
            return out;
        }
        // plain text on either side: one value
        out.add(new Change("", "Value", before, after));
        return out;
    }
}
