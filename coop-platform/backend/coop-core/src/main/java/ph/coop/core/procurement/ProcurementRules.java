package ph.coop.core.procurement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** The purchasing rules that need no database: what a request or order must contain, who may decide it, and the totals. */
final class ProcurementRules {

    private ProcurementRules() {}

    /** One line of a request or an order; {@code price} is the estimated cost of a request or the cost of an order, per unit. */
    record Line(String description, String unit, BigDecimal quantity, BigDecimal price) {}

    static final BigDecimal MAX_QUANTITY = new BigDecimal("999999999"), MAX_PRICE = new BigDecimal("999999999.99");

    /** The problems with the lines, empty when they can be used. */
    static List<String> lineProblems(List<Line> lines) {
        List<String> out = new ArrayList<>();
        if (lines == null || lines.isEmpty()) { out.add("Add at least one line."); return out; }
        if (lines.size() > 200) out.add("A document can have at most 200 lines.");
        int n = 0;
        for (Line l : lines) {
            n++;
            String at = "Line " + n + ": ";
            if (l.description() == null || l.description().isBlank()) out.add(at + "describe what is wanted.");
            else if (l.description().length() > 200) out.add(at + "the description is too long (200 characters at most).");
            if (l.unit() == null || l.unit().isBlank()) out.add(at + "give the unit (pc, box, kg ...).");
            else if (l.unit().length() > 20) out.add(at + "the unit is too long (20 characters at most).");
            if (l.quantity() == null || l.quantity().signum() <= 0) out.add(at + "the quantity must be more than zero.");
            else if (l.quantity().compareTo(MAX_QUANTITY) > 0) out.add(at + "the quantity is too large.");
            if (l.price() == null || l.price().signum() < 0) out.add(at + "the price cannot be negative.");
            else if (l.price().compareTo(MAX_PRICE) > 0) out.add(at + "the price is too large.");
        }
        return out;
    }

    /** Quantity x price over the lines, each line rounded half up to the centavo. */
    static BigDecimal total(List<Line> lines) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Line l : lines) sum = sum.add(l.quantity().multiply(l.price()).setScale(2, RoundingMode.HALF_UP));
        return sum;
    }

    /** Request or order number: PR-2026-0001. */
    static String number(String prefix, int year, int sequence) {
        return String.format("%s-%d-%04d", prefix, year, sequence);
    }

    /** The sequence in a number such as PR-2026-0007, or 0 when it is not one for that prefix and year. */
    static int sequenceOf(String number, String prefix, int year) {
        String head = prefix + "-" + year + "-";
        if (number == null || !number.startsWith(head)) return 0;
        try {
            return Integer.parseInt(number.substring(head.length()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Why this person may not decide a document someone else made, or null: nobody approves their own work, whatever their role. */
    static String approverProblem(String maker, String decider, String what) {
        if (maker != null && maker.equalsIgnoreCase(decider)) return "You made this " + what + ", so someone else must decide it.";
        return null;
    }

    /** The problems with a request before it is submitted. */
    static List<String> requestProblems(String purpose, LocalDate requestDate, LocalDate neededBy, List<Line> lines) {
        List<String> out = new ArrayList<>();
        if (purpose == null || purpose.isBlank()) out.add("Say what the purchase is for.");
        else if (purpose.length() > 255) out.add("The purpose is too long (255 characters at most).");
        if (neededBy != null && neededBy.isBefore(requestDate)) out.add("The date needed cannot be before the request date.");
        out.addAll(lineProblems(lines));
        return out;
    }

    /** The problems with an order before it is saved. */
    static List<String> orderProblems(boolean supplierActive, String requestStatus, LocalDate orderDate, LocalDate expected, List<Line> lines) {
        List<String> out = new ArrayList<>();
        if (!supplierActive) out.add("The supplier is not active.");
        if (requestStatus != null && !requestStatus.equals("APPROVED")) out.add("The purchase request is " + requestStatus.toLowerCase() + ", only an approved request can be ordered.");
        if (expected != null && expected.isBefore(orderDate)) out.add("The expected date cannot be before the order date.");
        out.addAll(lineProblems(lines));
        return out;
    }

    /** An order line as far as receiving goes: how much was ordered and how much has come in. */
    record Open(int lineNo, BigDecimal ordered, BigDecimal received) {}

    /** What one receipt takes in on an order line. */
    record Take(int lineNo, BigDecimal quantity) {}

    /** The problems with a receipt: the order must be approved (or partly received), each line must exist once, be above zero and not exceed what is still to come. */
    static List<String> receiptProblems(String orderStatus, List<Open> lines, List<Take> takes) {
        List<String> out = new ArrayList<>();
        if (!orderStatus.equals("APPROVED") && !orderStatus.equals("PARTIAL")) out.add("The order is " + orderStatus.toLowerCase() + ", goods can be received only on an approved order that is not yet complete.");
        if (takes == null || takes.isEmpty()) { out.add("Enter the quantity received on at least one line."); return out; }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Take t : takes) {
            Open o = lines.stream().filter((l) -> l.lineNo() == t.lineNo()).findFirst().orElse(null);
            if (o == null) { out.add("The order has no line " + t.lineNo() + "."); continue; }
            if (!seen.add(t.lineNo())) { out.add("Line " + t.lineNo() + " is listed twice."); continue; }
            if (t.quantity() == null || t.quantity().signum() <= 0) out.add("Line " + t.lineNo() + ": the quantity must be more than zero.");
            else if (t.quantity().compareTo(o.ordered().subtract(o.received())) > 0) out.add("Line " + t.lineNo() + ": only " + o.ordered().subtract(o.received()).stripTrailingZeros().toPlainString() + " more is expected, not " + t.quantity().stripTrailingZeros().toPlainString() + ".");
        }
        return out;
    }

    /** The order's status once these quantities have been received: APPROVED when nothing has come in, PARTIAL while some is missing, RECEIVED when every line is complete. */
    static String statusAfter(List<Open> lines) {
        boolean any = lines.stream().anyMatch((l) -> l.received().signum() > 0);
        boolean all = lines.stream().allMatch((l) -> l.received().compareTo(l.ordered()) >= 0);
        return all ? "RECEIVED" : any ? "PARTIAL" : "APPROVED";
    }
}
