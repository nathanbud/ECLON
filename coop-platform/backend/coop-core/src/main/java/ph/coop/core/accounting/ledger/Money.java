package ph.coop.core.accounting.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An amount of pesos with exactly two decimals. Postings carry Money, never a double or a BigDecimal of any scale, so a voucher's lines always add
 * up exactly. A value with more than two decimals is refused rather than rounded, because a posting must never change an amount silently.
 * Positive is a debit and negative a credit, as on the old system's voucher lines.
 */
public record Money(BigDecimal value) implements Comparable<Money> {

    public static final Money ZERO = new Money(BigDecimal.ZERO);

    public Money {
        if (value == null) throw new IllegalArgumentException("An amount is required.");
        if (value.stripTrailingZeros().scale() > 2) throw new IllegalArgumentException("An amount has at most two decimals: " + value.toPlainString());
        value = value.setScale(2, RoundingMode.UNNECESSARY);
    }

    public static Money of(BigDecimal v) { return new Money(v); }

    public static Money of(String v) { return new Money(new BigDecimal(v)); }

    public Money plus(Money o) { return new Money(value.add(o.value)); }

    public Money negate() { return new Money(value.negate()); }

    public int signum() { return value.signum(); }

    public boolean isZero() { return value.signum() == 0; }

    /** The debit side as the general ledger keeps it (0 for a credit). */
    public BigDecimal debit() { return value.signum() > 0 ? value : BigDecimal.ZERO.setScale(2); }

    /** The credit side as the general ledger keeps it: negative (0 for a debit). */
    public BigDecimal credit() { return value.signum() < 0 ? value : BigDecimal.ZERO.setScale(2); }

    @Override
    public int compareTo(Money o) { return value.compareTo(o.value); }

    @Override
    public String toString() { return value.toPlainString(); }
}
