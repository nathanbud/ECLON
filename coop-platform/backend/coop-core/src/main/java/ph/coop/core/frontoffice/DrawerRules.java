package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** Rules for drawer cash limits and the end-of-day count. No database access here. */
final class DrawerRules {

    private DrawerRules() {}

    /** The limit that applies to a drawer: its own, otherwise the default for its kind (kept under number 0), otherwise none. */
    static BigDecimal limitFor(Map<Integer, BigDecimal> byNumber, int number) {
        BigDecimal own = byNumber.get(number);
        return own != null ? own : byNumber.get(0);
    }

    /** How far a drawer is over its limit, or zero when it is within it or has no limit. */
    static BigDecimal excess(BigDecimal balance, BigDecimal limit) {
        if (limit == null || balance == null || balance.compareTo(limit) <= 0) return BigDecimal.ZERO;
        return balance.subtract(limit).setScale(2, RoundingMode.HALF_UP);
    }

    /** Counted minus expected: negative is short, positive is over. */
    static BigDecimal variance(BigDecimal expected, BigDecimal counted) {
        return counted.subtract(expected).setScale(2, RoundingMode.HALF_UP);
    }

    /** What stops the head cashier returning cash to the vault, or null when the day is ready to close. */
    static String notReadyToClose(int waiting, List<String> notClosed) {
        StringBuilder m = new StringBuilder();
        if (waiting > 0) m.append(waiting == 1 ? "1 hand-off is still waiting to be counted or settled. " : waiting + " hand-offs are still waiting to be counted or settled. ");
        if (!notClosed.isEmpty()) m.append(String.join(", ", notClosed)).append(notClosed.size() == 1 ? " has not counted the drawer today. " : " have not counted their drawers today. ");
        return m.isEmpty() ? null : "Not ready to close: " + m.toString().trim();
    }

    /** The start of the message the app shows when the day is not ready to close; the screen asks for a reason when it sees it. */
    static final String NOT_READY_PREFIX = "Not ready to close:";
}
