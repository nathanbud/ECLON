package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import ph.coop.core.frontoffice.SavingsRules.Need;

/**
 * Rules for paying a member's vouchers-payable balance out in cash, as the old Cash Voucher payment (module 2, type 24) did
 * (documentation/voucher-payout-map.md): the whole balance is paid in one go, by the cashier, from their own drawer, and posted at once. No database access here.
 */
final class VoucherPayoutRules {

    private VoucherPayoutRules() {}

    /** transtype 24, module 2. */
    static final int TRANSACTION = 24;
    /** The member account that is debited: class 29 (vouchers payable), of the type chosen in Product roles. */
    static final int PAYABLE_CLASS = 29;
    static final int ACTIVE = 15;
    static final String LIMIT_KIND = "VOUCHER_DISB_LIMIT";

    record Check(List<String> problems, List<Need> needs) {}

    private static String money(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP).toPlainString(); }

    /**
     * @param balance     what the account holds (credits less payments)
     * @param drawerCash  cash in the cashier's own drawer now
     * @param limit       the smaller of the cashier's disbursement limit and maximum withdrawal
     */
    static Check check(BigDecimal balance, int accountStatus, int clientStatus, BigDecimal drawerCash, BigDecimal limit) {
        List<String> problems = new ArrayList<>();
        List<Need> needs = new ArrayList<>();
        if (accountStatus != ACTIVE) problems.add("This vouchers-payable account is not active, so nothing can be paid out of it.");
        if (clientStatus == 3 || clientStatus == 98 || clientStatus == 99) problems.add("This member's account is closed, disapproved or not yet approved.");
        if (balance == null || balance.signum() <= 0) {
            problems.add("This account has nothing to pay out.");
            return new Check(problems, needs);
        }
        if (drawerCash == null || drawerCash.compareTo(balance) < 0) {
            problems.add("Your drawer holds " + money(drawerCash == null ? BigDecimal.ZERO : drawerCash) + ", less than the " + money(balance)
                + " to pay out. Ask the head cashier for cash first (Cash Handover).");
        }
        if (limit != null && balance.compareTo(limit) > 0) {
            needs.add(new Need(LIMIT_KIND, "the amount is over the cashier's withdrawal limit of " + money(limit), limit));
        }
        return new Check(problems, needs);
    }
}
