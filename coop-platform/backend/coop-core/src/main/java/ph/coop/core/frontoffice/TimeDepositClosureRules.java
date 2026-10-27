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
    record Amounts(BigDecimal principal, BigDecimal interest, BigDecimal accrued, BigDecimal expense, BigDecimal termFee) {
        Amounts(BigDecimal principal, BigDecimal interest, BigDecimal accrued, BigDecimal expense) { this(principal, interest, accrued, expense, BigDecimal.ZERO); }

        /** What the member is owed: principal plus interest, less the pre-termination charge. */
        BigDecimal due() { return principal.add(interest).subtract(termFee); }
    }

    /** Credited to 442201 on a pre-termination (the same account as every real one: vouchers 2025 and 2026). */
    static final String TERMINATION_FEE_ACCOUNT = "442201";
    static final BigDecimal SHORT_TERM_FEE = new BigDecimal("100.00");
    static final BigDecimal SHORT_TERM_RATE = BigDecimal.ONE;

    /**
     * Pre-termination, mapped on 2026-10-27 from 153 real vouchers of 2025 and 2026 (documentation/time-deposit-map.md). Short Term: interest = balance x 1% x days since
     * placement / 360 (exact on 100 of 131), a flat 100.00 charge. Lock In: the charge is 1% of the principal in the first year and 5% after, and the accrued interest is
     * normally taken back with nothing paid ({@code interestPaid}, typed, defaults to 0). What was accrued is debited back and the difference goes to 50105 (a credit when
     * the accrual was more than what is paid).
     */
    static Amounts pretermAmounts(int typeCode, BigDecimal balance, int daysHeld, BigDecimal accrued, BigDecimal pretermRate, BigDecimal interestPaid) {
        if (isLockIn(typeCode)) {
            BigDecimal paid = interestPaid == null ? BigDecimal.ZERO : interestPaid;
            BigDecimal pct = daysHeld <= 365 ? BigDecimal.ONE : new BigDecimal("5");
            return new Amounts(balance, paid, accrued, paid.subtract(accrued), balance.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        }
        BigDecimal rate = pretermRate == null ? SHORT_TERM_RATE : pretermRate;
        BigDecimal interest = balance.multiply(rate).multiply(BigDecimal.valueOf(Math.max(daysHeld, 0))).divide(BigDecimal.valueOf(36000), 2, RoundingMode.HALF_UP);
        return new Amounts(balance, interest, accrued, interest.subtract(accrued), SHORT_TERM_FEE);
    }

    static List<String> pretermProblems(int typeCode, BigDecimal certRate, BigDecimal pretermRate, BigDecimal interestPaid, BigDecimal accrued, LocalDate placed, LocalDate today) {
        List<String> p = new ArrayList<>();
        if (placed == null || placed.isAfter(today)) p.add("This certificate has no valid placement date.");
        if (isLockIn(typeCode)) {
            if (interestPaid != null && (interestPaid.signum() < 0 || interestPaid.compareTo(accrued) > 0 || interestPaid.stripTrailingZeros().scale() > 2))
                p.add("The interest paid on a pre-terminated Lock In must be from 0 to what was accrued (" + accrued.toPlainString() + ").");
        } else if (pretermRate != null && (pretermRate.signum() < 0 || (certRate != null && pretermRate.compareTo(certRate) > 0)))
            p.add("The pre-termination rate must be from 0 to the certificate's own rate.");
        return p;
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
        if (maturity == null) p.add("This certificate has no maturity date.");
        if (voucherWaiting) p.add("A voucher for this certificate is already waiting to be approved and posted.");
        if (!hasPayableAccount) p.add("This member has no vouchers-payable account to credit.");
        if (a.expense().signum() < 0 && !isLockIn(typeCode) && (maturity == null || !maturity.isAfter(today))) p.add("The interest already accrued (" + a.accrued().toPlainString() + ") is more than the interest for the term (" + a.interest().toPlainString() + "). Ask an accountant to look at it.");
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
