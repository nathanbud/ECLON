package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What a front office draft becomes when it is posted, mapped from the old system's own vouchers (documentation/general-voucher-map.md). Pure logic, no
 * database access: the controller loads the accounts and balances and this decides.
 *
 * <ul>
 *   <li>Cash Receipt (type 10), an <b>other receipt</b>: cash (and cheques) in, credits to plain ledger accounts such as a fee, rent or a collection
 *       for someone else; posted at once on the poster's drawer. Payments to a member's own accounts stay on the Teller tab, which checks them.</li>
 *   <li>Misc Disbursement (type 24), a <b>mixed payment</b>: debits to vouchers-payable accounts of one member (any payable type) and plain ledger
 *       accounts, the cash out of the poster's drawer; posted at once.</li>
 *   <li>Cash Voucher (type 21), a <b>general voucher</b>: debits to plain ledger accounts (an expense, an asset), the credit to a new
 *       vouchers-payable account of the payee; waits for another person's approval, then is posted, and the payee is paid on Disburse.</li>
 *   <li>Check Voucher (type 22), <b>paid by the cooperative's cheque</b>: debits to plain ledger accounts, a member's vouchers payable or savings,
 *       the credit to the bank account the cheque is drawn on; waits for approval, then is posted with its cheque.</li>
 * </ul>
 */
final class DraftPostRules {

    private DraftPostRules() {}

    enum Kind { OTHER_RECEIPT, MIXED_PAYMENT, CASH_VOUCHER, CHECK_VOUCHER }

    static final int RECEIPT = 10, CASH_VOUCHER = 21, CHECK_VOUCHER = 22, MISC_DISBURSEMENT = 24;
    static final int PAYABLE_CLASS = 29, SAVINGS_CLASS = 22;

    /** The cash and cash-items accounts of the drawers and the vault: the window side of a receipt or a payment. */
    static final Set<String> CASH_ACCOUNTS = Set.of("10001", "10002", "10003", "10004", "100111", "100112", "100113", "100114");

    /** One draft line, read: + debit, - credit; slc and slt null for a plain ledger line, refNo the member's account number. */
    record In(int n, String account, Integer slc, Integer slt, Integer refNo, BigDecimal amount, int dept) {
        boolean member() { return slc != null && slc > 0; }
        boolean cash() { return CASH_ACCOUNTS.contains(account); }
    }

    /** What the draft would write, without the window's cash line: the other lines (+ debit, - credit), the cash total and the problems. */
    record Plan(Kind kind, List<In> lines, BigDecimal total, List<String> problems) {}

    static Kind kindOf(int trCode) {
        return switch (trCode) {
            case RECEIPT -> Kind.OTHER_RECEIPT;
            case MISC_DISBURSEMENT -> Kind.MIXED_PAYMENT;
            case CASH_VOUCHER -> Kind.CASH_VOUCHER;
            case CHECK_VOUCHER -> Kind.CHECK_VOUCHER;
            default -> null;
        };
    }

    /** Why a type is not posted from a draft (it has its own screen, or the old system has no example to follow), or null when it is. */
    static String notPosted(int trCode) {
        return switch (trCode) {
            case RECEIPT, CASH_VOUCHER, CHECK_VOUCHER, MISC_DISBURSEMENT -> null;
            case 20 -> "Loan Vouchers are made by the Loan Module when a loan is approved.";
            case 23 -> "Withdrawals are made on the Teller tab, which checks the balance, holds and limits.";
            case 31, 32 -> "Cash is handed over on the Cash Handover tab, which counts it.";
            case 33, 35 -> "Bank deposits are made on the Cash Handover tab.";
            case 41, 42 -> "Sales are made at the point of sale.";
            default -> "The old system has no posted example of this type to follow, so it stays a draft. Use a Cash Receipt, Misc Disbursement, Cash Voucher or Check Voucher.";
        };
    }

    private static String money(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP).toPlainString(); }

    /**
     * Checks a balanced draft's lines for its kind. {@code payableAccount} is the ledger account of an active vouchers-payable account (type 1),
     * which a Cash Voucher credits; {@code bankAccount} the ledger account of the bank chosen for a Check Voucher (null when none chosen).
     */
    static Plan plan(int trCode, List<In> lines, boolean balanced, Integer clientId, String payorName, String payableAccount, String bankAccount) {
        Kind kind = kindOf(trCode);
        List<String> p = new ArrayList<>();
        if (kind == null) {
            p.add(notPosted(trCode));
            return new Plan(null, List.of(), BigDecimal.ZERO, p);
        }
        if (!balanced) p.add("Debits and credits must be equal before it can be posted.");
        List<In> kept = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean hasClient = clientId != null && clientId > 0;
        switch (kind) {
            case OTHER_RECEIPT -> {
                for (In l : lines) {
                    if (l.cash()) {
                        if (l.amount().signum() < 0) p.add("Entry " + l.n() + ": a receipt takes cash in; the cash entries must be debits.");
                        continue;
                    }
                    if (l.member()) p.add("Entry " + l.n() + " is on a member's account. Take member payments on the Teller tab, which checks them.");
                    else if (l.amount().signum() > 0) p.add("Entry " + l.n() + ": on a receipt only the cash is a debit; the other entries are credits.");
                    kept.add(l);
                    total = total.add(l.amount().negate());
                }
                if (!hasClient && (payorName == null || payorName.isBlank())) p.add("Name who paid (the member name).");
            }
            case MIXED_PAYMENT -> {
                for (In l : lines) {
                    if (l.cash()) {
                        if (l.amount().signum() > 0) p.add("Entry " + l.n() + ": a payment takes cash out; the cash entries must be credits.");
                        continue;
                    }
                    if (l.amount().signum() < 0) p.add("Entry " + l.n() + ": on a payment only the cash is a credit; the other entries are debits.");
                    if (l.member() && l.slc() != PAYABLE_CLASS) p.add("Entry " + l.n() + ": only vouchers-payable accounts are paid out here. Use the Teller tab for savings, shares and loans.");
                    if (l.member() && l.refNo() == null) p.add("Entry " + l.n() + " needs the account number.");
                    kept.add(l);
                    total = total.add(l.amount());
                }
                if (kept.stream().anyMatch(In::member) && !hasClient) p.add("Choose the member whose payable accounts are paid.");
            }
            case CASH_VOUCHER -> {
                for (In l : lines) {
                    if (l.amount().signum() < 0) {
                        if (payableAccount == null || !payableAccount.equals(l.account()) || l.member()) {
                            p.add("Entry " + l.n() + ": the credit of a Cash Voucher is the payee's vouchers payable" + (payableAccount == null ? "" : " (" + payableAccount + ")") + ".");
                        }
                        continue;
                    }
                    if (l.cash()) p.add("Entry " + l.n() + ": a Cash Voucher does not touch the cash; the payee is paid afterwards on Disburse.");
                    if (l.member()) p.add("Entry " + l.n() + ": a Cash Voucher's debits are ledger accounts (an expense, an asset), not a member's account.");
                    kept.add(l);
                    total = total.add(l.amount());
                }
                if (!hasClient) p.add("Choose the payee (a member), whose vouchers payable the voucher credits.");
            }
            case CHECK_VOUCHER -> {
                for (In l : lines) {
                    if (l.amount().signum() < 0) {
                        if (bankAccount == null) p.add("Choose the bank account the cheque is drawn on.");
                        else if (!bankAccount.equals(l.account()) || l.member()) p.add("Entry " + l.n() + ": the credit of a Check Voucher is the bank account the cheque is drawn on (" + bankAccount + ").");
                        continue;
                    }
                    if (l.cash()) p.add("Entry " + l.n() + ": a Check Voucher pays by cheque, not cash.");
                    if (l.member() && l.slc() != PAYABLE_CLASS && l.slc() != SAVINGS_CLASS) p.add("Entry " + l.n() + ": a cheque pays a member's vouchers payable or savings; use the member's own screens for the rest.");
                    if (l.member() && l.refNo() == null) p.add("Entry " + l.n() + " needs the account number.");
                    kept.add(l);
                    total = total.add(l.amount());
                }
                if (kept.stream().anyMatch(In::member) && !hasClient) p.add("Choose the member whose account is paid.");
            }
        }
        if (kept.isEmpty()) p.add("Add the entries to post.");
        if (total.signum() <= 0 && kept.size() > 0) p.add("The amount must be above zero.");
        return new Plan(kind, List.copyOf(kept), total, dedupe(p));
    }

    /** A payable or savings line over what the account can give: the balance (and for savings the available amount after holds). */
    static String overBalance(In l, BigDecimal balance, BigDecimal held, int status, int activeStatus) {
        if (status != activeStatus) return "Entry " + l.n() + ": account " + l.refNo() + " is not active.";
        BigDecimal available = balance.subtract(held == null ? BigDecimal.ZERO : held);
        if (l.amount().compareTo(available) > 0) return "Entry " + l.n() + ": account " + l.refNo() + " has " + money(available.max(BigDecimal.ZERO)) + " available, less than " + money(l.amount()) + ".";
        return null;
    }

    /** A cheque number of the cooperative's own cheque: digits, up to 15, written zero padded as the old Check Vouchers are. */
    static String ownChequeNumber(String typed) {
        return ChequeRules.number(typed);
    }

    private static List<String> dedupe(List<String> p) {
        return p.stream().distinct().toList();
    }
}
