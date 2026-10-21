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
import ph.coop.core.frontoffice.SavingsInterestController.Encode;
import ph.coop.core.frontoffice.SavingsInterestController.Summary;
import ph.coop.core.frontoffice.SavingsInterestController.VoucherRow;

/**
 * The quarterly interest run end to end on a quarter the database has no interest vouchers for yet: preview, encode, approve by a second person, post by a
 * third, and a second encode is refused. Real code at a fixed date, rolled back. Skips when no such quarter or fewer than three people with "new" rights on type 53.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@Import(SavingsInterestRunTest.FixedClock.class)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class SavingsInterestRunTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 18);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(LocalDateTime.of(TODAY, LocalTime.of(10, 0)).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }
    }

    @Autowired SavingsInterestController interest;
    @Autowired JdbcTemplate jdbc;

    private Authentication encoder, approver, poster;
    private String quarter;

    @BeforeEach
    void setUp() {
        try {
            jdbc.update("UPDATE transdt SET DTPOSTBY = DTPOSTBY WHERE DTTR_YEAR = -1");
        } catch (RuntimeException e) {
            assumeTrue(false, "the UPDATE grant on transdt (V015) is missing");
        }
        List<Map<String, Object>> people = jdbc.queryForList("""
                SELECT a.AccessUserName FROM access a
                JOIN access_trans x ON x.USERID = a.AccessID AND x.BR_CODE = a.AccessBR_CODE AND x.TR_CODE = 53 AND x.CAN_NEW = 1 AND x.CAN_FIND = 1
                WHERE a.AccessBR_CODE = 1 AND COALESCE(a.AccessIsDisabled, 0) = 0 AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 1
                ORDER BY a.AccessID LIMIT 3
                """);
        assumeTrue(people.size() == 3, "fewer than three people may add interest vouchers");
        encoder = new TestingAuthenticationToken((String) people.get(0).get("AccessUserName"), "x");
        approver = new TestingAuthenticationToken((String) people.get(1).get("AccessUserName"), "x");
        poster = new TestingAuthenticationToken((String) people.get(2).get("AccessUserName"), "x");
        for (String q : new String[] {"2026-06-30", "2026-09-30"}) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM transsum WHERE TR_BRCODE = 1 AND TR_YEAR = 2026 AND TR_MODULE = 8 AND TR_CODE = 53 AND TR_DATE = ?", Integer.class, q);
            if (n != null && n == 0 && quarter == null) quarter = q;
        }
        assumeTrue(quarter != null, "every quarter of 2026 already has interest vouchers");
    }

    @Test
    void previewEncodeApprovePostAndTheQuarterCannotBeRunTwice() {
        Summary s = interest.preview(quarter, encoder);
        assertFalse(s.alreadyEncoded());
        assertTrue(s.accounts() > 1000, "expected thousands of accounts, found " + s.accounts());
        assertTrue(s.total().signum() > 0);
        System.out.println("INTEREST RUN " + quarter + ": " + s.accounts() + " accounts, " + s.total() + ", held " + s.heldCount() + ", types " + s.types());

        List<VoucherRow> vouchers = interest.encode(new Encode(quarter), encoder);
        int expected = s.types().stream().mapToInt((t) -> t.vouchers()).sum();
        assertEquals(expected, vouchers.size());
        BigDecimal debits = vouchers.stream().map(VoucherRow::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, s.total().compareTo(debits), "the expense debits must equal the interest credited");
        assertEquals(vouchers.size(), interest.vouchers(quarter, encoder).size());
        ResponseStatusException again = assertThrows(ResponseStatusException.class, () -> interest.encode(new Encode(quarter), approver));
        assertTrue(again.getReason().contains("cannot be run again"));

        VoucherRow v = vouchers.get(vouchers.size() - 1);
        assertThrows(ResponseStatusException.class, () -> interest.approve(v.year(), v.controlNo(), encoder));
        assertThrows(ResponseStatusException.class, () -> interest.post(v.year(), v.controlNo(), poster));
        assertEquals("APPROVED", interest.approve(v.year(), v.controlNo(), approver).status());
        assertThrows(ResponseStatusException.class, () -> interest.post(v.year(), v.controlNo(), approver));
        assertThrows(ResponseStatusException.class, () -> interest.post(v.year(), v.controlNo(), encoder));
        assertEquals("POSTED", interest.post(v.year(), v.controlNo(), poster).status());

        int members = jdbc.queryForObject("SELECT COUNT(*) FROM sldtl WHERE TR_CODE = 53 AND TR_CTLNO = ? AND TR_DATE = ?", Integer.class, v.controlNo(), quarter);
        assertEquals(v.lines() - jdbc.queryForObject("SELECT COUNT(*) FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = 2026 AND DTTR_MODULE = 8 AND DTTR_CODE = 53 AND DTTR_CTLNO = ? AND DTAMT > 0", Integer.class, v.controlNo()), members);
        BigDecimal gl = jdbc.queryForObject("SELECT COALESCE(SUM(GLTransDR_AMT + GLTransCR_AMT), 0) FROM gltrans WHERE GLTransTR_CODE = 53 AND GLTransTR_DATE = ?", BigDecimal.class, quarter);
        assertEquals(0, BigDecimal.ZERO.compareTo(gl), "the general ledger lines of the voucher must balance");
        assertThrows(ResponseStatusException.class, () -> interest.post(v.year(), v.controlNo(), poster));
    }

    @Test
    void aQuarterThatHasNotEndedOrIsNotAQuarterEndIsRefused() {
        assertThrows(ResponseStatusException.class, () -> interest.preview("2026-10-31", encoder));
        assertThrows(ResponseStatusException.class, () -> interest.preview("2026-12-31", encoder));
    }
}
