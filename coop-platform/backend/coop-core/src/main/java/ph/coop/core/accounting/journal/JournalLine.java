package ph.coop.core.accounting.journal;

import java.math.BigDecimal;

/** One debit or credit line; exactly one of the two amounts is positive. */
public record JournalLine(String accountCode, BigDecimal debit, BigDecimal credit) {

    public JournalLine {
        if (accountCode == null || accountCode.isBlank()) throw new IllegalArgumentException("accountCode required");
        if (debit == null || credit == null) throw new IllegalArgumentException("amounts required");
        if (debit.signum() < 0 || credit.signum() < 0) throw new IllegalArgumentException("amounts must not be negative");
        if ((debit.signum() > 0) == (credit.signum() > 0)) {
            throw new IllegalArgumentException("a line must carry either a debit or a credit");
        }
    }

    public static JournalLine debit(String account, String amount) {
        return new JournalLine(account, new BigDecimal(amount), BigDecimal.ZERO);
    }

    public static JournalLine credit(String account, String amount) {
        return new JournalLine(account, BigDecimal.ZERO, new BigDecimal(amount));
    }

    JournalLine swapped() { return new JournalLine(accountCode, credit, debit); }
}
