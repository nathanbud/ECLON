package ph.coop.core.accounting.ledger;

import java.math.BigDecimal;
import java.util.List;

/** The ledger's own rules, without the database: a voucher balances, document numbers and explanations as the old system keeps them. */
public final class LedgerRules {

    private LedgerRules() {}

    /** Why the lines cannot be written, or null. Double entry: at least two lines, none of zero, debits equal to credits. */
    public static String problem(List<Voucher.Line> lines) {
        if (lines.size() < 2) return "A voucher needs at least two lines.";
        if (lines.stream().anyMatch((l) -> l.amount().isZero())) return "A voucher line cannot be zero.";
        Money net = lines.stream().map(Voucher.Line::amount).reduce(Money.ZERO, Money::plus);
        if (!net.isZero()) return "The voucher does not balance: debits " + total(lines).toPlainString() + ", credits " + total(lines).subtract(net.value()).toPlainString() + ".";
        return null;
    }

    /** The debits of the lines (equal to the credits when it balances). */
    public static BigDecimal total(List<Voucher.Line> lines) {
        return lines.stream().map((l) -> l.amount().debit()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The document number in the style's form: {@code *0000123} for a journal, {@code 00000123} for a closing entry. */
    public static String documentNo(Voucher.Style style, long ctl) {
        return switch (style) {
            case GENERAL_JOURNAL -> String.format("*%07d", ctl);
            case CLOSING_ENTRY -> String.format("%08d", ctl);
        };
    }

    /** The header's explanation, at most 200 characters as the old system keeps it. */
    public static String explanation(String text) {
        if (text == null) return "";
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}
