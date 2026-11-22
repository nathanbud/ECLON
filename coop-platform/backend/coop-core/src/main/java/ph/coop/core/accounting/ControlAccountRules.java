package ph.coop.core.accounting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whether a journal may touch control accounts (the chart accounts that members' accounts post to, see {@link SlReconciliation}). A journal changes
 * the general ledger only, never a member's account, so on a control account it would make the two disagree. It is allowed only to move amounts
 * between control accounts of the same members' accounts, netting to zero for each group (a loan moved from current to past due, a balance moved
 * between departments). Anything else belongs in a debit/credit memo, which changes the member's account and the control account together.
 * Pure, so it is tested on its own.
 * <p>
 * In the old system such journals were made to bring in opening balances (2023 "FS forwarding") and to force the ledger to the members' totals
 * ("to reconcile SL-GL variance", 2025); the first is legitimate, the second hides a difference. Both now need an administrator's written reason.
 */
public final class ControlAccountRules {

    private ControlAccountRules() {}

    /** A journal line's effect on an account: debit positive, credit negative. */
    public record Effect(String account, BigDecimal amount) {}

    /** A group the journal leaves out of balance: its title, the accounts it touched and by how much the group moves. */
    public record Unbalanced(String group, List<String> accounts, BigDecimal net) {}

    /**
     * @param lines   the journal's lines
     * @param groupOf control account to its group (accounts not in it are ordinary accounts)
     * @param titles  group to its title ("Savings Deposit: SD - Regular, Kiddies")
     * @param names   account to its name
     */
    public static List<Unbalanced> unbalanced(List<Effect> lines, Map<String, String> groupOf, Map<String, String> titles, Map<String, String> names) {
        Map<String, BigDecimal> net = new LinkedHashMap<>();
        Map<String, List<String>> touched = new LinkedHashMap<>();
        for (Effect e : lines) {
            String g = groupOf.get(e.account());
            if (g == null) continue;
            net.merge(g, e.amount(), BigDecimal::add);
            List<String> t = touched.computeIfAbsent(g, (k) -> new ArrayList<>());
            String label = e.account() + (names.getOrDefault(e.account(), "").isBlank() ? "" : " " + names.get(e.account()));
            if (!t.contains(label)) t.add(label);
        }
        List<Unbalanced> out = new ArrayList<>();
        for (var e : net.entrySet()) {
            if (e.getValue().signum() != 0) out.add(new Unbalanced(titles.getOrDefault(e.getKey(), e.getKey()), List.copyOf(touched.get(e.getKey())), e.getValue()));
        }
        return out;
    }

    /** The sentence a refused journal gets, or null when it is allowed. */
    public static String problem(List<Unbalanced> unbalanced) {
        if (unbalanced.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (Unbalanced u : unbalanced) parts.add(String.join(", ", u.accounts()) + " (" + u.group() + ")");
        return "A journal cannot change the balance of a control account: " + String.join("; ", parts) + ". A journal changes the general ledger only, so "
            + "the members' accounts would no longer add up to it. To change members' balances use a debit/credit memo, which changes the member's account "
            + "and the control account together. A journal may only move amounts between control accounts of the same members' accounts (for example "
            + "from current to past due), so that they net to zero. An administrator can still make one with a written reason (an opening balance, an approved correction).";
    }
}
