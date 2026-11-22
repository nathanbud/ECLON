package ph.coop.core.businessday;

import java.util.List;
import java.util.Locale;

/**
 * Which requests are transactions, refused while the branch has no open business day ({@link BusinessDayGuard}). Pure, so it is tested on its own.
 * <p>
 * A transaction is any change (POST, PUT, PATCH, DELETE) in the areas that move money or stock: the front office (cash, cheques, vouchers, drafts,
 * hand-offs, time deposits, dividends and interest), journals, debit/credit memos, penalty waivers, the year-end close, POS sales and returns, stock
 * movements, adjustments and counts, goods received, depreciation and asset disposals. While no day is open these screens are for looking only.
 * <p>
 * Still allowed, because they only look or set things up: quotes, checks and previews (a path ending in /check, /quote, /quote-..., /preview), printed
 * documents (.pdf) and statements or registers; passbook printing; the settings kept in those areas (drawer opening policy, dividend settings,
 * passbook layout); refreshing the drawer snapshot. Everything outside those areas (members, loan applications, users, settings, reports, Start Day
 * itself) is not a transaction here: what of it posts money is refused where it posts (a loan approval's voucher, for one).
 */
public final class BusinessDayGuardRules {

    private BusinessDayGuardRules() {}

    /** The areas whose changes are transactions. */
    static final List<String> AREAS = List.of(
        "/api/front-office/", "/api/journals", "/api/memos", "/api/loan-penalty-waivers", "/api/year-end-close",
        "/api/pos/sales", "/api/pos/returns",
        "/api/inventory/movements", "/api/inventory/transfers", "/api/inventory/adjustments", "/api/inventory/counts",
        "/api/procurement/receipts", "/api/assets/depreciation", "/api/assets/disposals");

    /** Inside those areas, what only looks or sets up and stays allowed. */
    static final List<String> LOOK_ONLY_ENDINGS = List.of("/check", "/quote", "/preview", ".pdf", "/statement", "/register");
    static final List<String> SETTINGS = List.of(
        "/api/front-office/passbooks/", "/api/front-office/drawer-open/policy", "/api/front-office/dividends/settings/", "/api/front-office/handover/snapshot/refresh");

    /** Whether this request is a transaction, refused while no business day is open. */
    public static boolean isTransaction(String method, String path) {
        if (method == null || path == null) return false;
        String m = method.toUpperCase(Locale.ROOT);
        if (!(m.equals("POST") || m.equals("PUT") || m.equals("PATCH") || m.equals("DELETE"))) return false;
        String p = path.toLowerCase(Locale.ROOT);
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        final String path0 = p;
        if (AREAS.stream().noneMatch((a) -> path0.equals(stripSlash(a)) || path0.startsWith(a.endsWith("/") ? a : a + "/"))) return false;
        if (SETTINGS.stream().anyMatch((s) -> path0.equals(stripSlash(s)) || path0.startsWith(s))) return false;
        if (LOOK_ONLY_ENDINGS.stream().anyMatch(path0::endsWith)) return false;
        if (path0.contains("/quote")) return false; // /interest/quote and the like
        return true;
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
