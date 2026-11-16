package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;
import ph.coop.core.frontoffice.TimeDepositPlacementController.Receipt;
import ph.coop.core.frontoffice.TimeDepositPlacementController.Request;

/**
 * Places a certificate through the real code, compares the rows with the old Cash Receipt for a new time deposit (documentation/time-deposit-map.md) and rolls
 * everything back. Needs V027 (INSERT on td) and a teller who may add cash receipts; skips otherwise. Runs only when DB_PASSWORD is set.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@Import(TimeDepositPlacementPostingTest.FixedClock.class)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class TimeDepositPlacementPostingTest {

    static final LocalDate TODAY = LocalDate.of(2026, 4, 25);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(LocalDateTime.of(TODAY, java.time.LocalTime.of(10, 0)).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }
    }

    @Autowired TimeDepositPlacementController placement;
    @Autowired OverrideController overrides;
    @Autowired JdbcTemplate jdbc;
    @Autowired Db db;

    private String tellerName;
    private int tellerId, tellerNumber, client;
    private TimeDepositPlacementRules.TermRates term180;

    @BeforeEach
    void setUp() {
        assumeTrue(db.canInsertInto("td"), "needs V027_time_deposit_placement.sql");
        List<Map<String, Object>> t = jdbc.queryForList("""
                SELECT a.AccessID, a.AccessUserName, a.AccessTellerNumber FROM access a
                JOIN access_trans x ON x.USERID = a.AccessID AND x.BR_CODE = a.AccessBR_CODE AND x.TR_CODE = 10 AND x.CAN_NEW = 1
                WHERE a.AccessCurrAssign LIKE '%Teller%' AND COALESCE(a.AccessIsHeadCashier, 0) = 0 AND COALESCE(a.AccessIsDisabled, 0) = 0
                  AND a.AccessTellerNumber > 0 AND a.AccessBR_CODE = 1 AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 1 AND COALESCE(a.AccessDepartment, 0) > 0
                  AND a.AccessLevel <> 'SUPERADMIN' ORDER BY a.AccessID LIMIT 1
                """);
        assumeTrue(!t.isEmpty(), "no teller who may add cash receipts");
        tellerId = ((Number) t.get(0).get("AccessID")).intValue();
        tellerName = (String) t.get(0).get("AccessUserName");
        tellerNumber = ((Number) t.get(0).get("AccessTellerNumber")).intValue();
        List<Integer> c = jdbc.queryForList("SELECT ClientID FROM client WHERE ClientIDBrCode = 1 AND AccountStatus = 1 ORDER BY ClientID DESC LIMIT 1", Integer.class);
        assumeTrue(!c.isEmpty(), "no active client");
        client = c.get(0);
        var setup = placement.setup(as(tellerName));
        var type1 = setup.types().stream().filter((x) -> x.typeCode() == 1).findFirst();
        assumeTrue(type1.isPresent(), "no TD - Short Term type with known terms");
        term180 = type1.get().terms().stream().filter((x) -> x.termDays() == 180).findFirst().orElse(null);
        assumeTrue(term180 != null, "no 180-day term in use");
    }

    private Authentication as(String name) { return new TestingAuthenticationToken(name, null); }

    private Request req(String amount, BigDecimal rate, boolean certificate) {
        return new Request(client, 1, 180, rate, new BigDecimal(amount), certificate, "itest", null, true, null);
    }

    @Test
    void aPlacementWritesTheRowsTheOldCashReceiptWrote() {
        Receipt r = placement.place(req("15000.00", term180.usual(), true), as(tellerName));
        assertEquals(TODAY.plusDays(180), r.maturity());
        assertEquals(0, new BigDecimal("15025.00").compareTo(r.total()));

        List<Map<String, Object>> dt = jdbc.queryForList("SELECT * FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 3 AND DTTR_CODE = 10 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO", r.year(), r.controlNo());
        assertEquals(3, dt.size());
        assertEquals("10003", dt.get(0).get("DTACCT_CODE"));
        assertEquals(0, new BigDecimal("15025.00").compareTo((BigDecimal) dt.get(0).get("DTAMT")), "the cash is the amount plus the fee");
        assertEquals(24, ((Number) dt.get(1).get("DTSLC_CODE")).intValue());
        assertEquals(11, ((Number) dt.get(1).get("DTSLE_CODE")).intValue());
        assertEquals(15, ((Number) dt.get(1).get("DTCTS_NUM")).intValue());
        assertEquals(r.certificate(), ((Number) dt.get(1).get("DTREF_NO")).intValue());
        assertEquals(0, new BigDecimal("-15000.00").compareTo((BigDecimal) dt.get(1).get("DTAMT")));
        assertEquals("442202", dt.get(2).get("DTACCT_CODE"));
        assertNull(dt.get(2).get("DTSLC_CODE"), "the fee is a plain general ledger line");
        assertEquals(client, ((Number) dt.get(2).get("ClientIDDT")).intValue());
        assertEquals(0, new BigDecimal("-25.00").compareTo((BigDecimal) dt.get(2).get("DTAMT")));
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> l : dt) sum = sum.add((BigDecimal) l.get("DTAMT"));
        assertEquals(0, sum.signum(), "debits equal credits");

        Map<String, Object> sl = jdbc.queryForMap("SELECT SEQNO, AMT, COCI_TYPE, ADJ_FLAG FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 24 AND SLT_CODE = 1 AND REF_NO = ?", client, r.certificate());
        assertEquals(1, ((Number) sl.get("SEQNO")).intValue(), "the first ledger row of the certificate");
        assertEquals(0, new BigDecimal("-15000.00").compareTo((BigDecimal) sl.get("AMT")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM slsum WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 24 AND SLT_CODE = 1 AND REF_NO = ? AND SLE_CODE = 11", Integer.class, client, r.certificate()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM slmastersum WHERE MBR_CODE = 1 AND MSLC_CODE = 24 AND MSLT_CODE = 1 AND MREF_NO = ? AND MCLIENTID = ?", Integer.class, r.certificate(), client));
        Map<String, Object> td = jdbc.queryForMap("SELECT * FROM td WHERE TDBR_CODE = 1 AND TDSLT_CODE = 1 AND TDREF_NO = ?", r.certificate());
        assertEquals(0, new BigDecimal("15000.00").compareTo((BigDecimal) td.get("TDPAMT")));
        assertEquals(15, ((Number) td.get("TDSTATUS")).intValue());
        assertEquals(180, ((Number) td.get("TDTERMS")).intValue());
        assertEquals(String.valueOf(r.certificate()), td.get("TDCERT_NUM"));
        assertEquals(java.sql.Date.valueOf(TODAY.plusDays(180)), td.get("TDMAT_DATE"));
        Map<String, Object> ck = jdbc.queryForMap("SELECT CKAMT, CKCOCI_TYPE FROM transck WHERE CKTR_BRCODE = 1 AND CKTR_YEAR = ? AND CKTR_CODE = 10 AND CKTR_CTLNO = ? AND CKSEQ_NO = 0", r.year(), r.controlNo());
        assertEquals(0, new BigDecimal("15025.00").compareTo((BigDecimal) ck.get("CKAMT")));
        assertEquals(1, ((Number) ck.get("CKCOCI_TYPE")).intValue());
        assertEquals(tellerId, ((Number) dt.get(0).get("DTENCDBY")).intValue());
        assertEquals(tellerNumber, ((Number) dt.get(0).get("DTCTS_NUM")).intValue());
        // the next certificate gets the next number
        Receipt second = placement.place(req("6000.00", term180.usual(), false), as(tellerName));
        assertEquals(r.certificate() + 1, second.certificate());
        assertEquals(0, new BigDecimal("6000.00").compareTo(second.total()), "no certificate, no fee");
        assertEquals(2, jdbc.queryForList("SELECT 1 FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 3 AND DTTR_CODE = 10 AND DTTR_CTLNO = ?", second.year(), second.controlNo()).size());
    }

    @Test
    void theRulesAreEnforcedOnTheServer() {
        // below the type minimum, a term never used, a rate with nobody's approval
        assertFalse(placement.check(req("100.00", term180.usual(), true), as(tellerName)).problems().isEmpty());
        assertFalse(placement.check(new Request(client, 1, 77, term180.usual(), new BigDecimal("10000.00"), true, null, null, false, null), as(tellerName)).problems().isEmpty());
        assertThrows(ResponseStatusException.class, () -> placement.place(req("100.00", term180.usual(), true), as(tellerName)));
        BigDecimal odd = term180.max().add(new BigDecimal("1.50"));
        var check = placement.check(req("10000.00", odd, true), as(tellerName));
        assertTrue(check.problems().isEmpty(), check.problems().toString());
        assertEquals("NONE", check.rateApproval());
        assertFalse(check.ready());
        assertThrows(ResponseStatusException.class, () -> placement.place(req("10000.00", odd, true), as(tellerName)), "a rate nobody allowed");
    }

    @Test
    void anOddRateIsPlacedOnlyAfterAnotherPersonAllowsItAndOnlyOnce() {
        List<Map<String, Object>> o = jdbc.queryForList("SELECT AccessUserName FROM access WHERE AccessBR_CODE = 1 AND AccessID <> ? AND COALESCE(AccessIsDisabled, 0) = 0 AND COALESCE(AccessCanAccessFrontOffice, 0) = 1 ORDER BY AccessID LIMIT 1", tellerId);
        assumeTrue(!o.isEmpty(), "no second person to approve");
        Authentication boss = new TestingAuthenticationToken((String) o.get(0).get("AccessUserName"), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        BigDecimal odd = term180.max().add(new BigDecimal("1.50"));
        Request req = req("10000.00", odd, false);
        OverrideController.Override ask = placement.ask(req, as(tellerName));
        assertEquals("PENDING", ask.status());
        assertEquals("PENDING", placement.check(req, as(tellerName)).rateApproval());
        overrides.decide(ask.id(), new OverrideController.Decision(true, "agreed"), boss);
        assertTrue(placement.check(req, as(tellerName)).ready());
        Receipt r = placement.place(req, as(tellerName));
        assertEquals(0, odd.compareTo((BigDecimal) jdbc.queryForMap("SELECT TDINT_RATE FROM td WHERE TDBR_CODE = 1 AND TDSLT_CODE = 1 AND TDREF_NO = ?", r.certificate()).get("TDINT_RATE")));
        assertThrows(ResponseStatusException.class, () -> placement.place(req, as(tellerName)), "the approval was spent");
    }
}
