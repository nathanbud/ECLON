package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Importing a list of members: checked row by row, saved as one batch, opened by a different officer. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.edit.enabled=true"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class MemberImportTest {

    @Autowired MemberImportController imports;
    @Autowired MemberLifecycleController lifecycle;
    @Autowired LookupController lookups;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private static Authentication me(String user, String... auths) {
        Authentication a = new TestingAuthenticationToken(user, "x", auths);
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    /** The uploader: a Membership Officer, and a real user of the old system, because the new members are credited to them. */
    private static Authentication admin() {
        return me("admin", "ROLE_MEMBERSHIP_OFFICER", "PERM_MEMBER_EDIT");
    }

    private static Authentication officer() {
        return me("officer", "ROLE_MEMBERSHIP_OFFICER", "PERM_MEMBER_EDIT");
    }

    @BeforeEach
    void ready() {
        assumeTrue(db.tableExists("ecis_member_import") && db.columnExists("ecis_member_request", "batch_id"), "V043 has not been applied");
    }

    private String female() {
        return lookups.all().get("gender").values().stream().filter((v) -> v != null && v.toLowerCase().startsWith("f")).findFirst().orElse("2");
    }

    private MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "list.csv", "text/csv", body.getBytes(StandardCharsets.UTF_8));
    }

    private String list() {
        return "LName,FName,DateOfBirth,Gender,CivilStatus,MemberType\n"
            + "Zzimport,Ana,1990-02-03," + female() + ",1,Regular\n"
            + "Zzimport,Ben,1988-05-06,1,1,Associate\n"
            + "Zzimport,Ana,1990-02-03,1,1,Regular\n"           // the same person twice
            + "Zzimport,Cara,2015-01-01,2,1,Regular\n"           // a child
            + ",Dan,1990-01-01,1,1,Regular\n";                   // no last name
    }

    @Test
    void thePreviewReportsEveryRowAndSavesNothing() throws IOException {
        admin();
        var p = imports.preview(csv(list()), 1);
        assertEquals(5, p.total());
        assertEquals(2, p.ok());
        assertEquals(3, p.errors());
        assertTrue(p.rows().get(2).problems().get(0).contains("earlier in this file"));
        assertTrue(p.rows().get(3).problems().get(0).contains("18"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_member_import", Integer.class));
    }

    @Test
    void problemsStopTheUploadUnlessRowsAreLeftOut() throws IOException {
        var a = admin();
        var e = assertThrows(ResponseStatusException.class, () -> imports.upload(csv(list()), 1, false, a));
        assertTrue(e.getReason().contains("3 row(s) have problems"));
        var batch = imports.upload(csv(list()), 1, true, admin());
        assertEquals(2, batch.okRows());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_member_request WHERE batch_id = ? AND status = 'PENDING'", Integer.class, batch.id()));
        // imported members show as one batch, not one by one
        assertFalse(lifecycle.list("PENDING", officer()).stream().anyMatch((r) -> r.summary().startsWith("Zzimport")));
    }

    @Test
    void anotherOfficerOpensTheMembersAndTheUploaderCannot() throws IOException {
        var batch = imports.upload(csv(list()), 1, true, admin());
        var own = me("admin", "ROLE_ADMIN", "ROLE_MEMBERSHIP_OFFICER");
        assertThrows(ResponseStatusException.class, () -> imports.approve(batch.id(), own));

        var done = imports.approve(batch.id(), officer());
        assertEquals("APPROVED", done.status());
        assertEquals(2, done.openedCount());
        assertEquals(0, done.failedCount());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM client WHERE LName = 'Zzimport' AND AccountStatus = 1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM client WHERE LName = 'Zzimport' AND FName = 'Ben' AND ClientType = 2", Integer.class));
    }

    @Test
    void aRejectedListOpensNobody() throws IOException {
        var batch = imports.upload(csv(list()), 1, true, admin());
        assertThrows(ResponseStatusException.class, () -> imports.reject(batch.id(), new MemberImportController.Decision("x"), officer()));
        assertEquals("REJECTED", imports.reject(batch.id(), new MemberImportController.Decision("Wrong file"), officer()).status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM client WHERE LName = 'Zzimport'", Integer.class));
    }

    @Test
    void onlyMembershipOfficersMayUpload() {
        var staff = me("clerk", "PERM_MEMBER_EDIT");
        assertThrows(AccessDeniedException.class, () -> imports.preview(csv(list()), 1));
        assertThrows(AccessDeniedException.class, () -> imports.upload(csv(list()), 1, true, staff));
        // an administrator who is not a Membership Officer may not either
        var administrator = me("admin", "ROLE_ADMIN", "PERM_MEMBER_EDIT");
        assertThrows(AccessDeniedException.class, () -> imports.preview(csv(list()), 1));
        assertThrows(AccessDeniedException.class, () -> imports.upload(csv(list()), 1, true, administrator));
        assertTrue(imports.templateCsv().getBody().startsWith("LName,"));
    }

    @Test
    void theBlankExcelListOpensAndItsExampleRowPassesTheChecks() throws IOException {
        byte[] book = imports.template().getBody();
        assertEquals('P', book[0]);
        assertEquals('K', book[1]);
        var rows = SpreadsheetReader.read("members-template.xlsx", book);
        assertEquals(1, rows.size());
        assertEquals("Reyes", rows.get(0).values().get("LName"));
        assertEquals("Baguio City", rows.get(0).values().get("ResAddCity"));
        admin();
        var preview = imports.preview(new MockMultipartFile("file", "members-template.xlsx", "application/octet-stream", book), 1);
        assertEquals(1, preview.total());
    }
}
