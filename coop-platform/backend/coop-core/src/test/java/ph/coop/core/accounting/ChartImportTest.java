package ph.coop.core.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.member.SpreadsheetReader;

/** Uploading a list of accounts into the chart: checked first, added like accounts made by hand, audited, administrators only. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
@WithMockUser(username = "tester", roles = "ADMIN")
class ChartImportTest {

    @Autowired ChartImportController imports;
    @Autowired JdbcTemplate jdbc;

    private static final String LIST = """
            Account number,Account name,Kind,Under,Journal entries
            100097,Test upload group,Heading,1000,
            1000971,Test upload account,Account,100097,Yes
            1000972,Test upload controlled,,,No
            10001,Cash in vault,,,
            """;

    @BeforeEach
    void ready() {
        assumeTrue(!jdbc.queryForList("SELECT 1 FROM access WHERE AccessUserName = 'tester'").isEmpty(), "no tester account");
        assumeTrue(new ph.coop.core.db.Db(jdbc).canInsertInto("coa"), "V022 has not been applied");
        assumeTrue(!jdbc.queryForList("SELECT 1 FROM coa WHERE COABR_CODE = 1 AND COAID = '1000' AND COAacct_tag = 'S'").isEmpty(), "no heading 1000 in this chart");
    }

    private Authentication me() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private static MockMultipartFile csv(String text) {
        return new MockMultipartFile("file", "accounts.csv", "text/csv", text.getBytes(StandardCharsets.UTF_8));
    }

    @Test void theListIsCheckedWithoutSavingAnything() throws Exception {
        var p = imports.preview(csv(LIST), me());
        assertEquals(4, p.total());
        assertEquals(3, p.ready());
        assertEquals(1, p.existing());
        assertEquals(0, p.errors());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM coa WHERE COAID IN ('100097', '1000971', '1000972')", Integer.class));
    }

    @Test void theNewAccountsAreAddedLikeOnesMadeByHandAndAudited() throws Exception {
        var d = imports.upload(csv(LIST), "new product line", false, me());
        assertEquals(3, d.added());
        assertEquals(1, d.existing());
        var h = jdbc.queryForMap("SELECT COAacct_tag, COAacct_sum FROM coa WHERE COAID = '100097'");
        assertEquals("S", h.get("COAacct_tag"));
        assertEquals("1000", h.get("COAacct_sum"));
        var a = jdbc.queryForMap("SELECT COAacct_tag, COAacct_sum, COAconso_acct, COAcanGLOnly, COAacct_type FROM coa WHERE COAID = '1000971'");
        assertEquals("D", a.get("COAacct_tag"));
        assertEquals("100097", a.get("COAacct_sum"));
        assertEquals("1000971", a.get("COAconso_acct"));
        assertEquals(1, ((Number) a.get("COAcanGLOnly")).intValue());
        assertEquals(1, ((Number) a.get("COAacct_type")).intValue());
        // with no Under it went to the longest heading its number starts with, and it is controlled by a member ledger
        var c = jdbc.queryForMap("SELECT COAacct_sum, COAcanGLOnly FROM coa WHERE COAID = '1000972'");
        assertEquals("100097", c.get("COAacct_sum"));
        assertEquals(0, ((Number) c.get("COAcanGLOnly")).intValue());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'chart.account.create' AND entity = 'coa' AND entity_id IN ('1/100097', '1/1000971', '1/1000972') "
            + "AND reason = 'new product line (uploaded from accounts.csv)'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'chart.import' AND entity = 'coa' AND entity_id = '1/upload' AND reason = 'new product line'", Integer.class));
        // sending the same list again adds nothing
        assertThrows(ResponseStatusException.class, () -> imports.upload(csv(LIST), "again", false, me()));
    }

    @Test void aRowWithAProblemStopsTheUploadUnlessItIsLeftOut() throws Exception {
        String bad = LIST + "9000971,Bad first digit,,,\n";
        assertThrows(ResponseStatusException.class, () -> imports.upload(csv(bad), "try", false, me()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM coa WHERE COAID = '100097'", Integer.class));
        var d = imports.upload(csv(bad), "try", true, me());
        assertEquals(3, d.added());
        assertEquals(1, d.leftOut());
    }

    @Test void aReasonIsRequiredAndTheFileMustHaveAccounts() {
        assertThrows(ResponseStatusException.class, () -> imports.upload(csv(LIST), " ", false, me()));
        assertThrows(ResponseStatusException.class, () -> imports.preview(csv("Account number,Account name\n"), me()));
        assertThrows(ResponseStatusException.class, () -> imports.preview(csv("Colour,Size\nred,big\n"), me()));
    }

    @Test void theBlankListIsAWorkbookTheReaderUnderstands() {
        byte[] book = imports.template().getBody();
        assertTrue(book != null && book.length > 500);
        // the Accounts sheet holds only the column names, so an untouched blank list adds nothing
        assertTrue(SpreadsheetReader.read("chart-of-accounts-template.xlsx", book).isEmpty());
    }

    @Test @WithMockUser(username = "tester", roles = "ACCOUNTANT") void accountantsMayNotUploadAList() {
        assertThrows(AccessDeniedException.class, () -> imports.preview(csv(LIST), me()));
        assertThrows(AccessDeniedException.class, () -> imports.upload(csv(LIST), "try", false, me()));
    }
}
