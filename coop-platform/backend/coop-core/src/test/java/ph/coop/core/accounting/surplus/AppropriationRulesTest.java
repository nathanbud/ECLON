package ph.coop.core.accounting.surplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ph.coop.core.accounting.surplus.AppropriationRules.Entry;
import ph.coop.core.accounting.surplus.AppropriationRules.Line;

class AppropriationRulesTest {

    private static final BigDecimal NET = new BigDecimal("1000000.00");
    private static final LocalDate TODAY = LocalDate.of(2027, 4, 1);
    private static final LocalDate ENTRY = LocalDate.of(2027, 3, 20);
    private static final String GA = "GA Resolution No. 3, s. 2027";

    private static Line fund(String kind, String pct, String account) { return new Line(kind, kind.toLowerCase() + " fund", new BigDecimal(pct), null, account); }

    private static Line members(String kind, String amount) { return new Line(kind, kind, null, new BigDecimal(amount), "20126"); }

    /** Article 86 at its limits: reserve 10, education 10, community 3, optional 7; the rest (70%) to members. */
    private static List<Line> lawful() {
        return new ArrayList<>(List.of(fund("RESERVE", "10", "3011"), fund("EDUCATION", "5", "3012"), fund("EDUCATION", "5", "2131"), fund("COMMUNITY", "3", "3013"),
            fund("OPTIONAL", "7", "3014"), members("ISC", "400000.00"), members("PR", "300000.00")));
    }

    private static List<String> problems(List<Line> lines) {
        return AppropriationRules.problems(2026, NET, lines, GA, ENTRY, TODAY, null, "30198", "20126");
    }

    @Test
    void fundsAreAPercentOfTheNetSurplusAndWhatIsLeftStaysUndivided() {
        assertEquals(List.of(), problems(lawful()));
        var t = AppropriationRules.totals(NET, lawful());
        assertEquals(0, new BigDecimal("300000.00").compareTo(t.funds()));
        assertEquals(0, new BigDecimal("700000.00").compareTo(t.isc().add(t.pr())));
        assertEquals(0, BigDecimal.ZERO.compareTo(t.undivided()));

        List<Line> less = lawful();
        less.set(6, members("PR", "250000.00"));
        assertEquals(0, new BigDecimal("50000.00").compareTo(AppropriationRules.totals(NET, less).undivided()), "what is not allocated stays in the undivided net surplus");
        assertEquals(List.of(), problems(less));
    }

    @Test
    void theArticle86LimitsAreChecked() {
        List<Line> l = lawful();
        l.set(0, fund("RESERVE", "9.5", "3011"));
        l.set(3, fund("COMMUNITY", "2", "3013"));
        l.set(4, fund("OPTIONAL", "8", "3014"));
        l.add(fund("EDUCATION", "1", "3015"));
        l.set(5, members("ISC", "100000.00"));
        String all = String.join(" ", problems(l));
        assertTrue(all.contains("reserve fund takes 9.5%") && all.contains("at least 10%"), all);
        assertTrue(all.contains("education and training fund takes 11%"), all);
        assertTrue(all.contains("community development fund takes 2%"), all);
        assertTrue(all.contains("optional fund takes 8%"), all);
    }

    @Test
    void aCooperativeInItsFirstFiveYearsSetsAsideHalfToTheReserveFund() {
        assertEquals(new BigDecimal("50"), AppropriationRules.reserveMinimum(2026, LocalDate.of(2023, 6, 1)));
        assertEquals(new BigDecimal("10"), AppropriationRules.reserveMinimum(2028, LocalDate.of(2023, 6, 1)), "2028 ends after 1 June 2028");
        assertEquals(new BigDecimal("10"), AppropriationRules.reserveMinimum(2026, null));
        String all = String.join(" ", AppropriationRules.problems(2026, NET, lawful(), GA, ENTRY, TODAY, LocalDate.of(2024, 1, 15), "30198", "20126"));
        assertTrue(all.contains("at least 50%") && all.contains("first five years"), all);
    }

    @Test
    void moreThanTheNetSurplusCannotBeAllocated() {
        List<Line> l = lawful();
        l.set(5, members("ISC", "400000.01"));
        String all = String.join(" ", problems(l));
        assertTrue(all.contains("more than the net surplus of 1000000.00 by 0.01"), all);
    }

    @Test
    void aYearNotClosedOrWithALossHasNothingToAppropriate() {
        assertTrue(AppropriationRules.problems(2026, null, lawful(), GA, ENTRY, TODAY, null, "30198", "20126").get(0).contains("not closed yet"));
        assertTrue(AppropriationRules.problems(2026, new BigDecimal("-5.00"), lawful(), GA, ENTRY, TODAY, null, "30198", "20126").get(0).contains("net loss of 5.00"));
    }

    @Test
    void theEntryIsDatedAfterTheYearWithAResolutionAndEveryLineHasItsOwnAccount() {
        String all = String.join(" ", AppropriationRules.problems(2026, NET, lawful(), "GA", LocalDate.of(2026, 12, 31), TODAY, null, "30198", "20126"));
        assertTrue(all.contains("general assembly resolution"), all);
        assertTrue(all.contains("appropriated after the year ends"), all);
        assertTrue(String.join(" ", AppropriationRules.problems(2026, NET, lawful(), GA, LocalDate.of(2027, 5, 1), TODAY, null, "30198", "20126")).contains("has not come yet"));

        List<Line> l = lawful();
        l.set(2, fund("EDUCATION", "5", "3011"));
        l.set(3, fund("COMMUNITY", "3", "30198"));
        l.set(6, new Line("PR", "Patronage refund", null, new BigDecimal("300000.00"), "2999"));
        all = String.join(" ", problems(l));
        assertTrue(all.contains("used by another fund too"), all);
        assertTrue(all.contains("undivided net surplus the appropriation is taken from"), all);
        assertTrue(all.contains("credited to 20126"), all);
    }

    @Test
    void theEntryDebitsTheUndividedNetSurplusAndCreditsEachAccountOnce() {
        List<Line> l = lawful();
        l.set(1, fund("EDUCATION", "3.3333", "3012"));
        List<Entry> e = AppropriationRules.entries(NET, l, "30198");
        assertEquals("30198", e.get(0).account());
        BigDecimal sum = e.stream().map(Entry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.signum(), "it balances");
        assertEquals(0, new BigDecimal("-33333.00").compareTo(e.stream().filter((x) -> x.account().equals("3012")).findFirst().orElseThrow().amount()), "3.3333% to the centavo");
        assertEquals(1, e.stream().filter((x) -> x.account().equals("20126")).count(), "interest and refund on the payout account are one credit");
        assertEquals(0, new BigDecimal("-700000.00").compareTo(e.stream().filter((x) -> x.account().equals("20126")).findFirst().orElseThrow().amount()));
    }

    @Test
    void threeDifferentPeopleAndKindsFromFundNames() {
        assertTrue(AppropriationRules.separation("ana", "ANA", null).contains("cannot be the person who prepared it"));
        assertTrue(AppropriationRules.separation("ana", "ben", "ben").contains("approved it"));
        assertNull(AppropriationRules.separation("ana", "ben", "cy"));
        assertEquals("RESERVE", AppropriationRules.kindFor("General Reserve Fund"));
        assertEquals("EDUCATION", AppropriationRules.kindFor("CETF - federation share"));
        assertEquals("COMMUNITY", AppropriationRules.kindFor("Community Development Fund"));
        assertEquals("OPTIONAL", AppropriationRules.kindFor("Optional fund (land and building)"));
        assertEquals("OTHER", AppropriationRules.kindFor("Calamity fund"));
    }
}
