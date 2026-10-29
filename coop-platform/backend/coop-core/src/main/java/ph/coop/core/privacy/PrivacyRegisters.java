package ph.coop.core.privacy;

import static ph.coop.core.web.ApiErrors.bad;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The simple privacy registers, each described once: which columns it has, which are required, which take only certain values, how long text may be. One validator reads these
 * descriptions, so every register checks its input the same way and an unknown field is refused rather than ignored. Pure logic, no database access.
 */
final class PrivacyRegisters {

    private PrivacyRegisters() {}

    enum Type { TEXT, LONGTEXT, DATE, DATETIME, INT, BOOL, ENUM }

    /** One column of a register. {@code max} is the length limit of text; {@code options} the allowed values of an ENUM. */
    record Col(String name, String label, Type type, boolean required, int max, List<String> options) {}

    /** One register: its table, the prefix of its reference number (null when it has none), its columns and what it lists by. */
    record Reg(String key, String title, String table, String refPrefix, List<Col> cols, String orderBy) {
        Col col(String name) {
            return cols.stream().filter((c) -> c.name().equals(name)).findFirst().orElse(null);
        }
    }

    private static Col text(String n, String label, boolean req, int max) { return new Col(n, label, Type.TEXT, req, max, List.of()); }
    private static Col longText(String n, String label, boolean req) { return new Col(n, label, Type.LONGTEXT, req, 4000, List.of()); }
    private static Col date(String n, String label, boolean req) { return new Col(n, label, Type.DATE, req, 0, List.of()); }
    private static Col dateTime(String n, String label, boolean req) { return new Col(n, label, Type.DATETIME, req, 0, List.of()); }
    private static Col number(String n, String label, boolean req) { return new Col(n, label, Type.INT, req, 0, List.of()); }
    private static Col bool(String n, String label) { return new Col(n, label, Type.BOOL, false, 0, List.of()); }
    private static Col oneOf(String n, String label, boolean req, String... options) { return new Col(n, label, Type.ENUM, req, 0, List.of(options)); }

    static final Map<String, Reg> ALL = Map.of(
        "sharing", new Reg("sharing", "Data sharing", "ecis_data_sharing", null, List.of(
            text("recipient", "Who it is shared with", true, 120), text("purpose", "Purpose", true, 255), text("data_categories", "What data", true, 255),
            text("legal_basis", "Legal basis", false, 255), text("agreement_ref", "Agreement reference", false, 80), date("started_on", "Started", true), date("ended_on", "Ended", false)), "started_on DESC, id DESC"),
        "dpia", new Reg("dpia", "Impact assessments", "ecis_dpia", "DPIA", List.of(
            text("title", "Title", true, 160), longText("scope", "Scope", false), oneOf("risk_level", "Risk", true, "LOW", "MEDIUM", "HIGH"),
            oneOf("status", "Status", true, "PLANNED", "IN_PROGRESS", "DONE", "REVIEW_DUE"), date("conducted_on", "Conducted", false), date("review_due", "Review due", false),
            text("owner", "Owner", false, 80), text("reference", "Where the document is kept", false, 160)), "id DESC"),
        "retention", new Reg("retention", "Retention rules", "ecis_retention_rule", null, List.of(
            text("category", "Data", true, 120), number("retain_years", "Years", false), text("starts_from", "Counted from", false, 80),
            oneOf("action", "Then", true, "KEEP", "ARCHIVE", "ANONYMIZE", "DELETE"), longText("legal_basis", "Legal basis", false), longText("notes", "Notes", false), bool("confirmed", "Confirmed")), "id"),
        "classification", new Reg("classification", "Data classification", "ecis_data_classification", null, List.of(
            text("data_item", "Data", true, 160), text("where_stored", "Where it is kept", true, 255),
            oneOf("classification", "Class", true, "PUBLIC", "INTERNAL", "CONFIDENTIAL", "SENSITIVE"), longText("handling", "How it is handled", false), bool("reviewed", "Reviewed")), "id"),
        "incidents", new Reg("incidents", "Incidents", "ecis_privacy_incident", "INC", List.of(
            dateTime("detected_at", "Detected", true), longText("description", "What happened", true), text("data_affected", "Data affected", false, 255), number("people_affected", "People affected", false),
            oneOf("severity", "Severity", true, "LOW", "MEDIUM", "HIGH"), oneOf("status", "Status", true, "OPEN", "CONTAINED", "RESOLVED"),
            longText("actions", "Actions taken", false), dateTime("notified_at", "Regulator and those affected told", false)), "detected_at DESC"),
        "notices", new Reg("notices", "Privacy notices", "ecis_privacy_notice", null, List.of(
            text("version", "Version", true, 20), text("title", "Title", true, 160), longText("body", "Text", true), date("effective_from", "Effective from", false)), "id DESC")
    );

    /** The hours after detection by which an incident must be reported, where the law requires it. Confirm the figure with the data protection officer. */
    static final int NOTIFY_HOURS = 72;

    /**
     * Checks the submitted values against the register and returns them typed for the database (only the columns given). When {@code creating}, every required column must be there;
     * when updating, a required column may be left out but not emptied.
     */
    static Map<String, Object> clean(Reg reg, Map<String, String> raw, boolean creating) {
        if (raw == null) throw bad("Fill in the form.");
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (String key : raw.keySet()) if (reg.col(key) == null) throw bad("The field '" + key + "' does not belong to " + reg.title().toLowerCase() + ".");
        for (Col c : reg.cols()) {
            boolean given = raw.containsKey(c.name());
            String v = given && raw.get(c.name()) != null ? raw.get(c.name()).trim() : "";
            if (!given && !creating) continue;
            if (v.isEmpty()) {
                if (c.required() && !(c.type() == Type.ENUM && creating && defaultOf(reg, c) != null)) { problems.add(c.label() + " is required."); continue; }
                if (c.type() == Type.ENUM && creating && defaultOf(reg, c) != null) { out.put(c.name(), defaultOf(reg, c)); continue; }
                out.put(c.name(), c.type() == Type.BOOL ? (Object) 0 : null);
                continue;
            }
            try {
                out.put(c.name(), convert(c, v));
            } catch (IllegalArgumentException | DateTimeParseException e) {
                problems.add(e instanceof DateTimeParseException ? c.label() + " must be a " + (c.type() == Type.DATE ? "date (2026-04-24)." : "date and time (2026-04-24T15:30).") : e.getMessage());
            }
        }
        if (!problems.isEmpty()) throw bad(String.join(" ", problems));
        return out;
    }

    /** A register's first allowed value is the starting one where a status is left empty on creation. */
    private static String defaultOf(Reg reg, Col c) {
        return c.name().equals("status") && !c.options().isEmpty() ? c.options().get(0) : null;
    }

    private static Object convert(Col c, String v) {
        return switch (c.type()) {
            case TEXT -> { if (v.length() > c.max()) throw new IllegalArgumentException(c.label() + " can be at most " + c.max() + " characters."); yield v; }
            case LONGTEXT -> { if (v.length() > c.max()) throw new IllegalArgumentException(c.label() + " is too long."); yield v; }
            case DATE -> java.sql.Date.valueOf(LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v));
            case DATETIME -> Timestamp.valueOf(LocalDateTime.parse(v.replace(' ', 'T').length() == 10 ? v + "T00:00" : v.replace(' ', 'T')));
            case INT -> {
                try {
                    long n = Long.parseLong(v.replace(",", ""));
                    if (n < 0 || n > 100_000_000L) throw new IllegalArgumentException(c.label() + " must be a whole number from 0 to 100,000,000.");
                    yield (int) n;
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(c.label() + " must be a whole number.");
                }
            }
            case BOOL -> v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes") ? 1 : 0;
            case ENUM -> {
                String up = v.toUpperCase();
                if (!c.options().contains(up)) throw new IllegalArgumentException(c.label() + " must be one of " + String.join(", ", c.options()) + ".");
                yield up;
            }
        };
    }
}
