package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import ph.coop.core.frontoffice.TimeDepositAccrualController.Encode;
import ph.coop.core.frontoffice.TimeDepositAccrualController.Summary;
import ph.coop.core.frontoffice.TimeDepositAccrualController.VoucherRow;

/**
 * A month-end Lock In accrual encoded, approved and posted through the real code by three people, compared with the old vouchers (documentation/time-deposit-accrual-map.md);
 * dates the old run already did are refused. Rolled back. Runs only when DB_PASSWORD is set.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@Import(TimeDepositAccrualPostingTest.FixedClock.class)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class TimeDepositAccrualPostingTest {

    static final LocalDate TODAY = LocalDate.of(2026, 5, 31);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(LocalDateTime.of(TODAY, java.time.LocalTime.of(10, 0)).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }
    }

    @Autowired TimeDepositAccrualController accruals;
    @Autowired JdbcTemplate jdbc;

    private Authentication as(String name) { return new TestingAuthenticationToken(name, null); }

    private List<Map<String, Object>> people() {
        List<Map<String, Object>> people = jdbc.queryForList("""
                SELECT a.AccessID, a.AccessUserName FROM access a JOIN access_trans x ON x.USERID = a.AccessID AND x.BR_CODE = a.AccessBR_CODE AND x.TR_CODE = 52 AND x.CAN_NEW = 1 AND x.CAN_FIND = 1
                WHERE a.AccessBR_CODE = 1 AND COALESCE(a.AccessIsDisabled, 0) = 0 AND COALESCE(a.AccessDepartment, 0) > 0 AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 1 AND a.AccessLevel = 'USER' ORDER BY a.AccessID LIMIT 3
                """);
        assumeTrue(people.size() == 3, "need three people who may add accrual vouchers");
        return people;
    }

    @Test
    void aDateTheOldSystemAlreadyRanIsRefusedAndAnUnsuitableDateToo() {
        String user = (String) people().get(0).get("AccessUserName");
        // March 2026 Lock In (type 2) and the 2025 Short Term year end (type 1) have old vouchers
        ResponseStatusException a = assertThrows(ResponseStatusException.class, () -> accruals.encode(new Encode("LOCK_IN", "2026-03-31", List.of(2)), as(user)));
        assertTrue(a.getReason().contains("already has"), a.getReason());
        assertThrows(ResponseStatusException.class, () -> accruals.encode(new Encode("SHORT_TERM", "2025-12-31", List.of(1)), as(user)));
        assertThrows(ResponseStatusException.class, () -> accruals.encode(new Encode("LOCK_IN", "2026-04-15", null), as(user)), "not a month end");
        assertThrows(ResponseStatusException.class, () -> accruals.encode(new Encode("SHORT_TERM", "2026-03-31", null), as(user)), "not a year end");
        assertThrows(ResponseStatusException.class, () -> accruals.encode(new Encode("LOCK_IN", "2026-06-30", null), as(user)), "the period has not ended");
        assertThrows(ResponseStatusException.class, () -> accruals.encode(new Encode("LOCK_IN", "2026-04-30", List.of(1)), as(user)), "a Short Term type in a Lock In run");
        Summary s = accruals.preview("LOCK_IN", "2026-03-31", as(user));
        assertTrue(s.types().stream().anyMatch((t) -> t.typeCode() == 2 && t.alreadyEncoded()));
    }

    @Test
    void aMonthEndRunIsEncodedThenApprovedAndPostedByThreePeople() {
        List<Map<String, Object>> people = people();
        String encoder = (String) people.get(0).get("AccessUserName"), approver = (String) people.get(1).get("AccessUserName"), poster = (String) people.get(2).get("AccessUserName");

        Summary s = accruals.preview("LOCK_IN", "2026-04-30", as(encoder));
        assertEquals("Normal Accrual", s.accrualType());
        assertEquals("2026-04-01", s.from());
        assertTrue(s.certificates() > 100, "Lock In certificates earn interest in April: " + s.certificates());
        assertTrue(s.types().stream().noneMatch((t) -> t.alreadyEncoded()));

        List<VoucherRow> vouchers = accruals.encode(new Encode("LOCK_IN", "2026-04-30", null), as(encoder));
        assertTrue(vouchers.size() >= 1);
        VoucherRow v = vouchers.get(0);
        assertEquals("WAITING FOR APPROVAL", v.status());
        assertEquals(String.format("%08d", v.controlNo()), v.documentNo());

        // every line waits, the voucher balances, certificates are credited (negative) and the expense is debited by department
        List<Map<String, Object>> dt = jdbc.queryForList("SELECT * FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 8 AND DTTR_CODE = 52 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO", v.year(), v.controlNo());
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> l : dt) { sum = sum.add((BigDecimal) l.get("DTAMT")); assertEquals(2, ((Number) l.get("DTUPD_TAG")).intValue()); }
        assertEquals(0, sum.signum());
        Map<String, Object> first = dt.get(0), last = dt.get(dt.size() - 1);
        assertEquals(1, ((Number) first.get("DTSEQ_NO")).intValue());
        assertEquals(24, ((Number) first.get("DTSLC_CODE")).intValue());
        assertEquals(71, ((Number) first.get("DTSLE_CODE")).intValue());
        assertEquals(99, ((Number) first.get("DTCTS_NUM")).intValue());
        assertEquals("2011601", first.get("DTACCT_CODE"));
        assertNull(first.get("DTCOCI_TYPE"));
        assertTrue(((BigDecimal) first.get("DTAMT")).signum() < 0);
        assertEquals("50105", last.get("DTACCT_CODE"));
        assertTrue(((BigDecimal) last.get("DTAMT")).signum() > 0);
        Map<String, Object> h = jdbc.queryForMap("SELECT TR_DATE, IS_FO, EXPLANATION FROM transsum WHERE TR_BRCODE = 1 AND TR_YEAR = ? AND TR_MODULE = 8 AND TR_CODE = 52 AND TR_CTLNO = ?", v.year(), v.controlNo());
        assertEquals("2026-04-30", h.get("TR_DATE").toString());
        assertTrue(((String) h.get("EXPLANATION")).contains("04/01/2026 to 04/30/2026"));
        assertTrue(((String) h.get("EXPLANATION")).contains("Normal Accrual"));

        // the same date and types cannot be run twice
        assertThrows(ResponseStatusException.class, () -> accruals.encode(new Encode("LOCK_IN", "2026-04-30", null), as(encoder)));

        // the encoder cannot approve, another person can; the approver cannot post, a third person can
        assertThrows(ResponseStatusException.class, () -> accruals.approve(v.year(), v.controlNo(), as(encoder)));
        assertThrows(ResponseStatusException.class, () -> accruals.post(v.year(), v.controlNo(), as(poster)), "not approved yet");
        assertEquals("APPROVED", accruals.approve(v.year(), v.controlNo(), as(approver)).status());
        assertThrows(ResponseStatusException.class, () -> accruals.post(v.year(), v.controlNo(), as(approver)));
        assertThrows(ResponseStatusException.class, () -> accruals.post(v.year(), v.controlNo(), as(encoder)));
        BigDecimal glBefore = jdbc.queryForObject("SELECT COALESCE(SUM(GLTransDR_AMT), 0) FROM gltrans WHERE GLTransBR_CODE = 1 AND GLTransTR_DATE = '2026-04-30' AND GLTransTR_CODE = 52 AND GLTransACCT_CODE = '50105'", BigDecimal.class);
        assertEquals("POSTED", accruals.post(v.year(), v.controlNo(), as(poster)).status());

        // the certificate ledger got one event 71 row for each certificate line, dated the period end, with no tender type
        Integer certLines = (int) dt.stream().filter((l) -> l.get("DTSLC_CODE") != null).count();
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM sldtl WHERE SL_BRCODE = 1 AND SLC_CODE = 24 AND SLE_CODE = 71 AND TR_CODE = 52 AND TR_CTLNO = ? AND TR_DATE = '2026-04-30'", Integer.class, v.controlNo());
        assertEquals(certLines, rows);
        Map<String, Object> one = jdbc.queryForMap("SELECT AMT, ADJ_FLAG, COCI_TYPE, TR_DOCNO FROM sldtl WHERE SL_BRCODE = 1 AND SLC_CODE = 24 AND SLE_CODE = 71 AND TR_CODE = 52 AND TR_CTLNO = ? AND TR_DATE = '2026-04-30' LIMIT 1", v.controlNo());
        assertTrue(((BigDecimal) one.get("AMT")).signum() < 0);
        assertEquals(1, ((Number) one.get("ADJ_FLAG")).intValue());
        assertNull(one.get("COCI_TYPE"));
        assertEquals(v.documentNo(), one.get("TR_DOCNO"));
        BigDecimal expenseLines = dt.stream().filter((l) -> l.get("DTSLC_CODE") == null).map((l) -> (BigDecimal) l.get("DTAMT")).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal glAfter = jdbc.queryForObject("SELECT COALESCE(SUM(GLTransDR_AMT), 0) FROM gltrans WHERE GLTransBR_CODE = 1 AND GLTransTR_DATE = '2026-04-30' AND GLTransTR_CODE = 52 AND GLTransACCT_CODE = '50105'", BigDecimal.class);
        assertEquals(0, glBefore.add(expenseLines).compareTo(glAfter));
        // posted vouchers cannot be posted or approved again
        assertThrows(ResponseStatusException.class, () -> accruals.post(v.year(), v.controlNo(), as(poster)));
        assertThrows(ResponseStatusException.class, () -> accruals.approve(v.year(), v.controlNo(), as(approver)));
    }
}
