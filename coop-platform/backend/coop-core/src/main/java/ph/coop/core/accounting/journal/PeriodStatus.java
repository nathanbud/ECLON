package ph.coop.core.accounting.journal;

/** Accounting period lifecycle (guide step 6.2). Only OPEN accepts new postings. */
public enum PeriodStatus {
    OPEN, SOFT_CLOSED, CLOSED, LOCKED;

    public boolean acceptsPosting() { return this == OPEN; }
}
