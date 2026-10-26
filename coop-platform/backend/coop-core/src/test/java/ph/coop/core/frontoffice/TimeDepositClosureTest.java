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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;
import ph.coop.core.frontoffice.TimeDepositClosureController.Encoded;
import ph.coop.core.frontoffice.TimeDepositClosureController.Quote;
import ph.coop.core.frontoffice.TimeDepositClosureController.Request;
import ph.coop.core.frontoffice.TimeDepositClosureRules.Amounts;

/**
 * The closing rules, the notice list, and a payout and a renewal of a real matured certificate encoded, approved and posted through the real code (three different
 * people), compared with the old vouchers (documentation/time-deposit-map.md); rolled back. Runs only when DB_PASSWORD is set.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@Import(TimeDepositClosureTest.FixedClock.class)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class TimeDepositClosureTest {

    static final LocalDate TODAY = LocalDate.of(2026, 4, 25);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(LocalDateTime.of(TODAY, java.time.LocalTime.of(10, 0)).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }
    }

    @Autowired TimeDepositClosureController closures;
    @Autowired TimeDepositMaturityController maturity;
    @Autowired LoanReleaseController release;
    @Autowired JdbcTemplate jdbc;
    @Autowired Db db;

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    @Test
    void theInterestAndTheExpenseFollowTheOldVouchers() {
        // client 1637, certificate 10070: 600,678.33 at 2.75% for 180 days, 5,597.99 accrued at year end (voucher 2026/21/37575)
        Amounts a = TimeDepositClosureRules.amounts(d("600678.33"), d("2.75"), 180, d("5597.99"));
        assertEquals(d("8259.33"), a.interest());
        assertEquals(d("2661.34"), a.expense());
        assertEquals(d("608937.66"), a.due());
        var rn = TimeDepositClosureRules.renewal(a, d("1000.00"), false);
        assertEquals(d("607937.66"), rn.newPrincipal(), "what the real voucher credited to the new certificate");
        // the computer-generated rollover (2026/1/4/3750): 260,406.52 at 1.75% for 90 days, 10.00 fee, nothing taken out
        Amounts b = TimeDepositClosureRules.amounts(d("260406.52"), d("1.75"), 90, d("0.00"));
        assertEquals(d("1139.28"), b.expense());
        assertEquals(d("261535.80"), TimeDepositClosureRules.renewal(b, BigDecimal.ZERO, true).newPrincipal());
    }

    @Test
    void aCertificateThatHasNotMaturedOrIsLockInIsRefused() {
        Amounts a = TimeDepositClosureRules.amounts(d("100000.00"), d("2"), 90, BigDecimal.ZERO);
        assertTrue(TimeDepositClosureRules.problems(1, "ACTIVE", TODAY.plusDays(3), TODAY, a, true, false).get(0).contains("pre-termination"));
        assertTrue(TimeDepositClosureRules.problems(1, "MATURED", TODAY.minusDays(1), TODAY, a, true, false).isEmpty());
        assertFalse(TimeDepositClosureRules.problems(2, "MATURED", TODAY.minusDays(1), TODAY, a, true, false).isEmpty(), "lock in");
        assertFalse(TimeDepositClosureRules.problems(1, "MATURED", TODAY.minusDays(1), TODAY, a, false, false).isEmpty(), "no vouchers payable account");
        assertFalse(TimeDepositClosureRules.problems(1, "MATURED", TODAY.minusDays(1), TODAY, a, true, true).isEmpty(), "a voucher is already waiting");
        assertFalse(TimeDepositClosureRules.renewalProblems(a, d("100000.00"), true, d("5000"), 90, d("2"), List.of(90, 180)).isEmpty(), "nothing left");
        assertFalse(TimeDepositClosureRules.renewalProblems(a, d("98000.00"), true, d("5000"), 90, d("2"), List.of(90, 180)).isEmpty(), "under the minimum");
        assertFalse(TimeDepositClosureRules.renewalProblems(a, d("0"), true, d("5000"), 77, d("2"), List.of(90, 180)).isEmpty(), "unused term");
        assertTrue(TimeDepositClosureRules.renewalProblems(a, d("0"), true, d("5000"), 180, d("2"), List.of(90, 180)).isEmpty());
    }

    private Authentication as(String name) { return new TestingAuthenticationToken(name, null); }

    @Test
    void theNoticeListShowsMaturedCertificatesStillHoldingMoneyAndNothingIsAutomatic() {
        List<Map<String, Object>> u = jdbc.queryForList("SELECT AccessUserName FROM access WHERE AccessBR_CODE = 1 AND COALESCE(AccessIsDisabled, 0) = 0 AND COALESCE(AccessCanAccessFrontOffice, 0) = 1 ORDER BY AccessID LIMIT 1");
        assumeTrue(!u.isEmpty(), "no front office user");
        var n = maturity.maturities(30, as((String) u.get(0).get("AccessUserName")));
        assumeTrue(!n.notices().isEmpty(), "no maturing certificates in this database");
        assertTrue(n.notices().stream().allMatch((x) -> x.balance().signum() > 0), "paid-out certificates are not listed");
        assertTrue(n.notices().stream().allMatch((x) -> x.maturity().isEqual(x.maturity()) && x.daysLeft() == java.time.temporal.ChronoUnit.DAYS.between(TODAY, x.maturity())));
        assertEquals(n.notices().stream().filter((x) -> x.overdue()).count(), n.overdue());
        assertTrue(n.notices().stream().filter((x) -> x.overdue()).allMatch((x) -> x.maturity().isBefore(TODAY)));
        assertThrows(ResponseStatusException.class, () -> maturity.maturities(9999, as((String) u.get(0).get("AccessUserName"))));
    }

    @Test
    void aPayoutAndARenewalAreEncodedThenApprovedAndPostedByThreePeople() {
        assumeTrue(db.canInsertInto("td", "slmastersum"), "needs V027_time_deposit_placement.sql and V028");
        List<Map<String, Object>> people = jdbc.queryForList("""
                SELECT a.AccessID, a.AccessUserName FROM access a JOIN access_trans x ON x.USERID = a.AccessID AND x.BR_CODE = a.AccessBR_CODE AND x.TR_CODE = 21 AND x.CAN_NEW = 1
                WHERE a.AccessBR_CODE = 1 AND COALESCE(a.AccessIsDisabled, 0) = 0 AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 1 AND COALESCE(a.AccessDepartment, 0) > 0 ORDER BY a.AccessID LIMIT 3
                """);
        assumeTrue(people.size() == 3, "need three people who may add cash vouchers");
        String encoder = (String) people.get(0).get("AccessUserName"), approver = (String) people.get(1).get("AccessUserName"), poster = (String) people.get(2).get("AccessUserName");
        // two matured Short Term certificates holding money, whose owners have a vouchers-payable account and an active record
        List<Map<String, Object>> certs = jdbc.queryForList("""
                SELECT d.ClientIDTD AS c, d.TDSLT_CODE AS t, d.TDREF_NO AS r FROM td d JOIN client k ON k.ClientIDBrCode = d.TDBR_CODE AND k.ClientID = d.ClientIDTD AND k.AccountStatus = 1
                WHERE d.TDBR_CODE = 1 AND d.TDSLT_CODE = 1 AND d.TDSTATUS = 15 AND d.TDMAT_DATE BETWEEN '2026-03-01' AND '2026-04-24'
                  AND EXISTS (SELECT 1 FROM slmastersum m WHERE m.MBR_CODE = 1 AND m.MCLIENTID = d.ClientIDTD AND m.MSLC_CODE = 29 AND m.MSLT_CODE = 1)
                  AND (SELECT COALESCE(-SUM(l.AMT), 0) FROM sldtl l WHERE l.SL_BRCODE = 1 AND l.SL_CLIENTID = d.ClientIDTD AND l.SLC_CODE = 24 AND l.SLT_CODE = d.TDSLT_CODE AND l.REF_NO = d.TDREF_NO AND l.SLE_CODE = 11) > 5000
                ORDER BY d.TDMAT_DATE DESC LIMIT 2
                """);
        assumeTrue(certs.size() == 2, "no two matured certificates holding money in this database");
        int c1 = ((Number) certs.get(0).get("c")).intValue(), r1 = ((Number) certs.get(0).get("r")).intValue();
        int c2 = ((Number) certs.get(1).get("c")).intValue(), r2 = ((Number) certs.get(1).get("r")).intValue();

        // payout
        Quote q = closures.quote(new Request(c1, 1, r1, "PAYOUT", null, null, null, null, null), as(encoder));
        assertTrue(q.problems().isEmpty(), q.problems().toString());
        Encoded e = closures.encode(new Request(c1, 1, r1, "PAYOUT", null, null, null, null, null), as(encoder));
        assertEquals(0, q.due().compareTo(e.paidToPayable()));
        List<Map<String, Object>> dt = jdbc.queryForList("SELECT * FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 2 AND DTTR_CODE = 21 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO", e.year(), e.controlNo());
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> l : dt) { sum = sum.add((BigDecimal) l.get("DTAMT")); assertEquals(2, ((Number) l.get("DTUPD_TAG")).intValue(), "waiting to be approved"); }
        assertEquals(0, sum.signum(), "the voucher balances");
        assertEquals(24, ((Number) dt.get(0).get("DTSLC_CODE")).intValue());
        assertEquals(0, q.principal().compareTo((BigDecimal) dt.get(0).get("DTAMT")));
        assertEquals(29, ((Number) dt.get(dt.size() - 1).get("DTSLC_CODE")).intValue());
        assertEquals(0, q.due().negate().compareTo((BigDecimal) dt.get(dt.size() - 1).get("DTAMT")));
        // a second voucher for the same certificate is refused while this one waits
        assertThrows(ResponseStatusException.class, () -> closures.encode(new Request(c1, 1, r1, "PAYOUT", null, null, null, null, null), as(encoder)));
        // the encoder cannot approve it, another person can, and a third posts it
        assertThrows(ResponseStatusException.class, () -> release.approve(e.year(), e.controlNo(), as(encoder)));
        release.approve(e.year(), e.controlNo(), as(approver));
        assertThrows(ResponseStatusException.class, () -> release.post(e.year(), e.controlNo(), as(approver)));
        release.post(e.year(), e.controlNo(), as(poster));
        BigDecimal left = jdbc.queryForObject("SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 24 AND SLT_CODE = 1 AND REF_NO = ? AND SLE_CODE = 11", BigDecimal.class, c1, r1);
        assertEquals(0, left.signum(), "the certificate holds nothing after the payout");
        assertTrue(closures.quote(new Request(c1, 1, r1, "PAYOUT", null, null, null, null, null), as(encoder)).problems().stream().anyMatch((p) -> p.contains("holds no money")));

        // renewal with something taken out and the renewal fee
        Quote rq = closures.quote(new Request(c2, 1, r2, "RENEW", 180, d("2.25"), d("1000.00"), true, null), as(encoder));
        assertTrue(rq.problems().isEmpty(), rq.problems().toString());
        Encoded re = closures.encode(new Request(c2, 1, r2, "RENEW", 180, d("2.25"), d("1000.00"), true, null), as(encoder));
        assertNotNullRef(re.newCertificate());
        assertEquals(0, rq.newPrincipal().compareTo(re.newPrincipal()));
        Map<String, Object> td = jdbc.queryForMap("SELECT * FROM td WHERE TDBR_CODE = 1 AND TDSLT_CODE = 1 AND TDREF_NO = ?", re.newCertificate());
        assertEquals(0, rq.newPrincipal().compareTo((BigDecimal) td.get("TDPAMT")));
        assertEquals(180, ((Number) td.get("TDTERMS")).intValue());
        assertEquals(java.sql.Date.valueOf(TODAY.plusDays(180)), td.get("TDMAT_DATE"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM slmastersum WHERE MBR_CODE = 1 AND MSLC_CODE = 24 AND MSLT_CODE = 1 AND MREF_NO = ?", Integer.class, re.newCertificate()));
        List<Map<String, Object>> rl = jdbc.queryForList("SELECT * FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 2 AND DTTR_CODE = 21 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO", re.year(), re.controlNo());
        BigDecimal rsum = BigDecimal.ZERO;
        for (Map<String, Object> l : rl) rsum = rsum.add((BigDecimal) l.get("DTAMT"));
        assertEquals(0, rsum.signum());
        assertTrue(rl.stream().anyMatch((l) -> "442202".equals(l.get("DTACCT_CODE")) && ((BigDecimal) l.get("DTAMT")).compareTo(d("-10.00")) == 0), "the renewal fee");
        assertTrue(rl.stream().anyMatch((l) -> l.get("DTSLC_CODE") != null && ((Number) l.get("DTSLC_CODE")).intValue() == 29 && ((BigDecimal) l.get("DTAMT")).compareTo(d("-1000.00")) == 0), "what was taken out");
        release.approve(re.year(), re.controlNo(), as(approver));
        release.post(re.year(), re.controlNo(), as(poster));
        BigDecimal fresh = jdbc.queryForObject("SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 24 AND SLT_CODE = 1 AND REF_NO = ? AND SLE_CODE = 11", BigDecimal.class, c2, re.newCertificate());
        assertEquals(0, rq.newPrincipal().compareTo(fresh), "the new certificate holds the renewed money");
        assertNull(jdbc.queryForList("SELECT 1 FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 2 AND DTTR_CODE = 21 AND DTTR_CTLNO = ? AND DTUPD_TAG = 2", re.year(), re.controlNo()).stream().findFirst().orElse(null), "all lines are posted");
    }

    private static void assertNotNullRef(Integer ref) {
        assertTrue(ref != null && ref > 0, "a new certificate number");
    }
}
