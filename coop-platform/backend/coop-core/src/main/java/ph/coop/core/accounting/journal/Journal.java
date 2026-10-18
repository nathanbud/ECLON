package ph.coop.core.accounting.journal;

import java.math.BigDecimal;
import java.util.List;

/**
 * A journal entry. Enforces double-entry (debits = credits), maker-checker with segregation of
 * duties, period control, and immutability once POSTED (corrections go through {@link #reverse}).
 */
public class Journal {

    private final String reference;
    private final List<JournalLine> lines;
    private final String createdBy;
    private JournalStatus status = JournalStatus.DRAFT;
    private String approvedBy;
    private String reverses;

    public Journal(String reference, String createdBy, List<JournalLine> lines) {
        if (reference == null || reference.isBlank()) throw new IllegalArgumentException("reference required");
        if (createdBy == null || createdBy.isBlank()) throw new IllegalArgumentException("createdBy required");
        if (lines == null || lines.size() < 2) throw new JournalException("a journal needs at least two lines");
        this.reference = reference;
        this.createdBy = createdBy;
        this.lines = List.copyOf(lines);
    }

    /** Rebuilds a journal read from storage in its saved state. */
    public static Journal restore(String reference, String createdBy, List<JournalLine> lines, JournalStatus status, String approvedBy) {
        Journal j = new Journal(reference, createdBy, lines);
        j.status = status;
        j.approvedBy = approvedBy;
        return j;
    }

    public BigDecimal totalDebit() { return lines.stream().map(JournalLine::debit).reduce(BigDecimal.ZERO, BigDecimal::add); }

    public BigDecimal totalCredit() { return lines.stream().map(JournalLine::credit).reduce(BigDecimal.ZERO, BigDecimal::add); }

    public boolean isBalanced() { return totalDebit().compareTo(totalCredit()) == 0; }

    public void submitForReview() {
        require(JournalStatus.DRAFT);
        if (!isBalanced()) throw new JournalException("debits " + totalDebit() + " != credits " + totalCredit());
        status = JournalStatus.FOR_REVIEW;
    }

    /** Review and approval are one step here; the approver must not be the creator. */
    public void approve(String approver) {
        require(JournalStatus.FOR_REVIEW);
        if (approver.equals(createdBy)) throw new JournalException("approver must differ from creator");
        status = JournalStatus.APPROVED;
        approvedBy = approver;
    }

    public void post(PeriodStatus period) {
        require(JournalStatus.APPROVED);
        if (!period.acceptsPosting()) throw new JournalException("period is " + period + "; posting not allowed");
        if (!isBalanced()) throw new JournalException("unbalanced journal cannot be posted");
        status = JournalStatus.POSTED;
    }

    /** Builds the DRAFT reversing entry (debits and credits swapped) for a posted journal. */
    public Journal reverse(String user) {
        require(JournalStatus.POSTED);
        Journal r = new Journal(reference + "-REV", user, lines.stream().map(JournalLine::swapped).toList());
        r.reverses = reference;
        return r;
    }

    private void require(JournalStatus expected) {
        if (status != expected) throw new JournalException("journal is " + status + ", expected " + expected);
    }

    public String reference() { return reference; }
    public JournalStatus status() { return status; }
    public List<JournalLine> lines() { return lines; }
    public String reverses() { return reverses; }
    public String approvedBy() { return approvedBy; }
}
