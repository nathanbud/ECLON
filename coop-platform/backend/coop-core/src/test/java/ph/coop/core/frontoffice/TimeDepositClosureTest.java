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
    void aCertificateThatCannotBeClosedIsRefused() {
        Amounts a = TimeDepositClosureRules.amounts(d("100000.00"), d("2"), 90, BigDecimal.ZERO);
        assertTrue(TimeDepositClosureRules.problems(1, "ACTIVE", TODAY.plusDays(3), TODAY, a, true, false).isEmpty(), "not matured is a pre-termination, handled by the controller");
        assertFalse(TimeDepositClosureRules.problems(1, "ACTIVE", null, TODAY, a, true, false).isEmpty(), "no maturity date");
        assertTrue(TimeDepositClosureRules.problems(1, "MATURED", TODAY.minusDays(1), TODAY, a, true, false).isEmpty());
        assertTrue(TimeDepositClosureRules.problems(2, "MATURED", TODAY.minusDays(1), TODAY, a, true, false).isEmpty(), "lock in matured");
        assertFalse(TimeDepositClosureRules.problems(9, "MATURED", TODAY.minusDays(1), TODAY, a, true, false).isEmpty(), "unknown kind");
        assertFalse(TimeDepositClosureRules.problems(1, "MATURED", TODAY.minusDays(1), TODAY, a, false, false).isEmpty(), "no vouchers payable account");
        assertFalse(TimeDepositClosureRules.problems(1, "MATURED", TODAY.minusDays(1), TODAY, a, true, true).isEmpty(), "a voucher is already waiting");
        assertFalse(TimeDepositClosureRules.renewalProblems(a, d("100000.00"), true, d("5000"), 90, d("2"), List.of(90, 180)).isEmpty(), "nothing left");
        assertFalse(TimeDepositClosureRules.renewalProblems(a, d("98000.00"), true, d("5000"), 90, d("2"), List.of(90, 180)).isEmpty(), "under the minimum");
        assertFalse(TimeDepositClosureRules.renewalProblems(a, d("0"), true, d("5000"), 77, d("2"), List.of(90, 180)).isEmpty(), "unused term");
        assertTrue(TimeDepositClosureRules.renewalProblems(a, d("0"), true, d("5000"), 180, d("2"), List.of(90, 180)).isEmpty());
    }

    @Test
    void lockInInterestIsAYearOfInterestPerUnpaidYearAndTheAccrualIsTakenBack() {
        // 2026/21/37997: 500,000 at 6%, 30,415.64 accrued, paid 530,000, 415.64 credited back to 50105
        Amounts a = TimeDepositClosureRules.lockInAmounts(d("500000.00"), d("6"), 1, d("30415.64"));
        assertEquals(d("30000.00"), a.interest());
        assertEquals(d("-415.64"), a.expense());
        assertEquals(d("530000.00"), a.due());
        // 2026/21/39485: 200,000 at 4%, 7,444.46 accrued, 555.54 expensed
        assertEquals(d("555.54"), TimeDepositClosureRules.lockInAmounts(d("200000.00"), d("4"), 1, d("7444.46")).expense());
        // 2026/21/36232: 31,000 at 4%, 1,570.72 accrued, paid 32,240, 330.72 credited back
        Amounts c = TimeDepositClosureRules.lockInAmounts(d("31000.00"), d("4"), 1, d("1570.72"));
        assertEquals(d("32240.00"), c.due());
        assertEquals(d("-330.72"), c.expense());
        // 2026/21/38370: 1,500,000 at 6%, nothing accrued, interest paid out and the principal renewed with no fee
        Amounts b = TimeDepositClosureRules.lockInAmounts(d("1500000.00"), d("6"), 1, BigDecimal.ZERO);
        assertEquals(d("90000.00"), b.expense());
        assertEquals(d("1500000.00"), TimeDepositClosureRules.renewal(b, b.interest(), false).newPrincipal());
        // 2025/21/34648: three years of interest on 100,000 at 4% paid at once
        assertEquals(d("12000.00"), TimeDepositClosureRules.lockInAmounts(d("100000.00"), d("4"), 3, BigDecimal.ZERO).interest());
        assertTrue(TimeDepositClosureRules.problems(2, "MATURED", TODAY.minusDays(1), TODAY, TimeDepositClosureRules.lockInAmounts(d("1000.00"), d("4"), 1, d("500.00")), true, false).isEmpty(), "over-accrual is fine for Lock In");
        assertEquals(5, TimeDepositClosureRules.maxYears(1826));
        assertEquals(3, TimeDepositClosureRules.maxYears(1096));
        assertTrue(TimeDepositClosureRules.yearsProblems(2, 1, 1826).isEmpty());
        assertFalse(TimeDepositClosureRules.yearsProblems(2, 0, 1826).isEmpty());
        assertFalse(TimeDepositClosureRules.yearsProblems(2, 6, 1826).isEmpty());
        assertTrue(TimeDepositClosureRules.yearsProblems(1, null, 180).isEmpty(), "years only matter for Lock In");
    }

    @Test
    void aLockInPayoutAndARenewalIntoShortTermArePostedLikeTheOldVouchers() {
        assumeTrue(db.canInsertInto("td", "slmastersum"), "needs V027_time_deposit_placement.sql and V028");
        List<Map<String, Object>> people = jdbc.queryForList("""
                SELECT a.AccessID, a.AccessUserName FROM access a JOIN access_trans x ON x.USERID = a.AccessID AND x.BR_CODE = a.AccessBR_CODE AND x.TR_CODE = 21 AND x.CAN_NEW = 1
                WHERE a.AccessBR_CODE = 1 AND COALESCE(a.AccessIsDisabled, 0) = 0 AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 1 AND COALESCE(a.AccessDepartment, 0) > 0 ORDER BY a.AccessID LIMIT 3
                """);
        assumeTrue(people.size() == 3, "need three people who may add cash vouchers");
        String encoder = (String) people.get(0).get("AccessUserName"), approver = (String) people.get(1).get("AccessUserName"), poster = (String) people.get(2).get("AccessUserName");
        // every matured Lock In here has already been closed, so two are made for an active member with a vouchers-payable account (rolled back with the test):
        // 100,000 at 4% for 1,096 days placed on 2023-04-01, with 700.00 of interest accrued on 2026-03-31 (a real accrual row is a credit, so negative)
        List<Map<String, Object>> owner = jdbc.queryForList("""
                SELECT k.ClientID AS c FROM client k WHERE k.ClientIDBrCode = 1 AND k.AccountStatus = 1
                  AND EXISTS (SELECT 1 FROM slmastersum m WHERE m.MBR_CODE = 1 AND m.MCLIENTID = k.ClientID AND m.MSLC_CODE = 29 AND m.MSLT_CODE = 1) ORDER BY k.ClientID LIMIT 1
                """);
        assumeTrue(!owner.isEmpty(), "no active member with a vouchers-payable account");
        int client = ((Number) owner.get(0).get("c")).intValue();
        int top = jdbc.queryForObject("SELECT COALESCE(MAX(TDREF_NO), 0) FROM td WHERE TDBR_CODE = 1 AND TDSLC_CODE = 24 AND TDSLT_CODE = 2", Integer.class);
        int c1 = client, r1 = top + 1, c2 = client, r2 = top + 2;
        for (int r : new int[] {r1, r2}) {
            jdbc.update("INSERT INTO slmastersum (MBR_CODE, MSLC_CODE, MSLT_CODE, MREF_NO, MCLIENTID) VALUES (1, 24, 2, ?, ?)", r, client);
            jdbc.update("INSERT INTO td (TDBR_CODE, TDSLC_CODE, TDSLT_CODE, TDREF_NO, ClientIDTD, TDPAMT, TDTR_DATE, TDSTATUS, TDINT_RATE, TDTERMS, TDTERM_PERD, TDMAT_DATE, TDCERT_NUM) VALUES (1, 24, 2, ?, ?, 100000.00, '2023-04-01', 15, 4, 1096, 1, '2026-04-01', ?)", r, client, String.valueOf(r));
            for (int ev : new int[] {11, 71}) jdbc.update("INSERT INTO slsum (SL_BRCODE, SL_CLIENTID, SLC_CODE, SLT_CODE, REF_NO, SLE_CODE, TR_DATE) VALUES (1, ?, 24, 2, ?, ?, '2023-04-01')", client, r, ev);
            jdbc.update("INSERT INTO sldtl (SL_BRCODE, SL_CLIENTID, SLC_CODE, SLT_CODE, REF_NO, SLE_CODE, TR_DATE, SEQNO, GL_DATE, TR_CODE, TR_CTLNO, TR_DOCNO, AMT, ADJ_FLAG) VALUES (1, ?, 24, 2, ?, 11, '2023-04-01', 1, '2023-04-01', 96, 1, '00000001', -100000.00, 1)", client, r);
            jdbc.update("INSERT INTO sldtl (SL_BRCODE, SL_CLIENTID, SLC_CODE, SLT_CODE, REF_NO, SLE_CODE, TR_DATE, SEQNO, GL_DATE, TR_CODE, TR_CTLNO, TR_DOCNO, AMT, ADJ_FLAG) VALUES (1, ?, 24, 2, ?, 71, '2026-03-31', 2, '2026-03-31', 52, 1, '00000001', -700.00, 1)", client, r);
        }

        // payout of one year of interest: debits the certificate, the accrual and 50105 (a credit when over-accrued), credits vouchers payable with principal plus interest
        Request pay = new Request(c1, 2, r1, "PAYOUT", null, null, null, null, null, null, 1);
        Quote q = closures.quote(pay, as(encoder));
        assertTrue(q.problems().isEmpty(), q.problems().toString());
        assertTrue(q.lockIn());
        assertEquals(0, d("4000.00").compareTo(q.interest()), "a year of interest on 100,000 at 4%");
        assertEquals(0, d("3300.00").compareTo(q.expense()), "less the 700.00 accrued");
        assertEquals(0, q.interest().compareTo(q.principal().multiply(q.ratePercent()).divide(d("100"), 2, java.math.RoundingMode.HALF_UP)));
        Encoded e = closures.encode(pay, as(encoder));
        List<Map<String, Object>> dt = jdbc.queryForList("SELECT * FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 2 AND DTTR_CODE = 21 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO", e.year(), e.controlNo());
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> l : dt) sum = sum.add((BigDecimal) l.get("DTAMT"));
        assertEquals(0, sum.signum(), "the voucher balances");
        assertEquals(0, q.due().negate().compareTo((BigDecimal) dt.get(dt.size() - 1).get("DTAMT")));
        release.approve(e.year(), e.controlNo(), as(approver));
        release.post(e.year(), e.controlNo(), as(poster));
        BigDecimal left = jdbc.queryForObject("SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 24 AND SLT_CODE = 2 AND REF_NO = ? AND SLE_CODE = 11", BigDecimal.class, c1, r1);
        assertEquals(0, left.signum(), "the Lock In certificate holds nothing after the payout");

        // renewal into a Short Term certificate: the principal rolls, the interest is paid out, no renewal fee
        Request ren = new Request(c2, 2, r2, "RENEW", 180, d("2.25"), null, null, null, 1, 1);
        Quote rq = closures.quote(ren, as(encoder));
        assertTrue(rq.problems().isEmpty(), rq.problems().toString());
        assertEquals(0, rq.newPrincipal().compareTo(rq.principal()), "only the principal is renewed");
        assertEquals(0, rq.fee().signum(), "no renewal fee on a Lock In");
        Encoded re = closures.encode(ren, as(encoder));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM td WHERE TDBR_CODE = 1 AND TDSLT_CODE = 1 AND TDREF_NO = ? AND ClientIDTD = ?", Integer.class, re.newCertificate(), c2));
        List<Map<String, Object>> rl = jdbc.queryForList("SELECT * FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 2 AND DTTR_CODE = 21 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO", re.year(), re.controlNo());
        BigDecimal rsum = BigDecimal.ZERO;
        for (Map<String, Object> l : rl) rsum = rsum.add((BigDecimal) l.get("DTAMT"));
        assertEquals(0, rsum.signum());
        assertTrue(rl.stream().noneMatch((l) -> "442202".equals(l.get("DTACCT_CODE"))));
        release.approve(re.year(), re.controlNo(), as(approver));
        release.post(re.year(), re.controlNo(), as(poster));
        BigDecimal fresh = jdbc.queryForObject("SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 24 AND SLT_CODE = 1 AND REF_NO = ? AND SLE_CODE = 11", BigDecimal.class, c2, re.newCertificate());
        assertEquals(0, rq.newPrincipal().compareTo(fresh));
    }

    @Test
    void preTerminationFollowsTheOldVouchers() {
        // 2025/21/20665: 155,000 held 94 days at the 1% termination rate = the 404.72 accrued; 2025/21/20711: 249,329.06 held 60 days = 415.55, 857.07 accrued, 441.52 credited back
        Amounts a = TimeDepositClosureRules.pretermAmounts(1, d("155000.00"), 94, d("404.72"), null, null);
        assertEquals(d("404.72"), a.interest());
        assertEquals(d("0.00"), a.expense());
        assertEquals(d("100.00"), a.termFee());
        Amounts b = TimeDepositClosureRules.pretermAmounts(1, d("249329.06"), 60, d("857.07"), null, null);
        assertEquals(d("415.55"), b.interest());
        assertEquals(d("-441.52"), b.expense());
        assertEquals(d("249329.06").add(d("415.55")).subtract(d("100.00")), b.due());
        // 2025/21/21659: 100,000 for 126 days @ 1% = 350.00 with nothing taken back; 2026/21/38798: 390,000 for 82 days = 888.33, paid 390,700 with 88.33 rounded to HABP
        assertEquals(d("350.00"), TimeDepositClosureRules.pretermAmounts(1, d("100000.00"), 126, d("350.00"), null, null).interest());
        assertEquals(d("888.33"), TimeDepositClosureRules.pretermAmounts(1, d("390000.00"), 82, BigDecimal.ZERO, null, null).interest());
        // Lock In: 2025/21/24493 1,000,000 after 343 days pays 1% = 10,000 and takes the 30,166.68 accrual back; 28882: 201,500 after 896 days pays 5% = 10,075
        Amounts c = TimeDepositClosureRules.pretermAmounts(2, d("1000000.00"), 343, d("30166.68"), null, null);
        assertEquals(d("10000.00"), c.termFee());
        assertEquals(d("-30166.68"), c.expense());
        assertEquals(d("990000.00"), c.due());
        assertEquals(d("10075.00"), TimeDepositClosureRules.pretermAmounts(2, d("201500.00"), 896, d("2037.40"), null, null).termFee());
        assertEquals(d("3800.00"), TimeDepositClosureRules.pretermAmounts(2, d("380000.00"), 205, d("7684.45"), null, null).termFee());
        // the typed rate cannot exceed the certificate's, and a Lock In cannot pay more than it accrued
        assertFalse(TimeDepositClosureRules.pretermProblems(1, d("2"), d("3"), null, BigDecimal.ZERO, LocalDate.of(2026, 1, 1), TODAY).isEmpty());
        assertTrue(TimeDepositClosureRules.pretermProblems(1, d("2"), d("1"), null, BigDecimal.ZERO, LocalDate.of(2026, 1, 1), TODAY).isEmpty());
        assertFalse(TimeDepositClosureRules.pretermProblems(2, d("4"), null, d("800"), d("700"), LocalDate.of(2026, 1, 1), TODAY).isEmpty());
        assertTrue(TimeDepositClosureRules.pretermProblems(2, d("4"), null, d("700"), d("700"), LocalDate.of(2026, 1, 1), TODAY).isEmpty());
    }

    @Test
    void lockInInterestWithdrawnOnAnAnniversaryFollowsTheOldVouchers() {
        // 2026/21/39465: 437,000 at 4% paid a year, 17,043.00 of it accrued, 437.00 to 50105; 39680: 1,000,000 at 4%, 37,222.18 accrued, 2,777.82 to 50105; 39794: 600,000 at 6% = 36,000.00
        assertEquals(d("17480.00"), TimeDepositClosureRules.yearInterest(d("437000.00"), d("4")));
        var a = TimeDepositClosureRules.interestAmounts(d("17480.00"), d("17043.00"));
        assertEquals(d("17043.00"), a.fromAccrual());
        assertEquals(d("437.00"), a.expense());
        assertEquals(d("2777.82"), TimeDepositClosureRules.interestAmounts(TimeDepositClosureRules.yearInterest(d("1000000.00"), d("4")), d("37222.18")).expense());
        assertEquals(d("36000.00"), TimeDepositClosureRules.yearInterest(d("600000.00"), d("6")));
        // with more accrued than paid only the amount is taken from the accrual, and nothing goes to 50105
        var b = TimeDepositClosureRules.interestAmounts(d("417.50"), d("452.50"));
        assertEquals(d("417.50"), b.fromAccrual());
        assertEquals(0, b.expense().signum());
        // anniversaries: one passed after a year, or 15 days short of it (a real withdrawal came at 350 days)
        assertEquals(1, TimeDepositClosureRules.completedYears(TODAY.minusDays(365), TODAY));
        assertEquals(1, TimeDepositClosureRules.completedYears(TODAY.minusDays(350), TODAY));
        assertEquals(0, TimeDepositClosureRules.completedYears(TODAY.minusDays(349), TODAY));
        assertEquals(3, TimeDepositClosureRules.completedYears(TODAY.minusDays(1100), TODAY));
        assertEquals(d("4000.00"), TimeDepositClosureRules.interestAvailable(d("100000.00"), d("4"), TODAY.minusDays(380), TODAY, BigDecimal.ZERO));
        assertEquals(d("500.00"), TimeDepositClosureRules.interestAvailable(d("100000.00"), d("4"), TODAY.minusDays(380), TODAY, d("3500.00")));
        assertEquals(0, TimeDepositClosureRules.interestAvailable(d("100000.00"), d("4"), TODAY.minusDays(380), TODAY, d("5000.00")).signum());
        assertTrue(TimeDepositClosureRules.interestProblems(2, "ACTIVE", TODAY.plusDays(400), TODAY, d("4000.00"), d("4000.00"), true, false).isEmpty());
        assertFalse(TimeDepositClosureRules.interestProblems(2, "ACTIVE", TODAY.plusDays(400), TODAY, d("4000.01"), d("4000.00"), true, false).isEmpty(), "more than has fallen due");
        assertFalse(TimeDepositClosureRules.interestProblems(1, "ACTIVE", TODAY.plusDays(40), TODAY, d("10.00"), d("100.00"), true, false).isEmpty(), "Short Term");
        assertFalse(TimeDepositClosureRules.interestProblems(2, "MATURED", TODAY.minusDays(1), TODAY, d("10.00"), d("100.00"), true, false).isEmpty(), "matured is paid out");
        assertFalse(TimeDepositClosureRules.interestProblems(2, "ACTIVE", TODAY.plusDays(40), TODAY, d("10.00"), d("100.00"), true, true).isEmpty(), "a voucher waits");
    }

    @Test
    void aLockInAnniversaryInterestIsEncodedApprovedAndPostedAndTheCertificateIsUntouched() {
        assumeTrue(db.canInsertInto("transsum", "transdt", "td", "slmastersum"), "needs the voucher and certificate grants");
        List<Map<String, Object>> people = jdbc.queryForList("""
                SELECT a.AccessID, a.AccessUserName FROM access a JOIN access_trans x ON x.USERID = a.AccessID AND x.BR_CODE = a.AccessBR_CODE AND x.TR_CODE = 21 AND x.CAN_NEW = 1
                WHERE a.AccessBR_CODE = 1 AND COALESCE(a.AccessIsDisabled, 0) = 0 AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 1 AND COALESCE(a.AccessDepartment, 0) > 0 ORDER BY a.AccessID LIMIT 3
                """);
        assumeTrue(people.size() == 3, "need three people who may add cash vouchers");
        String encoder = (String) people.get(0).get("AccessUserName"), approver = (String) people.get(1).get("AccessUserName"), poster = (String) people.get(2).get("AccessUserName");
        List<Map<String, Object>> owner = jdbc.queryForList("""
                SELECT k.ClientID AS c FROM client k WHERE k.ClientIDBrCode = 1 AND k.AccountStatus = 1
                  AND EXISTS (SELECT 1 FROM slmastersum m WHERE m.MBR_CODE = 1 AND m.MCLIENTID = k.ClientID AND m.MSLC_CODE = 29 AND m.MSLT_CODE = 1) ORDER BY k.ClientID LIMIT 1
                """);
        assumeTrue(!owner.isEmpty(), "no active member with a vouchers-payable account");
        int client = ((Number) owner.get(0).get("c")).intValue();
        int ref = jdbc.queryForObject("SELECT COALESCE(MAX(TDREF_NO), 0) FROM td WHERE TDBR_CODE = 1 AND TDSLC_CODE = 24 AND TDSLT_CODE = 2", Integer.class) + 1;
        // 100,000 at 4% placed 390 days ago, one anniversary passed, 3,500.00 accrued
        makeCertificate(client, 2, ref, TODAY.minusDays(390).toString(), TODAY.plusDays(700).toString(), 1096, "4", "3500.00");

        var q = closures.interestQuote(new TimeDepositClosureController.InterestRequest(client, 2, ref, null, null), as(encoder));
        assertTrue(q.problems().isEmpty(), q.problems().toString());
        assertEquals(1, q.completedYears());
        assertEquals(0, d("4000.00").compareTo(q.amount()), "a full year by default");
        assertEquals(0, d("3500.00").compareTo(q.fromAccrual()));
        assertEquals(0, d("500.00").compareTo(q.expense()));
        assertTrue(closures.interestQuote(new TimeDepositClosureController.InterestRequest(client, 2, ref, d("4000.01"), null), as(encoder)).problems().size() > 0, "more than has fallen due");
        Encoded e = closures.interestEncode(new TimeDepositClosureController.InterestRequest(client, 2, ref, null, null), as(encoder));
        List<Map<String, Object>> dt = jdbc.queryForList("SELECT * FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 2 AND DTTR_CODE = 21 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO", e.year(), e.controlNo());
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> l : dt) sum = sum.add((BigDecimal) l.get("DTAMT"));
        assertEquals(0, sum.signum(), "the voucher balances");
        assertEquals(3, dt.size(), "accrual, 50105 and vouchers payable");
        // a second voucher is refused while this one waits
        assertThrows(ResponseStatusException.class, () -> closures.interestEncode(new TimeDepositClosureController.InterestRequest(client, 2, ref, null, null), as(encoder)));
        release.approve(e.year(), e.controlNo(), as(approver));
        release.post(e.year(), e.controlNo(), as(poster));
        assertEquals(0, d("100000.00").compareTo(jdbc.queryForObject("SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 24 AND SLT_CODE = 2 AND REF_NO = ? AND SLE_CODE = 11", BigDecimal.class, client, ref)), "the principal is untouched");
        assertEquals(0, jdbc.queryForObject("SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 24 AND SLT_CODE = 2 AND REF_NO = ? AND SLE_CODE = 71", BigDecimal.class, client, ref).signum(), "the accrual was taken");
        // the year is spent: another withdrawal waits for the next anniversary
        assertTrue(closures.interestQuote(new TimeDepositClosureController.InterestRequest(client, 2, ref, d("500.01"), null), as(encoder)).problems().stream().anyMatch((x) -> x.contains("fallen due")));
    }

    private void makeCertificate(int client, int type, int ref, String placed, String maturity, int terms, String rate, String accrued) {
        jdbc.update("INSERT INTO slmastersum (MBR_CODE, MSLC_CODE, MSLT_CODE, MREF_NO, MCLIENTID) VALUES (1, 24, ?, ?, ?)", type, ref, client);
        jdbc.update("INSERT INTO td (TDBR_CODE, TDSLC_CODE, TDSLT_CODE, TDREF_NO, ClientIDTD, TDPAMT, TDTR_DATE, TDSTATUS, TDINT_RATE, TDTERMS, TDTERM_PERD, TDMAT_DATE, TDCERT_NUM) VALUES (1, 24, ?, ?, ?, 100000.00, ?, 15, ?, ?, 1, ?, ?)",
            type, ref, client, placed, rate, terms, maturity, String.valueOf(ref));
        for (int ev : new int[] {11, 71}) jdbc.update("INSERT INTO slsum (SL_BRCODE, SL_CLIENTID, SLC_CODE, SLT_CODE, REF_NO, SLE_CODE, TR_DATE) VALUES (1, ?, 24, ?, ?, ?, ?)", client, type, ref, ev, placed);
        jdbc.update("INSERT INTO sldtl (SL_BRCODE, SL_CLIENTID, SLC_CODE, SLT_CODE, REF_NO, SLE_CODE, TR_DATE, SEQNO, GL_DATE, TR_CODE, TR_CTLNO, TR_DOCNO, AMT, ADJ_FLAG) VALUES (1, ?, 24, ?, ?, 11, ?, 1, ?, 96, 1, '00000001', -100000.00, 1)", client, type, ref, placed, placed);
        if (accrued != null)
            jdbc.update("INSERT INTO sldtl (SL_BRCODE, SL_CLIENTID, SLC_CODE, SLT_CODE, REF_NO, SLE_CODE, TR_DATE, SEQNO, GL_DATE, TR_CODE, TR_CTLNO, TR_DOCNO, AMT, ADJ_FLAG) VALUES (1, ?, 24, ?, ?, 71, '2026-03-31', 2, '2026-03-31', 52, 1, '00000001', ?, 1)", client, type, ref, new BigDecimal(accrued).negate());
    }

    @Test
    void aShortTermAndALockInArePreTerminatedThenApprovedAndPostedByThreePeople() {
        assumeTrue(db.canInsertInto("td", "slmastersum"), "needs V027_time_deposit_placement.sql and V028");
        List<Map<String, Object>> people = jdbc.queryForList("""
                SELECT a.AccessID, a.AccessUserName FROM access a JOIN access_trans x ON x.USERID = a.AccessID AND x.BR_CODE = a.AccessBR_CODE AND x.TR_CODE = 21 AND x.CAN_NEW = 1
                WHERE a.AccessBR_CODE = 1 AND COALESCE(a.AccessIsDisabled, 0) = 0 AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 1 AND COALESCE(a.AccessDepartment, 0) > 0 ORDER BY a.AccessID LIMIT 3
                """);
        assumeTrue(people.size() == 3, "need three people who may add cash vouchers");
        String encoder = (String) people.get(0).get("AccessUserName"), approver = (String) people.get(1).get("AccessUserName"), poster = (String) people.get(2).get("AccessUserName");
        List<Map<String, Object>> owner = jdbc.queryForList("""
                SELECT k.ClientID AS c FROM client k WHERE k.ClientIDBrCode = 1 AND k.AccountStatus = 1
                  AND EXISTS (SELECT 1 FROM slmastersum m WHERE m.MBR_CODE = 1 AND m.MCLIENTID = k.ClientID AND m.MSLC_CODE = 29 AND m.MSLT_CODE = 1) ORDER BY k.ClientID LIMIT 1
                """);
        assumeTrue(!owner.isEmpty(), "no active member with a vouchers-payable account");
        int client = ((Number) owner.get(0).get("c")).intValue();
        int topShort = jdbc.queryForObject("SELECT COALESCE(MAX(TDREF_NO), 0) FROM td WHERE TDBR_CODE = 1 AND TDSLC_CODE = 24 AND TDSLT_CODE = 1", Integer.class);
        int topLock = jdbc.queryForObject("SELECT COALESCE(MAX(TDREF_NO), 0) FROM td WHERE TDBR_CODE = 1 AND TDSLC_CODE = 24 AND TDSLT_CODE = 2", Integer.class);
        int rs = topShort + 1, rl = topLock + 1;
        // Short Term: 100,000 at 2% placed 24 days ago, nothing accrued; Lock In: 100,000 at 4% placed 206 days ago with 700.00 accrued
        makeCertificate(client, 1, rs, "2026-04-01", "2026-06-30", 90, "2", null);
        makeCertificate(client, 2, rl, "2025-10-01", "2028-10-01", 1096, "4", "700.00");

        // Short Term, part of the money taken out and the rest placed again: no renewal fee, 100.00 termination charge
        Request shortReq = new Request(client, 1, rs, "RENEW", 90, d("2"), d("10000.00"), null, null);
        Quote sq = closures.quote(shortReq, as(encoder));
        assertTrue(sq.problems().isEmpty(), sq.problems().toString());
        assertTrue(sq.preterm());
        assertEquals(24, sq.daysHeld());
        assertEquals(0, d("66.67").compareTo(sq.interest()), "100,000 x 1% x 24 / 360");
        assertEquals(0, d("100.00").compareTo(sq.terminationFee()));
        assertEquals(0, d("0.00").compareTo(sq.fee()), "no renewal fee on a pre-termination");
        assertEquals(0, d("89966.67").compareTo(sq.newPrincipal()), "balance + interest - 100.00 - 10,000.00");
        Encoded se = closures.encode(shortReq, as(encoder));
        List<Map<String, Object>> dt = jdbc.queryForList("SELECT * FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 2 AND DTTR_CODE = 21 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO", se.year(), se.controlNo());
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> l : dt) sum = sum.add((BigDecimal) l.get("DTAMT"));
        assertEquals(0, sum.signum(), "the voucher balances");
        assertTrue(dt.stream().anyMatch((l) -> "442201".equals(l.get("DTACCT_CODE")) && ((BigDecimal) l.get("DTAMT")).compareTo(d("-100.00")) == 0), "the termination charge");
        assertTrue(dt.stream().anyMatch((l) -> "50105".equals(l.get("DTACCT_CODE")) && ((BigDecimal) l.get("DTAMT")).compareTo(d("66.67")) == 0), "the interest expense");
        release.approve(se.year(), se.controlNo(), as(approver));
        release.post(se.year(), se.controlNo(), as(poster));
        assertEquals(0, jdbc.queryForObject("SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 24 AND SLT_CODE = 1 AND REF_NO = ? AND SLE_CODE = 11", BigDecimal.class, client, rs).signum());
        assertEquals(0, d("89966.67").compareTo(jdbc.queryForObject("SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 24 AND SLT_CODE = 1 AND REF_NO = ? AND SLE_CODE = 11", BigDecimal.class, client, se.newCertificate())));

        // Lock In, paid out: 1% of 100,000 in the first year, the accrual taken back and none paid
        Request lockReq = new Request(client, 2, rl, "PAYOUT", null, null, null, null, null);
        Quote lq = closures.quote(lockReq, as(encoder));
        assertTrue(lq.problems().isEmpty(), lq.problems().toString());
        assertEquals(206, lq.daysHeld());
        assertEquals(0, d("1000.00").compareTo(lq.terminationFee()), "1% in the first year");
        assertEquals(0, d("-700.00").compareTo(lq.expense()));
        assertEquals(0, d("99000.00").compareTo(lq.due()));
        Encoded le = closures.encode(lockReq, as(encoder));
        release.approve(le.year(), le.controlNo(), as(approver));
        release.post(le.year(), le.controlNo(), as(poster));
        assertEquals(0, jdbc.queryForObject("SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 24 AND SLT_CODE = 2 AND REF_NO = ? AND SLE_CODE = 11", BigDecimal.class, client, rl).signum());
        assertEquals(0, jdbc.queryForObject("SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 24 AND SLT_CODE = 2 AND REF_NO = ? AND SLE_CODE = 71", BigDecimal.class, client, rl).signum(), "the accrual is taken back");
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
