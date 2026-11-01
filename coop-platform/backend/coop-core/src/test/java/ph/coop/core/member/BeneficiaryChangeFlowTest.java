package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

/** Adding, changing and removing a beneficiary through a request a Membership Officer approves, against the database and rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.edit.enabled=true", "coop.audit.seal.enabled=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class BeneficiaryChangeFlowTest {

    @Autowired MemberLifecycleController ctl;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private static Authentication me(Authentication a) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    private final Authentication maker = new TestingAuthenticationToken("maker", "x", "PERM_MEMBER_EDIT");
    private final Authentication officer = new TestingAuthenticationToken("officer", "x", "ROLE_MEMBERSHIP_OFFICER", "PERM_MEMBER_EDIT");
    private final Authentication nobody = new TestingAuthenticationToken("nobody", "x");

    private int client;

    @BeforeEach
    void ready() {
        assumeTrue(db.tableExists("ecis_member_request") && db.canInsertInto("benef", "ecis_member_request"), "V037 or V050 has not been applied");
        client = jdbc.queryForObject("SELECT ClientID FROM client WHERE ClientIDBrCode = 1 AND AccountStatus = 1 ORDER BY ClientID DESC LIMIT 1", Integer.class);
        jdbc.update("DELETE FROM benef WHERE BenefBR_CODE = 1 AND ClientIDBenef = ?", client);
    }

    private MemberLifecycleController.BeneficiaryRequest add(String name) {
        return new MemberLifecycleController.BeneficiaryRequest(1, client, "ADD", null, name, 36, "2010-05-04", "Student", 1, null);
    }

    private MemberLifecycleController.BeneficiaryRequest edit(long id, String name, int type) {
        return new MemberLifecycleController.BeneficiaryRequest(1, client, "EDIT", id, name, 36, "2010-05-04", "Student", type, null);
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM benef WHERE BenefBR_CODE = 1 AND ClientIDBenef = ?", Integer.class, client);
    }

    private long benefId() {
        return jdbc.queryForObject("SELECT BenefID FROM benef WHERE BenefBR_CODE = 1 AND ClientIDBenef = ? ORDER BY BenefID DESC LIMIT 1", Long.class, client);
    }

    @Test
    void addingWaitsForAnotherOfficerAndThenWritesTheRowAndAnAuditTrail() {
        var req = ctl.beneficiary(add("Zzitest Ana"), me(maker));
        assertEquals("PENDING", req.status());
        assertEquals("BENEF", req.kind());
        assertEquals(0, count(), "nothing is written until approval");
        assertThrows(ResponseStatusException.class, () -> ctl.approve(req.id(), null, me(maker)), "the maker cannot approve their own request");

        assertEquals("APPROVED", ctl.approve(req.id(), null, me(officer)).status());
        assertEquals(1, count());
        var row = jdbc.queryForMap("SELECT BenefName, BenefRelation, BenefDateofBirth, BenefOccu, BenefTypeID FROM benef WHERE BenefBR_CODE = 1 AND ClientIDBenef = ?", client);
        assertEquals("Zzitest Ana", row.get("BenefName"));
        assertEquals(36, ((Number) row.get("BenefRelation")).intValue());
        assertEquals("2010-05-04", row.get("BenefDateofBirth").toString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'MEMBER_BENEFICIARY_ADDED' AND username = 'officer' AND entity_id = ?", Integer.class, "1-" + client));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'MEMBER_BENEFICIARY_REQUESTED' AND username = 'maker' AND entity_id = ?", Integer.class, "1-" + client));
    }

    @Test
    void aRejectedOrWithdrawnRequestChangesNothing() {
        var req = ctl.beneficiary(add("Zzitest Rejected"), me(maker));
        ctl.reject(req.id(), new MemberLifecycleController.Decision("Not a family member"), me(officer));
        assertEquals(0, count());
        var again = ctl.beneficiary(add("Zzitest Rejected"), me(maker));
        ctl.withdraw(again.id(), me(maker));
        assertEquals(0, count());
    }

    @Test
    void changingAndRemovingNeedApprovalAndKeepTheOldValuesInTheAudit() {
        ctl.approve(ctl.beneficiary(add("Zzitest Ana"), me(maker)).id(), null, me(officer));
        long id = benefId();

        var change = ctl.beneficiary(edit(id, "Zzitest Ana Marie", 2), me(maker));
        assertEquals("Zzitest Ana", jdbc.queryForObject("SELECT BenefName FROM benef WHERE BenefID = ?", String.class, id), "not changed until approval");
        ctl.approve(change.id(), null, me(officer));
        assertEquals("Zzitest Ana Marie", jdbc.queryForObject("SELECT BenefName FROM benef WHERE BenefID = ?", String.class, id));
        assertEquals(2, jdbc.queryForObject("SELECT BenefTypeID FROM benef WHERE BenefID = ?", Integer.class, id));

        var remove = ctl.beneficiary(new MemberLifecycleController.BeneficiaryRequest(1, client, "REMOVE", id, null, null, null, null, null, "Entered by mistake"), me(maker));
        assertEquals(1, count(), "not removed until approval");
        ctl.approve(remove.id(), null, me(officer));
        assertEquals(0, count());
        String kept = jdbc.queryForObject("SELECT prev_value FROM ecis_audit_log WHERE action = 'MEMBER_BENEFICIARY_REMOVED' AND entity_id = ? ORDER BY id DESC LIMIT 1", String.class, "1-" + client);
        assertTrue(kept.contains("Zzitest Ana Marie"), "the removed values are kept in the audit row: " + kept);
    }

    @Test
    void aRowChangedAfterTheRequestIsNotOverwrittenBlindly() {
        ctl.approve(ctl.beneficiary(add("Zzitest Ana"), me(maker)).id(), null, me(officer));
        long id = benefId();
        var change = ctl.beneficiary(edit(id, "Zzitest Ana Marie", 1), me(maker));
        jdbc.update("UPDATE benef SET BenefOccu = 'Changed meanwhile' WHERE BenefID = ?", id);
        assertThrows(ResponseStatusException.class, () -> ctl.approve(change.id(), null, me(officer)));
        assertEquals("Zzitest Ana", jdbc.queryForObject("SELECT BenefName FROM benef WHERE BenefID = ?", String.class, id));
    }

    @Test
    void duplicatesAndBadRequestsAreRefused() {
        ctl.approve(ctl.beneficiary(add("Zzitest Ana"), me(maker)).id(), null, me(officer));
        assertThrows(ResponseStatusException.class, () -> ctl.beneficiary(add("  zzitest   ANA "), me(maker)), "already recorded");

        ctl.beneficiary(add("Zzitest Pending"), me(maker));
        assertThrows(ResponseStatusException.class, () -> ctl.beneficiary(add("Zzitest Pending"), me(maker)), "already waiting");

        long id = benefId();
        assertThrows(ResponseStatusException.class, () -> ctl.beneficiary(edit(id, "Zzitest Ana", 1), me(maker)), "nothing changed");
        var change = ctl.beneficiary(edit(id, "Zzitest Ana B", 1), me(maker));
        assertEquals("PENDING", change.status());
        assertThrows(ResponseStatusException.class, () -> ctl.beneficiary(edit(id, "Zzitest Ana C", 1), me(maker)), "a change is already waiting");

        assertThrows(ResponseStatusException.class, () -> ctl.beneficiary(new MemberLifecycleController.BeneficiaryRequest(1, client, "ADD", null, "", 36, null, null, 1, null), me(maker)), "no name");
        assertThrows(ResponseStatusException.class, () -> ctl.beneficiary(new MemberLifecycleController.BeneficiaryRequest(1, client, "REMOVE", id, null, null, null, null, null, ""), me(maker)), "no reason");
        assertThrows(ResponseStatusException.class, () -> ctl.beneficiary(new MemberLifecycleController.BeneficiaryRequest(1, client, "REMOVE", 999999999L, null, null, null, null, null, "no such row"), me(maker)), "unknown beneficiary");
        assertThrows(ResponseStatusException.class, () -> ctl.beneficiary(new MemberLifecycleController.BeneficiaryRequest(1, 999999999, "ADD", null, "Ana", 36, null, null, 1, null), me(maker)), "unknown member");
    }

    @Test
    void onlyAMembershipOfficerCanDecide() {
        var req = ctl.beneficiary(add("Zzitest Gate"), me(maker));
        assertThrows(ResponseStatusException.class, () -> ctl.approve(req.id(), null, me(nobody)));
        assertThrows(ResponseStatusException.class, () -> ctl.reject(req.id(), new MemberLifecycleController.Decision("No"), me(nobody)));
        assertEquals(0, count());
    }
}
