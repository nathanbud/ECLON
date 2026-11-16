package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Passbook printing on a real savings account whose passbook is part way down a page with entries waiting: the plan continues on the next line and the
 * balance agrees with the ledger; confirming a page moves the old system's passbk row on and logs it, and a second confirm of the same page is refused;
 * a new passbook starts with the balance forwarded; only the head cashier or an administrator may change the position. Rolled back.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class PassbookPrintTest {

    @Autowired PassbookController passbooks;
    @Autowired JdbcTemplate jdbc;

    private final Authentication admin = new TestingAuthenticationToken("itest-admin", null, "ROLE_ADMIN");
    private int client, type, ref, line, seq;

    @BeforeEach
    void setUp() {
        List<Map<String, Object>> a = jdbc.queryForList("""
                SELECT * FROM (SELECT p.PASSBKCLIENTID AS client, p.PASSBKSLT_CODE AS typ, p.PASSBKREF_NO AS ref, p.PASSBKLINENUM AS line, p.PASSBKSEQNO AS seq,
                  (SELECT MAX(l.SEQNO) FROM sldtl l WHERE l.SL_BRCODE = 1 AND l.SL_CLIENTID = p.PASSBKCLIENTID AND l.SLC_CODE = 22 AND l.SLT_CODE = p.PASSBKSLT_CODE
                     AND l.REF_NO = CAST(p.PASSBKREF_NO AS INTEGER) AND l.SLE_CODE = 11) AS top
                FROM passbk p JOIN sd s ON s.SDBR_CODE = p.PASSBKBR_CODE AND s.ClientIDSD = p.PASSBKCLIENTID AND s.SDSLC_CODE = 22 AND s.SDSLT_CODE = p.PASSBKSLT_CODE
                  AND CAST(s.SDREF_NO AS INTEGER) = CAST(p.PASSBKREF_NO AS INTEGER)
                WHERE p.PASSBKBR_CODE = 1 AND p.PASSBKSLC_CODE = 22 AND p.PASSBKLINENUM BETWEEN 25 AND 29 ORDER BY p.PASSBKCLIENTID LIMIT 3000) z
                WHERE top - seq >= 8 ORDER BY client LIMIT 1
                """);
        assumeTrue(!a.isEmpty(), "no savings passbook part way down a page with 8 entries waiting");
        client = ((Number) a.get(0).get("client")).intValue();
        type = ((Number) a.get(0).get("typ")).intValue();
        ref = Integer.parseInt(String.valueOf(a.get(0).get("ref")).trim());
        line = ((Number) a.get(0).get("line")).intValue();
        seq = ((Number) a.get(0).get("seq")).intValue();
    }

    private Authentication as(String name) { return new TestingAuthenticationToken(name, null); }

    @Test
    void thePlanContinuesOnTheNextLineAndCarriesTheLedgerBalance() {
        var d = passbooks.detail(client, type, ref, admin);
        assertEquals(line, d.position().lastLine());
        assertTrue(d.waiting() >= 8);
        var first = d.pages().get(0);
        assertEquals(line + 1, first.firstLine());
        assertEquals(31, first.lastLine(), "fills the rest of the page");
        assertEquals(1, d.pages().get(1).firstLine(), "and turns the page");
        // the last line's balance is the ledger's balance after that entry
        var lastOfPage = first.lines().get(first.lines().size() - 1);
        BigDecimal ledger = jdbc.queryForObject("SELECT -SUM(AMT) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 22 AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = 11 AND SEQNO <= ?",
            BigDecimal.class, client, type, ref, lastOfPage.seq());
        assertEquals(PassbookRules.money(ledger), lastOfPage.balance());
        var pdf = passbooks.nextPage(client, type, ref, admin).getBody();
        assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test
    void confirmingAPageMovesThePassbookOnOnceAndLogsIt() {
        var before = passbooks.detail(client, type, ref, admin);
        var page = before.pages().get(0);
        var after = passbooks.confirm(client, type, ref, new PassbookController.Confirm(seq, line, page.lastSeq()), admin);
        assertEquals(31, after.position().lastLine());
        assertEquals(page.lastSeq(), after.position().lastSeq());
        assertEquals(1, after.pages().get(0).firstLine(), "the next page starts at the top");
        Map<String, Object> row = jdbc.queryForMap("SELECT PASSBKLINENUM, PASSBKSEQNO FROM passbk WHERE PASSBKBR_CODE = 1 AND PASSBKCLIENTID = ? AND PASSBKSLC_CODE = 22 AND PASSBKSLT_CODE = ? AND PASSBKREF_NO = ?",
            client, type, String.valueOf(ref));
        assertEquals(31, ((Number) row.get("PASSBKLINENUM")).intValue());
        assertEquals(page.lastSeq(), ((Number) row.get("PASSBKSEQNO")).intValue());
        assertEquals("PRINT", after.history().get(0).action());
        // the same page confirmed again (a double click, another window) is refused
        ResponseStatusException again = assertThrows(ResponseStatusException.class,
            () -> passbooks.confirm(client, type, ref, new PassbookController.Confirm(seq, line, page.lastSeq()), admin));
        assertEquals(409, again.getStatusCode().value());
    }

    @Test
    void aNewPassbookStartsWithTheBalanceForwardedAndNeedsTheHeadCashierOrAnAdministrator() {
        List<String> teller = jdbc.queryForList("SELECT AccessUserName FROM access WHERE AccessBR_CODE = 1 AND COALESCE(AccessIsDisabled, 0) = 0 AND COALESCE(AccessCanAccessFrontOffice, 0) = 1 AND COALESCE(AccessIsHeadCashier, 0) = 0 AND AccessLevel NOT IN ('ADMIN', 'SUPERADMIN') ORDER BY AccessID LIMIT 1", String.class);
        assumeTrue(!teller.isEmpty(), "no teller who is not the head cashier");
        var refused = assertThrows(ResponseStatusException.class,
            () -> passbooks.position(client, type, ref, new PassbookController.PositionIn("ITEST-1", true, null, null, "passbook full"), as(teller.get(0))));
        assertEquals(403, refused.getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
            () -> passbooks.position(client, type, ref, new PassbookController.PositionIn("ITEST-1", true, null, null, ""), admin)).getStatusCode().value(), "a reason is needed");
        var d = passbooks.position(client, type, ref, new PassbookController.PositionIn("ITEST-1", true, null, null, "passbook full"), admin);
        assertEquals("ITEST-1", d.position().serial());
        assertEquals(0, d.position().lastLine());
        assertEquals(seq, d.position().lastSeq(), "a new passbook continues after the last printed entry");
        var fwd = d.pages().get(0).lines().get(0);
        assertNull(fwd.seq());
        assertEquals(PassbookRules.FORWARD, fwd.reference());
        assertEquals(1, fwd.line());
        Integer audited = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'passbook.new' AND entity_id = ?", Integer.class, "1/" + client + "/22/" + type + "/" + ref);
        assertEquals(1, audited);
    }

    @Test
    void aReprintDoesNotMoveThePassbookButIsLogged() {
        assumeTrue(seq >= 3, "fewer than three entries printed");
        var pdf = passbooks.reprint(client, type, ref, new PassbookController.Reprint(seq - 2, seq, 5, "paper jammed"), admin).getBody();
        assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        var d = passbooks.detail(client, type, ref, admin);
        assertEquals(seq, d.position().lastSeq());
        assertEquals("REPRINT", d.history().get(0).action());
        assertEquals(400, assertThrows(ResponseStatusException.class,
            () -> passbooks.reprint(client, type, ref, new PassbookController.Reprint(seq, seq + 1, 5, "paper jammed"), admin)).getStatusCode().value(), "only printed entries");
    }

    @Test
    void onlyAnAdministratorChangesTheLayoutAndABadOneIsRefused() {
        var l = passbooks.layout();
        assertEquals(31, l.linesPerPage());
        var shifted = new PassbookRules.Layout(l.pageWidth(), l.pageHeight(), l.linesPerPage(), l.firstLine(), l.lineHeight(), l.foldAfter(), l.foldGap(), l.fontSize(),
            l.dateX(), l.referenceX(), l.depositRight(), l.withdrawalRight(), l.balanceRight(), l.tcX(), l.tellerX(), 0.05, -0.02);
        assertEquals(0.05, passbooks.putLayout(new PassbookController.LayoutIn(shifted, "printer prints a little left"), admin).shiftX(), 1e-9);
        var bad = new PassbookRules.Layout(l.pageWidth(), l.pageHeight(), 60, l.firstLine(), l.lineHeight(), l.foldAfter(), l.foldGap(), l.fontSize(),
            l.dateX(), l.referenceX(), l.depositRight(), l.withdrawalRight(), l.balanceRight(), l.tcX(), l.tellerX(), 0, 0);
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> passbooks.putLayout(new PassbookController.LayoutIn(bad, "try more lines"), admin)).getStatusCode().value());
        List<String> plain = jdbc.queryForList("SELECT AccessUserName FROM access WHERE AccessBR_CODE = 1 AND COALESCE(AccessIsDisabled, 0) = 0 AND COALESCE(AccessCanAccessFrontOffice, 0) = 1 AND AccessLevel NOT IN ('ADMIN', 'SUPERADMIN') ORDER BY AccessID LIMIT 1", String.class);
        assumeTrue(!plain.isEmpty(), "no front office user who is not an administrator");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> passbooks.putLayout(new PassbookController.LayoutIn(shifted, "printer prints a little left"), as(plain.get(0)))).getStatusCode().value());
    }
}
