package ph.coop.core.cic;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The CIC submission batch: the header record, the checks on it, and the steps that wait for the CIC file layout. Against the database and rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.audit.seal.enabled=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class CicControllerTest {

    @Autowired CicController cic;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private final Authentication who = new TestingAuthenticationToken("acct", "x", "ROLE_ACCOUNTANT");

    @BeforeEach
    void ready() {
        assumeTrue(db.tableExists("ecis_cic_batch"), "V047 has not been applied");
        SecurityContextHolder.getContext().setAuthentication(who);
    }

    private CicController.NewBatch valid() {
        return new CicController.NewBatch("Standard", LocalDate.now().toString(), "co008840", "1.0", "January data");
    }

    /** The zip holds one text file named like the zip, {provider}_CSDF_{yyyyMMddHHmmss}.txt, at its root; returns the text. */
    static String unzip(byte[] zip) {
        try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry txt = z.getNextEntry();
            assertTrue(txt.getName().matches("CO008840_CSDF_\\d{14}\\.txt"), txt.getName());
            return new String(z.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void aBatchGetsItsHeaderRowAndIsAudited() {
        Map<String, Object> b = cic.create(valid(), who);
        assertTrue(String.valueOf(b.get("ref")).startsWith("CIC-" + LocalDate.now().getYear() + "-"));
        @SuppressWarnings("unchecked") Map<String, Object> h = (Map<String, Object>) b.get("header");
        assertEquals("HD", h.get("recordType"));
        assertEquals("CO008840", h.get("providerCode"));
        assertEquals("0", h.get("submissionType"));
        assertEquals(8, String.valueOf(h.get("fileReferenceDate")).length());
        assertEquals("January data", h.get("providerComments"));
        assertNotNull(b.get("logs"));
        Integer audited = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'cic.batch.created' AND entity_id = ?", Integer.class, b.get("ref"));
        assertEquals(1, audited);
        assertTrue(cic.list().stream().anyMatch((r) -> b.get("ref").equals(r.get("ref"))));
    }

    @Test
    void aHeaderThatIsMissingOrWrongIsRefused() {
        assertThrows(ResponseStatusException.class, () -> cic.create(new CicController.NewBatch("Weekly", null, "CO008840", "1.0", null), who), "unknown submission type");
        assertThrows(ResponseStatusException.class, () -> cic.create(new CicController.NewBatch("Standard", null, "", "1.0", null), who), "provider code");
        assertThrows(ResponseStatusException.class, () -> cic.create(new CicController.NewBatch("Standard", null, "CO008840", "one", null), who), "version");
        assertThrows(ResponseStatusException.class, () -> cic.create(new CicController.NewBatch("Standard", LocalDate.now().plusDays(1).toString(), "CO008840", "1.0", null), who), "future cut-off");
        assertThrows(ResponseStatusException.class, () -> cic.create(new CicController.NewBatch("Standard", "31/10/2026", "CO008840", "1.0", null), who), "date format");
    }

    @Test
    void stepsWhoseLayoutIsNotKnownAnswerNotImplemented() {
        long id = ((Number) cic.create(valid(), who).get("id")).longValue();
        for (var step : List.<java.util.function.LongFunction<Map<String, Object>>>of(cic::encrypt, cic::excel, cic::transmittal, cic::outstandingBalance)) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> step.apply(id));
            assertEquals(HttpStatus.NOT_IMPLEMENTED, e.getStatusCode());
        }
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> cic.encrypt(-1)).getStatusCode());
    }

    @Test
    void aTrialBatchIsGeneratedAndCheckedButNotApprovedWhileTheMappingIsUnconfirmed() {
        long id = ((Number) cic.create(valid(), who).get("id")).longValue();
        Map<String, Object> b = cic.generate(id, 25, who);
        assertEquals("VALIDATED", b.get("status"));
        int contracts = ((Number) b.get("installment_contracts")).intValue() + ((Number) b.get("other_contracts")).intValue();
        assertEquals(25, contracts);
        assertTrue(((Number) b.get("subjects")).intValue() > 0);
        assertEquals(false, b.get("mappingConfirmed"));
        var f = cic.findings(id, null);
        assertTrue(f.stream().anyMatch((x) -> "WARNING".equals(x.get("severity")) && String.valueOf(x.get("field")).startsWith("Mapping")), "the unverified columns are listed");
        Map<String, Object> page = cic.records(id, "CI", null, 0, 10);
        assertTrue(((Number) page.get("total")).longValue() + ((Number) cic.records(id, "CN", null, 0, 10).get("total")).longValue() == 25);
        var file = cic.file(id, who);
        assertTrue(file.getHeaders().getFirst("Content-Disposition").contains("PREVIEW_CO008840_CSDF_"));
        String text = unzip(file.getBody());
        String[] lines = text.split("\r\n", -1);
        assertTrue(lines[0].startsWith("HD|CO008840|"));
        assertTrue(lines[lines.length - 1].startsWith("FT|CO008840|"));
        assertEquals(String.valueOf(lines.length - 2), lines[lines.length - 1].split("\\|")[3], "the footer counts the records between header and footer");
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> cic.approve(id, new CicController.Approval(true, null), new TestingAuthenticationToken("boss", "x", "ROLE_ADMIN")));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertThrows(ResponseStatusException.class, () -> cic.submit(id, new CicController.Submission("R1"), who), "not approved");
    }
}
