package ph.coop.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DocumentRulesTest {

    private static final byte[] PDF = "%PDF-1.7\n%test".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    private static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    private static final byte[] ZIP = {0x50, 0x4B, 0x03, 0x04, 0};

    @Test
    void fileNamesAreCleanedOfFoldersAndOddCharacters() {
        assertEquals("passwd.pdf", DocumentRules.cleanName("../../etc/passwd.pdf"));
        assertEquals("scan.pdf", DocumentRules.cleanName("C:\\Users\\me\\scan.pdf"));
        assertEquals("Valid ID front.png", DocumentRules.cleanName("Valid ID front.png"));
        assertEquals("a_b.pdf", DocumentRules.cleanName("a<>b.pdf"));
        assertEquals("hidden.pdf", DocumentRules.cleanName(".hidden.pdf"));
        assertEquals("a.pdf", DocumentRules.cleanName("a....pdf").replace("..", "."), "runs of dots collapse");
        assertNull(DocumentRules.cleanName("   "));
        assertNull(DocumentRules.cleanName(null));
        String longName = "x".repeat(300) + ".pdf";
        String cleaned = DocumentRules.cleanName(longName);
        assertTrue(cleaned.length() <= 120 && cleaned.endsWith(".pdf"), cleaned.length() + " " + cleaned);
        assertEquals("pdf", DocumentRules.extension("a.b.PDF"));
        assertEquals("", DocumentRules.extension("noext"));
    }

    @Test
    void theContentMustMatchTheExtension() {
        assertTrue(DocumentRules.fileProblems("a.pdf", 100, PDF).isEmpty());
        assertTrue(DocumentRules.fileProblems("a.png", 100, PNG).isEmpty());
        assertTrue(DocumentRules.fileProblems("a.JPG", 100, JPG).isEmpty());
        assertTrue(DocumentRules.fileProblems("a.docx", 100, ZIP).isEmpty());
        assertTrue(DocumentRules.fileProblems("a.xlsx", 100, ZIP).isEmpty());
        assertTrue(DocumentRules.fileProblems("a.csv", 20, "id,name\n1,Ana\n".getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertFalse(DocumentRules.fileProblems("a.pdf", 100, "MZ\u0090\u0000program".getBytes(StandardCharsets.ISO_8859_1)).isEmpty(), "a program renamed to .pdf");
        assertFalse(DocumentRules.fileProblems("a.png", 100, PDF).isEmpty(), "a pdf named .png");
        assertFalse(DocumentRules.fileProblems("a.txt", 10, new byte[] {'a', 0, 'b'}).isEmpty(), "binary data as text");
        assertFalse(DocumentRules.fileProblems("a.exe", 100, PDF).isEmpty());
        assertFalse(DocumentRules.fileProblems("a.pdf", 0, new byte[0]).isEmpty(), "empty");
        assertFalse(DocumentRules.fileProblems("a.pdf", DocumentRules.MAX_BYTES + 1, PDF).isEmpty(), "too big");
        assertFalse(DocumentRules.fileProblems("noext", 100, PDF).isEmpty());
        assertFalse(DocumentRules.fileProblems(null, 100, PDF).isEmpty());
    }

    @Test
    void whoMayReadFollowsTheClassification() {
        Set<String> nobody = Set.of("PURCHASER"), officer = Set.of("MEMBERSHIP_OFFICER"), accountant = Set.of("ACCOUNTANT"), clerk = Set.of("DOCUMENT_CLERK"), admin = Set.of("ADMIN");
        // PUBLIC: anyone signed in
        assertTrue(DocumentRules.canRead("PUBLIC", "MEMBER", nobody, false, false));
        // INTERNAL: staff of that record, accountants, clerks, administrators
        assertTrue(DocumentRules.canRead("INTERNAL", "MEMBER", officer, false, false));
        assertFalse(DocumentRules.canRead("INTERNAL", "MEMBER", nobody, false, false));
        assertTrue(DocumentRules.canRead("INTERNAL", "MEMBER", accountant, false, false));
        assertFalse(DocumentRules.canRead("INTERNAL", "ASSET", officer, false, false), "the membership officer looks after members, not assets");
        // CONFIDENTIAL: clerks and administrators, the uploader and those granted
        assertFalse(DocumentRules.canRead("CONFIDENTIAL", "MEMBER", officer, false, false));
        assertFalse(DocumentRules.canRead("CONFIDENTIAL", "MEMBER", accountant, false, false));
        assertTrue(DocumentRules.canRead("CONFIDENTIAL", "MEMBER", clerk, false, false));
        assertTrue(DocumentRules.canRead("CONFIDENTIAL", "MEMBER", admin, false, false));
        assertTrue(DocumentRules.canRead("CONFIDENTIAL", "MEMBER", officer, true, false), "the uploader");
        assertTrue(DocumentRules.canRead("CONFIDENTIAL", "MEMBER", officer, false, true), "a grant");
        // RESTRICTED: administrators, the uploader and those granted only
        assertFalse(DocumentRules.canRead("RESTRICTED", "MEMBER", clerk, false, false));
        assertTrue(DocumentRules.canRead("RESTRICTED", "MEMBER", admin, false, false));
        assertTrue(DocumentRules.canRead("RESTRICTED", "MEMBER", nobody, false, true));
    }

    @Test
    void uploadingAndManagingAreLimited() {
        assertTrue(DocumentRules.canUpload("MEMBER", Set.of("MEMBERSHIP_OFFICER")));
        assertTrue(DocumentRules.canUpload("ASSET", Set.of("ASSET_CUSTODIAN")));
        assertTrue(DocumentRules.canUpload("LOAN", Set.of("ACCOUNTANT")));
        assertTrue(DocumentRules.canUpload("OTHER", Set.of("DOCUMENT_CLERK")));
        assertFalse(DocumentRules.canUpload("MEMBER", Set.of("PURCHASER")));
        // a member editor may attach proofs to a member, but to nothing else, and that does not let them read other people's documents
        assertTrue(DocumentRules.canUpload("MEMBER", Set.of("PERM_MEMBER_EDIT")));
        assertFalse(DocumentRules.canUpload("ASSET", Set.of("PERM_MEMBER_EDIT")));
        assertFalse(DocumentRules.canRead("INTERNAL", "MEMBER", Set.of("PERM_MEMBER_EDIT"), false, false));
        assertFalse(DocumentRules.canUpload("ASSET", Set.of("MEMBERSHIP_OFFICER")));
        assertTrue(DocumentRules.canManage(Set.of(), true));
        assertTrue(DocumentRules.canManage(Set.of("DOCUMENT_CLERK"), false));
        assertFalse(DocumentRules.canManage(Set.of("ACCOUNTANT"), false));
    }

    @Test
    void retentionDefaultsAndRemovalAfterIt() {
        LocalDate day = LocalDate.of(2026, 10, 29);
        assertEquals(LocalDate.of(2036, 10, 29), DocumentRules.minimumRetention("MEMBER", day));
        assertEquals(LocalDate.of(2031, 10, 29), DocumentRules.minimumRetention("SUPPLIER", day));
        assertEquals("CONFIDENTIAL", DocumentRules.defaultClassification("MEMBER"));
        assertEquals("CONFIDENTIAL", DocumentRules.defaultClassification("LOAN"));
        assertEquals("INTERNAL", DocumentRules.defaultClassification("ASSET"));
        assertNotNull(DocumentRules.removeProblem("ACTIVE", day.plusDays(1), day), "still to be kept");
        assertNull(DocumentRules.removeProblem("ACTIVE", day, day), "on the retention date it may go");
        assertNotNull(DocumentRules.removeProblem("SUPERSEDED", day.minusYears(1), day), "only the current version");
        assertNotNull(DocumentRules.removeProblem("REMOVED", day.minusYears(1), day));
        assertTrue(DocumentRules.knownEntity("MEMBER") && !DocumentRules.knownEntity("CIC"));
    }
}
