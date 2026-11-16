package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Saving a member's KYC record: the status follows what is filled in, the review date is suggested from the risk, the ID number is masked in the audit log, and a scan is checked. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class KycControllerTest {

    @Autowired KycController ctl;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private int branch;
    private int client;

    private static Authentication clerk() {
        Authentication a = new TestingAuthenticationToken("clerk", "x", "PERM_MEMBER_EDIT", "PERM_MEMBER_IMAGES");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    @BeforeEach
    void ready() {
        assumeTrue(db.tableExists("ecis_member_kyc"), "V018 / V055 has not been applied");
        var row = jdbc.queryForMap("SELECT ClientIDBrCode AS b, ClientID AS c FROM client ORDER BY ClientID LIMIT 1");
        branch = ((Number) row.get("b")).intValue();
        client = ((Number) row.get("c")).intValue();
    }

    private KycController.Save full(String risk) {
        return new KycController.Save("Passport", "P1234567A", "DFA", LocalDate.now().plusYears(5).toString(), "Salary", false, null, risk, null, null);
    }

    @Test
    void aMemberWithNothingRecordedIsNone() {
        var k = ctl.get(branch, client);
        assertEquals("NONE", k.status());
        assertFalse(k.hasScan());
        assertTrue(k.idTypes().contains("Passport"));
    }

    @Test
    void savingFillsTheStatusSuggestsAReviewDateAndIsAudited() {
        var k = ctl.save(branch, client, full("HIGH"), clerk());
        assertEquals("COMPLETE", k.status());
        assertEquals(LocalDate.now().plusYears(1).toString(), k.nextReview(), "high risk is reviewed in a year");
        assertEquals("clerk", k.updatedBy());

        var again = ctl.save(branch, client, new KycController.Save("Passport", "P1234567A", null, LocalDate.now().minusDays(1).toString(), "Salary", false, null, "LOW", null, null), clerk());
        assertEquals("EXPIRED", again.status());

        String logged = jdbc.queryForObject("SELECT new_value FROM ecis_audit_log WHERE action = 'member.kyc.update' AND entity_id = ? ORDER BY id DESC LIMIT 1", String.class, branch + "-" + client);
        assertTrue(logged.contains("567A") && !logged.contains("P1234567A"), "the ID number is masked in the audit log: " + logged);
    }

    @Test
    void badInputIsRefused() {
        assertThrows(ResponseStatusException.class, () -> ctl.save(branch, client, new KycController.Save("Library card", "x", null, null, null, false, null, null, null, null), clerk()));
        assertThrows(ResponseStatusException.class, () -> ctl.save(branch, client, new KycController.Save("Passport", "P1234567A", null, "not-a-date", null, false, null, null, null, null), clerk()));
        assertThrows(ResponseStatusException.class, () -> ctl.save(branch, client, new KycController.Save(null, null, null, null, null, true, "", "HIGH", null, null), clerk()), "politically exposed needs the position");
        assertThrows(ResponseStatusException.class, () -> ctl.get(branch, -5), "no such member");
    }

    @Test
    void anIdScanMustBeAPictureAndIsServedBack() throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4};
        assertThrows(ResponseStatusException.class, () -> ctl.setScan(branch, client, new MockMultipartFile("file", "x.txt", "text/plain", "hello world".getBytes()), clerk()));
        ctl.setScan(branch, client, new MockMultipartFile("file", "id.jpg", "image/jpeg", jpeg), clerk());
        assertTrue(ctl.get(branch, client).hasScan());
        var back = ctl.scan(branch, client, clerk());
        assertEquals(200, back.getStatusCode().value());
        assertEquals(jpeg.length, back.getBody().length);
        ctl.save(branch, client, full("LOW"), clerk());
        assertTrue(ctl.get(branch, client).hasScan(), "saving the form keeps the scan");
    }
}
