package ph.coop.core.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

/** The privacy registers and the data-subject request workflow, against the database and rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.audit.seal.enabled=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class PrivacyFlowTest {

    @Autowired PrivacyController registers;
    @Autowired PrivacyRequestController requests;
    @Autowired MemberPrivacyController memberPrivacy;
    @Autowired ExportLogController exportLog;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private static Authentication as(String user, String... roles) {
        Authentication a = new TestingAuthenticationToken(user, "x", roles);
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    private static Authentication dpo() {
        return as("dpo", "ROLE_DATA_PROTECTION_OFFICER");
    }

    private int member;

    @BeforeEach
    void ready() {
        assumeTrue(db.tableExists("ecis_privacy_request") && db.tableExists("ecis_member_fingerprint"), "V046 has not been applied");
        member = jdbc.queryForObject("SELECT ClientID FROM client WHERE ClientIDBrCode = 1 ORDER BY ClientID DESC LIMIT 1", Integer.class);
    }

    @Test
    void theSeededRegistersAreThereAndMarkedUnconfirmed() {
        dpo();
        var rules = registers.list("retention");
        assertTrue(rules.size() >= 7);
        assertTrue(rules.stream().allMatch((r) -> Integer.valueOf(0).equals(r.get("confirmed"))), "starting rules are not confirmed");
        assertTrue(registers.list("classification").stream().anyMatch((c) -> "SENSITIVE".equals(c.get("classification"))));
    }

    @Test
    void aRegisterRefusesWhatIsMissingWrongOrUnknown() {
        var auth = dpo();
        assertThrows(ResponseStatusException.class, () -> registers.create("sharing", Map.of("recipient", "CDA"), auth), "required fields");
        assertThrows(ResponseStatusException.class, () -> registers.create("dpia", Map.of("title", "Fingerprints", "risk_level", "EXTREME"), auth), "a value that is not allowed");
        assertThrows(ResponseStatusException.class, () -> registers.create("sharing", Map.of("recipient", "CDA", "purpose", "Reporting", "data_categories", "Members", "started_on", "next week"), auth), "a date");
        assertThrows(ResponseStatusException.class, () -> registers.create("sharing", Map.of("recipient", "CDA", "purpose", "x", "data_categories", "y", "started_on", "2026-01-01", "colour", "red"), auth), "an unknown field");
        assertThrows(ResponseStatusException.class, () -> registers.list("nope"));
    }

    @Test
    void sharingAndAssessmentsAreRecordedWithReferencesAndAudited() {
        var auth = dpo();
        var s = registers.create("sharing", Map.of("recipient", "Cooperative Development Authority", "purpose", "Annual report", "data_categories", "Member counts", "started_on", "2026-01-01"), auth);
        assertEquals("dpo", s.get("recorded_by"));
        var d = registers.create("dpia", Map.of("title", "Fingerprint enrolment", "risk_level", "high"), auth);
        assertTrue(String.valueOf(d.get("ref")).startsWith("DPIA-" + LocalDate.now().getYear() + "-"));
        assertEquals("PLANNED", d.get("status"), "a new assessment starts as planned");
        assertEquals("HIGH", d.get("risk_level"));
        var changed = registers.update("dpia", ((Number) d.get("id")).longValue(), Map.of("status", "DONE", "conducted_on", "2026-10-01"), dpo());
        assertEquals("DONE", changed.get("status"));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'privacy.dpia.updated' AND username = 'dpo'", Integer.class) >= 1);
    }

    @Test
    void anIncidentGetsA72HourDeadlineAndShowsWhenItIsLate() {
        var auth = dpo();
        var i = registers.create("incidents", Map.of("detected_at", "2026-10-01T09:00", "description", "A laptop with a member list was lost", "severity", "high"), auth);
        assertEquals("OPEN", i.get("status"));
        assertEquals("2026-10-04T09:00", i.get("notify_by"));
        assertTrue(registers.retentionReview().stream().anyMatch((r) -> r.what().startsWith("Incidents past the notification time") && r.count() >= 1));
        var told = registers.update("incidents", ((Number) i.get("id")).longValue(), Map.of("notified_at", "2026-10-02T10:00", "status", "CONTAINED"), dpo());
        assertEquals("CONTAINED", told.get("status"));
    }

    @Test
    void oneNoticeIsInForceAtATimeAndMembersAreGivenIt() {
        var auth = dpo();
        var v1 = registers.create("notices", Map.of("version", "T-1", "title", "Privacy notice", "body", "How we use your data."), auth);
        var v2 = registers.create("notices", Map.of("version", "T-2", "title", "Privacy notice", "body", "Changed wording."), dpo());
        registers.activate(((Number) v1.get("id")).longValue(), dpo());
        assertEquals("ACTIVE", registers.activate(((Number) v2.get("id")).longValue(), dpo()).get("status"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_privacy_notice WHERE status = 'ACTIVE'", Integer.class));
        assertEquals("RETIRED", registers.list("notices").stream().filter((n) -> "T-1".equals(n.get("version"))).findFirst().orElseThrow().get("status"));
        assertThrows(ResponseStatusException.class, () -> registers.update("notices", ((Number) v2.get("id")).longValue(), Map.of("body", "Rewritten"), dpo()), "an active notice is not rewritten");

        var staff = as("clerk", "PERM_MEMBER_EDIT");
        var state = memberPrivacy.acknowledge(1, member, new MemberPrivacyController.Ack("verbal"), staff);
        assertTrue(state.upToDate());
        assertEquals("T-2", state.latest().version());
        assertThrows(ResponseStatusException.class, () -> memberPrivacy.acknowledge(1, member, new MemberPrivacyController.Ack("verbal"), staff), "already given");
        assertThrows(ResponseStatusException.class, () -> memberPrivacy.acknowledge(1, member, new MemberPrivacyController.Ack("telepathy"), as("clerk", "PERM_MEMBER_EDIT")));
        assertThrows(AccessDeniedException.class, () -> memberPrivacy.acknowledge(1, member, new MemberPrivacyController.Ack("verbal"), as("nobody", "ROLE_USER")));
    }

    @Test
    void anAccessRequestNeedsTheIdentityCheckedThenHandsOverAPackageThatIsLogged() throws Exception {
        var auth = dpo();
        var r = requests.create(new PrivacyRequestController.NewRequest("access", 1, member, "The member", "09170000000", null, "Wants a copy of their data"), auth);
        long id = ((Number) r.get("id")).longValue();
        assertTrue(String.valueOf(r.get("ref")).startsWith("DSR-"));
        assertEquals(LocalDate.now().plusDays(30).toString(), r.get("due_on"));
        assertThrows(ResponseStatusException.class, () -> requests.dataPackage(id, dpo()), "no data before the identity is checked");
        assertThrows(ResponseStatusException.class, () -> requests.fulfil(id, new PrivacyRequestController.Note("done"), dpo()), "no fulfilment before the identity is checked");

        var verified = requests.verifyIdentity(id, new PrivacyRequestController.Note("Matched the ID shown at the counter"), dpo());
        assertEquals("IN_PROGRESS", verified.get("status"));
        var file = requests.dataPackage(id, dpo());
        String body = new String(file.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("\"member\"") && body.contains("\"accounts\"") && body.contains("\"privacyNoticesGiven\""));
        assertFalse(body.contains("\"template\""), "fingerprint templates are never in a package");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_data_export WHERE export_type = 'MEMBER_PACKAGE' AND username = 'dpo' AND purpose LIKE ?", Integer.class, "%" + r.get("ref") + "%"));

        var done = requests.fulfil(id, new PrivacyRequestController.Note("Package handed over in person"), dpo());
        assertEquals("FULFILLED", done.get("status"));
        assertNotNull(done.get("closed_at"));
        assertThrows(ResponseStatusException.class, () -> requests.fulfil(id, new PrivacyRequestController.Note("again"), dpo()), "a closed request is closed");
    }

    @Test
    void anErasureRequestWipesTheFingerprintsAndKeepsTheRestForTheOfficerToExplain() {
        jdbc.update("INSERT INTO ecis_biometric_consent (branch_code, client_id, consent_version, given_at, recorded_by) VALUES (1, ?, 'T', NOW(), 'clerk')", member);
        jdbc.update("INSERT INTO ecis_member_fingerprint (branch_code, client_id, finger, format, quality, template, status, captured_by, captured_at) VALUES (1, ?, 2, 'SIM1', 80, ?, 'ACTIVE', 'clerk', NOW())", member, new byte[] {1, 2, 3});
        var auth = dpo();
        var r = requests.create(new PrivacyRequestController.NewRequest("erasure", 1, member, "The member", null, null, "Wants their data erased"), auth);
        long id = ((Number) r.get("id")).longValue();
        assertThrows(ResponseStatusException.class, () -> requests.eraseBiometrics(id, dpo()), "identity first");
        requests.verifyIdentity(id, new PrivacyRequestController.Note("In person with ID"), dpo());
        requests.eraseBiometrics(id, dpo());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_member_fingerprint WHERE client_id = ? AND template IS NOT NULL", Integer.class, member));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_biometric_consent WHERE client_id = ? AND withdrawn_at IS NULL", Integer.class, member));
        var refused = requests.refuse(id, new PrivacyRequestController.Note("Ledger records must be kept by law; fingerprints erased"), dpo());
        assertEquals("REFUSED", refused.get("status"));
    }

    @Test
    void requestsAreCheckedAndExportsAreLoggedForAnySignedInPerson() {
        var auth = dpo();
        assertThrows(ResponseStatusException.class, () -> requests.create(new PrivacyRequestController.NewRequest("gossip", null, null, "Someone", null, null, "x"), auth));
        assertThrows(ResponseStatusException.class, () -> requests.create(new PrivacyRequestController.NewRequest("access", null, null, "Someone", null, null, "Wants data"), dpo()), "an access request is about a member");
        assertThrows(ResponseStatusException.class, () -> requests.create(new PrivacyRequestController.NewRequest("access", 1, 99999999, "Someone", null, null, "Wants data"), dpo()), "no such member");
        exportLog.log(new ExportLogController.Entry("report", "Loan portfolio", 500, "Board pack"), as("teller", "ROLE_USER"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_data_export WHERE username = 'teller' AND export_type = 'REPORT' AND subject = 'Loan portfolio'", Integer.class));
        assertThrows(ResponseStatusException.class, () -> exportLog.log(new ExportLogController.Entry("member_package", null, 1, null), as("teller", "ROLE_USER")));
    }

    @Test
    void onlyAdministratorsAndDataProtectionOfficersMayWorkTheRegisters() {
        var clerk = as("clerk", "PERM_MEMBER_EDIT", "ROLE_MEMBERSHIP_OFFICER");
        assertThrows(AccessDeniedException.class, () -> registers.list("sharing"));
        assertThrows(AccessDeniedException.class, () -> requests.list(null));
        assertThrows(AccessDeniedException.class, () -> registers.create("sharing", Map.of(), clerk));
        as("boss", "ROLE_ADMIN");
        assertTrue(registers.list("sharing") != null);
        List<Map<String, Object>> all = requests.list(null);
        assertNotNull(all);
    }
}
