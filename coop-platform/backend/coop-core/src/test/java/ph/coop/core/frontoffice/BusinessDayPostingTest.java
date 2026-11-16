package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import ph.coop.core.accounting.close.PostingPeriods;
import ph.coop.core.businessday.BusinessDay;
import ph.coop.core.businessday.BusinessDayController;
import ph.coop.core.businessday.BusinessDayController.StartIn;
import ph.coop.core.businessday.BusinessDayRules;
import ph.coop.core.frontoffice.SavingsPostingController.Request;

/**
 * Start Day and End Day with the business day switched on (the other backend tests run with it off): a deposit is refused while no day is open,
 * carries the open day's date even when that is not today, and End Day needs a reason while a teller who took cash has not closed the drawer.
 * Everything is rolled back; a day already open in the database is closed inside the test's own transaction first.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.business-day.required=true"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class BusinessDayPostingTest {

    @Autowired SavingsPostingController savings;
    @Autowired BusinessDayController days;
    @Autowired BusinessDay businessDay;
    @Autowired DayEndChecks dayEnd;
    @Autowired JdbcTemplate jdbc;

    private final Authentication admin = new TestingAuthenticationToken("itest-admin", null, "ROLE_ADMIN");
    private String teller;
    private int tellerNumber;
    private int client, type, ref;
    private LocalDate lastClosed;

    @BeforeEach
    void setUp() {
        assumeTrue(businessDay.available(), "no business day table in this database");
        List<Map<String, Object>> t = jdbc.queryForList("""
                SELECT a.AccessUserName, a.AccessTellerNumber FROM access a
                JOIN access_trans r ON r.USERID = a.AccessID AND r.BR_CODE = a.AccessBR_CODE AND r.TR_CODE = 10 AND r.CAN_NEW = 1
                WHERE a.AccessCurrAssign LIKE '%Teller%' AND COALESCE(a.AccessIsHeadCashier, 0) = 0 AND COALESCE(a.AccessIsDisabled, 0) = 0
                  AND a.AccessTellerNumber > 0 AND a.AccessBR_CODE = 1 AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 1 AND COALESCE(a.AccessDepartment, 0) > 0
                  AND a.AccessLevel <> 'SUPERADMIN' ORDER BY a.AccessID LIMIT 1
                """);
        assumeTrue(!t.isEmpty(), "no teller who may take deposits");
        teller = (String) t.get(0).get("AccessUserName");
        tellerNumber = ((Number) t.get(0).get("AccessTellerNumber")).intValue();
        List<Map<String, Object>> a = jdbc.queryForList("""
                SELECT s.ClientIDSD AS client, s.SDSLT_CODE AS typ, s.SDREF_NO AS ref FROM sd s
                JOIN client c ON c.ClientIDBrCode = s.SDBR_CODE AND c.ClientID = s.ClientIDSD AND c.AccountStatus = 1
                WHERE s.SDBR_CODE = 1 AND s.SDSLC_CODE = 22 AND s.SDSTATUS = 15 AND s.SDSLT_CODE = 1 ORDER BY s.ClientIDSD DESC LIMIT 1
                """);
        assumeTrue(!a.isEmpty(), "no active savings account");
        client = ((Number) a.get(0).get("client")).intValue();
        type = ((Number) a.get(0).get("typ")).intValue();
        ref = ((Number) a.get(0).get("ref")).intValue();
        // whatever day is open in this database, close it here (rolled back with the rest)
        jdbc.update("UPDATE ecis_business_day SET status = 'CLOSED', closed_by = 'itest', closed_at = CURRENT_TIMESTAMP WHERE branch_code = 1 AND status = 'OPEN'");
        List<java.sql.Date> c = jdbc.queryForList("SELECT MAX(business_date) FROM ecis_business_day WHERE branch_code = 1 AND status = 'CLOSED'", java.sql.Date.class);
        lastClosed = c.isEmpty() || c.get(0) == null ? null : c.get(0).toLocalDate();
    }

    private Authentication as(String name) { return new TestingAuthenticationToken(name, null); }

    private Request deposit(String amount) {
        return new Request(client, type, ref, new BigDecimal(amount), "itest business day", null, true, null);
    }

    /** Opens the date (confirming whatever it warns about) as an administrator. */
    private void start(LocalDate d) {
        days.start(new StartIn(d.toString(), true), admin);
    }

    private LocalDate openable(LocalDate d) {
        assumeTrue(lastClosed == null || d.isAfter(lastClosed), "the last closed day is " + lastClosed + ", so " + d + " cannot be opened");
        assumeTrue(PostingPeriods.problem(jdbc, 1, d) == null, d + " is in a closed period or year");
        return d;
    }

    @Test
    void aDepositIsRefusedWhileNoDayIsOpenAndNothingIsWritten() {
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM transsum WHERE TR_BRCODE = 1", Integer.class);
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> savings.deposit(deposit("100.00"), as(teller)));
        assertEquals(409, e.getStatusCode().value());
        assertEquals(BusinessDay.NOT_STARTED, e.getReason());
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM transsum WHERE TR_BRCODE = 1", Integer.class));
    }

    @Test
    void aDepositCarriesTheOpenBusinessDateEvenWhenItIsNotToday() {
        LocalDate yesterday = openable(LocalDate.now().minusDays(1));
        start(yesterday);
        assertEquals(yesterday, businessDay.postingDate(1));
        var r = savings.deposit(deposit("100.00"), as(teller));
        assertEquals(yesterday.getYear(), r.year());
        java.sql.Date dated = jdbc.queryForObject("SELECT TR_DATE FROM transsum WHERE TR_BRCODE = 1 AND TR_YEAR = ? AND TR_CTLNO = ? AND TR_DOCNO = ? ORDER BY TR_MODULE, TR_CODE LIMIT 1",
            java.sql.Date.class, r.year(), r.controlNo(), r.documentNo());
        assertEquals(yesterday, dated.toLocalDate(), "dated on the business day, not the calendar day");
        Integer ledger = jdbc.queryForObject("SELECT COUNT(*) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND TR_CTLNO = ? AND TR_DATE = ?", Integer.class, client, r.controlNo(), java.sql.Date.valueOf(yesterday));
        assertTrue(ledger != null && ledger > 0, "the member's ledger row carries the business date too");
    }

    @Test
    void startDayRefusesTheFutureASecondOpenDayAndPeopleWithoutTheRight() {
        LocalDate today = openable(LocalDate.now());
        ResponseStatusException future = assertThrows(ResponseStatusException.class, () -> start(today.plusDays(1)));
        assertTrue(future.getReason().contains("cannot be later than today"), future.getReason());
        start(today);
        ResponseStatusException second = assertThrows(ResponseStatusException.class, () -> start(today));
        assertTrue(second.getReason().contains("is still open"), second.getReason());
        List<String> plain = jdbc.queryForList("SELECT AccessUserName FROM access WHERE AccessBR_CODE = 1 AND COALESCE(AccessIsDisabled, 0) = 0 AND COALESCE(AccessStartDay, 0) = 0 AND COALESCE(AccessEndDay, 0) = 0 AND AccessLevel NOT IN ('ADMIN', 'SUPERADMIN') ORDER BY AccessID LIMIT 1", String.class);
        assumeTrue(!plain.isEmpty(), "no user without the rights");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> days.end(new BusinessDayController.ReasonIn(null), as(plain.get(0)))).getStatusCode().value());
    }

    @Test
    void endDayNeedsAReasonWhileATellerWhoTookCashHasNotClosedTheDrawer() {
        LocalDate today = openable(LocalDate.now());
        start(today);
        savings.deposit(deposit("100.00"), as(teller));
        var drawers = dayEnd.checks(1, today).stream().filter((c) -> c.key().equals("drawers")).findFirst().orElseThrow();
        assertTrue(!drawers.ok() && drawers.detail().contains("Teller " + tellerNumber), drawers.toString());
        ResponseStatusException refused = assertThrows(ResponseStatusException.class, () -> days.end(new BusinessDayController.ReasonIn(null), admin));
        assertTrue(refused.getReason().contains("Teller " + tellerNumber) && refused.getReason().contains("give the reason"), refused.getReason());
        var state = days.end(new BusinessDayController.ReasonIn("Teller counted on paper; system was down"), admin);
        assertNull(state.open());
        assertEquals(today.toString(), state.lastClosed().date());
        assertEquals("Teller counted on paper; system was down", state.lastClosed().overrideReason());
        assertTrue(state.lastClosed().failedChecks().contains("Teller " + tellerNumber), state.lastClosed().failedChecks());
        assertEquals(List.of("START", "END_OVERRIDE"), jdbc.queryForList("SELECT action FROM ecis_business_day_log WHERE branch_code = 1 AND business_date = ? ORDER BY id", String.class, java.sql.Date.valueOf(today)));
        Integer audited = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'business.day.end.override' AND entity_id = ?", Integer.class, "1/" + today);
        assertEquals(1, audited);
        // and posting is closed again until the next day is started
        assertEquals(BusinessDay.NOT_STARTED, assertThrows(ResponseStatusException.class, () -> savings.deposit(deposit("50.00"), as(teller))).getReason());
    }

    @Test
    void onlyAnAdministratorReopensTheLastClosedDayWithAReason() {
        LocalDate today = openable(LocalDate.now());
        start(today);
        days.end(new BusinessDayController.ReasonIn("closing for the reopen test"), admin);
        ResponseStatusException noReason = assertThrows(ResponseStatusException.class, () -> days.reopen(new BusinessDayController.ReasonIn("x"), admin));
        assertTrue(noReason.getReason().contains("reason"), noReason.getReason());
        var state = days.reopen(new BusinessDayController.ReasonIn("ended before the last deposit was keyed"), admin);
        assertEquals(today.toString(), state.open().date());
        assertEquals(today, businessDay.postingDate(1));
        List<String> plain = jdbc.queryForList("SELECT AccessUserName FROM access WHERE AccessBR_CODE = 1 AND COALESCE(AccessIsDisabled, 0) = 0 AND AccessLevel NOT IN ('ADMIN', 'SUPERADMIN') ORDER BY AccessID LIMIT 1", String.class);
        assumeTrue(!plain.isEmpty(), "no user who is not an administrator");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> days.reopen(new BusinessDayController.ReasonIn("ended before the last deposit was keyed"), as(plain.get(0)))).getStatusCode().value());
    }

    @Test
    void theStartCheckWarnsAboutASundayBeforeAnythingIsOpened() {
        LocalDate sunday = LocalDate.now().minusDays((LocalDate.now().getDayOfWeek().getValue()) % 7);
        assumeTrue(lastClosed == null || sunday.isAfter(lastClosed), "the last closed day is after the last Sunday");
        var check = days.startCheck(sunday.toString(), admin);
        assertNull(check.problem());
        assertTrue(check.warnings().stream().anyMatch((w) -> w.contains("is a Sunday") || w.contains("is a holiday")), check.warnings().toString());
        assertTrue(BusinessDayRules.restDay(sunday, Map.of()));
    }
}
