package ph.coop.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The audit log viewer filters, pages and escapes search text. Writes its own rows inside a transaction that is rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
@WithMockUser(roles = "ADMIN")
class AuditLogControllerTest {

    @Autowired AuditLogController log;
    @Autowired JdbcTemplate jdbc;

    private void add(String user, String action, String entity, String id, String reason) {
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, reason) VALUES (NOW(), ?, ?, ?, ?, ?)", user, action, entity, id, reason);
    }

    @Test void filtersByPersonKindActionAndText() {
        add("zz_tester_a", "TEST_ACT", "TEST_ENT", "k-1", "100% sure");
        add("zz_tester_b", "TEST_ACT", "TEST_ENT", "k-2", "plain");
        assertEquals(2, log.list(null, "TEST_ENT", "TEST_ACT", null, null, null, 0, 50).totalElements());
        assertEquals(1, log.list("zz_tester_a", "TEST_ENT", null, null, null, null, 0, 50).totalElements());
        // % in the search text is a plain character, not a wildcard
        assertEquals(1, log.list(null, "TEST_ENT", null, "100%", null, null, 0, 50).totalElements());
        assertEquals(0, log.list(null, "TEST_ENT", null, "1%0", null, null, 0, 50).totalElements());
        assertTrue(log.facets().entities().contains("TEST_ENT"));
    }

    @Test void pagesNewestFirstAndFiltersByDate() {
        add("zz_tester_a", "TEST_ACT", "TEST_ENT", "first", null);
        add("zz_tester_a", "TEST_ACT", "TEST_ENT", "second", null);
        var p = log.list(null, "TEST_ENT", null, null, LocalDate.now(), LocalDate.now(), 0, 1);
        assertEquals(2, p.totalElements());
        assertEquals("second", p.content().get(0).entityId());
        assertEquals("first", log.list(null, "TEST_ENT", null, null, null, null, 1, 1).content().get(0).entityId());
        assertEquals(0, log.list(null, "TEST_ENT", null, null, LocalDate.now().plusDays(1), null, 0, 50).totalElements());
    }

    @Test void refusesABadPageOrDateRange() {
        assertThrows(ResponseStatusException.class, () -> log.list(null, null, null, null, null, null, 0, 0));
        assertThrows(ResponseStatusException.class, () -> log.list(null, null, null, null, null, null, 0, 500));
        assertThrows(ResponseStatusException.class, () -> log.list(null, null, null, null, LocalDate.now(), LocalDate.now().minusDays(1), 0, 50));
    }

    @Test @WithMockUser(roles = "USER") void onlyAdministratorsMayRead() {
        assertThrows(AccessDeniedException.class, () -> log.list(null, null, null, null, null, null, 0, 50));
        assertThrows(AccessDeniedException.class, () -> log.facets());
    }
}
