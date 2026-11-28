package ph.coop.core.accounting;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an administrator may set on an account type (the old system's S/L type) outside loans, and the checks on it. A type belongs to a class (savings, time deposit, share capital,
 * receivables, payables and so on). Only the settings this application really obeys are offered: the names, the order, whether it is in use, and, where the class has them, the
 * interest rate and the minimum balance. Loan types have their own screen (Loan Setup), and the cash class is kept by the cashier and bank setup, so both are read only here.
 * Pure logic, no database access.
 */
final class AccountTypeRules {

    private AccountTypeRules() {}

    static final int CASH = 11;
    static final int LOANS = 12;
    static final int SAVINGS = 22;
    static final int TIME_DEPOSIT = 24;
    static final int SHARE_CAPITAL = 31;

    static final String EDIT = "EDIT";
    static final String LOAN_SETUP = "LOAN_SETUP";
    /** The cash class: its types are fixed, but a link can be pointed at another account and a drawer can be added. */
    static final String CASH_SETUP = "CASH";

    static final String RATE = "interestRate";
    static final String MIN_BALANCE = "minBalance";
    static final String MAX_BALANCE = "maxBalance";
    static final String DORMANCY = "dormantAfterMonths";
    /** The old system's way of writing no limit. */
    static final BigDecimal NO_LIMIT = new BigDecimal("999999999.00");
    private static final int MONTHS = 4;

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.00");

    /** Where the types of a class are kept. */
    /**
     * Why a type cannot be deleted; empty when it can. Only a type nothing ever used may go: {@code uses} counts, by what they are, the
     * accounts, entries and settings that point at it (zero counts are ignored). Loan and cash types are never deleted here.
     */
    static List<String> deleteProblems(int classId, Map<String, Long> uses) {
        if (!EDIT.equals(managed(classId))) {
            return List.of(classId == LOANS ? "Loan types are kept in Loan Setup." : "Cash types are fixed and cannot be deleted.");
        }
        List<String> used = uses.entrySet().stream().filter((e) -> e.getValue() != null && e.getValue() > 0)
            .map((e) -> String.format("%,d %s", e.getValue(), e.getKey())).toList();
        if (used.isEmpty()) return List.of();
        return List.of("It is in use: " + String.join(", ", used) + ". Switch it off instead; a type that was used stays so its history can still be read.");
    }

    static String managed(int classId) {
        if (classId == LOANS) return LOAN_SETUP;
        if (classId == CASH) return CASH_SETUP;
        return EDIT;
    }

    /** The settings beyond the names that the application obeys for a class. */
    static List<String> fields(int classId) {
        return switch (classId) {
            // the yearly rate of the interest run and of a new account; the balance a withdrawal must leave; the most a deposit may bring the balance to;
            // how long without a deposit or withdrawal before the account is offered for marking dormant
            case SAVINGS -> List.of(RATE, MIN_BALANCE, MAX_BALANCE, DORMANCY);
            case TIME_DEPOSIT, SHARE_CAPITAL -> List.of(MIN_BALANCE); // the smallest placement; the share capital a member must hold
            default -> List.of();
        };
    }

    /** Checks the form and returns the column values it sets, keyed by the old column name. Throws IllegalArgumentException with what is wrong. */
    /** The longest product type name (both name columns hold 100 since PostgreSQL V048 / MySQL V085). */
    public static final int NAME_MAX = 100;

    static Map<String, Object> columns(int classId, AccountTypeController.Form f) {
        if (f == null) throw new IllegalArgumentException("Nothing to save.");
        if (!EDIT.equals(managed(classId))) throw new IllegalArgumentException(classId == LOANS ? "Loan types are changed in Loan Setup." : "The types of this class cannot be changed here.");
        String name = f.name() == null ? "" : f.name().trim();
        if (name.isEmpty() || name.length() > NAME_MAX) throw new IllegalArgumentException("The name is required, up to " + NAME_MAX + " characters.");
        if (f.priority() != null && (f.priority() < 0 || f.priority() > 999)) throw new IllegalArgumentException("The order number is 0 to 999.");
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("SLTypeM_DESC", name);
        c.put("SLTypeM_DESC2", name); // one name: the old system's full-name column gets the same
        c.put("SLTypePriority", f.priority());
        c.put("SLTypeIsDisabled", Boolean.TRUE.equals(f.disabled()) ? 1 : 0);
        List<String> has = fields(classId);
        if (has.contains(RATE)) {
            BigDecimal r = f.interestRate();
            if (r != null && (r.signum() < 0 || r.compareTo(BigDecimal.valueOf(100)) > 0 || r.stripTrailingZeros().scale() > 2)) throw new IllegalArgumentException("The interest rate is 0 to 100 percent a year, with at most 2 decimals.");
            c.put("SLTypeINT_RATE", r);
        }
        if (has.contains(MIN_BALANCE)) {
            BigDecimal m = f.minBalance();
            if (m != null && (m.signum() < 0 || m.compareTo(MAX_AMOUNT) > 0 || m.stripTrailingZeros().scale() > 2)) throw new IllegalArgumentException("The minimum balance is 0 or more, with at most 2 decimals.");
            c.put("SLTypeMIN_BAL", m);
        }
        if (has.contains(MAX_BALANCE)) {
            BigDecimal m = f.maxBalance();
            if (m != null && (m.signum() <= 0 || m.compareTo(MAX_AMOUNT) > 0 || m.stripTrailingZeros().scale() > 2)) throw new IllegalArgumentException("The maximum balance is more than 0, with at most 2 decimals; leave it empty for no limit.");
            BigDecimal min = (BigDecimal) c.get("SLTypeMIN_BAL");
            if (m != null && min != null && m.compareTo(min) < 0) throw new IllegalArgumentException("The maximum balance cannot be less than the minimum balance.");
            c.put("SLTypeMAXBAL", m == null ? NO_LIMIT : m);
        }
        if (has.contains(DORMANCY)) {
            Integer d = f.dormantAfterMonths();
            if (d != null && (d < 1 || d > 600)) throw new IllegalArgumentException("Dormant after is 1 to 600 months; leave it empty for never.");
            c.put("SLTypeDORM_TERM", d);
            c.put("SLTypeDORM_PERD", d == null ? null : MONTHS);
        }
        return c;
    }

    /** The balance side a new ledger link gets when no other type of the class shows it: debit for assets and expenses, credit for the rest. */
    static int balanceSide(int accountKind) {
        return accountKind == 1 || accountKind == 5 ? 1 : 2;
    }

    /** Whether a status or posting code of the old lookup tables applies to a class: their "active" text lists patterns such as 22xx or 1101. */
    static boolean appliesTo(String active, int classId) {
        if (active == null) return false;
        for (String part : active.split(",")) if (part.trim().startsWith(String.valueOf(classId))) return true;
        return false;
    }

    /** The dormancy period of a type in months, or null when it has none (only a period counted in months or years is understood). */
    static Integer dormancyMonths(Integer term, Integer unit) {
        if (term == null || term <= 0 || unit == null) return null;
        return unit == MONTHS ? term : unit == 7 ? Integer.valueOf(term * 12) : null;
    }

    /** What a ledger link is for, in words. */
    static String eventName(int classId, int event) {
        return switch (event) {
            case 11 -> switch (classId) {
                case SAVINGS, TIME_DEPOSIT -> "Deposit balance";
                case SHARE_CAPITAL -> "Share capital balance";
                default -> "Balance";
            };
            case 50 -> "Vault";
            case 51 -> "On hand";
            case 52 -> "Cashier's drawer";
            case 53 -> "Teller's drawer";
            case 61 -> "Bank current account";
            case 62 -> "Bank savings account";
            case 63 -> "Bank time deposit";
            case 21 -> "Accrued interest receivable";
            case 22 -> "Unearned interest";
            case 23 -> "Interest paid (income)";
            case 24 -> "Interest earned";
            case 31 -> "Service fee";
            case 41 -> "Penalty / fines";
            case 71 -> "Accrued interest payable";
            case 72 -> "Accrued interest earned";
            case 73 -> "Withholding tax";
            case 74 -> "Compounded interest";
            case 75 -> "Accrued interest payable";
            default -> "Event " + event;
        };
    }

    /** The account status a balance link applies to; null when the link is not by status. */
    static String statusName(int number) {
        return switch (number) {
            case 11 -> "Current";
            case 12 -> "Past due";
            case 13 -> "In litigation";
            case 14 -> "Restructured";
            case 15 -> "Active";
            case 16 -> "Dormant";
            case 17 -> "Closed";
            default -> null;
        };
    }

    /** Classes whose accounts sit in a table of their own, so the number of accounts of a type can be shown: class to {table, branch, class and type columns}. */
    static final Map<Integer, String[]> ACCOUNT_TABLES = Map.of(
        SAVINGS, new String[] {"sd", "SDBR_CODE", "SDSLC_CODE", "SDSLT_CODE"},
        TIME_DEPOSIT, new String[] {"td", "TDBR_CODE", "TDSLC_CODE", "TDSLT_CODE"},
        SHARE_CAPITAL, new String[] {"sc", "SCBR_CODE", "SCSLC_CODE", "SCSLT_CODE"},
        13, new String[] {"ar", "ARBR_CODE", "ARSLC_CODE", "ARSLT_CODE"},
        26, new String[] {"ap", "APBR_CODE", "APSLC_CODE", "APSLT_CODE"});

    static final Set<Integer> BY_STATUS = Set.of(11);
}
