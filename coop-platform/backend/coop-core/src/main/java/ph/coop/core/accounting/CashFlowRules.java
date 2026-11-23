package ph.coop.core.accounting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The Statement of Cash Flows by the indirect method, from the ledger balances at the start and the end of a period. Net surplus, then the change in every
 * balance-sheet account that is not cash, each in its class (operating, investing, financing); together they explain the change in cash.
 * <p>
 * Balances are as the ledger keeps them: debit positive, credit negative. Because every entry balances, the change in cash equals minus the change in all other
 * accounts, so each account's cash effect is minus its change: an asset that grew used cash, a liability or equity that grew brought it in. The year-end close
 * moves the year's income and expenses into equity; those closing amounts count as net surplus, not as a financing change. Whatever the changes do not explain
 * (entries that do not balance in the old data) is shown as its own line, never hidden. Pure logic.
 */
final class CashFlowRules {

    private CashFlowRules() {}

    static final String CASH = "CASH";
    static final String OPERATING = "OPERATING";
    static final String INVESTING = "INVESTING";
    static final String FINANCING = "FINANCING";
    static final List<String> CLASSES = List.of(CASH, OPERATING, INVESTING, FINANCING);

    /** An account of the chart as the statement needs it: its type (1 to 6), name, the names of the headings above it (nearest first), and its class. */
    record Account(String code, String name, int type, List<String> headings, String category) {}

    record Line(String accountCode, String description, BigDecimal amount) {}

    record Activity(String category, String name, List<Line> lines, BigDecimal total) {}

    record Statement(List<Activity> activities, BigDecimal netSurplus, BigDecimal netChange, BigDecimal cashBeginning, BigDecimal cashEnding, BigDecimal unexplained) {}

    /**
     * The class an account most likely has, from its type and the names of the headings above it: cash and cash equivalents; current assets and the
     * accumulated depreciation (added back) are operating, other assets investing; borrowings and long-term liabilities financing, other liabilities (members'
     * deposits among them) operating; equity financing. Null for income and expense accounts, which make net surplus.
     */
    static String suggest(int type, String name, List<String> headings) {
        String own = upper(name);
        List<String> up = headings.stream().map(CashFlowRules::upper).toList();
        if (type == 1) {
            if (up.stream().anyMatch((h) -> h.contains("CASH AND CASH EQUIVALENT") || h.startsWith("CASH ON HAND") || h.startsWith("CASH IN BANK"))
                || own.contains("CASH AND CASH EQUIVALENT")) return CASH;
            if (own.contains("DEPRECIATION")) return OPERATING;
            if (up.stream().anyMatch((h) -> h.contains("CURRENT ASSETS") && !h.contains("NON-CURRENT") && !h.contains("NONCURRENT"))) return OPERATING;
            return INVESTING;
        }
        if (type == 2) {
            boolean borrowed = own.contains("LOANS PAYABLE") || own.contains("BORROWING") || own.contains("NOTES PAYABLE")
                || up.stream().anyMatch((h) -> h.contains("LOANS PAYABLE") || h.contains("BORROWING") || h.contains("NON-CURRENT") || h.contains("NONCURRENT") || h.contains("LONG-TERM") || h.contains("LONG TERM"));
            return borrowed ? FINANCING : OPERATING;
        }
        if (type == 3) return FINANCING;
        return null;
    }

    static String className(String category) {
        return switch (category) {
            case CASH -> "Cash and cash equivalents";
            case OPERATING -> "Operating activities";
            case INVESTING -> "Investing activities";
            case FINANCING -> "Financing activities";
            default -> category;
        };
    }

    /**
     * The statement. {@code begin} and {@code end} are the balances per account at the day before the period and at its last day; {@code closing} the year-end
     * closing amounts posted to equity accounts within the period (credit negative). {@code accounts} gives each account's type and class.
     */
    static Statement build(Map<String, BigDecimal> begin, Map<String, BigDecimal> end, Map<String, BigDecimal> closing, Map<String, Account> accounts) {
        TreeSet<String> codes = new TreeSet<>();
        codes.addAll(begin.keySet());
        codes.addAll(end.keySet());
        BigDecimal profitAndLoss = BigDecimal.ZERO; // change of income and expense accounts
        BigDecimal cashBegin = BigDecimal.ZERO, cashEnd = BigDecimal.ZERO;
        Map<String, List<Line>> byClass = new LinkedHashMap<>();
        for (String c : List.of(OPERATING, INVESTING, FINANCING)) byClass.put(c, new ArrayList<>());
        BigDecimal closed = BigDecimal.ZERO;
        for (BigDecimal v : closing.values()) closed = closed.add(v);
        for (String code : codes) {
            BigDecimal b = begin.getOrDefault(code, BigDecimal.ZERO), e = end.getOrDefault(code, BigDecimal.ZERO);
            BigDecimal change = e.subtract(b);
            Account a = accounts.get(code);
            int type = a == null ? 0 : a.type();
            if (type >= 4) { profitAndLoss = profitAndLoss.add(change); continue; }
            String cat = a == null || a.category() == null ? OPERATING : a.category();
            if (CASH.equals(cat)) { cashBegin = cashBegin.add(b); cashEnd = cashEnd.add(e); continue; }
            // the closing of the year is net surplus, not a change in equity
            BigDecimal adjusted = change.subtract(closing.getOrDefault(code, BigDecimal.ZERO));
            if (adjusted.signum() == 0) continue;
            byClass.get(cat).add(new Line(code, a == null ? code : a.name(), adjusted.negate()));
        }
        BigDecimal netSurplus = profitAndLoss.add(closed).negate();
        List<Activity> activities = new ArrayList<>();
        BigDecimal explained = netSurplus;
        for (var e : byClass.entrySet()) {
            List<Line> lines = new ArrayList<>(e.getValue());
            if (OPERATING.equals(e.getKey())) lines.add(0, new Line("", "Net surplus for the period", netSurplus));
            BigDecimal t = BigDecimal.ZERO;
            for (Line l : lines) t = t.add(l.amount());
            activities.add(new Activity(e.getKey(), className(e.getKey()), lines, t));
            explained = explained.add(OPERATING.equals(e.getKey()) ? t.subtract(netSurplus) : t);
        }
        BigDecimal netChange = cashEnd.subtract(cashBegin);
        return new Statement(activities, netSurplus, netChange, cashBegin, cashEnd, netChange.subtract(explained));
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }
}
