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
import ph.coop.core.frontoffice.DividendController.VoucherRow;

/**
 * The yearly distribution end to end on a year the database has no dividend vouchers for (the clock is set to March 2027, the cut-off is 2027-02-21 and covers
 * 2026): prepare (stored member by member), submit, approval by someone else, vouchers made from exactly what was stored, the year closed to another
 * preparation, a voucher approved by a second person and posted by a third; the settings choosing who takes part and what counts; the reports. Real code,
 * rolled back. Skips without three people who may add type 54.
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
    @Autowired DistributionController distributions;
    @Autowired JdbcTemplate jdbc;

    private Authentication encoder, approver, poster;
    private final Authentication admin = new TestingAuthenticationToken("itest-admin", null, "ROLE_ADMIN");

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
        Integer b = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_distribution_batch WHERE branch_code = 1 AND year = 2026 AND status IN ('APPROVED', 'ENCODED')", Integer.class);
        assumeTrue(b != null && b == 0, "the 2026 distribution is already approved in this database");
    }

    @Test
    void preparedReviewedApprovedThenEncodedFromWhatWasStored() {
        var draft = distributions.prepare(request(), encoder);
        var b = draft.batch();
        assertEquals("DRAFT", b.status());
        assertEquals(2026, b.year());
        assertTrue(b.members() > 1000, "expected thousands of members, found " + b.members());
        assertEquals(0, b.total().compareTo(b.totalIsc().add(b.totalPr())));
        assertTrue(draft.controls().stream().anyMatch((c) -> c.startsWith("The credits to members add up to the total")), draft.controls().toString());
        Integer stored = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_distribution_member WHERE batch_id = ?", Integer.class, b.id());
        assertEquals(b.members(), stored);
        System.out.println("DISTRIBUTION: " + b.members() + " members, " + b.total() + " (interest " + b.totalIsc() + ", refund " + b.totalPr() + ")");

        // vouchers only from an approved distribution, and the one who prepared it cannot approve it
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> dividends.encode(new DividendController.EncodeIn(b.id()), encoder)).getStatusCode().value());
        assertEquals("SUBMITTED", distributions.submit(b.id(), encoder).batch().status());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> distributions.approve(b.id(), null, encoder)).getStatusCode().value());
        assertEquals("APPROVED", distributions.approve(b.id(), new DistributionController.Decision("checked against the board resolution"), approver).batch().status());
        ResponseStatusException again = assertThrows(ResponseStatusException.class, () -> distributions.prepare(request(), encoder));
        assertTrue(again.getReason().contains("already approved"), again.getReason());

        List<VoucherRow> vouchers = dividends.encode(new DividendController.EncodeIn(b.id()), encoder);
        BigDecimal debits = vouchers.stream().map(VoucherRow::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, b.total().compareTo(debits), "the vouchers carry exactly the approved total");
        var encoded = distributions.detail(b.id(), encoder).batch();
        assertEquals("ENCODED", encoded.status());
        assertEquals(vouchers.get(0).controlNo(), encoded.voucherFirst());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> dividends.encode(new DividendController.EncodeIn(b.id()), approver)).getStatusCode().value());

        VoucherRow v = vouchers.get(vouchers.size() - 1);
        assertThrows(ResponseStatusException.class, () -> dividends.approve(v.year(), v.controlNo(), encoder));
        assertEquals("APPROVED", dividends.approve(v.year(), v.controlNo(), approver).status());
        assertThrows(ResponseStatusException.class, () -> dividends.post(v.year(), v.controlNo(), approver));
        assertEquals("POSTED", dividends.post(v.year(), v.controlNo(), poster).status());
        int members = jdbc.queryForObject("SELECT COUNT(*) FROM sldtl WHERE TR_CODE = 54 AND TR_CTLNO = ? AND TR_DATE = ?", Integer.class, v.controlNo(), LocalDate.parse(CUTOFF));
        assertEquals(v.lines() - 1, members);

        // the ledger report and the reconciliation follow the vouchers
        var ledger = distributions.report(b.id(), "ledger", encoder);
        assertTrue(ledger.sections().get(1).notes().get(0).contains("Debits equal credits"), ledger.sections().get(1).notes().toString());
        var audit = distributions.report(b.id(), "audit", encoder);
        assertTrue(audit.sections().get(0).rows().stream().anyMatch((r) -> r.get(0).equals("Voucher posted")), audit.sections().get(0).rows().toString());
    }

    @Test
    void theSettingsChooseWhoTakesPartAndWhatCountsAndEveryVersionIsKept() {
        // only interest paid counts as patronage, and type 16 (less than 20k share capital, 26 of whom paid loan interest in 2026) earns no refund
        distributions.putPolicy(new DistributionController.PolicyIn(List.of(23), null, null, List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 17, 99), BigDecimal.ZERO, "test the settings"), admin);
        assertEquals(403, assertThrows(ResponseStatusException.class,
            () -> distributions.putPolicy(new DistributionController.PolicyIn(List.of(23), null, null, null, BigDecimal.ZERO, "not an administrator"), encoder)).getStatusCode().value());
        distributions.putFunds(new DistributionController.FundsIn(List.of(new DistributionController.Fund(null, "Reserve fund", d("10"), null, true),
            new DistributionController.Fund(null, "Education and training fund", d("10"), null, true)), "test funds"), admin);
        var settings = distributions.settings(encoder);
        assertEquals(List.of(23), settings.policy().patronageEvents());
        assertEquals(2, settings.funds().size());
        assertTrue(settings.words().contains("Interest Paid (23)"), settings.words());

        var first = distributions.prepare(request(), encoder);
        assertEquals(List.of(23), first.patronage().stream().map(DistributionController.EventRow::event).toList(), "only the chosen loan event counts");
        assertTrue(first.batch().excluded() > 0, "members of type 16 who paid interest are left out of the refund");
        Integer type16WithRefund = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ecis_distribution_member m JOIN client c ON c.ClientIDBrCode = 1 AND c.ClientID = m.client_id
                WHERE m.batch_id = ? AND c.ClientType = 16 AND m.pr > 0
                """, Integer.class, first.batch().id());
        assertEquals(0, type16WithRefund);
        Integer excludedReason = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_distribution_held WHERE batch_id = ? AND reason LIKE '%earns no patronage refund%'", Integer.class, first.batch().id());
        assertTrue(excludedReason > 0, "the reason is kept with each member left out");
        assertTrue(first.batch().policy().contains("Patronage refund:"), first.batch().policy());

        // from the allocation, with the statutory funds from the settings
        Request alloc = new Request(CUTOFF, null, null, null, null, null, d("5000000"), d("3000000"), d("10000000"), null, "GA Res. 2027-01");
        var second = distributions.prepare(alloc, encoder);
        assertEquals("ALLOCATION", second.batch().method());
        assertEquals(0, d("8000000.00").compareTo(second.batch().available()), "10,000,000 less 20% statutory funds");
        assertEquals(2, second.funds().size());
        assertEquals("SUPERSEDED", distributions.detail(first.batch().id(), encoder).batch().status(), "the earlier version is kept");
        assertEquals(second.batch().id(), distributions.detail(first.batch().id(), encoder).batch().supersededBy());

        for (String kind : List.of("net-surplus", "funds", "isc", "pr", "ledger", "audit")) {
            var r = distributions.report(second.batch().id(), kind, encoder);
            assertFalse(r.sections().isEmpty(), kind);
        }
        var funds = distributions.report(second.batch().id(), "funds", encoder).sections().get(0).rows();
        assertTrue(funds.stream().anyMatch((r) -> r.get(0).equals("Reserve fund") && r.get(2).equals("1,000,000.00")), funds.toString());
        String csv = new String(distributions.register(second.batch().id(), encoder).getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(second.batch().members() + 1, csv.split("\r\n").length);
        int someone = jdbc.queryForObject("SELECT MIN(client_id) FROM ecis_distribution_member WHERE batch_id = ?", Integer.class, second.batch().id());
        var st = distributions.statement(second.batch().id(), someone, encoder);
        assertEquals(0, st.total().compareTo(st.dividend().add(st.refund())));

        assertEquals(400, assertThrows(ResponseStatusException.class, () -> distributions.reject(second.batch().id(), new DistributionController.Decision(""), approver)).getStatusCode().value());
        assertEquals("REJECTED", distributions.reject(second.batch().id(), new DistributionController.Decision("figures to be redone"), approver).batch().status());
        assertTrue(distributions.list(encoder).stream().filter((x) -> x.year() == 2026).count() >= 2);
    }

    @Test
    void aFutureCutOffAndBadRatesAreRefused() {
        assertThrows(ResponseStatusException.class, () -> distributions.prepare(new Request("2027-04-01", d("8"), d("8.23"), d("902.17"), d("864.06"), null), encoder));
        assertThrows(ResponseStatusException.class, () -> distributions.prepare(new Request(CUTOFF, d("-1"), d("8.23"), d("902.17"), d("864.06"), null), encoder));
        assertThrows(ResponseStatusException.class, () -> distributions.prepare(new Request("soon", d("8"), d("8.23"), d("902.17"), d("864.06"), null), encoder));
        assertThrows(ResponseStatusException.class, () -> dividends.preview(new Request("2027-04-01", d("8"), d("8.23"), d("902.17"), d("864.06"), null), encoder));
    }
}
