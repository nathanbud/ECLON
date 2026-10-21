package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Checks for a withdrawal from a member's share capital (a Cash Voucher, module 2 type 21). No database access here. */
final class ShareWithdrawalRules {

    private ShareWithdrawalRules() {}

    /** transtype 21, module 2. */
    static final int CASH_VOUCHER = 21;

    /**
     * Problems with a withdrawal, empty when it is fine. {@code balance} is the ledger balance and {@code pending} the amount already asked for
     * in vouchers that are not posted yet, so the same capital is not promised twice.
     */
    static List<String> check(BigDecimal amount, int accountStatus, BigDecimal balance, BigDecimal pending, int clientStatus) {
        List<String> p = new ArrayList<>();
        if (amount == null || amount.signum() <= 0) {
            p.add("Enter an amount above zero.");
        } else {
            if (amount.stripTrailingZeros().scale() > 2) p.add("The amount can have at most 2 decimal places.");
            if (amount.compareTo(SharePaymentRules.MAX_PAYMENT) > 0) p.add("The amount is too large.");
            BigDecimal available = balance.subtract(pending);
            if (amount.compareTo(available) > 0) {
                p.add("The withdrawal is more than the available capital of " + available.max(BigDecimal.ZERO).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                    + (pending.signum() > 0 ? " (" + pending.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " is already waiting in other vouchers)." : "."));
            }
        }
        if (accountStatus != SharePaymentRules.ACTIVE) p.add("This share capital account is not active, so nothing can be withdrawn.");
        if (clientStatus == 3 || clientStatus == 98 || clientStatus == 99) p.add("This client's account is closed, disapproved or not yet approved.");
        return p;
    }
}
