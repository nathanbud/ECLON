package ph.coop.core.accounting.ledger;

import java.time.LocalDate;
import java.util.List;

/**
 * A voucher of the front office's module 2 that waits for approval before it is posted, as the old system keeps it (documentation/general-voucher-map.md):
 * a Cash Voucher (type 21), whose credit is the payee's vouchers-payable account, or a Check Voucher (type 22), whose credit is the cooperative's bank
 * account and which pays by the cooperative's own cheque. Encoding writes the header and the lines marked waiting (tag 2); nothing reaches the general
 * ledger or a member's ledger until it is posted ({@link Ledger#postWaiting}).
 *
 * @param clientId  the payee's client number (the header's TR_CLIENTID and the member lines' ClientIDDT)
 */
public record WaitingVoucher(String requestKey, String username, int branch, int code, int clientId, String explanation, int encodedBy, List<Line> lines) {

    public WaitingVoucher {
        if (requestKey == null || requestKey.isBlank()) throw new IllegalArgumentException("A voucher needs a request key.");
        if (code != 21 && code != 22) throw new IllegalArgumentException("Only Cash Vouchers (21) and Check Vouchers (22) wait for approval here.");
        lines = List.copyOf(lines);
    }

    /**
     * One line, + debit and - credit. With {@code slc} null it is a plain general-ledger line (an expense, the bank); otherwise it is on the payee's
     * account of that class, type, reference, event and status.
     */
    public record Line(String account, int dept, Integer slc, Integer slt, Integer refNo, Integer event, Integer cts, Money amount) {
        boolean member() { return slc != null && slc > 0; }
    }

    /** The cooperative's own cheque that pays a Check Voucher: drawn on bank {@code bankCode} (a BCID), number, date and payee. */
    public record OwnCheque(String number, int bankCode, LocalDate date, String payee, Money amount) {}
}
