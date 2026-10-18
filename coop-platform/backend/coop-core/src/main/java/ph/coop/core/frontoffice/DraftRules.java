package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Checks for a front office draft. Pure logic, no database access. A draft is never posted, so these checks are about
 * catching mistakes early: real accounts, sensible amounts, and whether debits equal credits.
 */
final class DraftRules {

    private DraftRules() {}

    /** slc and slt name the member's account (class and type) when the entry is for one; both are null for a plain ledger entry. */
    record Line(String accountCode, String accountDesc, String acctNum, String debit, String credit, Integer dept, Integer slc, Integer slt) {}

    record Denomination(int typeId, String qty) {}

    record Payload(int trCode, String date, String batchNo, Integer dept, Integer clientId, String clientName, String refNo,
                   String explanation, List<Line> lines, List<Denomination> denominations) {}

    record Totals(BigDecimal debit, BigDecimal credit, BigDecimal difference, boolean balanced) {}

    static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");

    /** The draft in tidy form, or an {@link IllegalArgumentException} saying what is wrong. */
    static Payload validate(Payload p, Set<Integer> types, Set<String> accounts, Set<Integer> denomTypes) {
        if (p == null) throw new IllegalArgumentException("There is nothing to save.");
        if (!types.contains(p.trCode())) throw new IllegalArgumentException("That transaction type cannot be used here.");
        String date = parseDate(p.date());
        String batch = limit(p.batchNo(), 8, "Batch number");
        String ref = limit(p.refNo(), 200, "Reference number");
        String explanation = limit(p.explanation(), 2000, "Explanation");
        String clientName = limit(p.clientName(), 100, "Client name");

        if (p.lines() == null || p.lines().isEmpty()) throw new IllegalArgumentException("Add at least one entry.");
        if (p.lines().size() > 200) throw new IllegalArgumentException("A transaction can have at most 200 entries.");
        List<Line> lines = new ArrayList<>();
        int n = 0;
        for (Line l : p.lines()) {
            n++;
            String code = l.accountCode() == null ? "" : l.accountCode().trim();
            if (code.isEmpty()) throw new IllegalArgumentException("Entry " + n + " needs an account.");
            if (!accounts.contains(code)) throw new IllegalArgumentException("Entry " + n + ": account " + code + " does not exist or is disabled.");
            BigDecimal debit = amount(l.debit(), "Entry " + n + " debit");
            BigDecimal credit = amount(l.credit(), "Entry " + n + " credit");
            if ((debit.signum() > 0) == (credit.signum() > 0)) {
                throw new IllegalArgumentException("Entry " + n + " must have either a debit or a credit, not both and not neither.");
            }
            lines.add(new Line(code, limit(l.accountDesc(), 100, "Account description"), limit(l.acctNum(), 20, "Account number"),
                debit.toPlainString(), credit.toPlainString(), l.dept(), l.slc(), l.slt()));
        }

        List<Denomination> denoms = new ArrayList<>();
        if (p.denominations() != null) {
            for (Denomination d : p.denominations()) {
                if (!denomTypes.contains(d.typeId())) throw new IllegalArgumentException("Unknown cash denomination.");
                int qty;
                try {
                    qty = Integer.parseInt(d.qty() == null || d.qty().isBlank() ? "0" : d.qty().trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Denomination counts must be whole numbers.");
                }
                if (qty < 0 || qty > 1_000_000) throw new IllegalArgumentException("A denomination count is out of range.");
                if (qty > 0) denoms.add(new Denomination(d.typeId(), Integer.toString(qty)));
            }
        }
        return new Payload(p.trCode(), date, batch, p.dept(), p.clientId(), clientName, ref, explanation, lines, denoms);
    }

    static Totals totals(List<Line> lines) {
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (Line l : lines) {
            debit = debit.add(new BigDecimal(l.debit()));
            credit = credit.add(new BigDecimal(l.credit()));
        }
        BigDecimal diff = debit.subtract(credit);
        return new Totals(debit, credit, diff, diff.signum() == 0 && debit.signum() > 0);
    }

    private static String parseDate(String s) {
        try {
            return LocalDate.parse(s == null ? "" : s.trim()).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("The transaction date is not a valid date.");
        }
    }

    private static BigDecimal amount(String s, String label) {
        String v = s == null || s.isBlank() ? "0" : s.trim().replace(",", "");
        try {
            BigDecimal a = new BigDecimal(v);
            if (a.signum() < 0) throw new IllegalArgumentException(label + " cannot be negative.");
            if (a.scale() > 2) throw new IllegalArgumentException(label + " can have at most two decimal places.");
            if (a.compareTo(MAX_AMOUNT) > 0) throw new IllegalArgumentException(label + " is too large.");
            return a.setScale(2);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a number.");
        }
    }

    private static String limit(String s, int max, String label) {
        String v = s == null ? "" : s.trim();
        if (v.length() > max) throw new IllegalArgumentException(label + " is too long (at most " + max + " characters).");
        return v;
    }
}
