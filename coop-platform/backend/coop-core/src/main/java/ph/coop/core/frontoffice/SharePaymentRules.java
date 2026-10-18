package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks for a cash payment into a member's share capital account, as the old system applied them (see
 * documentation/share-payment-posting-map.md). No database access here.
 */
final class SharePaymentRules {

    private SharePaymentRules() {}

    /** sc.SCSTATUS of an account that can take payments. */
    static final int ACTIVE = 15;
    /** transtype 10, module 3. */
    static final int CASH_RECEIPT = 10;
    static final int RECEIPT_MODULE = 3;
    static final BigDecimal MAX_PAYMENT = new BigDecimal("999999999999.99");

    /** Problems with a payment, empty when it is fine. {@code maxBalance} is null when the account has no ceiling. */
    static List<String> check(BigDecimal amount, int accountStatus, BigDecimal balance, BigDecimal maxBalance, int clientStatus) {
        List<String> p = new ArrayList<>();
        if (amount == null || amount.signum() <= 0) {
            p.add("Enter an amount above zero.");
        } else {
            if (amount.stripTrailingZeros().scale() > 2) p.add("The amount can have at most 2 decimal places.");
            if (amount.compareTo(MAX_PAYMENT) > 0) p.add("The amount is too large.");
            if (maxBalance != null && balance.add(amount).compareTo(maxBalance) > 0) {
                p.add("The balance would become " + balance.add(amount).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                    + ", over the maximum of " + maxBalance.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " for this account. A supervisor override is needed.");
            }
        }
        if (accountStatus != ACTIVE) p.add("This share capital account is not active, so it cannot take payments.");
        if (clientStatus == 3 || clientStatus == 98 || clientStatus == 99) p.add("This client's account is closed, disapproved or not yet approved.");
        return p;
    }

    /** The document number: the one the teller typed (up to 8 letters or digits), else {@code *} and the control number as 7 digits. */
    static String documentNo(String typed, long controlNo) {
        String t = typed == null ? "" : typed.trim();
        if (t.isEmpty()) return String.format("*%07d", controlNo);
        if (!t.matches("[A-Za-z0-9]{1,8}")) throw new IllegalArgumentException("The receipt number can have up to 8 letters or digits.");
        return t;
    }
}
