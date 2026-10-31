package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;
import ph.coop.core.frontoffice.ChequeClearingController.ClearRequest;
import ph.coop.core.frontoffice.ChequeClearingController.Item;
import ph.coop.core.frontoffice.ChequeClearingController.Row;

/** Lists and clears real cheques through the real code, then rolls back. Clearing needs the V026 grant; those tests skip until it is applied. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class ChequeClearingTest {

    @Autowired ChequeClearingController clearing;
    @Autowired JdbcTemplate jdbc;
    @Autowired Db db;

    private static Authentication admin() {
        return new TestingAuthenticationToken("itest-admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    void theExpectedDayIsTheReceiptDatePlusTheKindDaysPlusTwo() {
        assertEquals(LocalDate.of(2026, 4, 29), ChequeClearingController.clearsOn(LocalDate.of(2026, 4, 24), 3));
        assertEquals(LocalDate.of(2026, 5, 3), ChequeClearingController.clearsOn(LocalDate.of(2026, 4, 24), 7));
        assertEquals(LocalDate.of(2026, 4, 24), ChequeClearingController.clearsOn(LocalDate.of(2026, 4, 24), 0), "kinds with no wait clear at once");
    }

    @Test
    void moneyPaidInByAnOpenChequeIsHeldBackFromTheAvailableBalance() {
        // The old data has savings credits paid by cheques that are still marked not cleared (April 2026); the hold-back must find them and nothing negative.
        List<java.math.BigDecimal> found = jdbc.query("SELECT " + ChequeRules.unclearedSql("s") + " FROM sd s WHERE s.SDBR_CODE = 1 AND s.SDSLC_CODE = 22 AND EXISTS "
            + "(SELECT 1 FROM sldtl x WHERE x.SL_BRCODE = s.SDBR_CODE AND x.SL_CLIENTID = s.ClientIDSD AND x.SLC_CODE = s.SDSLC_CODE AND x.SLT_CODE = s.SDSLT_CODE AND x.REF_NO = s.SDREF_NO "
            + "AND x.COCI_TYPE = 2 AND x.TR_DATE >= '2026-04-01') ORDER BY 1 DESC LIMIT 20", (rs, i) -> rs.getBigDecimal(1));
        assumeTrue(!found.isEmpty(), "no savings account took a cheque in April 2026");
        assertTrue(found.stream().allMatch((v) -> v.signum() >= 0));
        assertTrue(found.get(0).signum() > 0, "at least one account has money waiting on an open cheque");
    }

    @Test
    void aClearingDayInTheFutureOrBeforeTheReceiptIsRefused() {
        assumeTrue(db.tableExists("ecis_cheque_clearing"), "V048_cheque_clearing_record.sql has not been run");
        List<Row> open = clearing.list(admin(), false);
        assumeTrue(!open.isEmpty(), "no uncleared cheques in this database");
        Item item = new Item(open.get(0).year(), open.get(0).ctlNo(), open.get(0).seq());
        assertThrows(ResponseStatusException.class, () -> clearing.clear(new ClearRequest(List.of(item), "integration test", LocalDate.now().plusDays(1).toString(), null), admin()), "future day");
        assertThrows(ResponseStatusException.class, () -> clearing.clear(new ClearRequest(List.of(item), "integration test", "10/31/2026", null), admin()), "date format");
        assertThrows(ResponseStatusException.class, () -> clearing.clear(new ClearRequest(List.of(item), "integration test", "2000-01-01", null), admin()), "before the receipt");
        assertThrows(ResponseStatusException.class, () -> clearing.clear(new ClearRequest(List.of(item), "integration test", null, "x".repeat(41)), admin()), "long bank reference");
    }

    @Test
    void anOrdinaryUserCannotSeeOrClear() {
        Authentication nobody = new TestingAuthenticationToken("nobody-at-all", null);
        assertThrows(ResponseStatusException.class, () -> clearing.list(nobody, false));
        assertThrows(ResponseStatusException.class, () -> clearing.clear(new ClearRequest(List.of(new Item(2026, 1, 0)), null), nobody));
    }

    @Test
    void uncleared_cheques_list_and_clear_with_a_reason_when_early() {
        assumeTrue(db.canUpdateColumn("transck", "CKISCLEARED"), "V026_cheque_clearing.sql has not been run");
        List<Row> open = clearing.list(admin(), false);
        assumeTrue(!open.isEmpty(), "no uncleared cheques in this database");
        assertTrue(open.stream().noneMatch(Row::cleared));
        Row r = open.get(0);
        Item item = new Item(r.year(), r.ctlNo(), r.seq());
        if (!r.due()) {
            assertThrows(ResponseStatusException.class, () -> clearing.clear(new ClearRequest(List.of(item), null), admin()), "early clearing needs a reason");
        }
        assumeTrue(db.tableExists("ecis_cheque_clearing"), "V048_cheque_clearing_record.sql has not been run");
        var done = clearing.clear(new ClearRequest(List.of(item), "integration test", LocalDate.now().toString(), " BANK-REF-1 "), admin());
        assertEquals(1, done.cleared());
        var rec = jdbc.queryForMap("SELECT cleared_on, bank_reference, cleared_by FROM ecis_cheque_clearing WHERE tr_year = ? AND ctl_no = ? AND seq_no = ?", r.year(), r.ctlNo(), r.seq());
        assertEquals(LocalDate.now().toString(), rec.get("cleared_on").toString());
        assertEquals("BANK-REF-1", rec.get("bank_reference"));
        assertEquals("itest-admin", rec.get("cleared_by"));
        assertEquals("itest-admin", clearing.list(admin(), true).stream().filter((x) -> x.year() == r.year() && x.ctlNo() == r.ctlNo() && x.seq() == r.seq()).findFirst().orElseThrow().clearedBy());
        Integer flag = jdbc.queryForObject("SELECT CKISCLEARED FROM transck WHERE CKTR_YEAR = ? AND CKTR_CODE = 10 AND CKTR_CTLNO = ? AND CKSEQ_NO = ?", Integer.class, r.year(), r.ctlNo(), r.seq());
        assertEquals(1, flag);
        assertFalse(clearing.list(admin(), false).stream().anyMatch((x) -> x.year() == r.year() && x.ctlNo() == r.ctlNo() && x.seq() == r.seq()));
        assertEquals(0, clearing.clear(new ClearRequest(List.of(item), "integration test"), admin()).cleared(), "a cleared cheque is skipped the second time");
        Integer audit = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'cheque.clear' AND username = 'itest-admin'", Integer.class);
        assertEquals(1, audit);
    }
}
