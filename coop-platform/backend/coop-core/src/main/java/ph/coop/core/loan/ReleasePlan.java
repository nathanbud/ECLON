package ph.coop.core.loan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The lines of a loan release voucher (Cash Voucher, module 2 type 21), see loan-posting-map.md: one debit for the new loan, a credit
 * for the service fee, a credit for every other deduction the clerk typed, and the rest as net proceeds into the member's vouchers
 * payable. No database access. The clerk types every deduction: the old formulas for share capital cannot be reproduced yet.
 */
public final class ReleasePlan {

    private ReleasePlan() {}

    /** Where a deduction goes: a member's ledger account (class, type, ref) or a plain general ledger account. */
    public record Deduction(String description, int slc, int slt, int refNo, int event, String glAccount, BigDecimal amount) {}

    /** A voucher line; debits are positive and credits negative, as in `transdt`. */
    public record Line(String description, BigDecimal amount, Deduction target) {}

    public record Plan(BigDecimal loanAmount, BigDecimal totalDeductions, BigDecimal netProceeds, List<Line> lines, List<String> problems) {
        public boolean ok() { return problems.isEmpty(); }
    }

    /** The service fee as a percentage of the loan (1 for type 48, 3 for type 49), rounded to the cent. */
    public static BigDecimal serviceFee(BigDecimal loanAmount, BigDecimal percent) {
        return loanAmount.multiply(percent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    public static Plan build(BigDecimal loanAmount, BigDecimal serviceFee, List<Deduction> deductions) {
        List<String> problems = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        if (loanAmount == null || loanAmount.signum() <= 0) {
            problems.add("The loan amount must be above zero.");
            return new Plan(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, lines, problems);
        }
        lines.add(new Line("Loan released", loanAmount, null));
        BigDecimal total = BigDecimal.ZERO;
        if (serviceFee != null && serviceFee.signum() > 0) {
            lines.add(new Line("Filing fee/Service fee", serviceFee.negate(), null));
            total = total.add(serviceFee);
        }
        for (Deduction d : deductions) {
            if (d.amount() == null || d.amount().signum() <= 0) {
                problems.add("Deduction \"" + d.description() + "\" must be above zero.");
                continue;
            }
            if (d.amount().stripTrailingZeros().scale() > 2) problems.add("Deduction \"" + d.description() + "\" can have at most 2 decimal places.");
            if (d.glAccount() == null && d.slc() == 0) problems.add("Deduction \"" + d.description() + "\" has no account to credit.");
            lines.add(new Line(d.description(), d.amount().negate(), d));
            total = total.add(d.amount());
        }
        BigDecimal net = loanAmount.subtract(total);
        if (net.signum() < 0) problems.add("The deductions (" + total.toPlainString() + ") are more than the loan (" + loanAmount.toPlainString() + ").");
        else lines.add(new Line("Net proceeds", net.negate(), null));
        BigDecimal sum = lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (problems.isEmpty() && sum.signum() != 0) problems.add("The voucher does not balance.");
        return new Plan(loanAmount, total, net, lines, problems);
    }
}
