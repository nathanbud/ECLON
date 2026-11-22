package ph.coop.core.feature;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.AntPathMatcher;

/**
 * The areas of the system a cooperative may or may not use (each database is its own cooperative), switched on or off by a super administrator
 * (Settings > System > Features). Pure: which addresses and which subsidiary ledger classes belong to each area, so it is tested alone. Membership, share
 * capital, the books, the teller's cash and the setup screens are always on: a cooperative cannot run without them.
 */
public final class FeatureRules {

    private FeatureRules() {}

    /** One area: its key, its name and what switching it off means, the server addresses that belong to it, and the ledger classes it opens accounts in. */
    public record Feature(String key, String label, String note, List<String> paths, Set<Integer> classes) {}

    public static final List<Feature> ALL = List.of(
        new Feature("savings", "Savings", "Savings deposit accounts: deposits and withdrawals at the teller, interest runs and dormancy.",
            List.of("/api/front-office/savings/**", "/api/front-office/savings-interest/**", "/api/members/*/*/savings/**", "/api/savings-dormancy/**"), Set.of(22)),
        new Feature("passbook", "Passbook printing", "Printing savings passbooks at the teller.", List.of("/api/front-office/passbooks/**"), Set.of()),
        new Feature("timedeposits", "Time deposits", "Time deposit placements, interest, accruals, maturities and renewals.",
            List.of("/api/front-office/time-deposits/**", "/api/front-office/time-deposit-accruals/**", "/api/front-office/time-deposit-closures/**", "/api/members/*/*/time-deposits/**"),
            Set.of(24)),
        new Feature("loans", "Loans", "Loan applications, releases, payments, penalty waivers and the loan setup.",
            List.of("/api/loan-applications/**", "/api/members/*/*/loan-applications/**", "/api/members/*/*/loans/**", "/api/members/*/*/comaker-holds/**",
                "/api/front-office/loan-payments/**", "/api/front-office/loan-releases/**", "/api/loan-penalty-waivers/**", "/api/admin/loan-setup/**", "/api/admin/single-hand-loans/**"),
            Set.of(12)),
        new Feature("dividends", "Dividends and patronage refund", "The yearly dividend on share capital and patronage refund.", List.of("/api/front-office/dividends/**"), Set.of()),
        new Feature("pos", "Point of sale", "Selling goods at the counter, with payments and returns.", List.of("/api/pos/**"), Set.of()),
        new Feature("inventory", "Inventory", "Products, warehouses, stock movements, adjustments and counts.", List.of("/api/inventory/**"), Set.of()),
        new Feature("procurement", "Purchasing", "Suppliers, purchase requests, orders and receiving.", List.of("/api/procurement/**"), Set.of()),
        new Feature("assets", "Fixed assets", "The asset register, depreciation and disposals.", List.of("/api/assets/**"), Set.of()),
        new Feature("cic", "CIC submission", "The credit information file for the Credit Information Corporation.", List.of("/api/cic/**"), Set.of()));

    private static final AntPathMatcher PATHS = new AntPathMatcher();

    public static Feature find(String key) {
        return ALL.stream().filter((f) -> f.key().equals(key)).findFirst().orElse(null);
    }

    /** The area an address belongs to, or null when it belongs to none (always on). */
    public static Feature of(String path) {
        if (path == null) return null;
        for (Feature f : ALL) {
            for (String p : f.paths()) {
                String base = p.endsWith("/**") ? p.substring(0, p.length() - 3) : p;
                if (PATHS.match(p, path) || PATHS.match(base, path)) return f;
            }
        }
        return null;
    }

    /** True when accounts of this ledger class may be opened: its area, if it has one, is on. */
    public static boolean classOn(int slc, Map<String, Boolean> on) {
        for (Feature f : ALL) if (f.classes().contains(slc) && !on.getOrDefault(f.key(), true)) return false;
        return true;
    }

    /** The refusal for an address of an area that is switched off. */
    public static String refusal(Feature f) {
        return f.label() + " is switched off for this cooperative, so this cannot be done here. A super administrator can switch it on in Settings > System > Features"
            + " if the cooperative offers it.";
    }

    static List<String> problems(Map<String, Boolean> changes, String reason) {
        List<String> out = new ArrayList<>();
        if (changes == null || changes.isEmpty()) out.add("Switch an area on or off first, then save; nothing was changed.");
        else for (String k : changes.keySet()) if (find(k) == null) out.add(k + " is not an area that can be switched; reload the screen and try again.");
        if (reason == null || reason.trim().length() < 3) out.add("Give a reason for the change (at least 3 characters); it is kept in the audit log.");
        return out;
    }
}
