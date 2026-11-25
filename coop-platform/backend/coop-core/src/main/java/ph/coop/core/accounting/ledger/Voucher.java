package ph.coop.core.accounting.ledger;

import java.time.LocalDate;
import java.util.List;

/**
 * What a module hands to the {@link Ledger} to be written: one voucher of the old system, its header and its lines. The ledger checks it, numbers
 * it and writes it; the module never writes the ledger tables itself (documentation/design/posting-engine.md).
 *
 * @param requestKey  the same key twice gives back the first voucher instead of posting again (a double click, a retry); unique per voucher
 * @param module      the old system's module (1 back office, 2 front office ...) and {@code code} its transaction type
 * @param style       how the old system writes this kind of voucher; each style is written down in {@link Style}, not copied in each module
 * @param people      the old system's user ids (access.AccessID) of whoever encoded, approved and posted it
 */
public record Voucher(String requestKey, String username, int branch, int module, int code, LocalDate date, String explanation, Style style,
                      People people, List<Line> lines, List<GlOnly> glOnly) {

    public Voucher {
        if (requestKey == null || requestKey.isBlank()) throw new IllegalArgumentException("A voucher needs a request key.");
        lines = List.copyOf(lines);
        glOnly = glOnly == null ? List.of() : List.copyOf(glOnly);
    }

    /**
     * One line: the account, the department, and the amount (+ debit, - credit). A line on a member's account (General Journal only, such as an opening
     * balance) names it in {@code member}; its account is then the control account of that member account, and the member's ledger gets a row too.
     */
    public record Line(String account, int dept, Money amount, Member member) {
        public Line(String account, int dept, Money amount) {
            this(account, dept, amount, null);
        }
    }

    /** The member account a line is on: the member, the class (22 savings, 31 share capital ...), type, reference number, event and the account's status. */
    public record Member(int clientId, int slc, int typeCode, int refNo, int event, int cts) {}

    /**
     * A general-ledger row with no voucher line of its own. Only the year-end close writes these: one-sided rows on the net surplus for the period
     * account that cancel the old system's daily type 98 rows there (see documentation/state/year-end-close.md). Not counted in the balance check.
     */
    public record GlOnly(String account, int dept, Money amount) {}

    public record People(int encodedBy, int approvedBy, int postedBy) {}

    /**
     * The kinds of voucher moved onto the ledger so far, each as the old system writes it (proved by the golden snapshots in
     * src/test/resources/ledger-golden). A flow moved later adds its style here.
     */
    public enum Style {
        /**
         * General Journal (module 1, type 1): document number {@code *} + 7 digits, not a front-office voucher; each line dated (DTSL_DATE), amount due
         * 0, no posted mark (DTUPD_TAG empty), reference blank; a general-ledger row per line.
         */
        GENERAL_JOURNAL,
        /**
         * Year-end Closing Entry (module 1, type 99): document number of 8 digits, no line date, adjustment flag 1, posted mark 1, reference blank,
         * no amount due; a general-ledger row per line, plus the {@link GlOnly} rows. Dated 31 December in a year about to close, so no period check.
         */
        CLOSING_ENTRY
    }
}
