package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

/** Registering and closing a member end to end against the database, rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.edit.enabled=true"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class MemberLifecycleTest {

    @Autowired MemberLifecycleController ctl;
    @Autowired MembershipPolicyController policy;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private static Authentication as(String user, String... auths) {
        return new TestingAuthenticationToken(user, "x", auths);
    }

    private static Authentication me(Authentication a) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    private final Authentication maker = as("admin", "PERM_MEMBER_EDIT");
    private final Authentication officer = as("officer", "ROLE_MEMBERSHIP_OFFICER", "PERM_MEMBER_EDIT");

    @BeforeEach
    void ready() {
        assumeTrue(db.tableExists("ecis_member_request"), "V037 has not been applied");
        assumeTrue(db.canInsertInto("client", "sc", "sd", "ecis_member_request"), "the grants of V037 are missing");
    }

    private MemberLifecycleController.Register ana() {
        return new MemberLifecycleController.Register(1, 1, Map.of("LName", "Zzitest", "FName", "Ana", "DateOfBirth", "1990-02-03", "Gender", "2", "CivilStatus", "1", "CellNum", "09170000000"));
    }

    @Test
    void registrationOpensMemberWithItsAccounts() {
        var req = ctl.register(ana(), me(maker));
        assertEquals("PENDING", req.status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM client WHERE LName = 'Zzitest'", Integer.class));

        assertThrows(ResponseStatusException.class, () -> ctl.approve(req.id(), null, me(maker)));
        var done = ctl.approve(req.id(), null, me(officer));
        assertEquals("APPROVED", done.status());

        int id = done.clientId();
        Map<String, Object> c = jdbc.queryForMap("SELECT ClientIDChkDgt, AccountStatus, ClientType, AccountType FROM client WHERE ClientIDBrCode = 1 AND ClientID = ?", id);
        assertEquals(LifecycleRules.checkDigit(id), ((Number) c.get("ClientIDChkDgt")).intValue());
        assertEquals(1, ((Number) c.get("AccountStatus")).intValue());
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM slmastersum WHERE MBR_CODE = 1 AND MCLIENTID = ?", Integer.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sc WHERE SCBR_CODE = 1 AND ClientIDSC = ? AND SCMIN_BAL = 1000", Integer.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sd WHERE SDBR_CODE = 1 AND ClientIDSD = ?", Integer.class, id));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM osl WHERE OSLBR_CODE = 1 AND ClientIDOSL = ?", Integer.class, id));
    }

    @Test
    void anOfficersOwnRegistrationWaitsForASecondOfficerUnlessASuperAdministratorAllowsIt() {
        // a real user of the old system acting as the officer (the new member is credited to whoever registered it)
        List<String> u = jdbc.queryForList("SELECT AccessUserName FROM access WHERE COALESCE(AccessIsDisabled, 0) = 0 ORDER BY AccessID LIMIT 1", String.class);
        assumeTrue(!u.isEmpty(), "no user in the old system");
        Authentication own = as(u.get(0), "ROLE_MEMBERSHIP_OFFICER", "PERM_MEMBER_EDIT");

        // off (the default): the officer's own registration waits, like anyone's, and they cannot approve it themselves
        var waiting = ctl.register(ana(), me(own));
        assertEquals("PENDING", waiting.status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM client WHERE LName = 'Zzitest'", Integer.class));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> ctl.approve(waiting.id(), null, me(own))).getStatusCode().value());

        // an administrator who is not a super administrator cannot switch it on
        var notSuper = assertThrows(ResponseStatusException.class,
            () -> policy.set(new MembershipPolicyController.Change(null, null, true, "Small office"), me(as("boss", "ROLE_ADMIN"))));
        assertEquals(403, notSuper.getStatusCode().value());

        // a super administrator switches it on, audited
        policy.set(new MembershipPolicyController.Change(null, null, true, "One officer at this branch"), me(as("super", "ROLE_ADMIN", "ROLE_SUPERADMIN")));
        assertTrue(policy.get().officerRegistersDirectly());

        // the registration sent before the switch was on can now be approved by the officer who sent it, recorded as registered directly
        var approvedOwn = ctl.approve(waiting.id(), null, me(own));
        assertEquals("APPROVED", approvedOwn.status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM client WHERE ClientIDBrCode = 1 AND ClientID = ? AND FName = 'Ana'", Integer.class, approvedOwn.clientId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'MEMBER_REGISTERED_DIRECTLY' AND entity_id = ?", Integer.class, "1-" + approvedOwn.clientId()));

        // now an officer's registration opens the member at once, with its accounts, and says so in the audit log
        var direct = ctl.register(new MemberLifecycleController.Register(1, 1, Map.of("LName", "Zzitest", "FName", "Ben", "DateOfBirth", "1991-04-05", "Gender", "1", "CivilStatus", "2")), me(own));
        assertEquals("APPROVED", direct.status());
        int id = direct.clientId();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM client WHERE ClientIDBrCode = 1 AND ClientID = ? AND FName = 'Ben'", Integer.class, id));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM sc WHERE SCBR_CODE = 1 AND ClientIDSC = ?", Integer.class, id) >= 1, "a share capital account opens");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'MEMBER_REGISTERED_DIRECTLY' AND entity_id = ?", Integer.class, "1-" + id));

        // someone who is not an officer still waits for one, even with the switch on
        assertEquals("PENDING", ctl.register(new MemberLifecycleController.Register(1, 1, Map.of("LName", "Zzitest", "FName", "Cara", "DateOfBirth", "1992-06-07", "Gender", "2", "CivilStatus", "2")), me(maker)).status());
    }

    @Test
    void duplicatesAndBadDataAreRefused() {
        ctl.register(ana(), me(maker));
        assertThrows(ResponseStatusException.class, () -> ctl.register(ana(), me(maker)));
        assertThrows(ResponseStatusException.class, () -> ctl.register(new MemberLifecycleController.Register(1, 1, Map.of("LName", "X")), me(maker)));
        assertThrows(ResponseStatusException.class, () -> ctl.register(new MemberLifecycleController.Register(1, 1, Map.of("AccountStatus", "3")), me(maker)));
    }

    @Test
    void rejectAndWithdrawLeaveNothing() {
        var req = ctl.register(ana(), me(maker));
        assertThrows(ResponseStatusException.class, () -> ctl.reject(req.id(), new MemberLifecycleController.Decision("x"), me(officer)));
        assertEquals("REJECTED", ctl.reject(req.id(), new MemberLifecycleController.Decision("Not a member"), me(officer)).status());
        var again = ctl.register(ana(), me(maker));
        assertEquals("WITHDRAWN", ctl.withdraw(again.id(), me(maker)).status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM client WHERE LName = 'Zzitest'", Integer.class));
    }

    @Test
    void closingNeedsZeroBalancesThenCloses() {
        var opened = ctl.approve(ctl.register(ana(), me(maker)).id(), null, me(officer));
        int id = opened.clientId();

        var close = ctl.close(new MemberLifecycleController.Close(1, id, 1, null, "Moving away"), me(maker));
        assertThrows(ResponseStatusException.class, () -> ctl.close(new MemberLifecycleController.Close(1, id, 1, null, null), me(maker)));
        assertEquals("APPROVED", ctl.approve(close.id(), null, me(officer)).status());
        Map<String, Object> c = jdbc.queryForMap("SELECT AccountStatus, ClosedReason, ClientType FROM client WHERE ClientIDBrCode = 1 AND ClientID = ?", id);
        assertEquals(3, ((Number) c.get("AccountStatus")).intValue());
        assertEquals(1, ((Number) c.get("ClosedReason")).intValue());
        assertEquals(6, ((Number) c.get("ClientType")).intValue());
        assertThrows(ResponseStatusException.class, () -> ctl.close(new MemberLifecycleController.Close(1, id, 1, null, null), me(maker)));
    }

    @Test
    void aMemberWithMoneyCannotBeClosed() {
        List<Integer> ids = jdbc.queryForList("SELECT c.ClientID FROM client c WHERE c.ClientIDBrCode = 1 AND c.AccountStatus = 1 AND c.ClientID > 100000 AND (SELECT ABS(SUM(AMT)) FROM sldtl d WHERE d.SL_BRCODE = 1 AND d.SL_CLIENTID = c.ClientID AND d.SLC_CODE = 31 AND d.SLE_CODE = 11) > 100 LIMIT 1", Integer.class);
        assumeTrue(!ids.isEmpty());
        int id = ids.get(0);
        var e = assertThrows(ResponseStatusException.class, () -> ctl.close(new MemberLifecycleController.Close(1, id, 1, null, null), me(maker)));
        assertTrue(e.getReason().contains("Share capital"));
    }

    @Test
    void onlyOfficersDecide() {
        var req = ctl.register(ana(), me(maker));
        assertThrows(ResponseStatusException.class, () -> ctl.approve(req.id(), null, me(as("other", "PERM_MEMBER_EDIT"))));
    }

    private int openedAndClosed(int reason, String died) {
        int id = ctl.approve(ctl.register(ana(), me(maker)).id(), null, me(officer)).clientId();
        var close = ctl.close(new MemberLifecycleController.Close(1, id, reason, died, null), me(maker));
        ctl.approve(close.id(), null, me(officer));
        return id;
    }

    @Test
    void aClosedMemberIsReactivatedOnTheSameRecordKeepingItsAccounts() {
        int id = openedAndClosed(1, null);
        var req = ctl.reactivate(new MemberLifecycleController.Reactivate(1, id, 1, "Wants to rejoin"), me(maker));
        assertEquals("APPROVED", ctl.approve(req.id(), null, me(officer)).status());
        Map<String, Object> c = jdbc.queryForMap("SELECT AccountStatus, ClientType, ClosedDate, ClosedReason FROM client WHERE ClientIDBrCode = 1 AND ClientID = ?", id);
        assertEquals(1, ((Number) c.get("AccountStatus")).intValue());
        assertEquals(1, ((Number) c.get("ClientType")).intValue());
        assertEquals(null, c.get("ClosedDate"));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM slmastersum WHERE MBR_CODE = 1 AND MCLIENTID = ?", Integer.class, id));
    }

    @Test
    void aDeceasedMemberIsNeverReactivated() {
        int id = openedAndClosed(6, "2026-01-01");
        var e = assertThrows(ResponseStatusException.class, () -> ctl.reactivate(new MemberLifecycleController.Reactivate(1, id, 1, "Mistake"), me(maker)));
        assertTrue(e.getReason().contains("deceased"));
    }

    @Test
    void aBadDebtMemberNeedsAnAdministrator() {
        int id = openedAndClosed(3, null);
        var req = ctl.reactivate(new MemberLifecycleController.Reactivate(1, id, 2, "Settled outside"), me(maker));
        assertThrows(ResponseStatusException.class, () -> ctl.approve(req.id(), null, me(officer)));
        var admin = as("boss", "ROLE_MEMBERSHIP_OFFICER", "ROLE_ADMIN");
        assertEquals("APPROVED", ctl.approve(req.id(), null, me(admin)).status());
        assertEquals(2, jdbc.queryForObject("SELECT ClientType FROM client WHERE ClientIDBrCode = 1 AND ClientID = ?", Integer.class, id));
    }

    @Test
    void activeMembersAndMissingReasonsAreRefused() {
        int id = ctl.approve(ctl.register(ana(), me(maker)).id(), null, me(officer)).clientId();
        assertThrows(ResponseStatusException.class, () -> ctl.reactivate(new MemberLifecycleController.Reactivate(1, id, 1, "x"), me(maker)));
    }

    @Test
    void theSharePolicyBlocksARegularMemberWhoHasNotPaidAgain() {
        assumeTrue(db.tableExists("ecis_setting"), "V038 has not been applied");
        int id = openedAndClosed(1, null);
        policy.set(new MembershipPolicyController.Change(true, "Bylaws"), me(as("admin", "ROLE_ADMIN")));
        var req = ctl.reactivate(new MemberLifecycleController.Reactivate(1, id, 1, "Rejoining"), me(maker));
        var e = assertThrows(ResponseStatusException.class, () -> ctl.approve(req.id(), null, me(officer)));
        assertTrue(e.getReason().contains("share capital"));
        assertEquals(3, jdbc.queryForObject("SELECT AccountStatus FROM client WHERE ClientIDBrCode = 1 AND ClientID = ?", Integer.class, id));
    }

    @Test
    void theSharePolicyOffOrAnAssociateGoesThrough() {
        assumeTrue(db.tableExists("ecis_setting"), "V038 has not been applied");
        int id = openedAndClosed(1, null);
        policy.set(new MembershipPolicyController.Change(true, "Bylaws"), me(as("admin", "ROLE_ADMIN")));
        var assoc = ctl.reactivate(new MemberLifecycleController.Reactivate(1, id, 2, "Rejoining as associate"), me(maker));
        assertEquals("APPROVED", ctl.approve(assoc.id(), null, me(officer)).status());
        policy.set(new MembershipPolicyController.Change(false, "Changed"), me(as("admin", "ROLE_ADMIN")));
        assertEquals(false, policy.returningPaysShareCapital());
    }
}
