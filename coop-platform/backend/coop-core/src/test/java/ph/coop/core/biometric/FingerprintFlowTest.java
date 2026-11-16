package ph.coop.core.biometric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.biometric.FingerprintController.Capture;
import ph.coop.core.biometric.FingerprintController.Reason;
import ph.coop.core.biometric.FingerprintController.Verify;

/** Consent, enrolment, duplicate refusal, verification, revoking and withdrawal against the database, rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class FingerprintFlowTest {

    @Autowired FingerprintController fp;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private int a, b;

    /** Signs in as a person with both permissions; each call re-sets the context so method security sees it. */
    private static Authentication staff() {
        return me("PERM_MEMBER_EDIT", "PERM_MEMBER_IMAGES");
    }

    private static Authentication me(String... auths) {
        Authentication x = new TestingAuthenticationToken("admin", "x", auths);
        SecurityContextHolder.getContext().setAuthentication(x);
        return x;
    }

    private static Capture scan(int finger, byte[] t, int quality) {
        return new Capture(finger, Base64.getEncoder().encodeToString(t), "SIM1", quality, "Simulated scanner");
    }

    private static Verify probe(byte[] t) {
        return new Verify(Base64.getEncoder().encodeToString(t), "SIM1");
    }

    @BeforeEach
    void ready() {
        assumeTrue(db.tableExists("ecis_member_fingerprint"), "V040 has not been applied");
        List<Integer> ids = jdbc.queryForList("SELECT ClientID FROM client WHERE ClientIDBrCode = 1 ORDER BY ClientID DESC LIMIT 2", Integer.class);
        a = ids.get(0);
        b = ids.get(1);
    }

    @Test
    void scanningNeedsConsentFirst() {
        var e = assertThrows(ResponseStatusException.class, () -> fp.enrol(1, a, scan(1, BiometricRulesTest.random(1), 80), staff()));
        assertTrue(e.getReason().contains("consent"));
    }

    @Test
    void enrolVerifyReplaceAndRevoke() {
        fp.giveConsent(1, a, staff());
        byte[] thumb = BiometricRulesTest.random(11);
        var s = fp.enrol(1, a, scan(1, thumb, 82), staff());
        assertEquals(1, s.fingers().size());
        assertEquals("Right thumb", s.fingers().get(0).name());

        byte[] stored = jdbc.queryForObject("SELECT template FROM ecis_member_fingerprint WHERE branch_code = 1 AND client_id = ? AND status = 'ACTIVE'", byte[].class, a);
        assertFalse(java.util.Arrays.equals(thumb, stored));

        var ok = fp.verify(1, a, probe(BiometricRulesTest.noisy(thumb, 40, 5)), staff());
        assertTrue(ok.matched());
        assertEquals(1, ok.finger());
        assertFalse(fp.verify(1, a, probe(BiometricRulesTest.random(99)), staff()).matched());

        fp.enrol(1, a, scan(1, BiometricRulesTest.noisy(thumb, 30, 6), 90), staff());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_member_fingerprint WHERE branch_code = 1 AND client_id = ? AND status = 'REPLACED' AND template IS NULL", Integer.class, a));

        s = fp.revoke(1, a, 1, new Reason("Injured finger"), staff());
        assertEquals(0, s.fingers().size());
        assertNotNull(s.consent());
    }

    @Test
    void poorScansAndTheSameFingerForAnotherMemberAreRefused() {
        fp.giveConsent(1, a, staff());
        fp.giveConsent(1, b, staff());
        assertThrows(ResponseStatusException.class, () -> fp.enrol(1, a, scan(1, BiometricRulesTest.random(21), 20), staff()));
        byte[] finger = BiometricRulesTest.random(22);
        fp.enrol(1, a, scan(2, finger, 85), staff());
        var dup = assertThrows(ResponseStatusException.class, () -> fp.enrol(1, b, scan(2, BiometricRulesTest.noisy(finger, 35, 9), 85), staff()));
        assertTrue(dup.getReason().contains("already enrolled for member 1-" + a));
        var same = assertThrows(ResponseStatusException.class, () -> fp.enrol(1, a, scan(3, BiometricRulesTest.noisy(finger, 35, 10), 85), staff()));
        assertTrue(same.getReason().contains("right index"));
    }

    @Test
    void withdrawingConsentErasesEveryTemplate() {
        fp.giveConsent(1, a, staff());
        fp.enrol(1, a, scan(1, BiometricRulesTest.random(31), 80), staff());
        fp.enrol(1, a, scan(6, BiometricRulesTest.random(32), 80), staff());
        var s = fp.withdrawConsent(1, a, new Reason("Member asked"), staff());
        assertNull(s.consent());
        assertEquals(0, s.fingers().size());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_member_fingerprint WHERE branch_code = 1 AND client_id = ? AND template IS NOT NULL", Integer.class, a));
    }

    @Test
    void onlyPeopleWithBothPermissionsMayEnrol() {
        Authentication editOnly = me("PERM_MEMBER_EDIT");
        assertThrows(AccessDeniedException.class, () -> fp.giveConsent(1, a, editOnly));
    }
}
