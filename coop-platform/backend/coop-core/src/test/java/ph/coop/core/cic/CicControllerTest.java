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
    void stepsThatNeedTheCicLayoutAnswerNotImplemented() {
        long id = ((Number) cic.create(valid(), who).get("id")).longValue();
        for (var step : List.<java.util.function.LongFunction<Map<String, Object>>>of(cic::members, cic::loans, cic::files, cic::encrypt, cic::excel, cic::transmittal, cic::submit, cic::outstandingBalance)) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> step.apply(id));
            assertEquals(HttpStatus.NOT_IMPLEMENTED, e.getStatusCode());
        }
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> cic.members(-1)).getStatusCode());
    }
}
