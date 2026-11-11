package ph.coop.core.member;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** The Know Your Customer rules, kept free of the database so they can be tested alone: the IDs accepted, the risk ratings, when a review is due, and the status of a record. */
public final class KycRules {

    private KycRules() {}

    /** Government-issued IDs a member may present. */
    public static final List<String> ID_TYPES = List.of(
        "Passport", "Driver's License", "UMID", "SSS ID", "PhilSys National ID", "Postal ID", "Voter's ID", "PRC ID", "TIN ID", "PhilHealth ID",
        "Pag-IBIG ID", "Senior Citizen ID", "PWD ID", "GSIS ID", "Other government ID");

    public static final Set<String> RISKS = Set.of("LOW", "MEDIUM", "HIGH");

    /** What is known about a member, as stored. */
    public record Record(String idType, String idNumber, LocalDate idExpiry, String sourceOfFunds, String risk, LocalDate nextReview, boolean pep, String pepNote) {}

    /** NONE: nothing recorded. INCOMPLETE: something required is missing. EXPIRED: the ID has expired. REVIEW_DUE: the review date has passed. COMPLETE: all in order. */
    public enum Status { NONE, INCOMPLETE, EXPIRED, REVIEW_DUE, COMPLETE }

    /** How long a record stays good: the riskier the member, the sooner it is looked at again. */
    public static LocalDate suggestedReview(String risk, LocalDate from) {
        return switch (risk == null ? "" : risk) {
            case "HIGH" -> from.plusYears(1);
            case "MEDIUM" -> from.plusYears(2);
            default -> from.plusYears(3);
        };
    }

    /** Messages for what is wrong with a submitted record; empty when it may be saved. */
    public static List<String> problems(String idType, String idNumber, String risk, boolean pep, String pepNote) {
        var out = new java.util.ArrayList<String>();
        if (idType != null && !idType.isBlank() && !ID_TYPES.contains(idType)) out.add("That kind of ID is not on the list.");
        if (idNumber != null && !idNumber.isBlank() && !idNumber.matches("[A-Za-z0-9 .\\-/]{3,60}")) out.add("The ID number can have letters, digits, spaces and - . / only (3 to 60 characters).");
        if (risk != null && !risk.isBlank() && !RISKS.contains(risk)) out.add("The risk rating must be Low, Medium or High.");
        if (pep && (pepNote == null || pepNote.isBlank())) out.add("Say what public position makes the member politically exposed.");
        return out;
    }

    public static Status status(Record r, LocalDate today) {
        if (r == null) return Status.NONE;
        boolean missing = blank(r.idType()) || blank(r.idNumber()) || r.idExpiry() == null || blank(r.sourceOfFunds()) || blank(r.risk()) || r.nextReview() == null;
        if (missing) return Status.INCOMPLETE;
        if (r.idExpiry().isBefore(today)) return Status.EXPIRED;
        if (r.nextReview().isBefore(today)) return Status.REVIEW_DUE;
        return Status.COMPLETE;
    }

    /** An ID number with all but its last four characters hidden, for the audit log. */
    public static String mask(String s) {
        if (s == null || s.isBlank()) return "";
        return s.length() <= 4 ? "****" : "*".repeat(s.length() - 4) + s.substring(s.length() - 4);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
