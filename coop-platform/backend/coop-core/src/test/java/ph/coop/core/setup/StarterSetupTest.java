package ph.coop.core.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;
import ph.coop.core.setup.StarterSetupController.Load;

/** Starter setup against the coop database, rolled back: the lists there are complete, a missing row is added and audited, a row whose link is missing is left out with its reason. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class StarterSetupTest {

    @Autowired StarterSetupController setup;
    @Autowired JdbcTemplate jdbc;
    @Autowired Db db;

    private static Authentication admin() {
        Authentication a = new TestingAuthenticationToken("tester", "x", "ROLE_ADMIN");
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    @BeforeEach
    void ready() {
        assumeTrue(db.canInsertInto("term", "inv_adjreason"), "PostgreSQL V034 (MySQL V071) has not been applied");
    }

    @Test
    void theCoopDatabaseHasEveryStandardList() {
        admin();
        var s = setup.status();
        assertEquals(setup.catalog().size(), s.lists().size());
        for (var l : s.lists()) {
            assertEquals(null, l.problem(), l.table());
            assertEquals(0, l.missing(), l.table() + " is complete on coop (the package was taken from it)");
            assertTrue(l.here() >= l.inPackage(), l.table());
        }
        assertEquals(0, s.missing());
        assertTrue(s.canLoad());
    }

    @Test
    void aMissingRowIsAddedAndAuditedWhileOneWithoutItsBranchIsLeftOut() {
        Map<String, List<List<String>>> pack = Map.of(
            "term", List.of(List.of("termid", "termdesc", "termoldid"), List.of("1", "Days", "D"), List.of("999", "Fortnights (test)", "F")),
            "inv_adjreason", List.of(List.of("id", "desc1", "desc2", "br_code"), List.of("990", "Lost (test)", "Lost", "77")));
        var r = setup.load(new Load("starter setup test", List.of("term", "inv_adjreason")), admin(), pack);
        assertEquals(1, r.added(), "only the missing term is added; Days is there already and is not changed");
        assertEquals(List.of("term"), r.loaded());
        assertEquals("Fortnights (test)", jdbc.queryForObject("SELECT termdesc FROM term WHERE termid = 999", String.class));
        assertEquals("Days", jdbc.queryForObject("SELECT termdesc FROM term WHERE termid = 1", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'setup.starter.load' AND entity_id = 'term' AND reason = 'starter setup test'", Integer.class));

        var adj = r.status().lists().stream().filter((l) -> l.table().equals("inv_adjreason")).findFirst().orElseThrow();
        assertEquals(1, adj.leftOut());
        assertTrue(adj.leftOutWhy().get(0).contains("77") && adj.leftOutWhy().get(0).contains("branches"), adj.leftOutWhy().toString());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inv_adjreason WHERE id = 990", Integer.class));

        var again = setup.load(new Load("starter setup test", List.of("term")), admin(), pack);
        assertEquals(0, again.added(), "loading twice adds nothing the second time");
    }

    @Test
    void aListLaidOutDifferentlyIsNotLoadedAndAReasonIsNeeded() {
        Map<String, List<List<String>>> pack = Map.of("gender", List.of(List.of("genderid", "gendername"), Arrays.asList("9", "Other")));
        var r = setup.load(new Load("starter setup test", List.of("gender")), admin(), pack);
        assertEquals(0, r.added());
        String problem = r.status().lists().stream().filter((l) -> l.table().equals("gender")).findFirst().orElseThrow().problem();
        assertNotNull(problem);
        assertTrue(problem.contains("gendername"), problem);

        var e = assertThrows(ResponseStatusException.class, () -> setup.load(new Load(" ", null), admin(), pack));
        assertTrue(e.getReason().startsWith("Give a reason"));
        var e2 = assertThrows(ResponseStatusException.class, () -> setup.load(new Load("why not", List.of("access")), admin(), pack));
        assertTrue(e2.getReason().contains("not a standard list"), "only the standard lists can be loaded, never the users or anything else");
    }
}
