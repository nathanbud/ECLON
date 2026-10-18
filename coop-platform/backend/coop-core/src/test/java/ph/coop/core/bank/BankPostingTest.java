package ph.coop.core.bank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.bank.BankController.Bank;
import ph.coop.core.bank.BankController.Key;
import ph.coop.core.bank.BankController.Recon;
import ph.coop.core.bank.BankController.SaveRequest;
import ph.coop.core.bank.BankController.Sheet;

/**
 * Reads a real bank account and reconciles it through the real code, then rolls everything back. Runs only when DB_PASSWORD is set;
 * needs V013. Skips when the database has no bank account with recent entries.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class BankPostingTest {

    @Autowired BankController banks;
    @Autowired JdbcTemplate jdbc;

    private int bankId;
    private String coa;
    /** The bank's last day with entries: the old data stops well before today, so every date in these tests is counted back from here. */
    private LocalDate anchor;

    @BeforeEach
    void setUp() {
        List<java.util.Map<String, Object>> b = jdbc.queryForList("""
                SELECT c.BCID, c.BCCOAID, (SELECT MAX(g.GLTransTR_DATE) FROM gltrans g WHERE g.GLTransBR_CODE = 1 AND g.GLTransACCT_CODE = c.BCCOAID) AS last_day
                FROM bankcode c WHERE c.BCBR_CODE = 1 AND c.BCCOAID IS NOT NULL ORDER BY c.BCID
                """);
        for (java.util.Map<String, Object> row : b) {
            if (row.get("last_day") == null) continue;
            LocalDate last = ((java.sql.Date) row.get("last_day")).toLocalDate();
            if (last.isAfter(LocalDate.now())) continue;
            Integer recent = jdbc.queryForObject("SELECT COUNT(*) FROM gltrans WHERE GLTransBR_CODE = 1 AND GLTransACCT_CODE = ? AND GLTransTR_DATE BETWEEN ? AND ?",
                Integer.class, row.get("BCCOAID"), last.minusDays(58).toString(), last.minusDays(2).toString());
            if (recent != null && recent >= 6) {
                bankId = ((Number) row.get("BCID")).intValue();
                coa = (String) row.get("BCCOAID");
                anchor = last;
                return;
            }
        }
        assumeTrue(false, "no bank account with enough entries in its last two months");
    }

    private Authentication as(String name) {
        Authentication a = new TestingAuthenticationToken(name, null, "ROLE_ACCOUNTANT");
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    private Bank bank() {
        return banks.banks(1).stream().filter((x) -> x.id() == bankId).findFirst().orElseThrow();
    }

    @Test
    void listsBanksWithTheLedgerBalance() {
        as("maker");
        Bank b = bank();
        BigDecimal gl = jdbc.queryForObject("SELECT SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT) FROM gltrans WHERE GLTransBR_CODE = 1 AND GLTransACCT_CODE = ?", BigDecimal.class, coa);
        assertEquals(0, gl.compareTo(b.bookBalance()));
        assertEquals(coa, b.ledgerAccount());
        assertNotNull(b.lastActivity());
        assertNull(b.reconciledThrough());
    }

    @Test
    void entriesRunFromTheOpeningBalanceToTheClosingBalance() {
        as("maker");
        LocalDate to = anchor, from = to.minusDays(120);
        BankController.Statement s = banks.entries(bankId, 1, from, to);
        assertFalse(s.entries().isEmpty());
        BigDecimal sum = s.entries().stream().map(BankController.Entry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, s.opening().add(sum).compareTo(s.closing()));
        assertEquals(0, s.closing().compareTo(s.entries().get(s.entries().size() - 1).balanceAfter()));
        // the ledger detail adds up to the general ledger for this account
        BigDecimal gl = jdbc.queryForObject("SELECT SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT) FROM gltrans WHERE GLTransBR_CODE = 1 AND GLTransACCT_CODE = ? AND GLTransTR_DATE <= ?", BigDecimal.class, coa, to.toString());
        assertEquals(0, gl.compareTo(s.closing()), "entries (tagged 1 or 5) match the general ledger");
        assertThrows(ResponseStatusException.class, () -> banks.entries(bankId, 1, to.minusDays(500), to));
    }

    @Test
    void aReconciliationIsPreparedByOnePersonAndFinalizedByAnother() {
        as("maker");
        LocalDate first = anchor.minusDays(2);
        Sheet sheet = banks.sheet(bankId, 1, first);
        assertFalse(sheet.items().isEmpty());
        assertTrue(sheet.items().stream().noneMatch(BankController.SheetItem::outstanding), "nothing is outstanding before the first reconciliation");
        List<Key> outstanding = sheet.items().subList(Math.max(0, sheet.items().size() - 2), sheet.items().size()).stream().map(BankController.SheetItem::key).toList();
        BigDecimal outstandingTotal = sheet.items().subList(Math.max(0, sheet.items().size() - 2), sheet.items().size()).stream().map(BankController.SheetItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        // wrong balance: saved as a draft, but it cannot be finalized
        Recon wrong = banks.save(bankId, 1, new SaveRequest(first, sheet.bookBalance().add(new BigDecimal("12.34")), outstanding, null), as("maker"));
        assertEquals("DRAFT", wrong.status());
        assertFalse(wrong.reconciled());
        assertEquals(0, new BigDecimal("12.34").add(outstandingTotal).compareTo(wrong.difference()));
        assertEquals(2, wrong.outstandingCount());
        assertThrows(ResponseStatusException.class, () -> banks.finalizeIt(wrong.id(), as("checker")));

        // the right balance replaces the draft
        BigDecimal statement = sheet.bookBalance().subtract(outstandingTotal);
        Recon good = banks.save(bankId, 1, new SaveRequest(first, statement, outstanding, "itest"), as("maker"));
        assertTrue(good.reconciled());
        assertEquals(0, sheet.bookBalance().compareTo(good.bookBalance()));
        assertEquals("REPLACED", jdbc.queryForObject("SELECT status FROM ecis_bank_recon WHERE id = ?", String.class, wrong.id()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_bank_recon WHERE branch_code = 1 AND bank_id = ? AND status = 'DRAFT'", Integer.class, bankId));
        assertThrows(ResponseStatusException.class, () -> banks.finalizeIt(good.id(), as("maker")), "the person who prepared it cannot finalize it");

        Recon done = banks.finalizeIt(good.id(), as("checker"));
        assertEquals("FINAL", done.status());
        assertEquals("checker", done.finalizedBy());
        assertEquals(first, bank().reconciledThrough());
        assertEquals(0, statement.compareTo(bank().lastStatementBalance()));
        assertThrows(ResponseStatusException.class, () -> banks.finalizeIt(good.id(), as("checker")), "already final");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'bank.recon.finalize' AND entity_id = ?", Integer.class, String.valueOf(good.id())));

        // the next statement starts after the last one and carries what was still outstanding
        as("maker");
        Sheet next = banks.sheet(bankId, 1, anchor);
        assertEquals(good.id(), next.previousId());
        assertEquals(first, next.previousDate());
        assertEquals(2, next.items().stream().filter(BankController.SheetItem::outstanding).count());
        assertTrue(next.items().stream().filter(BankController.SheetItem::outstanding).map(BankController.SheetItem::key).allMatch(outstanding::contains));
        assertThrows(ResponseStatusException.class, () -> banks.save(bankId, 1, new SaveRequest(first, statement, List.of(), null), as("maker")), "not later than the last finalized statement");
    }

    @Test
    void refusesBadRequests() {
        as("maker");
        assertThrows(ResponseStatusException.class, () -> banks.save(bankId, 1, new SaveRequest(LocalDate.now().plusDays(1), BigDecimal.ZERO, List.of(), null), as("maker")));
        assertThrows(ResponseStatusException.class, () -> banks.save(bankId, 1, new SaveRequest(LocalDate.now(), BigDecimal.ZERO, List.of(new Key(2026, 5, 33, 999999999L, 0)), null), as("maker")));
        assertThrows(ResponseStatusException.class, () -> banks.entries(99999, 1, LocalDate.now().minusDays(5), LocalDate.now()));
        assertThrows(ResponseStatusException.class, () -> banks.finalizeIt(999999999L, as("checker")));
    }
}
