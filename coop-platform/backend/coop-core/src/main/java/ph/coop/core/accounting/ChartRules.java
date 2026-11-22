package ph.coop.core.accounting;

/**
 * Rules of the chart of accounts beyond numbering: the normal balance follows the account's type (debit for assets and expenses; credit for liabilities,
 * equity, revenues and net income), and an account that runs the other way is a contra account (an allowance for losses under assets, accumulated
 * depreciation). Only a contra account's balance is stored; every other account takes its type's. Pure logic.
 */
final class ChartRules {

    private ChartRules() {}

    static final int MAX_DESCRIPTION = 250;

    /** The normal balance of a type: "D" or "C". */
    static String normalBalance(int type) {
        return type == 1 || type == 5 ? "D" : "C";
    }

    /** What to store for a chosen normal balance: null when it is the type's own (nothing to record), else the contra side. */
    static String stored(int type, String chosen) {
        if (chosen == null || chosen.isBlank()) return null;
        String c = chosen.trim().toUpperCase(java.util.Locale.ROOT);
        if (!c.equals("D") && !c.equals("C")) throw new IllegalArgumentException("The normal balance is D (debit) or C (credit).");
        return c.equals(normalBalance(type)) ? null : c;
    }

    /** A description trimmed, or null when empty; refused when longer than the column. */
    static String description(String d) {
        if (d == null || d.isBlank()) return null;
        String t = d.trim();
        if (t.length() > MAX_DESCRIPTION) throw new IllegalArgumentException("The description can be up to " + MAX_DESCRIPTION + " characters; shorten it and save again.");
        return t;
    }
}
