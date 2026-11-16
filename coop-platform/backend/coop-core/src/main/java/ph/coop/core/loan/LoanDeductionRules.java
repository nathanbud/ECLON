package ph.coop.core.loan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The deduction lines a loan type starts an application with (the old system's Loan Deductions window, table loansldeduc), and the checks on changing them. A line says what is
 * taken from the loan and where it goes: into one of the member's own accounts (share capital, savings, a payable), to a charge of the loan itself (advance interest, service fee,
 * insurance), or straight to a chart account. The amount is typed by the clerk on the application, so a new line gets the old system's "ask for the amount" formula; the formula of
 * an existing line is left exactly as it is, because the old system still reads it. The net proceeds line closes every list and is neither added nor removed. Pure logic.
 */
final class LoanDeductionRules {

    private LoanDeductionRules() {}

    static final String MEMBER_ACCOUNT = "MEMBER_ACCOUNT";
    static final String LOAN_CHARGE = "LOAN_CHARGE";
    static final String CHART_ACCOUNT = "CHART_ACCOUNT";
    static final String NET_PROCEEDS = "NET_PROCEEDS";
    static final String OTHER = "OTHER";

    static final int BEHAVIOR_FEE = 1, BEHAVIOR_MEMBER = 3, BEHAVIOR_NET = 6;
    static final int NET_ORDER = 99;

    /** Classes of member accounts a deduction may be credited to: savings, share capital, accounts payable, miscellaneous liabilities. */
    static final Set<Integer> MEMBER_CLASSES = Set.of(22, 31, 26, 29);

    record Form(String description, Integer order, String kind, Integer classId, Integer typeCode, Integer event, String account, Boolean hidePrinting, String reason) {}

    /** What kind of line a stored row is. */
    static String kind(Integer behavior, Integer classId, Integer event, String account) {
        if (behavior != null && behavior == BEHAVIOR_NET) return NET_PROCEEDS;
        if (classId != null && (behavior == null || behavior == BEHAVIOR_MEMBER)) return MEMBER_ACCOUNT;
        if (classId == null && account != null && !account.isBlank()) return CHART_ACCOUNT;
        if (classId == null && event != null && (behavior == null || behavior == BEHAVIOR_FEE)) return LOAN_CHARGE;
        return OTHER;
    }

    static String description(String d) {
        String t = d == null ? "" : d.trim();
        if (t.isEmpty() || t.length() > 45) throw new IllegalArgumentException("The description is required, up to 45 characters.");
        if (t.contains("\"") || t.contains("<") || t.contains(">")) throw new IllegalArgumentException("The description cannot contain quotation marks or angle brackets.");
        return t;
    }

    static int order(Integer o) {
        if (o == null || o < 1 || o >= NET_ORDER) throw new IllegalArgumentException("The order is 1 to 98; the net proceeds line is always last.");
        return o;
    }

    /** The old system's formula for a line whose amount the clerk types. */
    static String promptFormula(String description) {
        return "PROMPT_AMOUNT (\"Enter Amount for " + description + ": \", \"0\", false)";
    }

    /**
     * The columns that say where a new or changed line goes. The caller has already checked that the member account type, the loan's own link or the chart account exists.
     * Throws IllegalArgumentException with what is wrong.
     */
    static Map<String, Object> target(Form f) {
        Map<String, Object> c = new LinkedHashMap<>();
        String kind = f.kind() == null ? "" : f.kind();
        switch (kind) {
            case MEMBER_ACCOUNT -> {
                if (f.classId() == null || f.typeCode() == null) throw new IllegalArgumentException("Choose the kind of member account the deduction goes to.");
                if (!MEMBER_CLASSES.contains(f.classId())) throw new IllegalArgumentException("A deduction can go to a savings, share capital or payable account of the member.");
                c.put("DSLC_CODE", f.classId()); c.put("DSLT_CODE", f.typeCode()); c.put("DSLE_CODE", 11); c.put("DACCT_CODE", null); c.put("SLBEHAVIOR", BEHAVIOR_MEMBER);
            }
            case LOAN_CHARGE -> {
                if (f.event() == null || f.event() == 11) throw new IllegalArgumentException("Choose the charge of the loan the deduction is for.");
                c.put("DSLC_CODE", null); c.put("DSLT_CODE", null); c.put("DSLE_CODE", f.event()); c.put("DACCT_CODE", null); c.put("SLBEHAVIOR", BEHAVIOR_FEE);
            }
            case CHART_ACCOUNT -> {
                if (f.account() == null || f.account().isBlank()) throw new IllegalArgumentException("Give the chart account the deduction goes to.");
                c.put("DSLC_CODE", null); c.put("DSLT_CODE", null); c.put("DSLE_CODE", null); c.put("DACCT_CODE", f.account().trim()); c.put("SLBEHAVIOR", null);
            }
            default -> throw new IllegalArgumentException("Choose where the deduction goes: a member's account, a charge of the loan or a chart account.");
        }
        return c;
    }
}
