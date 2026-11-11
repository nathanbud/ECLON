package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ph.coop.core.member.KycRules.Record;
import ph.coop.core.member.KycRules.Status;

class KycRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);

    private static Record full() {
        return new Record("Passport", "P1234567A", TODAY.plusYears(3), "Salary", "LOW", TODAY.plusYears(2), false, null);
    }

    @Test
    void noRecordIsNone_andAMissingRequiredPieceIsIncomplete() {
        assertEquals(Status.NONE, KycRules.status(null, TODAY));
        assertEquals(Status.COMPLETE, KycRules.status(full(), TODAY));
        assertEquals(Status.INCOMPLETE, KycRules.status(new Record("Passport", "P1234567A", TODAY.plusYears(3), " ", "LOW", TODAY.plusYears(2), false, null), TODAY));
        assertEquals(Status.INCOMPLETE, KycRules.status(new Record(null, null, null, null, null, null, false, null), TODAY));
        assertEquals(Status.INCOMPLETE, KycRules.status(new Record("Passport", "P1234567A", TODAY.plusYears(3), "Salary", null, TODAY.plusYears(2), false, null), TODAY));
    }

    @Test
    void theReviewListNamesAMemberAsTheProfileDoes() {
        assertEquals("CLIMBS, CLAIMS FUND", KycReviewController.name(" CLIMBS, CLAIMS FUND ", null, null, null), "an organisation by its account name");
        assertEquals("Dela Cruz, Juan Santos", KycReviewController.name("", "Dela Cruz", "Juan", "Santos"));
        assertEquals("Reyes", KycReviewController.name(null, "Reyes", null, null));
        assertEquals("", KycReviewController.name(null, null, null, null));
    }

    @Test
    void aWithdrawalWaitsForAMissingIncompleteOrExpiredRecord_butNotForAnOverdueReview() {
        assertTrue(KycRules.withdrawalProblem(Status.NONE).contains("no KYC record"));
        assertTrue(KycRules.withdrawalProblem(Status.INCOMPLETE).contains("incomplete"));
        assertTrue(KycRules.withdrawalProblem(Status.EXPIRED).contains("expired"));
        assertEquals(null, KycRules.withdrawalProblem(Status.REVIEW_DUE), "an overdue review is a warning, not a refusal");
        assertEquals(null, KycRules.withdrawalProblem(Status.COMPLETE));
    }

    @Test
    void anExpiredIdOrAPassedReviewDateIsFlagged_theIdFirst() {
        assertEquals(Status.EXPIRED, KycRules.status(new Record("Passport", "P1234567A", TODAY.minusDays(1), "Salary", "LOW", TODAY.plusYears(2), false, null), TODAY));
        assertEquals(Status.COMPLETE, KycRules.status(new Record("Passport", "P1234567A", TODAY, "Salary", "LOW", TODAY, false, null), TODAY), "today is still good");
        assertEquals(Status.REVIEW_DUE, KycRules.status(new Record("Passport", "P1234567A", TODAY.plusYears(3), "Salary", "LOW", TODAY.minusDays(1), false, null), TODAY));
        assertEquals(Status.EXPIRED, KycRules.status(new Record("Passport", "P1234567A", TODAY.minusDays(1), "Salary", "LOW", TODAY.minusDays(1), false, null), TODAY));
    }

    @Test
    void theRiskerTheMemberTheSoonerTheReview() {
        assertEquals(TODAY.plusYears(1), KycRules.suggestedReview("HIGH", TODAY));
        assertEquals(TODAY.plusYears(2), KycRules.suggestedReview("MEDIUM", TODAY));
        assertEquals(TODAY.plusYears(3), KycRules.suggestedReview("LOW", TODAY));
    }

    @Test
    void submittedValuesAreChecked() {
        assertTrue(KycRules.problems("Passport", "P1234567A", "LOW", false, null).isEmpty());
        assertTrue(KycRules.problems(null, null, null, false, null).isEmpty(), "a partly filled record may be saved");
        assertFalse(KycRules.problems("Library card", "P1234567A", "LOW", false, null).isEmpty());
        assertFalse(KycRules.problems("Passport", "<script>", "LOW", false, null).isEmpty());
        assertFalse(KycRules.problems("Passport", "P1234567A", "EXTREME", false, null).isEmpty());
        assertFalse(KycRules.problems("Passport", "P1234567A", "HIGH", true, " ").isEmpty(), "a politically exposed member needs the position named");
        assertTrue(KycRules.problems("Passport", "P1234567A", "HIGH", true, "Municipal councilor").isEmpty());
    }

    @Test
    void theIdNumberIsMaskedInTheAuditLog() {
        assertEquals("*****567A", KycRules.mask("P1234567A"));
        assertEquals("****", KycRules.mask("123"));
        assertEquals("", KycRules.mask(null));
    }
}
