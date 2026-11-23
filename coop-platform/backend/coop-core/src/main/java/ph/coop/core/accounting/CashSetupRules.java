package ph.coop.core.accounting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The cash class (11): its types are the old system's own structure, not products, and the ledger writes them by number (cash on hand 11/1, cheques and other
 * cash items 11/3), so a new database creates them as they are; what each cooperative sets is the chart account each one posts to, chosen in the application.
 * Pure logic.
 */
final class CashSetupRules {

    private CashSetupRules() {}

    static final int VAULT = 50, ON_HAND = 51, CASHIER = 52, TELLER = 53;
    /** A fixed link (vault, on hand) is not per drawer. */
    static final int NOT_PER_DRAWER = 99;

    /** The system's cash types, in order, with their names as the old system has them. */
    static final Map<Integer, String> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put(1, "Cash-on-Hand");
        TYPES.put(2, "Cash-in-Bank");
        TYPES.put(3, "COCI");
        TYPES.put(97, "COCI ATM");
    }

    /** The types a cashier's or teller's drawer holds: cash, cheques and other cash items, ATM items. */
    static final List<Integer> DRAWER_TYPES = List.of(1, 3, 97);

    /** A fixed link each type needs before the window can count money: the vault's cash, and the cheques and ATM items on hand. */
    record Fixed(int type, int event, String label, boolean required) {}

    static final List<Fixed> FIXED = List.of(
        new Fixed(1, VAULT, "Cash in the vault", true),
        new Fixed(3, ON_HAND, "Cheques and other cash items on hand", true),
        new Fixed(97, ON_HAND, "ATM items on hand", false));

    /** What is still missing for the teller window, in words; empty when the cash types and their fixed links are set up. */
    static List<String> missing(Set<Integer> types, Set<String> links) {
        List<String> out = new ArrayList<>();
        for (var t : TYPES.entrySet()) if (!types.contains(t.getKey())) out.add("the cash type " + t.getValue() + " (11/" + t.getKey() + ")");
        for (Fixed f : FIXED) {
            if (f.required() && !links.contains(f.type() + "/" + f.event())) out.add("the chart account for " + f.label().toLowerCase());
        }
        return out;
    }

    /** Why a first drawer cannot be added: cash and cheques (ATM items are optional) need their chart account when no drawer of that kind exists yet. */
    static List<String> drawerAccountsMissing(Set<Integer> types, Set<Integer> typesWithDrawerOfKind, Map<Integer, String> given) {
        List<String> out = new ArrayList<>();
        for (int t : DRAWER_TYPES) {
            if (!types.contains(t) || typesWithDrawerOfKind.contains(t) || t == 97) continue;
            String a = given == null ? null : given.get(t);
            if (a == null || a.isBlank()) out.add(TYPES.get(t) + " (11/" + t + ")");
        }
        return out;
    }
}
