package ph.coop.core.member;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What registering and closing a member must satisfy, from the old system's own data: the member number's check digit (fits all 100,614 numbers in the database), the accounts the
 * old system opens with each kind of member, and the closing reasons. Pure logic, no database access.
 */
final class LifecycleRules {

    private LifecycleRules() {}

    static final int MIN_AGE = 18;

    /** The member types a registration can create. Regular Individual and Associate are individuals; joint, kiddie, company and the other types are registered in the old system. */
    static final Map<Integer, String> TYPES = Map.of(1, "Regular Individual", 2, "Associate");

    /** The fields a registration must give. */
    static final List<String> REQUIRED = List.of("LName", "FName", "DateOfBirth", "Gender", "CivilStatus");

    /** The old system's closing reasons (lookup {@code closedreason}). */
    static final Map<Integer, String> REASONS = Map.of(1, "Voluntary", 2, "Transferred", 3, "Bad debts", 4, "Offset", 5, "Zero balance", 6, "Deceased");

    /** One account opened with a member: the ledger class and type, and which table holds it. */
    record Acct(int slc, int slt, String table) {}

    /** The accounts the old system opens with each kind of member (22 savings, 29 funds, 31 share capital), as recent real registrations show. */
    static List<Acct> accountsFor(int clientType) {
        return switch (clientType) {
            case 1 -> List.of(new Acct(22, 1, "sd"), new Acct(29, 5, "osl"), new Acct(29, 6, "osl"), new Acct(31, 1, "sc"));
            case 2 -> List.of(new Acct(22, 2, "sd"), new Acct(31, 3, "sc"));
            default -> List.of();
        };
    }

    /**
     * The check digit of a member number: double every second digit starting with the rightmost (subtract 9 from a result over 9), add them up, and the digit is 9 less that sum,
     * modulo 10. Verified against every member in the database.
     */
    static int checkDigit(int clientId) {
        int sum = 0;
        String s = Integer.toString(clientId);
        for (int i = 0; i < s.length(); i++) {
            int d = s.charAt(s.length() - 1 - i) - '0';
            if (i % 2 == 0) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
        }
        return ((9 - sum) % 10 + 10) % 10;
    }

    /** The problems with a registration's cleaned fields, empty when it can be submitted. */
    static List<String> registrationProblems(int clientType, Map<String, String> fields, LocalDate today) {
        List<String> out = new ArrayList<>();
        if (!TYPES.containsKey(clientType)) out.add("Only " + String.join(" and ", TYPES.values()) + " members can be registered here; other kinds of membership are registered in the old system.");
        for (String f : REQUIRED) if (fields.get(f) == null || fields.get(f).isBlank()) out.add("Give the " + label(f) + ".");
        String dob = fields.get("DateOfBirth");
        if (dob != null && !dob.isBlank()) {
            LocalDate born = LocalDate.parse(dob);
            if (!born.isBefore(today)) out.add("The date of birth must be in the past.");
            else if (Period.between(born, today).getYears() < MIN_AGE) out.add("A " + TYPES.getOrDefault(clientType, "member").toLowerCase(Locale.ROOT) + " must be at least " + MIN_AGE + " years old.");
            else if (born.isBefore(today.minusYears(120))) out.add("Check the date of birth: that member would be over 120.");
        }
        return out;
    }

    private static String label(String column) {
        return switch (column) {
            case "LName" -> "last name";
            case "FName" -> "first name";
            case "DateOfBirth" -> "date of birth";
            case "Gender" -> "gender";
            case "CivilStatus" -> "civil status";
            default -> column;
        };
    }

    /** The key two registrations are compared on to spot the same person twice: last name, first name and birth date, ignoring case and spacing. */
    static String duplicateKey(Map<String, String> fields) {
        return (norm(fields.get("LName")) + "|" + norm(fields.get("FName")) + "|" + norm(fields.get("DateOfBirth")));
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** The problems with asking to close a member: a status that can be closed, a known reason, and a date of death exactly when the reason is deceased. */
    static List<String> closeProblems(int status, int reason, LocalDate deceased, LocalDate today) {
        List<String> out = new ArrayList<>();
        if (status != 1 && status != 2) out.add("Only an active or dormant membership can be closed.");
        if (!REASONS.containsKey(reason)) out.add("Choose the reason for closing.");
        if (reason == 6) {
            if (deceased == null) out.add("Give the date of death.");
            else if (deceased.isAfter(today)) out.add("The date of death cannot be in the future.");
        } else if (deceased != null) {
            out.add("A date of death goes only with the reason Deceased.");
        }
        return out;
    }

    /**
     * What stops a membership closing: every account must be at zero, so share capital and savings have been paid out and loans cleared first. The one exception is a loan that is
     * written off (reason Bad debts), which stays on the books. {@code balances} is the sum of the principal and balance ledger entries per ledger class.
     */
    static List<String> balanceProblems(Map<Integer, BigDecimal> balances, int reason) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Integer, BigDecimal> e : new java.util.TreeMap<>(balances).entrySet()) {
            if (e.getValue().abs().compareTo(new BigDecimal("0.005")) < 0) continue;
            if (e.getKey() == 12 && reason == 3) continue;
            out.add(className(e.getKey()) + " still holds " + e.getValue().abs().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        }
        return out;
    }

    static String className(int slc) {
        return switch (slc) {
            case 12 -> "Loans";
            case 13 -> "Receivables";
            case 22 -> "Savings";
            case 24 -> "Time deposits";
            case 26 -> "Other payables";
            case 29 -> "Vouchers payable and funds";
            case 31 -> "Share capital";
            default -> "Ledger class " + slc;
        };
    }

    /** The member type a closed membership takes in the old data: Deceased for a death, Closed/Withdrawn otherwise. */
    static int closedClientType(int reason) {
        return reason == 6 ? 7 : 6;
    }

    /** Closing reasons that left money owing; only an administrator may let such a member back. */
    static boolean needsAdministrator(Integer closedReason) {
        return closedReason != null && (closedReason == 3 || closedReason == 4);
    }

    /** The problems with asking to reactivate: only a dormant or closed membership, never a deceased member, and a membership type the app can open. */
    static List<String> reactivateProblems(int status, Integer closedReason, int clientType, LocalDate deceased, int newType, String note) {
        List<String> out = new ArrayList<>();
        if (status != 2 && status != 3) out.add("Only a dormant or closed membership can be reactivated.");
        if ((closedReason != null && closedReason == 6) || deceased != null || clientType == 7) {
            out.add("A deceased member cannot be reactivated. If the death was recorded by mistake, ask an administrator to correct the record.");
        }
        if (!TYPES.containsKey(newType)) out.add("Choose Regular Individual or Associate.");
        if (note == null || note.trim().length() < 3) out.add("Give the reason for reactivating.");
        return out;
    }
}
