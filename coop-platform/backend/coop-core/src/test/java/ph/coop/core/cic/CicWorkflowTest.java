package ph.coop.core.cic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.LocalDate;
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

/** The whole CIC workflow with the mapping treated as confirmed: generate, approve by a second person, file, submit, result, reconcile, correction. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.audit.seal.enabled=false", "coop.cic.mapping-confirmed=true"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class CicWorkflowTest {

    @Autowired CicController cic;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private final Authentication maker = new TestingAuthenticationToken("acct", "x", "ROLE_ACCOUNTANT");
    private final Authentication checker = new TestingAuthenticationToken("boss", "x", "ROLE_ADMIN");

    @BeforeEach
    void ready() {
        assumeTrue(db.tableExists("ecis_cic_record"), "V052 has not been applied");
        SecurityContextHolder.getContext().setAuthentication(maker);
    }

    private long batch() {
        return ((Number) cic.create(new CicController.NewBatch("Standard", LocalDate.of(2024, 12, 31).toString(), "CO008840", "1.0", "test"), maker).get("id")).longValue();
    }

    private static String status(Map<String, Object> b) {
        return String.valueOf(b.get("status"));
    }

    @Test
    void theBatchGoesThroughEveryStepWithTheRightPeopleAndIsAudited() {
        long id = batch();
        assertEquals("VALIDATED", status(cic.generate(id, 40, maker)));
        assertThrows(ResponseStatusException.class, () -> cic.submit(id, new CicController.Submission("R"), maker), "not approved");
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> cic.approve(id, new CicController.Approval(true, null), maker)).getStatusCode(), "the maker cannot approve");
        Map<String, Object> approved = cic.approve(id, new CicController.Approval(true, "ok"), checker);
        assertEquals("APPROVED", status(approved));
        assertEquals("boss", approved.get("approved_by"));
        assertThrows(ResponseStatusException.class, () -> cic.generate(id, 40, maker), "an approved batch is not rebuilt");
        var file = cic.file(id, checker);
        assertTrue(!file.getHeaders().getFirst("Content-Disposition").contains("PREVIEW_"));
        int sent = CicControllerTest.unzip(file.getBody()).split("\r\n", -1).length - 2;
        assertEquals("SUBMITTED", status(cic.submit(id, new CicController.Submission("CIC-REC-1"), checker)));
        assertThrows(ResponseStatusException.class, () -> cic.reconcile(id, checker), "no result yet");
        assertEquals("RESULT", status(cic.response(id, new CicController.Result(sent - 1, 1, "one rejected"), checker)));
        assertEquals("RECONCILED", status(cic.reconcile(id, checker)));
        Map<String, Object> fix = cic.correction(id, maker);
        assertEquals("DRAFT", status(fix));
        assertEquals(1, ((Number) fix.get("submission_type")).intValue());
        assertEquals(id, ((Number) fix.get("corrects_batch_id")).longValue());
        for (String action : new String[] {"cic.batch.generated", "cic.batch.validated", "cic.batch.approved", "cic.file.downloaded", "cic.batch.submitted", "cic.batch.result", "cic.batch.reconciled", "cic.batch.correction"}) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = ?", Integer.class, action);
            assertTrue(n != null && n > 0, action + " is audited");
        }
    }

    @Test
    void aResultThatDoesNotAddUpIsNotReconciled() {
        long id = batch();
        cic.generate(id, 10, maker);
        cic.approve(id, new CicController.Approval(true, null), checker);
        cic.submit(id, new CicController.Submission("R2"), checker);
        cic.response(id, new CicController.Result(1, 0, null), checker);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> cic.reconcile(id, checker)).getStatusCode());
    }
}
