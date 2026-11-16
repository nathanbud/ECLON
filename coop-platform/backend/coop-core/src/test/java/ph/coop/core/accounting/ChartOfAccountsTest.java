package ph.coop.core.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Adding and changing accounts of the chart: numbering rules, parent rules, the accounts the system posts to stay on, a reason each time, audited. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
@WithMockUser(username = "tester", roles = "ADMIN")
class ChartOfAccountsTest {

    @Autowired ChartOfAccountsController chart;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void ready() {
        assumeTrue(!jdbc.queryForList("SELECT 1 FROM access WHERE AccessUserName = 'tester'").isEmpty(), "no tester account");
        assumeTrue(new ph.coop.core.db.Db(jdbc).canInsertInto("coa"), "V022 has not been applied");
    }

    private Authentication me() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private ChartOfAccountsController.Form form(String code, String name, boolean summary, String parent) {
        return new ChartOfAccountsController.Form(code, name, summary, parent, null, null, "needed for testing");
    }

    @Test void aNewDetailAccountIsAddedLikeTheOldOnes() {
        var a = chart.create(form("1000991", "Test petty cash fund", false, "1000"), me());
        assertEquals(1, a.type());
        assertFalse(a.summary());
        assertEquals("1000", a.parent());
        assertTrue(a.glOnly());
        assertFalse(a.disabled());
        var row = jdbc.queryForMap("SELECT COAacct_tag, COAconso_acct, COAUpdUser, COAupdDateTime FROM coa WHERE COAID = '1000991'");
        assertEquals("D", row.get("COAacct_tag"));
        assertEquals("1000991", row.get("COAconso_acct"));
        assertTrue(row.get("COAUpdUser") != null && row.get("COAupdDateTime") != null);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'chart.account.create' AND entity = 'coa' AND entity_id = '1/1000991'", Integer.class));
    }

    @Test void aSummaryAccountCanBeAddedAndTakesDetailAccountsUnderIt() {
        var s = chart.create(form("100099", "Test group", true, "1000"), me());
        assertTrue(s.summary());
        assertFalse(s.glOnly());
        var d = chart.create(form("1000990", "Test under group", false, "100099"), me());
        assertEquals("100099", d.parent());
        assertTrue(chart.list(me()).stream().filter((x) -> x.code().equals("100099")).findFirst().orElseThrow().hasChildren());
    }

    @Test void numberingRulesAreEnforced() {
        // must start with its parent's number
        assertThrows(ResponseStatusException.class, () -> chart.create(form("1100991", "Wrong prefix", false, "1000"), me()));
        // a detail account cannot be a parent
        assertThrows(ResponseStatusException.class, () -> chart.create(form("100019", "Under detail", false, "10001"), me()));
        // first digit must be an account type
        assertThrows(ResponseStatusException.class, () -> chart.create(form("9000991", "Bad type", false, "1000"), me()));
        assertThrows(ResponseStatusException.class, () -> chart.create(form("10X0991", "Letters", false, "1000"), me()));
        // already exists
        assertThrows(ResponseStatusException.class, () -> chart.create(form("10001", "Cash in vault again", false, "1000"), me()));
        // unknown parent
        assertThrows(ResponseStatusException.class, () -> chart.create(form("1999991", "Orphan", false, "19999"), me()));
        // the same name twice under one parent
        chart.create(form("1000992", "Same name", false, "1000"), me());
        assertThrows(ResponseStatusException.class, () -> chart.create(form("1000993", "same NAME", false, "1000"), me()));
    }

    @Test void aReasonAndANameAreRequired() {
        assertThrows(ResponseStatusException.class, () -> chart.create(new ChartOfAccountsController.Form("1000994", "No reason", false, "1000", null, null, " "), me()));
        assertThrows(ResponseStatusException.class, () -> chart.create(form("1000994", " ", false, "1000"), me()));
        assertThrows(ResponseStatusException.class, () -> chart.create(form("1000994", "x".repeat(51), false, "1000"), me()));
    }

    @Test void renameAndSwitchOffAreAudited() {
        chart.create(form("1000995", "Before", false, "1000"), me());
        var r = chart.update("1000995", new ChartOfAccountsController.Form(null, "After", null, null, null, null, "renamed"), me());
        assertEquals("After", r.name());
        var off = chart.update("1000995", new ChartOfAccountsController.Form(null, null, null, null, null, true, "not used"), me());
        assertTrue(off.disabled());
        var on = chart.update("1000995", new ChartOfAccountsController.Form(null, null, null, null, null, false, "used again"), me());
        assertFalse(on.disabled());
        List<String> actions = jdbc.queryForList("SELECT action FROM ecis_audit_log WHERE entity = 'coa' AND entity_id = '1/1000995' ORDER BY id", String.class);
        assertEquals(List.of("chart.account.create", "chart.account.update", "chart.account.disable", "chart.account.enable"), actions);
    }

    @Test void anAccountTheSystemPostsToCannotBeSwitchedOff() {
        // 10001 Cash in vault is the vault's ledger account (glcontrol)
        var u = chart.usage("10001", me());
        assertFalse(u.usedBy().isEmpty());
        assertThrows(ResponseStatusException.class, () -> chart.update("10001", new ChartOfAccountsController.Form(null, null, null, null, null, true, "try"), me()));
    }

    @Test void aSummaryAccountWithActiveAccountsUnderItCannotBeSwitchedOff() {
        chart.create(form("100098", "Test group two", true, "1000"), me());
        chart.create(form("1000980", "Under it", false, "100098"), me());
        assertThrows(ResponseStatusException.class, () -> chart.update("100098", new ChartOfAccountsController.Form(null, null, null, null, null, true, "try"), me()));
        chart.update("1000980", new ChartOfAccountsController.Form(null, null, null, null, null, true, "first"), me());
        assertTrue(chart.update("100098", new ChartOfAccountsController.Form(null, null, null, null, null, true, "then"), me()).disabled());
    }

    @Test void anAccountWithLedgerEntriesCannotMove() {
        // 10001 has entries and a parent 1000; moving it to another summary account whose number is a prefix is refused
        assertThrows(ResponseStatusException.class, () -> chart.update("10001", new ChartOfAccountsController.Form(null, null, null, "100", null, null, "move"), me()));
    }

    @Test @WithMockUser(username = "tester", roles = "ACCOUNTANT") void accountantsMayAdd() {
        assertEquals("1000996", chart.create(form("1000996", "Accountant added", false, "1000"), me()).code());
    }

    @Test @WithMockUser(username = "tester", roles = "USER") void othersMayNot() {
        assertThrows(AccessDeniedException.class, () -> chart.list(me()));
        assertThrows(AccessDeniedException.class, () -> chart.create(form("1000997", "Nope", false, "1000"), me()));
    }
}
