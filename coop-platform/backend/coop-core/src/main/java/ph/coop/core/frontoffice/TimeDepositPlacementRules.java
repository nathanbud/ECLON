package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rules for placing a time deposit at the window, mapped from real placements (documentation/time-deposit-map.md). No database access here.
 * The rate is typed by the cashier; the app knows what has been used for each term and asks a supervisor when it is outside that.
 */
final class TimeDepositPlacementRules {

    /** The certificate fee of the old window: credited to 442202 "Other income - certificates" on the same receipt (346 of 359 placements in 2026; 10.00 and 50.00 are rarer). */
    static final BigDecimal CERTIFICATE_FEE = new BigDecimal("25.00");
    static final String FEE_ACCOUNT = "442202";
    static final BigDecimal MAX_RATE = new BigDecimal("20");
    static final int MAX_TERM_DAYS = 3660;

    private TimeDepositPlacementRules() {}

    /** The rates seen for one type and term in the last year: lowest, highest and the one used most. */
    record TermRates(int termDays, BigDecimal min, BigDecimal max, BigDecimal usual) {}

    /** The date a certificate matures: the placement date plus its term in days (verified against the 2025 and 2026 certificates). */
    static LocalDate maturity(LocalDate placed, int termDays) {
        return placed.plusDays(termDays);
    }

    /** True when the typed rate is outside what has been used for this term, so a supervisor has to allow it. */
    static boolean rateNeedsApproval(BigDecimal rate, TermRates known) {
        if (rate == null || known == null) return false;
        return rate.compareTo(known.min()) < 0 || rate.compareTo(known.max()) > 0;
    }

    /** Problems with a placement, empty when it is fine. {@code terms} maps the known term days of the type to their rates. */
    static List<String> check(BigDecimal amount, BigDecimal minBalance, int termDays, BigDecimal rate, Map<Integer, TermRates> terms, int clientStatus, boolean typeEnabled) {
        List<String> p = new ArrayList<>();
        if (!typeEnabled) p.add("That type of time deposit is not available.");
        if (clientStatus == 3 || clientStatus == 98 || clientStatus == 99) p.add("This member's account is closed, disapproved or not yet approved.");
        if (amount == null || amount.signum() <= 0) {
            p.add("Enter the amount to place.");
        } else {
            if (amount.stripTrailingZeros().scale() > 2) p.add("An amount can have at most 2 decimal places (centavos). Round it and try again.");
            if (amount.compareTo(SharePaymentRules.MAX_PAYMENT) > 0) p.add("The amount is too large.");
            if (minBalance != null && minBalance.signum() > 0 && amount.compareTo(minBalance) < 0)
                p.add("The least this type takes is " + minBalance.setScale(2, RoundingMode.HALF_UP).toPlainString() + ".");
        }
        if (termDays <= 0 || termDays > MAX_TERM_DAYS) p.add("Choose the term in days.");
        else if (!terms.containsKey(termDays)) p.add("No " + termDays + "-day term has been used for this type. Choose one of: " + terms.keySet().stream().sorted().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("none") + ".");
        if (rate == null || rate.signum() <= 0) p.add("Enter the interest rate.");
        else {
            if (rate.compareTo(MAX_RATE) > 0) p.add("The rate cannot be above " + MAX_RATE.toPlainString() + "% a year.");
            if (rate.stripTrailingZeros().scale() > 5) p.add("The rate can have at most 5 decimal places.");
        }
        return p;
    }
}
