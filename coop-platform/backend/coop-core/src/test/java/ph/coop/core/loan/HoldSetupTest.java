package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
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
import ph.coop.core.db.Db;

/** Which deposit types can take a co-maker's hold: administrators only, a reason each time, share capital suggested first, and the choice is honoured. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
@WithMockUser(username = "tester", roles = "ADMIN")
class HoldSetupTest {

    @Autowired HoldSetupController setup;
    @Autowired CoMakerHolds holds;
    @Autowired CoMakerNoticeController notices;
    @Autowired JdbcTemplate jdbc;
    @Autowired Db db;

    @BeforeEach
    void ready() {
        assumeTrue(db.tableExists("ecis_hold_setting"), "V021 has not been applied");
        assumeTrue(!jdbc.queryForList("SELECT 1 FROM access WHERE AccessUserName = 'tester'").isEmpty(), "no tester account");
    }

    private Authentication me() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test void shareCapitalIsSuggestedFirstToBeginWith() {
        var rows = setup.list(me());
        var def = rows.stream().filter(HoldSetupController.Row::isDefault).toList();
        assertEquals(1, def.size());
        assertEquals(31, def.get(0).slc());
        assertTrue(rows.stream().anyMatch((r) -> r.slc() == 22), "savings types are listed too");
    }

    @Test void aTypeSwitchedOffNoLongerTakesNewHolds() {
        // a member with an active savings account of a holdable type
        List<int[]> a = jdbc.query("SELECT s.ClientIDSD, s.SDSLT_CODE FROM sd s WHERE s.SDBR_CODE = 1 AND s.SDSLC_CODE = 22 AND s.SDSTATUS = 15 AND s.SDSLT_CODE = 1 LIMIT 1", (rs, i) -> new int[] { rs.getInt(1), rs.getInt(2) });
        assumeTrue(!a.isEmpty());
        int client = a.get(0)[0];
        assertTrue(holds.accounts(1, client).stream().anyMatch((x) -> x.slc() == 22 && x.slt() == 1));
        setup.setHoldable(22, 1, new HoldSetupController.Change(false, "savings holds not wanted"), me());
        assertFalse(holds.accounts(1, client).stream().anyMatch((x) -> x.slc() == 22 && x.slt() == 1));
        setup.setHoldable(22, 1, new HoldSetupController.Change(true, "wanted again"), me());
        assertTrue(holds.accounts(1, client).stream().anyMatch((x) -> x.slc() == 22 && x.slt() == 1));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE entity = 'hold_setting' AND action IN ('hold.type.forbid', 'hold.type.allow')", Integer.class));
    }

    @Test void theTypeSuggestedFirstCannotBeSwitchedOffUntilAnotherTakesOver() {
        var def = setup.list(me()).stream().filter(HoldSetupController.Row::isDefault).findFirst().orElseThrow();
        assertThrows(ResponseStatusException.class, () -> setup.setHoldable(def.slc(), def.slt(), new HoldSetupController.Change(false, "x"), me()));
        setup.setDefault(new HoldSetupController.Default(22, 1, "savings first"), me());
        assertEquals(1, setup.list(me()).stream().filter(HoldSetupController.Row::isDefault).count());
        setup.setHoldable(def.slc(), def.slt(), new HoldSetupController.Change(false, "now fine"), me());
        // a type that is not allowed cannot be the one suggested first
        assertThrows(ResponseStatusException.class, () -> setup.setDefault(new HoldSetupController.Default(def.slc(), def.slt(), "back"), me()));
    }

    @Test void aReasonIsRequired() {
        assertThrows(ResponseStatusException.class, () -> setup.setHoldable(22, 1, new HoldSetupController.Change(false, " "), me()));
        assertThrows(ResponseStatusException.class, () -> setup.setDefault(new HoldSetupController.Default(22, 1, null), me()));
    }

    @Test void theNoticeShowsPlacedAndPendingAmountsAsACoMaker() {
        int[] acct = jdbc.query("SELECT ClientIDSC, SCSLT_CODE, SCREF_NO FROM sc WHERE SCBR_CODE = 1 AND SCSLC_CODE = 31 AND SCSTATUS = 15 LIMIT 1", (rs, i) -> new int[] { rs.getInt(1), rs.getInt(2), rs.getInt(3) }).get(0);
        int client = acct[0];
        jdbc.update("INSERT INTO hold (HBR_CODE, HSLC_CODE, HSLT_CODE, HREF_NO, ClientIDH, HHELD_AMT, HHOLD_CODE, HHOLDDESCR, HHOLDDATE, HOL_REFNO) VALUES (1, 31, ?, ?, ?, 500.00, 1, 'CM to Juan Cruz', '2026-04-24', 'CV#9')", acct[1], acct[2], client);
        var n = notices.notice(1, client);
        var mine = n.placed().stream().filter((p) -> p.borrower().equals("Juan Cruz")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("500.00").compareTo(mine.amount()));
        assertEquals("CV#9", mine.reference());
        assertTrue(n.totalHeld().compareTo(new BigDecimal("500.00")) >= 0);
    }

    @Test @WithMockUser(username = "tester", roles = "USER") void onlyAdministratorsMayChangeIt() {
        assertThrows(AccessDeniedException.class, () -> setup.list(me()));
        assertThrows(AccessDeniedException.class, () -> setup.setHoldable(22, 1, new HoldSetupController.Change(false, "x"), me()));
    }
}
