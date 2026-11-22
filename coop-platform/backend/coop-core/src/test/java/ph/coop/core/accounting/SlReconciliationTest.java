package ph.coop.core.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ph.coop.core.accounting.SlReconciliation.Link;

/** The SL to control account reconciliation's grouping and comparison. Pure, no database. */
class SlReconciliationTest {

    private static final Map<Integer, String> CLASSES = Map.of(12, "Loans Receivable", 22, "Savings Deposit", 31, "Share Capital");
    private static final Map<String, String> TYPES = Map.of("12/21", "Salary Loan", "22/1", "SD - Regular", "22/2", "Kiddies", "31/1", "Paid up Share Cap");
    private static final Map<String, String> ACCOUNTS = Map.of("10110129", "Salary loan - current", "10110229", "Salary loan - past due",
        "2010101", "Savings Deposit", "3010101", "Paid-up share capital");

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test void aLoanTypeIsComparedWithAllItsStatusAccountsTogether() {
        // 1,000 lent; 300 of it moved to past due by a general-ledger entry that leaves the member's account alone
        var r = SlReconciliation.reconcile(List.of(new Link(12, 21, "10110129"), new Link(12, 21, "10110229")),
            Map.of("12/21", d("1000.00")), Map.of("10110129", d("700.00"), "10110229", d("300.00")), TYPES, CLASSES, ACCOUNTS);
        assertEquals(1, r.groups().size());
        var g = r.groups().get(0);
        assertTrue(g.reconciled());
        assertFalse(g.credit(), "a receivable is a debit balance");
        assertEquals(d("1000.00"), g.members());
        assertEquals(2, g.accounts().size());
        assertEquals("Loans Receivable: Salary Loan", g.title());
    }

    @Test void typesSharingAnAccountAreOneGroupShownAsCreditBalances() {
        // two savings types post to Savings Deposit; members hold 600 + 400 (credits are negative in the ledgers)
        var r = SlReconciliation.reconcile(List.of(new Link(22, 1, "2010101"), new Link(22, 2, "2010101")),
            Map.of("22/1", d("-600.00"), "22/2", d("-400.00")), Map.of("2010101", d("-1000.00")), TYPES, CLASSES, ACCOUNTS);
        var g = r.groups().get(0);
        assertEquals(2, g.types().size());
        assertTrue(g.credit());
        assertEquals(d("1000.00"), g.members());
        assertEquals(d("1000.00"), g.ledger());
        assertTrue(g.reconciled());
        assertEquals("Savings Deposit: SD - Regular, Kiddies", g.title());
    }

    @Test void aDifferenceIsTheLedgerLessTheMembersAndComesFirst() {
        // a journal debited Savings Deposit 50 directly: the account shows 950 owed to members, the members hold 1,000
        var r = SlReconciliation.reconcile(List.of(new Link(22, 1, "2010101"), new Link(31, 1, "3010101")),
            Map.of("22/1", d("-1000.00"), "31/1", d("-500.00")), Map.of("2010101", d("-950.00"), "3010101", d("-500.00")), TYPES, CLASSES, ACCOUNTS);
        assertEquals(1, r.unreconciled());
        var first = r.groups().get(0);
        assertFalse(first.reconciled());
        assertEquals(d("-50.00"), first.difference());
        assertEquals("Savings Deposit: SD - Regular", first.title());
        assertTrue(r.groups().get(1).reconciled());
        assertEquals(d("50.00"), r.totalDifference());
    }

    @Test void entriesOnAControlAccountWithNoMembersAreADifference() {
        var r = SlReconciliation.reconcile(List.of(new Link(31, 1, "3010101")), Map.of(), Map.of("3010101", d("-25.00")), TYPES, CLASSES, ACCOUNTS);
        assertEquals(1, r.groups().size());
        assertEquals(d("25.00"), r.groups().get(0).difference());
    }

    @Test void aTypeWithNeitherMembersNorEntriesIsLeftOut() {
        var r = SlReconciliation.reconcile(List.of(new Link(31, 1, "3010101")), Map.of(), Map.of(), TYPES, CLASSES, ACCOUNTS);
        assertTrue(r.groups().isEmpty());
    }

    @Test void aChainOfSharedAccountsJoinsIntoOneGroup() {
        // type A posts to X, type B to X and Y, type C to Y: all one group
        var r = SlReconciliation.reconcile(List.of(new Link(22, 1, "X"), new Link(22, 2, "X"), new Link(22, 2, "Y"), new Link(22, 3, "Y")),
            Map.of("22/1", d("-1"), "22/2", d("-1"), "22/3", d("-1")), Map.of("X", d("-2"), "Y", d("-1")), TYPES, CLASSES, ACCOUNTS);
        assertEquals(1, r.groups().size());
        assertEquals(3, r.groups().get(0).types().size());
        assertEquals(2, r.groups().get(0).accounts().size());
        assertTrue(r.groups().get(0).reconciled());
    }
}
