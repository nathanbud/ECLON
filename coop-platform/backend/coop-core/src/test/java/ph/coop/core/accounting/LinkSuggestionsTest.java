package ph.coop.core.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import ph.coop.core.accounting.LinkSuggestions.Account;
import ph.coop.core.accounting.LinkSuggestions.Peer;
import ph.coop.core.accounting.LinkSuggestions.Suggestion;

class LinkSuggestionsTest {

    /** A small chart like coop_bgempc's: deposits and funds are liabilities, share capital equity, bank savings an asset. */
    private static final List<Account> CHART = List.of(
        new Account("10020202", "Cib - land bank - savings", 1),
        new Account("2010101", "Savings Deposit", 2),
        new Account("2010106", "Kaya savings deposit", 2),
        new Account("2010201", "Time deposit - short term", 2),
        new Account("2021401", "Mutual benefit funds payable", 2),
        new Account("2021402", "Mutual benefit fund payable-mf", 2),
        new Account("3010101", "Paid-up share capital", 3),
        new Account("50102", "Interest expense on deposits", 5));

    private static List<String> codes(List<Suggestion> s) {
        return s.stream().map(Suggestion::account).toList();
    }

    @Test void whatTheOtherTypesOfTheClassUseComesFirstMostUsedFirst() {
        List<Suggestion> s = LinkSuggestions.suggest(22, 11, "Kiddies Savings", "Savings Deposit", "Deposit balance",
            List.of(new Peer("2010106", "Kaya"), new Peer("2010101", "Regular"), new Peer("2010101", "Associate")), List.of(), CHART);
        assertEquals(List.of("2010101", "2010106"), codes(s).subList(0, 2));
        assertEquals("Used for this posting by Regular and Associate", s.get(0).why());
    }

    @Test void thisTypesOwnLinkForAnotherStatusComesNext() {
        List<Suggestion> s = LinkSuggestions.suggest(22, 11, "Kaya", "Savings Deposit", "Deposit balance", List.of(), List.of("2010106"), CHART);
        assertEquals("2010106", s.get(0).account());
        assertTrue(s.get(0).why().contains("other account statuses"));
    }

    @Test void aFirstShareCapitalTypeIsSuggestedTheEquityAccountByName() {
        List<Suggestion> s = LinkSuggestions.suggest(31, 11, "Share Capital", "Share Capital", "Share capital balance", List.of(), List.of(), CHART);
        assertEquals(List.of("3010101"), codes(s));
        assertTrue(s.get(0).why().contains("equity"));
    }

    @Test void aFundIsSuggestedTheFundsPayableNotTheBanksSavings() {
        List<Suggestion> s = LinkSuggestions.suggest(29, 11, "Mutual Aid", "Funds", "Balance", List.of(), List.of(), CHART);
        assertEquals(List.of("2021401", "2021402"), codes(s));
    }

    @Test void aSavingsBalanceIsNeverSuggestedAnAssetOrExpenseAccount() {
        List<Suggestion> s = LinkSuggestions.suggest(22, 11, "Savings", "Savings Deposit", "Deposit balance", List.of(), List.of(), CHART);
        assertTrue(codes(s).containsAll(List.of("2010101", "2010106")));
        assertTrue(!codes(s).contains("10020202") && !codes(s).contains("50102"));
    }

    @Test void anAccountSwitchedOffOrMissingFromTheChartIsNotSuggestedEvenWhenAnotherTypeUsesIt() {
        List<Suggestion> s = LinkSuggestions.suggest(22, 11, "X", "", "", List.of(new Peer("9999999", "Old")), List.of(), CHART);
        assertTrue(s.isEmpty());
    }

    @Test void atMostFiveAndNamesAreShortened() {
        List<Peer> many = List.of(new Peer("2010101", "A"), new Peer("2010101", "B"), new Peer("2010101", "C"), new Peer("2010101", "D"), new Peer("2010101", "E"));
        List<Suggestion> s = LinkSuggestions.suggest(22, 11, "Savings", "Savings Deposit", "Deposit balance", many, List.of(), CHART);
        assertTrue(s.size() <= LinkSuggestions.MAX);
        assertEquals("Used for this posting by A, B, C and 2 more", s.get(0).why());
    }

    @Test void wordsDropShortAndEverydayWords() {
        assertEquals(List.of("mutual", "aid"), List.copyOf(LinkSuggestions.words("MA - Mutual Aid balance of the")));
    }
}
