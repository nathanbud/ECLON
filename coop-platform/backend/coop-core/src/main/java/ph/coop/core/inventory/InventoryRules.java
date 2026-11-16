package ph.coop.core.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The stock rules that need no database: which way each kind of movement goes, average cost, and what a movement must satisfy. */
final class InventoryRules {

    private InventoryRules() {}

    static final BigDecimal MAX_QUANTITY = new BigDecimal("999999999");
    static final Set<String> TYPES = Set.of("RECEIVE", "ISSUE", "SALE", "RETURN", "TRANSFER", "ADJUSTMENT", "DAMAGE", "EXPIRY");

    /** +1 stock comes in, -1 it goes out, 0 the caller says (a transfer has both, an adjustment goes either way). */
    static int sign(String type) {
        return switch (type) {
            case "RECEIVE", "RETURN" -> 1;
            case "ISSUE", "SALE", "DAMAGE", "EXPIRY" -> -1;
            default -> 0;
        };
    }

    /** The problems with a quantity: above zero, at most three decimals, not absurd. */
    static List<String> quantityProblems(BigDecimal q) {
        List<String> out = new ArrayList<>();
        if (q == null || q.signum() <= 0) out.add("The quantity must be more than zero.");
        else if (q.scale() > 3 && q.stripTrailingZeros().scale() > 3) out.add("The quantity can have at most three decimals.");
        else if (q.compareTo(MAX_QUANTITY) > 0) out.add("The quantity is too large.");
        return out;
    }

    /** The moving average cost after {@code inQty} units at {@code inCost} join {@code onHand} units at {@code cost}; the unit cost of the receipt when nothing was on hand. */
    static BigDecimal movingAverage(BigDecimal onHand, BigDecimal cost, BigDecimal inQty, BigDecimal inCost) {
        BigDecimal total = onHand.add(inQty);
        if (onHand.signum() <= 0 || total.signum() <= 0) return inCost.setScale(4, RoundingMode.HALF_UP);
        return onHand.multiply(cost).add(inQty.multiply(inCost)).divide(total, 4, RoundingMode.HALF_UP);
    }

    /** Quantity x unit cost to the centavo. */
    static BigDecimal value(BigDecimal quantity, BigDecimal unitCost) {
        return quantity.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
    }

    /** What a batch needs: a product that is tracked by batch must name one (and an expiry date when it expires), one that is not must not. */
    static List<String> batchProblems(boolean trackBatch, boolean trackExpiry, String batchNo, LocalDate expiry, boolean newBatch) {
        List<String> out = new ArrayList<>();
        boolean named = batchNo != null && !batchNo.isBlank();
        if (trackBatch && !named) out.add("This product is tracked by batch: give the batch number.");
        if (!trackBatch && named) out.add("This product is not tracked by batch.");
        if (named && batchNo.trim().length() > 30) out.add("The batch number is too long (30 characters at most).");
        if (trackBatch && trackExpiry && newBatch && expiry == null) out.add("This product expires: give the expiry date of the new batch.");
        return out;
    }

    /** True when stock of this batch may not be sold or issued any more. */
    static boolean expired(LocalDate expiry, LocalDate today) {
        return expiry != null && expiry.isBefore(today);
    }

    /** Why this much cannot go out, or null. */
    static String shortage(BigDecimal onHand, BigDecimal out) {
        if (onHand.compareTo(out) >= 0) return null;
        return "Only " + onHand.stripTrailingZeros().toPlainString() + " on hand, so " + out.stripTrailingZeros().toPlainString() + " cannot go out.";
    }

    /** The stock effect of a line of an adjustment: damage and expiry only take stock out, an adjustment may go either way but not by zero. */
    static List<String> adjustmentLineProblems(String type, BigDecimal effect) {
        List<String> out = new ArrayList<>();
        if (effect == null || effect.signum() == 0) { out.add("An adjustment line cannot be zero."); return out; }
        if ((type.equals("DAMAGE") || type.equals("EXPIRY")) && effect.signum() > 0) out.add(type.toLowerCase() + " can only take stock out.");
        if (effect.abs().compareTo(MAX_QUANTITY) > 0) out.add("The quantity is too large.");
        if (effect.stripTrailingZeros().scale() > 3) out.add("The quantity can have at most three decimals.");
        return out;
    }

    /** What a count says the books are off by: counted minus what the books showed. */
    static BigDecimal variance(BigDecimal system, BigDecimal counted) {
        return counted.subtract(system);
    }

    /** True when stock is at or under the level that asks for a new order (a level of zero means none is set). */
    static boolean low(BigDecimal onHand, BigDecimal reorderLevel) {
        return reorderLevel.signum() > 0 && onHand.compareTo(reorderLevel) <= 0;
    }

    /** Inventory transaction number: INV-2026-000001. */
    static String txnNumber(int year, int sequence) {
        return String.format("INV-%d-%06d", year, sequence);
    }

    static int txnSequence(String number, int year) {
        String head = "INV-" + year + "-";
        if (number == null || !number.startsWith(head)) return 0;
        try {
            return Integer.parseInt(number.substring(head.length()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
