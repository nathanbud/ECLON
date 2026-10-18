package ph.coop.core.accounting.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class JournalTest {

    private Journal balanced() {
        return new Journal("JV-1", "maker", List.of(JournalLine.debit("1010", "100.00"), JournalLine.credit("3010", "100.00")));
    }

    private Journal approved() {
        Journal j = balanced();
        j.submitForReview();
        j.approve("checker");
        return j;
    }

    @Test void unbalancedJournalIsRejected() {
        Journal j = new Journal("JV-2", "maker", List.of(JournalLine.debit("1010", "100"), JournalLine.credit("3010", "90")));
        assertThrows(JournalException.class, j::submitForReview);
    }

    @Test void creatorCannotApproveOwnJournal() {
        Journal j = balanced();
        j.submitForReview();
        assertThrows(JournalException.class, () -> j.approve("maker"));
    }

    @Test void fullWorkflowPosts() {
        Journal j = approved();
        j.post(PeriodStatus.OPEN);
        assertEquals(JournalStatus.POSTED, j.status());
    }

    @Test void closedPeriodRejectsPosting() {
        Journal j = approved();
        assertThrows(JournalException.class, () -> j.post(PeriodStatus.CLOSED));
    }

    @Test void cannotPostWithoutApproval() {
        assertThrows(JournalException.class, () -> balanced().post(PeriodStatus.OPEN));
    }

    @Test void reversalSwapsSidesAndStaysBalanced() {
        Journal j = approved();
        j.post(PeriodStatus.OPEN);
        Journal r = j.reverse("maker");
        assertEquals("JV-1", r.reverses());
        assertTrue(r.isBalanced());
        assertEquals(j.totalDebit(), r.totalCredit());
        assertEquals(JournalStatus.DRAFT, r.status());
    }

    @Test void onlyPostedJournalCanBeReversed() {
        assertThrows(JournalException.class, () -> balanced().reverse("maker"));
    }

    @Test void lineMustCarryExactlyOneSide() {
        assertThrows(IllegalArgumentException.class, () -> new JournalLine("1010", BigDecimal.ONE, BigDecimal.ONE));
    }
}
