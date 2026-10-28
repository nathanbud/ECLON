package ph.coop.core.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.document.DocumentController.Doc;
import ph.coop.core.document.DocumentController.GrantIn;
import ph.coop.core.document.DocumentController.Reason;

/**
 * Documents end to end with real files in a test folder and the real register, rolled back: classification decides who can list, read and download; refused uploads leave
 * nothing; versions supersede; removal waits for the retention date; every use is audited and a file that no longer matches its hash is not released.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.documents.root=target/test-documents"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class DocumentFlowTest {

    @Autowired DocumentController documents;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private String member;

    private static Authentication as(String user, String... roles) {
        return new TestingAuthenticationToken(user, "x", java.util.Arrays.stream(roles).map((r) -> "ROLE_" + r).toArray(String[]::new));
    }

    private static MockMultipartFile pdf(String name, String body) {
        return new MockMultipartFile("file", name, "application/pdf", ("%PDF-1.7\n" + body).getBytes(StandardCharsets.ISO_8859_1));
    }

    @BeforeEach
    void ready() {
        assumeTrue(db.tableExists("ecis_document") && db.tableExists("ecis_document_grant"), "V035 has not been applied");
        try {
            jdbc.update("UPDATE ecis_document SET status = status WHERE id = -1");
        } catch (RuntimeException e) {
            assumeTrue(false, "the grants of V035 are missing");
        }
        member = String.valueOf(jdbc.queryForObject("SELECT MIN(ClientID) FROM client WHERE ClientIDBrCode = 1 AND AccountStatus = 1", Integer.class));
    }

    private Doc upload(Authentication who, MockMultipartFile file, String cls) {
        return documents.upload(file, "MEMBER", member, "Valid ID", "front and back", cls, null, who);
    }

    @Test
    void aMemberDocumentIsConfidentialByDefaultAndOnlyThePermittedCanReadIt() {
        Authentication clerk = as("clerk", "DOCUMENT_CLERK");
        Doc d = upload(clerk, pdf("id.pdf", "first scan"), null);
        assertTrue(d.docNo().matches("DOC-\\d{4}-\\d{6}"));
        assertEquals("CONFIDENTIAL", d.classification());
        assertEquals(1, d.version());
        assertEquals(LocalDate.now().plusYears(10), d.retentionDate(), "members' papers are kept 10 years");
        assertEquals(64, d.sha256().length());
        assertEquals("application/pdf", d.mimeType());

        // the clerk who uploaded it and an administrator can read it; an officer and an accountant cannot, and do not learn that it exists
        // the grant needs real, enabled users of the old system
        List<String> users = jdbc.queryForList("SELECT AccessUserName FROM access WHERE AccessBR_CODE = 1 AND COALESCE(AccessIsDisabled, 0) = 0 AND AccessUserName <> 'clerk' ORDER BY AccessID LIMIT 2", String.class);
        assumeTrue(users.size() == 2, "need two enabled users");
        String officerName = users.get(0), acctName = users.get(1);
        Authentication officer = as(officerName, "MEMBERSHIP_OFFICER"), accountant = as(acctName, "ACCOUNTANT"), admin = as("boss", "ADMIN");
        assertTrue(documents.list("MEMBER", member, null, false, clerk).stream().anyMatch((x) -> x.id() == d.id()));
        assertTrue(documents.list("MEMBER", member, null, false, admin).stream().anyMatch((x) -> x.id() == d.id()));
        assertTrue(documents.list("MEMBER", member, null, false, officer).stream().noneMatch((x) -> x.id() == d.id()));
        assertTrue(documents.list(null, null, "valid id", false, accountant).stream().noneMatch((x) -> x.id() == d.id()));
        for (Authentication who : new Authentication[] {officer, accountant}) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> documents.get(d.id(), who));
            assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
            assertThrows(ResponseStatusException.class, () -> documents.content(d.id(), who));
        }

        // the download is the same bytes, is audited, and is not cached
        ResponseEntity<byte[]> out = documents.content(d.id(), clerk);
        assertArrayEquals(("%PDF-1.7\nfirst scan").getBytes(StandardCharsets.ISO_8859_1), out.getBody());
        assertTrue(out.getHeaders().getFirst("Content-Disposition").contains("id.pdf"));
        assertEquals("nosniff", out.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("no-store", out.getHeaders().getFirst("Cache-Control"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'document.download' AND entity_id = ? AND username = 'clerk'", Integer.class, d.docNo()));

        // a grant lets the officer in; only managers grant; taking it back closes the door again
        documents.grant(d.id(), new GrantIn(officerName), clerk);
        assertEquals(List.of(officerName), documents.get(d.id(), clerk).grants());
        assertFalse(documents.get(d.id(), officer).document().canManage());
        assertArrayEquals(out.getBody(), documents.content(d.id(), officer).getBody());
        assertThrows(ResponseStatusException.class, () -> documents.grant(d.id(), new GrantIn(acctName), officer), "a reader who is not a manager cannot share it");
        assertThrows(ResponseStatusException.class, () -> documents.grant(d.id(), new GrantIn(officerName), clerk), "already has access");
        assertThrows(ResponseStatusException.class, () -> documents.grant(d.id(), new GrantIn("nobody-at-all"), clerk), "no such user");
        documents.revoke(d.id(), officerName, clerk);
        assertThrows(ResponseStatusException.class, () -> documents.get(d.id(), officer));
        assertTrue(documents.access(d.id(), clerk).stream().anyMatch((a) -> a.action().equals("document.grant")));
        assertThrows(ResponseStatusException.class, () -> documents.access(d.id(), as("nobody", "PURCHASER")), "someone who may not read it sees no history");
    }

    @Test
    void uploadsThatAreNotAllowedAreRefusedAndLeaveNothingBehind() {
        Authentication clerk = as("clerk", "DOCUMENT_CLERK");
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_document", Integer.class);
        assertThrows(ResponseStatusException.class, () -> upload(clerk, new MockMultipartFile("file", "run.exe", "application/octet-stream", "MZ".getBytes()), null), "a program");
        assertThrows(ResponseStatusException.class, () -> upload(clerk, new MockMultipartFile("file", "fake.pdf", "application/pdf", "this is not a pdf".getBytes()), null), "wrong content");
        assertThrows(ResponseStatusException.class, () -> upload(clerk, new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]), null), "empty");
        assertThrows(ResponseStatusException.class, () -> upload(clerk, new MockMultipartFile("file", "big.pdf", "application/pdf", ("%PDF-" + "x".repeat(11 * 1024 * 1024)).getBytes()), null), "11 MB");
        assertThrows(ResponseStatusException.class, () -> documents.upload(pdf("a.pdf", "x"), "CIC", "1", "t", null, null, null, clerk), "unknown kind of record");
        assertThrows(ResponseStatusException.class, () -> documents.upload(pdf("a.pdf", "x"), "MEMBER", "999999999", "t", null, null, null, clerk), "no such member");
        assertThrows(ResponseStatusException.class, () -> documents.upload(pdf("a.pdf", "x"), "MEMBER", member, "t", null, "SECRET", null, clerk), "bad classification");
        assertThrows(ResponseStatusException.class, () -> documents.upload(pdf("a.pdf", "x"), "MEMBER", member, "t", null, null, LocalDate.now().plusYears(2), clerk), "kept for less than the minimum");
        assertThrows(ResponseStatusException.class, () -> documents.upload(pdf("a.pdf", "x"), "MEMBER", member, " ", null, null, null, clerk), "no title");
        assertThrows(ResponseStatusException.class, () -> documents.upload(pdf("a.pdf", "x"), "MEMBER", member, "t", null, null, null, as("buyer", "PURCHASER")), "a purchaser may not attach member papers");
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_document", Integer.class));

        // a path in the file name never reaches the store
        Doc d = documents.upload(pdf("../../../etc/evil.pdf", "ok"), "MEMBER", member, "Odd name", null, null, null, clerk);
        assertEquals("evil.pdf", d.fileName());
        assertFalse(jdbc.queryForObject("SELECT object_key FROM ecis_document WHERE id = ?", String.class, d.id()).contains("evil"));
        // the same file twice on one record is refused
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> documents.upload(pdf("again.pdf", "ok"), "MEMBER", member, "Again", null, null, null, clerk)).getStatusCode());
        // a longer keep period than the minimum is fine
        Doc long10 = documents.upload(pdf("other.pdf", "different"), "MEMBER", member, "Kept longer", null, "RESTRICTED", LocalDate.now().plusYears(15), clerk);
        assertEquals(LocalDate.now().plusYears(15), long10.retentionDate());
        assertEquals("RESTRICTED", long10.classification());
    }

    @Test
    void aNewVersionSupersedesAndRemovalWaitsForTheRetentionDate() {
        Authentication clerk = as("clerk", "DOCUMENT_CLERK");
        Doc v1 = upload(clerk, pdf("lease.pdf", "version one"), "INTERNAL");
        Doc v2 = documents.newVersion(v1.id(), pdf("lease-renewed.pdf", "version two"), clerk);
        assertEquals(2, v2.version());
        assertEquals(v1.id(), v2.supersedesId());
        assertEquals("lease.pdf", v1.fileName());
        assertEquals("SUPERSEDED", documents.get(v1.id(), clerk).document().status());
        assertEquals("ACTIVE", v2.status());
        assertEquals(List.of(2, 1), documents.get(v2.id(), clerk).versions().stream().map(Doc::version).toList());
        assertArrayEquals("%PDF-1.7\nversion one".getBytes(StandardCharsets.ISO_8859_1), documents.content(v1.id(), clerk).getBody(), "the old file is still there");
        assertTrue(documents.list("MEMBER", member, null, false, clerk).stream().noneMatch((x) -> x.id() == v1.id()), "the list shows current versions");
        assertTrue(documents.list("MEMBER", member, null, true, clerk).stream().anyMatch((x) -> x.id() == v1.id()), "history shows all");
        assertThrows(ResponseStatusException.class, () -> documents.newVersion(v2.id(), pdf("same.pdf", "version two"), clerk), "same file");
        assertThrows(ResponseStatusException.class, () -> documents.newVersion(v1.id(), pdf("old.pdf", "late"), clerk), "only the current version");
        assertThrows(ResponseStatusException.class, () -> documents.newVersion(v2.id(), new MockMultipartFile("file", "x.png", "image/png", new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1}), clerk), "a different kind of file");

        // not before the retention date, then yes
        assertThrows(ResponseStatusException.class, () -> documents.remove(v2.id(), new Reason("tidy up"), clerk));
        assertThrows(ResponseStatusException.class, () -> documents.remove(v1.id(), new Reason("tidy up"), clerk), "a superseded version is not removed on its own");
        jdbc.update("UPDATE ecis_document SET retention_date = ? WHERE id = ?", LocalDate.now().minusDays(1), v2.id());
        assertThrows(ResponseStatusException.class, () -> documents.remove(v2.id(), new Reason(" "), clerk), "needs a reason");
        assertThrows(ResponseStatusException.class, () -> documents.remove(v2.id(), new Reason("tidy up"), as("officer", "MEMBERSHIP_OFFICER")), "not a manager");
        assertEquals("REMOVED", documents.remove(v2.id(), new Reason("retention period over"), clerk).status());
        assertTrue(documents.list("MEMBER", member, null, false, clerk).stream().noneMatch((x) -> x.id() == v2.id()));
    }

    @Test
    void aFileThatNoLongerMatchesItsHashIsNotReleased() throws Exception {
        Authentication clerk = as("clerk", "DOCUMENT_CLERK");
        Doc d = upload(clerk, pdf("deed.pdf", "original deed"), "INTERNAL");
        String key = jdbc.queryForObject("SELECT object_key FROM ecis_document WHERE id = ?", String.class, d.id());
        Path stored = Path.of("target/test-documents").toAbsolutePath().resolve(key);
        assertTrue(Files.isRegularFile(stored));
        Files.write(stored, "%PDF-1.7\ntampered".getBytes(StandardCharsets.ISO_8859_1));
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> documents.content(d.id(), clerk));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getStatusCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'document.integrity.failed' AND entity_id = ?", Integer.class, d.docNo()));
        // the store never overwrites an existing key
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> new LocalDocumentStore("target/test-documents").put(key, new byte[] {1}));
        assertThrows(java.io.IOException.class, () -> new LocalDocumentStore("target/test-documents").get("../../pom.xml"));
    }
}
