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

    /** The loan application steps a person may do (Settings > Users > Receivables > Loan process). */
    record LoanRights(boolean deduct, boolean verify, boolean approve) {}

    /**
     * What a waiting loan application asks of this person, or null: DEDUCTION while it waits for its deductions, VERIFY when it is new, APPROVE when it is verified.
     * Nobody verifies or approves an application they encoded. {@code status}: 1 new, 2 verified.
     */
    static String loanStep(int status, boolean waitingForDeductions, int encoder, int me, LoanRights r) {
        if (status == 1 && waitingForDeductions) return r.deduct() ? "LOAN_DEDUCTION" : null;
        if (encoder == me) return null;
        if (status == 1) return r.verify() ? "LOAN_VERIFY" : null;
        if (status == 2) return r.approve() ? "LOAN_APPROVE" : null;
        return null;
    }

    /**
     * What a waiting voucher asks of this person, or null: APPROVE when nobody has approved it and they did not encode it; POST when approved, by a third person
     * (neither its encoder nor its approver). {@code approver} is null until approved.
     */
    static String voucherStep(int encoder, Integer approver, int me) {
        if (encoder == me) return null;
        if (approver == null) return "APPROVE";
        return approver == me ? null : "POST";
    }

    /** Who decides a supervisor request (ecis_fo_override), by its kind: penalty waivers accountants; returned cheques also the head cashier; the rest the head cashier. */
    static boolean decidesOverride(String kind, boolean head, boolean accountant, boolean admin) {
        if (admin) return true;
        if ("PENALTY_WAIVER".equals(kind)) return accountant;
        if ("CHEQUE_RETURN".equals(kind)) return head || accountant;
        return head;
    }
}
