package ph.coop.core.accounting.close;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The month-end review and the year-end checklist. A practical close: each month the books are reconciled and reviewed and material adjustments posted; at
 * year end every adjustment is made before the books are closed. The application checks what it can (the books balance, entries are posted, banks are
 * reconciled, depreciation is run, business days are ended); the rest an accountant confirms, with a note. An item the application finds wanting can be
 * accepted with an explanation (at least 10 characters), so a known difference does not hold up the close but is on record. Pure logic.
 */
public final class CloseChecklist {

    private CloseChecklist() {}

    public static final String MONTH = "MONTH";
    public static final String YEAR = "YEAR";

    /** What an item is: its key, words, a line of help, and whether the application checks it. */
    public record Definition(String key, String label, String help, boolean automatic) {}

    /** A tick or an explanation an accountant recorded. */
    public record Tick(String note, String by, String at) {}

    /**
     * An item as shown: {@code status} OK, NOT_APPLICABLE or ATTENTION for an automatic one (EXPLAINED once accepted with a note), DONE or OPEN for one an
     * accountant confirms; {@code detail} says what the application found.
     */
    public record Item(String key, String label, String help, boolean automatic, String status, String detail, String note, String by, String at) {
        public boolean settled() {
            return !status.equals("ATTENTION") && !status.equals("OPEN");
        }
    }

    /** What the application found for a month or a year. {@code monthsSignedOff} is null when monthly closing is not required. */
    public record Facts(boolean balanced, String unbalancedBy, int pendingEntries, int banks, int banksReconciled, int assetsDue, int assetsDepreciated,
                        int openBusinessDays, Integer monthsSignedOff, int monthsDue) {}

    private static final List<Definition> COMMON = List.of(
        new Definition("BOOKS_BALANCE", "The books balance", "Debits equal credits for every entry of the period.", true),
        new Definition("ENTRIES_POSTED", "Entries posted", "No journal or memo dated in the period is left as a draft or waiting for approval.", true),
        new Definition("BANKS_RECONCILED", "Banks reconciled", "Each bank account has a finished reconciliation dated in the period's last month.", true),
        new Definition("DEPRECIATION_RUN", "Depreciation run", "The fixed assets in service were depreciated for the period's last month.", true),
        new Definition("BUSINESS_DAYS_ENDED", "Business days ended", "Every business day of the period was ended.", true),
        new Definition("SL_RECONCILED", "Members' accounts agree with the control accounts",
            "The SL reconciliation (Reports > Accounting) was reviewed and any difference explained or corrected.", false),
        new Definition("CASH_COUNTED", "Cash counted", "Cash on hand, the vault and the tellers' drawers were counted and agree with the books.", false));

    private static final List<Definition> MONTH_ONLY = List.of(
        new Definition("MATERIAL_ADJUSTMENTS", "Material adjustments posted",
            "Any adjustment large enough to matter was posted this month (or none was needed).", false));

    private static final List<Definition> YEAR_ONLY = List.of(
        new Definition("MONTHS_REVIEWED", "Every month reviewed", "When monthly closing is required, each month of the year was signed off.", true),
        new Definition("ACCRUALS", "Accruals recorded", "Interest earned and owed, and expenses incurred but not yet paid, are recorded at 31 December.", false),
        new Definition("ALLOWANCE", "Allowance for probable loan losses", "The allowance was reviewed against the loan aging and the provision booked.", false),
        new Definition("PREPAYMENTS", "Prepayments and unearned income", "Prepaid expenses and income received in advance were adjusted to the year.", false),
        new Definition("FIXED_ASSETS", "Fixed assets reviewed", "The register agrees with the ledger (Reports > Fixed assets against the ledger); disposals and impairments recorded.", false),
        new Definition("INVENTORY", "Inventory counted", "A year-end stock count was agreed to the books (or the cooperative keeps no inventory).", false));

    public static List<Definition> definitions(String scope) {
        List<Definition> d = new ArrayList<>(COMMON);
        d.addAll(MONTH.equals(scope) ? MONTH_ONLY : YEAR_ONLY);
        return d;
    }

    /** The items of a month or a year from what the application found and what accountants recorded. */
    public static List<Item> evaluate(String scope, Facts f, Map<String, Tick> ticks) {
        List<Item> out = new ArrayList<>();
        for (Definition d : definitions(scope)) {
            Tick t = ticks.get(d.key());
            if (!d.automatic()) {
                out.add(new Item(d.key(), d.label(), d.help(), false, t == null ? "OPEN" : "DONE", null, t == null ? null : t.note(), t == null ? null : t.by(), t == null ? null : t.at()));
                continue;
            }
            String[] found = check(d.key(), f);
            String status = found[0];
            if (status.equals("ATTENTION") && t != null) status = "EXPLAINED";
            out.add(new Item(d.key(), d.label(), d.help(), true, status, found[1], t == null ? null : t.note(), t == null ? null : t.by(), t == null ? null : t.at()));
        }
        return out;
    }

    private static String[] check(String key, Facts f) {
        return switch (key) {
            case "BOOKS_BALANCE" -> f.balanced() ? ok("Debits equal credits.") : attention("Debits and credits differ by " + f.unbalancedBy() + ".");
            case "ENTRIES_POSTED" -> f.pendingEntries() == 0 ? ok("Nothing is waiting.")
                : attention(f.pendingEntries() + (f.pendingEntries() == 1 ? " entry is" : " entries are") + " not posted yet.");
            case "BANKS_RECONCILED" -> f.banks() == 0 ? na("No bank account is set up.") : f.banksReconciled() >= f.banks() ? ok("All " + f.banks() + " reconciled.")
                : attention((f.banks() - f.banksReconciled()) + " of " + f.banks() + " bank accounts have no finished reconciliation.");
            case "DEPRECIATION_RUN" -> f.assetsDue() == 0 ? na("No fixed asset in service.") : f.assetsDepreciated() >= f.assetsDue() ? ok("Run for all " + f.assetsDue() + " assets.")
                : attention((f.assetsDue() - f.assetsDepreciated()) + " of " + f.assetsDue() + " assets are not depreciated yet.");
            case "BUSINESS_DAYS_ENDED" -> f.openBusinessDays() == 0 ? ok("All ended.") : attention(f.openBusinessDays() + " business day" + (f.openBusinessDays() == 1 ? " is" : "s are") + " still open.");
            case "MONTHS_REVIEWED" -> f.monthsSignedOff() == null ? na("Monthly closing is not required.")
                : f.monthsSignedOff() >= f.monthsDue() ? ok("All " + f.monthsDue() + " months signed off.")
                : attention((f.monthsDue() - f.monthsSignedOff()) + " of " + f.monthsDue() + " months are not signed off.");
            default -> na("");
        };
    }

    private static String[] ok(String d) {
        return new String[] {"OK", d};
    }

    private static String[] na(String d) {
        return new String[] {"NOT_APPLICABLE", d};
    }

    private static String[] attention(String d) {
        return new String[] {"ATTENTION", d};
    }

    public static boolean complete(List<Item> items) {
        return items.stream().allMatch(Item::settled);
    }

    public static long open(List<Item> items) {
        return items.stream().filter((i) -> !i.settled()).count();
    }

    /** Why a tick cannot be recorded: the key must be an item of the scope, and accepting a difference the application found needs an explanation. */
    public static String tickProblem(String scope, String key, String note, boolean automatic) {
        if (definitions(scope).stream().noneMatch((d) -> d.key().equals(key))) return "There is no such item on this checklist.";
        if (automatic && (note == null || note.trim().length() < 10))
            return "Explain why this can be accepted (at least 10 characters); the explanation is kept with the close.";
        if (note != null && note.length() > 500) return "The note can be up to 500 characters.";
        return null;
    }

    /** The day a year's close is due, and whether it is overdue on a day. */
    public static LocalDate dueDate(int year, int dueDays) {
        return LocalDate.of(year, 12, 31).plusDays(dueDays);
    }
}
