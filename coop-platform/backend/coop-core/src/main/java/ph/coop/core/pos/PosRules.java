package ph.coop.core.pos;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import ph.coop.core.inventory.StockService.BatchStock;

/** The point-of-sale rules that need no database: line and sale totals, discounts, which batch is sold first, what a payment must satisfy, and refunds. */
final class PosRules {

    private PosRules() {}

    static final Set<String> METHODS = Set.of("CASH", "CARD", "OTHER");
    static final BigDecimal MAX_QUANTITY = new BigDecimal("999999");

    /** Rounds half up to the centavo. */
    static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** Quantity x price, rounded to the centavo. */
    static BigDecimal gross(BigDecimal quantity, BigDecimal price) {
        return money(quantity.multiply(price));
    }

    /** What a promotion of {@code percent} takes off a line. */
    static BigDecimal promoOff(BigDecimal quantity, BigDecimal price, BigDecimal percent) {
        return money(gross(quantity, price).multiply(percent).divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP));
    }

    /** What a sale-level discount takes off the subtotal: a percent, or a fixed amount that can never exceed the subtotal. */
    static BigDecimal saleDiscount(String kind, BigDecimal value, BigDecimal subtotal) {
        if (kind.equals("PERCENT")) return money(subtotal.multiply(value).divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP));
        return money(value.min(subtotal));
    }

    /** The problems with a quantity sold: above zero, at most three decimals. */
    static List<String> quantityProblems(BigDecimal q) {
        List<String> out = new ArrayList<>();
        if (q == null || q.signum() <= 0) out.add("The quantity must be more than zero.");
        else if (q.stripTrailingZeros().scale() > 3) out.add("The quantity can have at most three decimals.");
        else if (q.compareTo(MAX_QUANTITY) > 0) out.add("The quantity is too large.");
        return out;
    }

    /** What is taken from one batch. */
    record Allocation(long batchId, String batchNo, BigDecimal quantity) {}

    /**
     * Takes {@code quantity} from the batches in the order given (earliest expiry first), skipping any that expired before {@code today}.
     * Returns null when the unexpired stock is not enough.
     */
    static List<Allocation> allocate(List<BatchStock> batches, BigDecimal quantity, LocalDate today) {
        List<Allocation> out = new ArrayList<>();
        BigDecimal left = quantity;
        for (BatchStock b : batches) {
            if (left.signum() == 0) break;
            if (b.expiry() != null && b.expiry().isBefore(today)) continue;
            BigDecimal take = b.quantity().min(left);
            if (take.signum() > 0) { out.add(new Allocation(b.batchId(), b.batchNo(), take)); left = left.subtract(take); }
        }
        return left.signum() == 0 ? out : null;
    }

    /** One payment tendered. */
    record Payment(String method, BigDecimal amount) {}

    /** The problems with the payments for a sale of {@code total}: known methods, amounts above zero, enough paid, and only cash can be more than the total (it gives change). */
    static List<String> paymentProblems(BigDecimal total, List<Payment> payments) {
        List<String> out = new ArrayList<>();
        if (payments == null || payments.isEmpty()) { if (total.signum() > 0) out.add("Take a payment."); return out; }
        BigDecimal paid = BigDecimal.ZERO, cash = BigDecimal.ZERO;
        for (Payment p : payments) {
            if (p.method() == null || !METHODS.contains(p.method())) { out.add("The payment method is CASH, CARD or OTHER."); continue; }
            if (p.amount() == null || p.amount().signum() <= 0) { out.add("A payment must be more than zero."); continue; }
            paid = paid.add(p.amount());
            if (p.method().equals("CASH")) cash = cash.add(p.amount());
        }
        if (!out.isEmpty()) return out;
        if (paid.compareTo(total) < 0) out.add("The payments (" + paid.toPlainString() + ") do not cover the total (" + total.toPlainString() + ").");
        else if (paid.subtract(total).compareTo(cash) > 0) out.add("Only cash can be more than the total; the card and other payments must not exceed it.");
        return out;
    }

    /** The change to give: whatever was paid over the total. */
    static BigDecimal change(BigDecimal total, List<Payment> payments) {
        BigDecimal paid = BigDecimal.ZERO;
        for (Payment p : payments) paid = paid.add(p.amount());
        return paid.subtract(total).max(BigDecimal.ZERO);
    }

    /** What is refunded for {@code returning} units out of {@code sold} on a line that totalled {@code lineTotal}, with the sale's discount shared out in proportion. */
    static BigDecimal refund(BigDecimal lineTotal, BigDecimal sold, BigDecimal returning, BigDecimal subtotal, BigDecimal discountTotal) {
        BigDecimal share = lineTotal.multiply(returning).divide(sold, 10, RoundingMode.HALF_UP);
        if (subtotal.signum() > 0 && discountTotal.signum() > 0) share = share.multiply(subtotal.subtract(discountTotal)).divide(subtotal, 10, RoundingMode.HALF_UP);
        return money(share);
    }

    /** The problems with returning {@code requested} of a line that sold {@code sold}, of which {@code alreadyBack} came back or is waiting to. */
    static List<String> returnProblems(BigDecimal sold, BigDecimal alreadyBack, BigDecimal requested) {
        List<String> out = quantityProblems(requested);
        if (out.isEmpty() && requested.compareTo(sold.subtract(alreadyBack)) > 0) out.add("Only " + sold.subtract(alreadyBack).stripTrailingZeros().toPlainString() + " can still be returned.");
        return out;
    }
}
