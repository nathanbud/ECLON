package ph.coop.core.bank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Rules of a bank reconciliation. The ledger (book) balance of the bank's account, less the entries the bank has not cleared yet
 * (outstanding), should equal the balance on the bank's statement. Amounts are the ledger's own: debit positive, so a deposit is
 * positive and a withdrawal or cheque is negative. No database access here.
 */
final class BankReconRules {

    private BankReconRules() {}

    /** Zero when reconciled: statement balance minus (book balance minus outstanding entries). */
    static BigDecimal difference(BigDecimal statementBalance, BigDecimal bookBalance, BigDecimal outstandingTotal) {
        return statementBalance.subtract(bookBalance.subtract(outstandingTotal));
    }

    static boolean reconciled(BigDecimal difference) { return difference.signum() == 0; }

    /** Problems with a reconciliation about to be saved, empty when it is fine. {@code lastFinalDate} is null for a first reconciliation. */
    static List<String> checkSave(LocalDate statementDate, BigDecimal statementBalance, LocalDate today, LocalDate lastFinalDate) {
        List<String> p = new ArrayList<>();
        if (statementDate == null) p.add("Enter the statement date.");
        else {
            if (statementDate.isAfter(today)) p.add("The statement date cannot be in the future.");
            if (lastFinalDate != null && !statementDate.isAfter(lastFinalDate)) p.add("This bank was already reconciled up to " + lastFinalDate + ". Use a later statement date.");
        }
        if (statementBalance == null) p.add("Enter the balance on the bank statement.");
        else if (statementBalance.stripTrailingZeros().scale() > 2) p.add("The statement balance can have at most 2 decimal places.");
        return p;
    }

    /** Problems with finalizing, empty when it is fine: the checker must be a different person and the difference must be zero. */
    static List<String> checkFinalize(String preparedBy, String finalizer, BigDecimal difference, String status) {
        List<String> p = new ArrayList<>();
        if (!"DRAFT".equals(status)) p.add("This reconciliation is already " + status.toLowerCase() + ".");
        if (preparedBy.equals(finalizer)) p.add("Someone other than the person who prepared it has to finalize it.");
        if (!reconciled(difference)) p.add("It cannot be finalized while the difference is " + difference.toPlainString() + ".");
        return p;
    }
}
