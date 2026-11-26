package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ph.coop.core.member.SpreadsheetReader;

class OpeningBalanceRulesTest {

    private static final LocalDate DAY = LocalDate.of(2027, 1, 1);
    private static final String WHY = "Opening balances as of 1 January 2027";

    private static OpeningBalanceRules.Line gl(String account, String debit, String credit) {
        return new OpeningBalanceRules.Line("GL", null, null, null, account, new BigDecimal(debit), new BigDecimal(credit), null);
    }

    private static OpeningBalanceRules.Line savings(int member, String credit) {
        return new OpeningBalanceRules.Line("SAVINGS", member, 1, null, null, BigDecimal.ZERO, new BigDecimal(credit), null);
    }

    private static OpeningBalanceRules.Line share(int member, String credit) {
        return new OpeningBalanceRules.Line("SHARE", member, 1, null, null, BigDecimal.ZERO, new BigDecimal(credit), null);
    }

    private static List<String> problems(OpeningBalanceRules.Line... lines) {
        return OpeningBalanceRules.problems(List.of(lines), WHY, DAY, DAY);
    }

    @Test
    void aBalancedBatchOfLedgerAndMemberLinesIsFine() {
        assertEquals(List.of(), problems(gl("10101", "1500.00", "0"), savings(7, "1000.00"), share(7, "500.00")));
    }

    @Test
    void anUnbalancedBatchSaysByHowMuchAndOnWhichSide() {
        List<String> p = problems(gl("10101", "1500.00", "0"), savings(7, "1000.00"));
        assertEquals(1, p.size());
        assertTrue(p.get(0).contains("difference of 500.00 on the debit side"), p.get(0));
    }

    @Test
    void aMemberLineIsACreditAndNamesTheMemberAndType() {
        var debit = new OpeningBalanceRules.Line("SAVINGS", 7, 1, null, null, new BigDecimal("10"), BigDecimal.ZERO, null);
        var credit = gl("30101", "0", "10");
        assertTrue(String.join(" ", problems(debit, credit)).contains("is a credit"));
        var noMember = new OpeningBalanceRules.Line("SHARE", null, 1, null, null, BigDecimal.ZERO, new BigDecimal("10"), null);
        assertTrue(String.join(" ", problems(gl("10101", "10", "0"), noMember)).contains("give the member and the account type"));
    }

    @Test
    void theSameAccountTwiceIsRefused() {
        assertTrue(String.join(" ", problems(gl("10101", "20", "0"), savings(7, "10"), savings(7, "10"))).contains("already on an earlier line"));
        assertTrue(String.join(" ", problems(gl("10101", "10", "0"), gl("10101", "10", "0"), share(7, "20"))).contains("already on an earlier line"));
    }

    @Test
    void eachLineHasOneSideOnlyAndAtMostTwoDecimals() {
        assertTrue(String.join(" ", problems(gl("10101", "10", "10"), share(7, "0.01"))).contains("not both and not neither"));
        assertTrue(String.join(" ", problems(gl("10101", "10.005", "0"), share(7, "10.005"))).contains("at most two decimals"));
        assertTrue(String.join(" ", problems(gl("10101", "-10", "0"), share(7, "10"))).contains("cannot be negative"));
    }

    @Test
    void theDateAndDescriptionAreRequiredAndTheDateNotInTheFuture() {
        List<OpeningBalanceRules.Line> ok = List.of(gl("10101", "10", "0"), share(7, "10"));
        assertTrue(String.join(" ", OpeningBalanceRules.problems(ok, "x", DAY, DAY)).contains("Describe the opening balances"));
        assertTrue(String.join(" ", OpeningBalanceRules.problems(ok, WHY, null, DAY)).contains("Give the opening date"));
        assertTrue(String.join(" ", OpeningBalanceRules.problems(ok, WHY, DAY.plusDays(1), DAY)).contains("cannot be in the future"));
    }

    @Test
    void makerApproverAndPosterAreThreeDifferentPeople() {
        assertTrue(OpeningBalanceRules.separation("ana", "ANA", null).contains("cannot be the person who prepared"));
        assertTrue(OpeningBalanceRules.separation("ana", "ben", "ana").contains("prepared"));
        assertTrue(OpeningBalanceRules.separation("ana", "ben", "ben").contains("approved"));
        assertNull(OpeningBalanceRules.separation("ana", "ben", "cy"));
    }

    @Test
    void theTrialBalanceAddsTheMembersIntoTheirControlAccounts() {
        List<OpeningBalanceRules.Line> lines = List.of(gl("10101", "1600.00", "0"), savings(7, "1000.00"), savings(8, "100.00"), share(7, "500.00"));
        Map<Integer, String> control = Map.of(1, "20101", 2, "20101", 3, "30101");
        var tb = OpeningBalanceRules.trialBalance(lines, control);
        assertEquals(3, tb.size());
        assertEquals("10101", tb.get(0).account());
        assertEquals(new BigDecimal("1600.00"), tb.get(0).debit());
        assertEquals("20101", tb.get(1).account());
        assertEquals(new BigDecimal("1100.00"), tb.get(1).credit());
        assertEquals(2, tb.get(1).memberAccounts());
        assertEquals(1, tb.get(2).memberAccounts());
    }

    @Test
    void aFileIsReadByItsColumnNamesAndBadRowsAreReportedWithTheirLine() {
        Map<String, String> cash = new LinkedHashMap<>(Map.of("Kind", "GL", "Ledger account", "10101", "Debit", "1,500.00"));
        Map<String, String> sav = new LinkedHashMap<>(Map.of("kind", "Savings", "Member No.", "7", "Type", "1.0", "Credit", "1000"));
        Map<String, String> sc = new LinkedHashMap<>(Map.of("KIND", "share capital", "member", "7", "type", "1", "Account no", "3", "credit", "500"));
        Map<String, String> bad = new LinkedHashMap<>(Map.of("Kind", "insurance", "Debit", "1"));
        Map<String, String> badAmount = new LinkedHashMap<>(Map.of("Kind", "GL", "Ledger account", "10102", "Debit", "abc"));
        var in = OpeningBalanceRules.read(List.of(new SpreadsheetReader.Row(2, cash), new SpreadsheetReader.Row(3, sav), new SpreadsheetReader.Row(4, sc),
            new SpreadsheetReader.Row(5, bad), new SpreadsheetReader.Row(6, badAmount)));
        assertEquals(3, in.lines().size());
        assertEquals(new BigDecimal("1500.00"), in.lines().get(0).debit());
        assertEquals("SAVINGS", in.lines().get(1).kind());
        assertEquals(1, in.lines().get(1).typeCode());
        assertEquals(3, in.lines().get(2).refNo());
        assertEquals(2, in.problems().size());
        assertTrue(in.problems().get(0).startsWith("File line 5:"));
        assertTrue(in.problems().get(1).contains("not an amount"));
    }
}
