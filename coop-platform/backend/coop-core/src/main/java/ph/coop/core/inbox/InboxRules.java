package ph.coop.core.inbox;

import java.util.Set;

/**
 * Which kinds of waiting work a person is shown, from their roles. The inbox lists only what this person can act on: a membership officer's requests, an accountant's journals and
 * memos, a purchase approver's requests and orders, a stock approver's adjustments, and any extra workflow step for their role. Nobody is shown their own work to decide.
 */
final class InboxRules {

    /** What a person may see. */
    record Sources(boolean membership, boolean accounting, boolean purchasing, boolean stock) {
        boolean any() { return membership || accounting || purchasing || stock; }
    }

    private InboxRules() {}

    /** The roles without their ROLE_ prefix, as the authorities give them. */
    static Set<String> roles(Iterable<String> authorities) {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        for (String a : authorities) out.add(a.startsWith("ROLE_") ? a.substring(5) : a);
        return out;
    }

    static Sources sourcesFor(Set<String> roles) {
        boolean admin = roles.contains("ADMIN");
        return new Sources(
            roles.contains("MEMBERSHIP_OFFICER"),
            admin || roles.contains("ACCOUNTANT"),
            admin || roles.contains("PURCHASE_APPROVER"),
            admin || roles.contains("STOCK_APPROVER"));
    }

    /** True when the item is the person's own: they made it, so someone else has to decide it. */
    static boolean own(String maker, String user) {
        return maker != null && maker.equalsIgnoreCase(user);
    }

    /** A memo ready to post needs a third person: neither its maker nor its approver. */
    static boolean mayPostMemo(String maker, String approver, String user) {
        return !own(maker, user) && !own(approver, user);
    }
}
