package ph.coop.core.accounting.ledger;

import java.time.LocalDate;
import java.util.List;

/**
 * A cash transaction at the window, for {@link Ledger#writeCash}: money taken in for one or more member accounts (a Cash Receipt), or paid out of
 * them (a Deposit Withdrawal, a Cash Voucher payment). The ledger adds the cash line on the teller's drawer (and a line per cheque taken) so the
 * voucher balances; the module only says who, which accounts and how much (documentation/design/posting-engine.md).
 *
 * @param requestKey  the screen's key for this one action; the same key twice gives back the first voucher (null: not protected, as before)
 * @param receipt     money in (true) or out
 * @param receiptNo   a receipt number typed at the window (already checked), or blank for the next number
 * @param confirmDuplicate  false: refused when the same client had one of these in the last minute (the teller confirms to post it anyway)
 */
public record CashVoucher(String requestKey, String username, int branch, int module, int code, boolean receipt, int clientId, String explanation,
                          String receiptNo, boolean confirmDuplicate, Teller teller, List<MemberLine> lines, List<Cheque> cheques, String auditAction, String auditDetail) {

    public CashVoucher {
        lines = List.copyOf(lines);
        cheques = cheques == null ? List.of() : List.copyOf(cheques);
    }

    /** The teller or cashier posting it: their old-system id, department and drawer (event 52 cashier or 53 teller, its number, its cash and cheque accounts). */
    public record Teller(int id, int dept, int drawerNumber, int event, String cashAccount, String chequeAccount) {}

    /**
     * One line on a member account (or, with class 0, a plain general-ledger line such as a fee): + debit, - credit, as the ledger stores it.
     * {@code cts} is the account's status (the old system's number for it).
     */
    public record MemberLine(int slc, int typeCode, int refNo, int event, int cts, String account, Money amount) {
        public MemberLine withAmount(Money other) { return new MemberLine(slc, typeCode, refNo, event, cts, account, other); }
    }

    /** A cheque taken at the window: its number, bank, date, kind and amount, the kind's clearing days and the days the member ledger keeps for it. */
    public record Cheque(String number, int bankCode, LocalDate date, int kind, Money amount, int clearingDays, int ledgerDays) {}
}
