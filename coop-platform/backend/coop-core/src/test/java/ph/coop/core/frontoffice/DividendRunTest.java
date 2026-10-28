package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
import ph.coop.core.frontoffice.DividendController.Request;
import ph.coop.core.frontoffice.DividendController.Summary;
import ph.coop.core.frontoffice.DividendController.VoucherRow;

/**
 * The dividend run end to end on a year the database has no dividend vouchers for (the clock is set to March 2027, the cut-off is 2027-02-21 and covers 2026):
 * preview, encode, approve by a second person, post by a third, and a second encode is refused. Real code, rolled back. Skips without three people who may add type 54.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@Import(DividendRunTest.FixedClock.class)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class DividendRunTest {

    static final LocalDate TODAY = LocalDate.of(2027, 3, 1);
    static final String CUTOFF = "2027-02-21";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(LocalDateTime.of(TODAY, LocalTime.of(10, 0)).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }
    }

    @Autowired DividendController dividends;
    @Autowired JdbcTemplate jdbc;

    private Authentication encoder, approver, poster;

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private Request request() {
        return new Request(CUTOFF, d("8"), d("8.23"), d("902.17"), d("864.06"), null);
    }

    @BeforeEach
    void setUp() {
        try {
            jdbc.update("UPDATE transdt SET DTPOSTBY = DTPOSTBY WHERE DTTR_YEAR = -1");
        } catch (RuntimeException e) {
            assumeTrue(false, "the UPDATE grant on transdt (V015) is missing");
        }
        List<Map<String, Object>> people = jdbc.queryForList("""
                SELECT a.AccessUserName FROM access a
                JOIN access_trans x ON x.USERID = a.AccessID AND x.BR_CODE = a.AccessBR_CODE AND x.TR_CODE = 54 AND x.CAN_NEW = 1 AND x.CAN_FIND = 1
                WHERE a.AccessBR_CODE = 1 AND COALESCE(a.AccessIsDisabled, 0) = 0 AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 1 AND COALESCE(a.AccessDepartment, 0) > 0
                ORDER BY a.AccessID LIMIT 3
                """);
        assumeTrue(people.size() == 3, "fewer than three people may add dividend vouchers");
        encoder = new TestingAuthenticationToken((String) people.get(0).get("AccessUserName"), "x");
        approver = new TestingAuthenticationToken((String) people.get(1).get("AccessUserName"), "x");
        poster = new TestingAuthenticationToken((String) people.get(2).get("AccessUserName"), "x");
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM transsum WHERE TR_BRCODE = 1 AND TR_YEAR = 2027 AND TR_MODULE = 6 AND TR_CODE = 54", Integer.class);
        assumeTrue(n != null && n == 0, "2027 already has dividend vouchers");
    }

    @Test
    void previewEncodeApprovePostAndTheYearCannotBeRunTwice() {
        Summary s = dividends.preview(request(), encoder);
        assertFalse(s.alreadyEncoded());
        assertTrue(s.members() > 1000, "expected thousands of members, found " + s.members());
        assertEquals(0, s.total().compareTo(s.dividend().add(s.refund())));
        assertEquals(0, s.total().compareTo(s.mutualBenefit().add(s.hospitalization()).add(s.savings()).add(s.shareCapital())), "everything credited adds up to the total");
        System.out.println("DIVIDEND PREVIEW: " + s.members() + " members, " + s.total() + " (dividend " + s.dividend() + ", refund " + s.refund() + "), vouchers " + s.vouchers() + ", held " + s.heldCount());

        List<VoucherRow> vouchers = dividends.encode(request(), encoder);
        assertEquals(s.vouchers(), vouchers.size());
        BigDecimal debits = vouchers.stream().map(VoucherRow::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, s.total().compareTo(debits), "the fund debits must equal what is credited to members");
        assertEquals(vouchers.size(), dividends.vouchers(CUTOFF, encoder).size());
        ResponseStatusException again = assertThrows(ResponseStatusException.class, () -> dividends.encode(request(), approver));
        assertTrue(again.getReason().contains("cannot be run again"));

        VoucherRow v = vouchers.get(vouchers.size() - 1);
        assertThrows(ResponseStatusException.class, () -> dividends.approve(v.year(), v.controlNo(), encoder));
        assertThrows(ResponseStatusException.class, () -> dividends.post(v.year(), v.controlNo(), poster));
        assertEquals("APPROVED", dividends.approve(v.year(), v.controlNo(), approver).status());
        assertThrows(ResponseStatusException.class, () -> dividends.post(v.year(), v.controlNo(), approver));
        assertThrows(ResponseStatusException.class, () -> dividends.post(v.year(), v.controlNo(), encoder));
        assertEquals("POSTED", dividends.post(v.year(), v.controlNo(), poster).status());

        int members = jdbc.queryForObject("SELECT COUNT(*) FROM sldtl WHERE TR_CODE = 54 AND TR_CTLNO = ? AND TR_DATE = ?", Integer.class, v.controlNo(), CUTOFF);
        assertEquals(v.lines() - 1, members);
        BigDecimal gl = jdbc.queryForObject("SELECT COALESCE(SUM(GLTransDR_AMT + GLTransCR_AMT), 0) FROM gltrans WHERE GLTransTR_CODE = 54 AND GLTransTR_DATE = ?", BigDecimal.class, CUTOFF);
        assertEquals(0, BigDecimal.ZERO.compareTo(gl), "the general ledger lines of the voucher must balance");
        assertThrows(ResponseStatusException.class, () -> dividends.post(v.year(), v.controlNo(), poster));
    }

    @Test
    void aFutureCutOffAndBadRatesAreRefused() {
        assertThrows(ResponseStatusException.class, () -> dividends.preview(new Request("2027-04-01", d("8"), d("8.23"), d("902.17"), d("864.06"), null), encoder));
        assertThrows(ResponseStatusException.class, () -> dividends.preview(new Request(CUTOFF, d("-1"), d("8.23"), d("902.17"), d("864.06"), null), encoder));
        assertThrows(ResponseStatusException.class, () -> dividends.preview(new Request("soon", d("8"), d("8.23"), d("902.17"), d("864.06"), null), encoder));
    }
}
