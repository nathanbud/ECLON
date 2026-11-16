package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.biometric.BiometricStaging.StagedFinger;
import ph.coop.core.biometric.BiometricStaging.StagedItems;

/** A photo, consent and fingerprints taken during registration are held with the request and moved to the member on approval; a rejected request keeps nothing. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.edit.enabled=true"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class RegistrationBiometricsTest {

    @Autowired MemberLifecycleController ctl;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private static Authentication me(String... auths) {
        String user = java.util.Arrays.asList(auths).contains("ROLE_MEMBERSHIP_OFFICER") ? "officer" : "admin";
        Authentication a = new TestingAuthenticationToken(user, "x", auths);
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    private static Authentication maker() {
        return me("PERM_MEMBER_EDIT", "PERM_MEMBER_IMAGES");
    }

    private static Authentication officer() {
        return me("ROLE_MEMBERSHIP_OFFICER", "PERM_MEMBER_EDIT");
    }

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4, 5};

    private static String sim(long seed) {
        byte[] b = new byte[128];
        new Random(seed).nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    private static StagedFinger finger(int f, long seed) {
        return new StagedFinger(f, sim(seed), "SIM1", 80, "Simulated scanner");
    }

    private MemberLifecycleController.Register reg(String last, StagedItems items) {
        return new MemberLifecycleController.Register(1, 1,
            Map.of("LName", last, "FName", "Ana", "DateOfBirth", "1990-02-03", "Gender", "2", "CivilStatus", "1"), items);
    }

    @BeforeEach
    void ready() {
        assumeTrue(db.tableExists("ecis_member_request_item"), "V042 has not been applied");
        assumeTrue(db.tableExists("ecis_member_fingerprint"), "V040 has not been applied");
    }

    @Test
    void approvalMovesThePhotoConsentAndFingerprintsToTheNewMember() {
        var items = new StagedItems(Base64.getEncoder().encodeToString(JPEG), true, List.of(finger(1, 501), finger(2, 502)));
        var req = ctl.register(reg("Zzbio", items), maker());
        assertTrue(req.summary().contains("photo, 2 fingerprints"));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_member_request_item WHERE request_id = ? AND status = 'HELD'", Integer.class, req.id()));

        int id = ctl.approve(req.id(), null, officer()).clientId();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE ImageBR_CODE = 1 AND ClientIDimg = ? AND ImageType = 'img'", Integer.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_biometric_consent WHERE branch_code = 1 AND client_id = ? AND withdrawn_at IS NULL", Integer.class, id));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_member_fingerprint WHERE branch_code = 1 AND client_id = ? AND status = 'ACTIVE' AND template IS NOT NULL", Integer.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_member_request_item WHERE request_id = ? AND status = 'HELD'", Integer.class, req.id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_member_request_item WHERE request_id = ? AND data IS NOT NULL", Integer.class, req.id()));
        assertNotNull(jdbc.queryForObject("SELECT captured_by FROM ecis_member_fingerprint WHERE branch_code = 1 AND client_id = ? LIMIT 1", String.class, id));
    }

    @Test
    void approvalStoresTheSignatureTakenAtRegistration() {
        var items = new StagedItems(null, false, List.of(), Base64.getEncoder().encodeToString(JPEG));
        var req = ctl.register(reg("Zzsign", items), maker());
        assertTrue(req.summary().contains("signature"));
        int id = ctl.approve(req.id(), null, officer()).clientId();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE ImageBR_CODE = 1 AND ClientIDimg = ? AND ImageType = 'sig'", Integer.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_member_request_item WHERE request_id = ? AND data IS NOT NULL", Integer.class, req.id()));
        assertThrows(ResponseStatusException.class, () -> ctl.register(reg("Zzbad", new StagedItems(null, false, List.of(), Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4, 5}))), maker()));
    }

    @Test
    void aRejectedRegistrationKeepsNothing() {
        var req = ctl.register(reg("Zzbio", new StagedItems(Base64.getEncoder().encodeToString(JPEG), true, List.of(finger(1, 511)))), maker());
        ctl.reject(req.id(), new MemberLifecycleController.Decision("Not eligible"), officer());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_member_request_item WHERE request_id = ? AND data IS NOT NULL", Integer.class, req.id()));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_member_request_item WHERE request_id = ? AND status = 'DISCARDED'", Integer.class, req.id()));
    }

    @Test
    void fingerprintsNeedConsentAndTheRightPermissionAndMayNotRepeatAFingerHeldForAnotherRegistration() {
        assertThrows(ResponseStatusException.class, () -> ctl.register(reg("Zzone", new StagedItems(null, false, List.of(finger(1, 521)))), maker()));
        var noImages = me("PERM_MEMBER_EDIT");
        assertThrows(ResponseStatusException.class, () -> ctl.register(reg("Zztwo", new StagedItems(null, true, List.of(finger(1, 522)))), noImages));

        ctl.register(reg("Zzthree", new StagedItems(null, true, List.of(finger(1, 523)))), maker());
        var dup = assertThrows(ResponseStatusException.class, () -> ctl.register(reg("Zzfour", new StagedItems(null, true, List.of(finger(3, 523)))), maker()));
        assertTrue(dup.getReason().contains("already held for another registration"));
        var self = assertThrows(ResponseStatusException.class, () -> ctl.register(reg("Zzfive", new StagedItems(null, true, List.of(finger(1, 524), finger(2, 524)))), maker()));
        assertTrue(self.getReason().contains("already scanned") || self.getReason().contains("matches"));
    }

    @Test
    void theCheckEndpointReportsADuplicateAtOnce() {
        ctl.register(reg("Zzsix", new StagedItems(null, true, List.of(finger(1, 531)))), maker());
        var again = ctl.biometricCheck(new MemberLifecycleController.Check(sim(531), "SIM1", null));
        assertTrue(again.duplicate());
        assertEquals(false, ctl.biometricCheck(new MemberLifecycleController.Check(sim(532), "SIM1", null)).duplicate());
    }
}
