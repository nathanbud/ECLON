package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ph.coop.core.frontoffice.MemoRules.Line;

class MemoRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);
    private static final String WHY = "Transfer to beneficiary, authorised by the family";

    private static BigDecimal n(String v) { return new BigDecimal(v); }

    private static Line savings(String debit, String credit) {
        return new Line(MemoRules.SAVINGS, 100, 1, 5, null, n(debit), n(credit), null);
    }

    private static Line ledger(String code, String debit, String credit) {
        return new Line(MemoRules.LEDGER, null, null, null, code, n(debit), n(credit), null);
    }

    private static List<String> check(List<Line> lines) {
        return MemoRules.problems(lines, WHY, TODAY, TODAY);
    }

    @Test
    void aBalancedTransferBetweenTwoMembersHasNoProblems() {
        Line to = new Line(MemoRules.SAVINGS, 200, 1, 9, null, n("0"), n("500.00"), null);
        assertTrue(check(List.of(savings("500.00", "0"), to)).isEmpty());
    }

    @Test
    void aMemberLineAgainstALedgerAccountIsFine() {
        assertTrue(check(List.of(savings("25.50", "0"), ledger("4420", "0", "25.50"))).isEmpty());
        assertTrue(check(List.of(new Line(MemoRules.SHARE, 100, 1, 2, null, n("0"), n("10"), null), ledger("10120", "10", "0"))).isEmpty());
    }

    @Test
    void aMemoThatChangesNoMemberIsAJournalInstead() {
        List<String> p = check(List.of(ledger("10120", "10", "0"), ledger("4420", "0", "10")));
        assertEquals(1, p.size());
        assertTrue(p.get(0).contains("journal"));
    }

    @Test
    void debitsAndCreditsMustBeEqual() {
        List<String> p = check(List.of(savings("500.00", "0"), ledger("4420", "0", "499.99")));
        assertEquals(1, p.size());
        assertTrue(p.get(0).contains("500.00") && p.get(0).contains("499.99"));
    }

    @Test
    void eachLineIsEitherADebitOrACredit() {
        assertEquals(1, check(List.of(savings("5", "5"), ledger("4420", "0", "5"))).stream().filter((s) -> s.startsWith("Line 1")).count());
        assertEquals(1, check(List.of(savings("0", "0"), ledger("4420", "0", "5"))).stream().filter((s) -> s.startsWith("Line 1")).count());
        assertEquals(1, check(List.of(savings("-5", "0"), ledger("4420", "0", "5"))).stream().filter((s) -> s.startsWith("Line 1")).count());
        assertEquals(2, check(List.of(savings("1.005", "0"), ledger("4420", "0", "1.005"))).stream().filter((s) -> s.contains("two decimals")).count(), "reported for each line");
    }

    @Test
    void aLineNeedsItsOwnPartsAndNothingElse() {
        assertFalse(check(List.of(new Line(MemoRules.SAVINGS, null, 1, 5, null, n("5"), n("0"), null), ledger("4420", "0", "5"))).isEmpty(), "a member line needs the member");
        assertFalse(check(List.of(new Line(MemoRules.SAVINGS, 100, 1, 5, "4420", n("5"), n("0"), null), ledger("4420", "0", "5"))).isEmpty(), "a member line takes no ledger account");
        assertFalse(check(List.of(savings("5", "0"), new Line(MemoRules.LEDGER, 100, null, null, "4420", n("0"), n("5"), null))).isEmpty(), "a ledger line takes no member");
        assertFalse(check(List.of(savings("5", "0"), ledger("", "0", "5"))).isEmpty(), "a ledger line needs the account");
        assertFalse(check(List.of(savings("5", "0"), new Line("LOAN", 100, 1, 5, null, n("0"), n("5"), null))).isEmpty(), "loans are not part of memos yet");
    }

    @Test
    void aMemoNeedsAReasonADateNotInTheFutureAndTwoLines() {
        List<Line> ok = List.of(savings("5", "0"), ledger("4420", "0", "5"));
        assertEquals(1, MemoRules.problems(ok, "", TODAY, TODAY).size());
        assertEquals(1, MemoRules.problems(ok, "xy", TODAY, TODAY).size());
        assertEquals(1, MemoRules.problems(ok, "x".repeat(201), TODAY, TODAY).size());
        assertEquals(1, MemoRules.problems(ok, WHY, TODAY.plusDays(1), TODAY).size());
        assertEquals(1, MemoRules.problems(ok, WHY, null, TODAY).size());
        assertEquals(1, MemoRules.problems(List.of(savings("5", "0")), WHY, TODAY, TODAY).size());
        assertEquals(1, MemoRules.problems(null, WHY, TODAY, TODAY).size());
    }

    @Test
    void theMakerTheApproverAndThePosterAreThreeDifferentPeople() {
        assertNull(MemoRules.separation("ana", "ben", "carla"));
        assertNull(MemoRules.separation("ana", "ben", null), "before it is posted");
        assertTrue(MemoRules.separation("ana", "ANA", null).contains("approves"));
        assertTrue(MemoRules.separation("ana", "ben", "ana").contains("posts"));
        assertTrue(MemoRules.separation("ana", "ben", "Ben").contains("approved it"));
    }

    @Test
    void aMemoCannotTakeAnAccountBelowZero() {
        assertNull(MemoRules.balanceProblem("Savings of Ana", n("0.00")));
        assertNull(MemoRules.balanceProblem("Savings of Ana", n("12.34")));
        assertTrue(MemoRules.balanceProblem("Savings of Ana", n("-0.01")).contains("overdrawn by 0.01"));
    }

    @Test
    void theSignedAmountFollowsTheLedgerConvention() {
        assertEquals(0, n("5.00").compareTo(savings("5.00", "0").signed()), "a debit is positive");
        assertEquals(0, n("-5.00").compareTo(savings("0", "5.00").signed()), "a credit is negative");
    }

    private static void assertFalse(boolean b, String message) { org.junit.jupiter.api.Assertions.assertFalse(b, message); }
}
