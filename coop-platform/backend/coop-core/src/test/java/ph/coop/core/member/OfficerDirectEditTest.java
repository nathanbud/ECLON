package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * A Membership Officer's own profile edit goes straight through, with no second approval, and is still logged field by field; anyone else's edit still waits for an officer.
 * Rolled back, so no member is changed.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.edit.enabled=true"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class OfficerDirectEditTest {

    @Autowired MemberChangeService changes;
    @Autowired JdbcTemplate jdbc;

    private int member() {
        int id = jdbc.queryForObject("SELECT ClientID FROM client WHERE ClientIDBrCode = 1 ORDER BY ClientID DESC LIMIT 1", Integer.class);
        assumeTrue(changes.editAvailable(), "editing is not available on this database (audit or change table missing)");
        assumeTrue(changes.pendingFor(1, id) == null, "the test member already has a change waiting");
        return id;
    }

    private String stamp(int id) {
        return jdbc.queryForObject("SELECT UpdDateTime FROM client WHERE ClientIDBrCode = 1 AND ClientID = ?", String.class, id);
    }

    private String houseNo(int id) {
        return jdbc.queryForObject("SELECT ResAddHouseNo FROM client WHERE ClientIDBrCode = 1 AND ClientID = ?", String.class, id);
    }

    @Test
    void aMembershipOfficersEditIsAppliedAtOnceAndLogged() {
        int id = member();
        var change = changes.submit(1, id, new MemberChangeService.SubmitRequest(Map.of("ResAddHouseNo", "Blk 9 Lot 2"), stamp(id), "Fixing the house number"), "officer1", true);

        assertEquals("APPROVED", change.status());
        assertEquals("officer1", change.requestedBy());
        assertEquals("officer1", change.decidedBy(), "decided by the same person, with no second approval");
        assertEquals("Blk 9 Lot 2", houseNo(id), "the client row is changed at once");
        assertEquals(null, changes.pendingFor(1, id), "nothing is left waiting");

        String entity = "1-" + id;
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'UPDATE:ResAddHouseNo' AND entity_id = ? AND username = 'officer1' AND reason LIKE '%no second approval%'",
            Integer.class, entity), "one audit row for the field, saying it was made directly");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'CHANGE_APPLIED_DIRECTLY' AND entity_id = ?", Integer.class, entity));
    }

    @Test
    void anyoneElsesEditStillWaitsForAnOfficer() {
        int id = member();
        String before = houseNo(id);
        var change = changes.submit(1, id, new MemberChangeService.SubmitRequest(Map.of("ResAddHouseNo", "Blk 1 Lot 1"), stamp(id), null), "clerk1", false);

        assertEquals("PENDING", change.status());
        assertEquals(before, houseNo(id), "the client row is not changed until an officer approves");
        assertTrue(changes.pendingFor(1, id) != null);
    }
}
