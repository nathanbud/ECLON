package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks for a cash deposit into, or a cash withdrawal from, a savings account, as the old system applied them (see
 * documentation/savings-posting-map.md). A rule the old system let a supervisor allow is returned as a {@link Need}; the rest
 * are plain problems. No database access here.
 */
final class SavingsRules {

    private SavingsRules() {}

    static final int ACTIVE = 15;
    static final int DORMANT = 16;
    static final int CLOSED = 17;
    /** transtype 23, module 2. */
    static final int WITHDRAWAL = 23;
    static final String MIN_BALANCE = "SAV_MIN_BALANCE";
    static final String DISBURSEMENT_LIMIT = "SAV_DISB_LIMIT";

    /** Something a supervisor has to allow before the withdrawal can be posted. */
    record Need(String kind, String title, BigDecimal limit) {}

    record Check(List<String> problems, List<Need> needs) {}

    private static String money(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP).toPlainString(); }

    private static void amountProblems(BigDecimal amount, List<String> p) {
        if (amount == null || amount.signum() <= 0) {
            p.add("Enter an amount above zero.");
        } else {
            if (amount.stripTrailingZeros().scale() > 2) p.add("The amount can have at most 2 decimal places.");
            if (amount.compareTo(SharePaymentRules.MAX_PAYMENT) > 0) p.add("The amount is too large.");
        }
    }

    private static void accountProblems(int accountStatus, int clientStatus, String doing, List<String> p) {
        if (accountStatus == DORMANT) p.add("This savings account is dormant, so it cannot " + doing + " until it is reactivated.");
        else if (accountStatus == CLOSED) p.add("This savings account is closed, so it cannot " + doing + ".");
        else if (accountStatus != ACTIVE) p.add("This savings account is not active, so it cannot " + doing + ".");
        if (clientStatus == 3 || clientStatus == 98 || clientStatus == 99) p.add("This client's account is closed, disapproved or not yet approved.");
    }

    static List<String> checkDeposit(BigDecimal amount, int accountStatus, int clientStatus) {
        List<String> p = new ArrayList<>();
        amountProblems(amount, p);
        accountProblems(accountStatus, clientStatus, "take deposits", p);
        return p;
    }

    /**
     * @param held       total of the account's holds
     * @param minBalance the account's minimum balance (null or zero for none)
     * @param limit      the smaller of the person's disbursement limit and maximum withdrawal
     */
    static Check checkWithdrawal(BigDecimal amount, int accountStatus, BigDecimal balance, BigDecimal held, BigDecimal minBalance, int clientStatus, BigDecimal limit) {
        List<String> problems = new ArrayList<>();
        List<Need> needs = new ArrayList<>();
        amountProblems(amount, problems);
        accountProblems(accountStatus, clientStatus, "pay out money", problems);
        if (amount == null || amount.signum() <= 0) return new Check(problems, needs);
        BigDecimal available = balance.subtract(held).max(BigDecimal.ZERO);
        if (amount.compareTo(balance) > 0) {
            problems.add("The balance is " + money(balance) + ", which is less than the amount.");
        } else if (amount.compareTo(available) > 0) {
            problems.add("Part of the balance (" + money(held) + ") is on hold. The most that can be withdrawn is " + money(available) + ".");
        } else {
            BigDecimal after = balance.subtract(amount);
            if (minBalance != null && minBalance.signum() > 0 && after.compareTo(minBalance) < 0) {
                needs.add(new Need(MIN_BALANCE, "the balance after the withdrawal (" + money(after) + ") is below the minimum balance of " + money(minBalance), minBalance));
            }
        }
        if (limit != null && amount.compareTo(limit) > 0) {
            needs.add(new Need(DISBURSEMENT_LIMIT, "the amount is over the cashier's withdrawal limit of " + money(limit), limit));
        }
        return new Check(problems, needs);
    }
}
