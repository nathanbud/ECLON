package ph.coop.core.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.TestingAuthenticationToken;

/**
 * The SL reconciliation on the whole of the main database's history: it runs, every group's figures add up, and the outcome is printed for the record.
 * Adds up all of sldtl, so it takes about a minute (tag realdata: scripts/test-all.ps1 -SkipRealData leaves it out).
 */
@Tag("realdata")
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class SlReconciliationRealDataTest {

    @Autowired SlReconciliationController controller;

    @Test void reconcilesTheWholeHistoryAndTheFiguresAddUp() {
        var r = controller.run(1, LocalDate.now(), true, new TestingAuthenticationToken("itest", null, "ROLE_ACCOUNTANT"));
        assertFalse(r.groups().isEmpty(), "no member account type posts to the chart");
        BigDecimal total = BigDecimal.ZERO;
        for (var g : r.groups()) {
            assertEquals(0, g.members().compareTo(g.types().stream().map(SlReconciliation.TypeTotal::total).reduce(BigDecimal.ZERO, BigDecimal::add)), g.title());
            assertEquals(0, g.ledger().compareTo(g.accounts().stream().map(SlReconciliation.AccountTotal::balance).reduce(BigDecimal.ZERO, BigDecimal::add)), g.title());
            assertEquals(0, g.difference().compareTo(g.ledger().subtract(g.members())), g.title());
            assertTrue(g.reconciled() == (g.difference().signum() == 0), g.title());
            total = total.add(g.difference().abs());
        }
        assertEquals(0, total.compareTo(r.totalDifference()));

        System.out.println("SL RECONCILIATION as of " + r.asOf() + ": " + r.groups().size() + " groups, " + r.unreconciled() + " unreconciled, total difference " + r.totalDifference());
        for (var g : r.groups()) {
            System.out.printf("SLREC | %s | %s | members %,.2f | ledger %,.2f | difference %,.2f | accounts %s%n", g.reconciled() ? "OK " : "DIFF", g.title(), g.members(), g.ledger(), g.difference(),
                String.join(", ", g.accounts().stream().map((a) -> a.account() + " " + a.name()).toList()));
        }
    }
}
