package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import ph.coop.core.deposit.TimeDepositRules;

/**
 * Rules for paying out or renewing a matured time deposit, mapped from real vouchers (documentation/time-deposit-map.md): the interest for the whole term is
 * balance x rate x term days / 360; what was already accrued at year end (ledger event 71) is taken back and the rest is expensed to 50105. No database access here.
 */
final class TimeDepositClosureRules {

    static final String INTEREST_EXPENSE = "50105";
    /** Short Term (1), Lock In (2), Short Term New (3) and Lock In New (4). */
    static final List<Integer> HANDLED_TYPES = List.of(1, 2, 3, 4);
    /** Lock In pays its interest a year at a time, so a closing voucher pays the years still unpaid (documentation/time-deposit-map.md). */
    static final List<Integer> LOCK_IN_TYPES = List.of(2, 4);

    private TimeDepositClosureRules() {}

    /** What a closing voucher is made of. {@code interest} is for the whole term; {@code expense} is the part not accrued yet. */
    record Amounts(BigDecimal principal, BigDecimal interest, BigDecimal accrued, BigDecimal expense) {
        BigDecimal due() { return principal.add(interest); }
    }

    static Amounts amounts(BigDecimal balance, BigDecimal ratePercent, int termDays, BigDecimal accrued) {
        BigDecimal interest = TimeDepositRules.termInterest(balance, ratePercent, termDays);
        return new Amounts(balance, interest, accrued, interest.subtract(accrued));
    }

    static boolean isLockIn(int typeCode) { return LOCK_IN_TYPES.contains(typeCode); }

    /** Years of interest a Lock In closing can pay: the term in whole years, at least 1. */
    static int maxYears(int termDays) { return Math.max(1, (int) Math.round(termDays / 365.0)); }

    /**
     * Lock In closing, mapped from real vouchers 2026/21/37997, 38370, 39485, 36232 and 34648: the interest paid is balance x rate% for each unpaid year (the clerk says how
     * many, 1 for the last year), what the monthly accrual already booked is taken back, and the difference goes to 50105 (a credit when the accrual was more).
     */
    static Amounts lockInAmounts(BigDecimal balance, BigDecimal ratePercent, int years, BigDecimal accrued) {
        BigDecimal interest = balance.multiply(ratePercent).multiply(BigDecimal.valueOf(years)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return new Amounts(balance, interest, accrued, interest.subtract(accrued));
    }

    static List<String> yearsProblems(int typeCode, Integer years, int termDays) {
        if (!isLockIn(typeCode)) return List.of();
        int max = maxYears(termDays);
        if (years == null || years < 1 || years > max) return List.of("Choose how many years of interest are paid, from 1 to " + max + ".");
        return List.of();
    }

    /** Why a certificate cannot be closed, empty when it can. */
    static List<String> problems(int typeCode, String status, LocalDate maturity, LocalDate today, Amounts a, boolean hasPayableAccount, boolean voucherWaiting) {
        List<String> p = new ArrayList<>();
        if (!HANDLED_TYPES.contains(typeCode)) p.add("This kind of certificate is not paid out or renewed here.");
        if (!"ACTIVE".equals(status) && !"MATURED".equals(status)) p.add("This certificate holds no money.");
        if (maturity == null || maturity.isAfter(today)) p.add("This certificate matures on " + maturity + ". Paying it out before then is a pre-termination, which is not built yet.");
        if (voucherWaiting) p.add("A voucher for this certificate is already waiting to be approved and posted.");
        if (!hasPayableAccount) p.add("This member has no vouchers-payable account to credit.");
        if (a.expense().signum() < 0 && !isLockIn(typeCode)) p.add("The interest already accrued (" + a.accrued().toPlainString() + ") is more than the interest for the term (" + a.interest().toPlainString() + "). Ask an accountant to look at it.");
        return p;
    }

    /** What a renewal makes of the money: the new certificate is the balance plus interest, less what is taken out and the renewal fee. */
    record Renewal(BigDecimal newPrincipal, BigDecimal withdraw, BigDecimal fee) {}

    /** Problems with the renewal figures, empty when fine. */
    static List<String> renewalProblems(Amounts a, BigDecimal withdraw, boolean charge, BigDecimal minNewPrincipal, int termDays, BigDecimal rate, List<Integer> knownTerms) {
        List<String> p = new ArrayList<>();
        if (withdraw == null || withdraw.signum() < 0) p.add("The amount taken out cannot be negative.");
        else if (withdraw.stripTrailingZeros().scale() > 2) p.add("The amount taken out can have at most 2 decimal places.");
        BigDecimal fee = charge ? TimeDepositRules.ROLLOVER_FEE : BigDecimal.ZERO;
        BigDecimal next = a.due().subtract(withdraw == null ? BigDecimal.ZERO : withdraw).subtract(fee);
        if (next.signum() <= 0) p.add("Nothing is left to renew after what is taken out.");
        else if (minNewPrincipal != null && minNewPrincipal.signum() > 0 && next.compareTo(minNewPrincipal) < 0)
            p.add("The new certificate would be " + next.setScale(2, RoundingMode.HALF_UP).toPlainString() + ", under the minimum of " + minNewPrincipal.setScale(2, RoundingMode.HALF_UP).toPlainString() + ".");
        if (termDays <= 0 || !knownTerms.contains(termDays)) p.add("Choose one of the terms in use: " + knownTerms.stream().sorted().map(String::valueOf).reduce((x, y) -> x + ", " + y).orElse("none") + " days.");
        if (rate == null || rate.signum() <= 0) p.add("Enter the interest rate.");
        else if (rate.compareTo(TimeDepositPlacementRules.MAX_RATE) > 0) p.add("The rate cannot be above " + TimeDepositPlacementRules.MAX_RATE.toPlainString() + "% a year.");
        return p;
    }

    static Renewal renewal(Amounts a, BigDecimal withdraw, boolean charge) {
        BigDecimal fee = charge ? TimeDepositRules.ROLLOVER_FEE : BigDecimal.ZERO;
        return new Renewal(a.due().subtract(withdraw).subtract(fee), withdraw, fee);
    }
}
