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


    /** The fields a registration must give. */
    static final List<String> REQUIRED = List.of("LName", "FName", "DateOfBirth", "Gender", "CivilStatus");

    /** The old system's closing reasons (lookup {@code closedreason}). */
    static final Map<Integer, String> REASONS = Map.of(1, "Voluntary", 2, "Transferred", 3, "Bad debts", 4, "Offset", 5, "Zero balance", 6, "Deceased");

    /** One account opened with a member: the ledger class and type, and which table holds it. */
    record Acct(int slc, int slt, String table) {}

    /**
     * The accounts that opened with each kind of member in the first cooperative's old system (22 savings, 29 funds, 31 share capital). Used only where the SL Controls
     * table is not installed (the old MySQL database); every other database opens exactly what its own SL Controls say, since each cooperative has its own products.
     */
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
    /** @param types the member types this cooperative registers (Settings > Membership > Member defaults), code to name */
    static List<String> registrationProblems(int clientType, Map<String, String> fields, LocalDate today, Map<Integer, String> types) {
        List<String> out = new ArrayList<>();
        if (!types.containsKey(clientType)) out.add(typeProblem(types));
        for (String f : REQUIRED) if (fields.get(f) == null || fields.get(f).isBlank()) out.add("Give the " + label(f) + ".");
        String dob = fields.get("DateOfBirth");
        if (dob != null && !dob.isBlank()) {
            LocalDate born = LocalDate.parse(dob);
            if (!born.isBefore(today)) out.add("The date of birth must be in the past.");
            else if (Period.between(born, today).getYears() < MIN_AGE) out.add("A " + types.getOrDefault(clientType, "member").toLowerCase(Locale.ROOT) + " must be at least " + MIN_AGE + " years old.");
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
    /** The refusal for a member type this cooperative does not register, naming the ones it does, or where to choose them when there are none. */
    static String typeProblem(Map<Integer, String> types) {
        if (types.isEmpty()) return "No member type is set up for registration in this cooperative yet, so no one can be registered. A super administrator chooses them in"
            + " Settings > Membership > Member defaults.";
        return "Choose " + String.join(" or ", types.values()) + ": those are the member types this cooperative registers. A super administrator can change"
            + " them in Settings > Membership > Member defaults.";
    }

    static List<String> reactivateProblems(int status, Integer closedReason, int clientType, LocalDate deceased, int newType, String note, Map<Integer, String> types) {
        List<String> out = new ArrayList<>();
        if (status != 2 && status != 3) out.add("Only a dormant or closed membership can be reactivated.");
        if ((closedReason != null && closedReason == 6) || deceased != null || clientType == 7) {
            out.add("A deceased member cannot be reactivated. If the death was recorded by mistake, ask an administrator to correct the record.");
        }
        if (!types.containsKey(newType)) out.add(typeProblem(types));
        if (note == null || note.trim().length() < 3) out.add("Give the reason for reactivating.");
        return out;
    }

    /** The kind of account a class holds, in everyday words. */
    static String accountKind(int slc) {
        return switch (slc) {
            case 22 -> "savings account";
            case 24 -> "time deposit";
            case 26 -> "payable account";
            case 29 -> "member fund account";
            case 31 -> "share capital account";
            default -> "account";
        };
    }

    /**
     * Why a member cannot be opened (or reactivated) when an account that opens with their membership is not set up: who, why, that nothing was saved,
     * and the two ways to finish. Written for the person approving, not an administrator.
     */
    /** The refusal when the registration gives no department (or office) and this cooperative has not chosen a default one. */
    static String noDefault(String who, String what) {
        return who + " cannot be registered yet. The registration gives no " + what + ", and this cooperative has not chosen the " + what + " a new member is given"
            + " when none is chosen, so nothing was saved. To finish, either: 1. a super administrator chooses it in Settings > Membership > Member defaults; or"
            + " 2. reject this request and register the member again with the " + what + " chosen. Then approve again.";
    }

    /**
     * Something the new member's record points to that this database lacks: what it is in words ("department"), the value, whether it was given because none
     * was chosen (a default), and where it is added.
     */
    record Missing(String what, String value, boolean byDefault, String where) {}

    /** Where each kind of list is set up; the standard lists come with Starter setup. */
    static String whereToAdd(String column) {
        return switch (column.toLowerCase(Locale.ROOT)) {
            case "clientdept" -> "Settings > Organization > Departments";
            case "restrikid" -> "Settings > Organization > Offices";
            case "clientidbrcode" -> "the branches of this database (ask the person who set it up)";
            case "encdby" -> "Settings > Users";
            default -> "Settings > System > Starter setup";
        };
    }

    /** The refusal when the new member's record points to things this database lacks: each one, why it is used, and where to add it. */
    static String missingLinks(String who, List<Missing> missing) {
        StringBuilder b = new StringBuilder(who).append(" cannot be registered yet. The member's record points to ")
            .append(missing.size() == 1 ? "something" : missing.size() + " things").append(" this database does not have, so nothing was saved. To finish: ");
        int n = 1;
        for (Missing m : missing) {
            b.append(n++).append(". ").append(m.byDefault() ? "a new member is given " + m.what() + " " + m.value() + " when none is chosen"
                : "the registration names " + m.what() + " " + m.value())
                .append(", but this database has no ").append(m.what()).append(" with that number: add it in ").append(m.where()).append("; ");
        }
        return b.append("then approve again. If the member should have another one, reject this request and register them again with the right choice.").toString();
    }

    /** The refusal when this cooperative has not chosen any account to open for the member type (SL Controls). */
    static String noAccountsChosen(String who, String what, String memberType) {
        return who + " cannot be " + what + " yet. No account is set to open for " + memberType + " members in this cooperative, and every member needs at least a"
            + " share capital account, so nothing was saved. To finish: an administrator switches on the accounts " + memberType + " members get in Settings > Products"
            + " > Account opening (SL Controls), with at least one share capital type; then approve again.";
    }

    static String missingAccountType(String who, String what, String memberType, int slc, int slt) {
        String kind = accountKind(slc);
        return who + " cannot be " + what + " yet. Every " + memberType + " member gets a " + kind + " (type " + slc + "/" + slt + ") when they join, "
            + "but that account type is not set up on this database, so nothing was saved. To finish, either: "
            + "1. set up the " + kind + " type in Settings > Products, with the chart accounts it posts to; or "
            + "2. if " + memberType + " members should not get one, switch it off in Settings > Products > Account opening (SL Controls). "
            + "Then approve again.";
    }
}
