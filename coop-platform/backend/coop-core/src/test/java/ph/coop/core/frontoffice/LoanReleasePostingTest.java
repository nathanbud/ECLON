package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;
import ph.coop.core.frontoffice.LoanReleaseController.Detail;

/**
 * Builds a release voucher in the state the old system leaves it in (all lines tag 2, shaped like the real posted voucher 2026/2/21/39888, see
 * documentation/loan-posting-map.md), approves and posts it through the real code at a fixed date, checks the ledger rows, then rolls everything
 * back. Needs three people with "new" rights on type 21 and the V015 grant; skips when either is missing. Runs only when DB_PASSWORD is set.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@Import(LoanReleasePostingTest.FixedClock.class)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class LoanReleasePostingTest {

    static final LocalDate TODAY = LocalDate.of(2026, 4, 25);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(LocalDateTime.of(TODAY, java.time.LocalTime.of(10, 0)).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }
    }

    @Autowired LoanReleaseController releases;
    @Autowired JdbcTemplate jdbc;
    @Autowired Db db;

    private int[] ids = new int[3];
    private String[] names = new String[3];
    private long ctl;

    @BeforeEach
    void setUp() {
        try {
            jdbc.update("UPDATE transdt SET DTPOSTBY = DTPOSTBY WHERE DTTR_YEAR = -1");
        } catch (RuntimeException e) {
            assumeTrue(false, "V015 (UPDATE on transdt) has not been applied");
        }
        List<Map<String, Object>> people = jdbc.queryForList("""
                SELECT a.AccessID, a.AccessUserName FROM access a
                JOIN access_trans x ON x.USERID = a.AccessID AND x.BR_CODE = a.AccessBR_CODE AND x.TR_CODE = 21 AND x.CAN_NEW = 1
                WHERE a.AccessBR_CODE = 1 AND COALESCE(a.AccessIsDisabled, 0) = 0 AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 1 AND COALESCE(a.AccessDepartment, 0) > 0
                ORDER BY a.AccessID LIMIT 3
                """);
        assumeTrue(people.size() == 3, "fewer than three people may add vouchers of type 21");
        for (int i = 0; i < 3; i++) {
            ids[i] = ((Number) people.get(i).get("AccessID")).intValue();
            names[i] = (String) people.get(i).get("AccessUserName");
        }
        ctl = db.nextControlNumber(1, 2, 21, 2026);
        jdbc.update("""
                INSERT INTO transsum (TR_BRCODE, TR_YEAR, TR_MODULE, TR_CODE, TR_CTLNO, TR_DOCNO, TR_DATE, TR_TIME, TR_CLIENTID, BATCH_NO, EXPLANATION, IS_FO, DATETIMEADDED)
                VALUES (1, 2026, 2, 21, ?, ?, '2026-04-23', '16:13:53', 94724, '20260423', 'TEST RELEASE', 0, '2026-04-23 16:13:53')
                """, ctl, String.format("%08d", ctl));
        line(1, "10110103", 12, 3, 235, 11, 11, "500000.00", 2);
        line(2, "412", 12, 3, 235, 31, 99, "-5000.00", 1);
        line(3, "2012305", 29, 1, 19416, 11, 15, "-495000.00", 1);
    }

    private void line(int seq, String account, int slc, int slt, int ref, int event, int cts, String amount, int adj) {
        jdbc.update("""
                INSERT INTO transdt (DTTR_BRCODE, DTTR_YEAR, DTTR_MODULE, DTTR_CODE, DTTR_CTLNO, DTSEQ_NO, DTDEPT, DTACCT_CODE, ClientIDDT, DTSLC_CODE, DTSLT_CODE, DTREF_NO,
                  DTSLE_CODE, DTCTS_NUM, DTSL_DATE, DTADJ_FLAG, DTAMT, DTENCDBY, DTUPD_TAG, DTCHECKISRELEASE, DTISTRANSIT, DTISUNDENTIFIED)
                VALUES (1, 2026, 2, 21, ?, ?, 4, ?, 94724, ?, ?, ?, ?, ?, '2026-04-23', ?, ?, ?, 2, 0, 0, 0)
                """, ctl, seq, account, slc, slt, ref, event, cts, adj, new BigDecimal(amount), ids[0]);
    }

    private Authentication as(int i) { return new TestingAuthenticationToken(names[i], null); }

    @Test
    void approveThenPostWritesTheLedgerAsTheOldSystemDid() {
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM sldtl WHERE SL_BRCODE = 1 AND SLC_CODE = 12 AND SLT_CODE = 3 AND REF_NO = 235", Integer.class);
        assertEquals("WAITING FOR APPROVAL", releases.detail(2026, ctl, as(1)).voucher().status());

        Detail approved = releases.approve(2026, ctl, as(1));
        assertEquals("APPROVED", approved.voucher().status());
        assertEquals(ids[1], approved.voucher().approvedBy());

        Detail posted = releases.post(2026, ctl, as(2));
        assertEquals("POSTED", posted.voucher().status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM transdt WHERE DTTR_YEAR = 2026 AND DTTR_CODE = 21 AND DTTR_CTLNO = ? AND (DTUPD_TAG <> 1 OR DTPOSTBY <> ?)", Integer.class, ctl, ids[2]));

        List<Map<String, Object>> sl = jdbc.queryForList("SELECT SLC_CODE, SLE_CODE, AMT, TR_DATE FROM sldtl WHERE TR_CODE = 21 AND TR_CTLNO = ? AND TR_DATE = '2026-04-25' ORDER BY SLC_CODE, SLE_CODE", ctl);
        assertEquals(3, sl.size());
        assertEquals(0, new BigDecimal("500000.00").compareTo((BigDecimal) sl.get(0).get("AMT")));
        assertEquals(0, new BigDecimal("-5000.00").compareTo((BigDecimal) sl.get(1).get("AMT")));
        assertEquals(before + 2, jdbc.queryForObject("SELECT COUNT(*) FROM sldtl WHERE SL_BRCODE = 1 AND SLC_CODE = 12 AND SLT_CODE = 3 AND REF_NO = 235", Integer.class));
        BigDecimal dr = jdbc.queryForObject("SELECT COALESCE(SUM(GLTransDR_AMT), 0) FROM gltrans WHERE GLTransTR_CODE = 21 AND GLTransTR_DATE = '2026-04-25' AND GLTransACCT_CODE = '10110103' AND GLTransLASTUPDATED >= '2026-04-25 10:00:00'", BigDecimal.class);
        assertTrue(dr.compareTo(new BigDecimal("500000.00")) >= 0);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'loan.release.post' AND entity_id = ?", Integer.class, "1/2026/2/21/" + ctl));

        ResponseStatusException again = assertThrows(ResponseStatusException.class, () -> releases.post(2026, ctl, as(2)));
        assertTrue(again.getReason().contains("already posted"));
    }

    @Test
    void theEncoderCannotApproveAndTheApproverCannotPost() {
        assertThrows(ResponseStatusException.class, () -> releases.approve(2026, ctl, as(0)));
        releases.approve(2026, ctl, as(1));
        assertThrows(ResponseStatusException.class, () -> releases.post(2026, ctl, as(1)));
        assertThrows(ResponseStatusException.class, () -> releases.post(2026, ctl, as(0)));
    }

    @Test
    void anUnapprovedVoucherCannotBePosted() {
        assertThrows(ResponseStatusException.class, () -> releases.post(2026, ctl, as(2)));
    }
}
