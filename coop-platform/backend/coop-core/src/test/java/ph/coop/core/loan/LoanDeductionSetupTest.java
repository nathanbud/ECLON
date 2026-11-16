package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.loan.LoanDeductionRules.Form;
import ph.coop.core.loan.LoanDeductionSetupController.Line;

/** A loan type's deduction lines: listed in words, added, changed and removed with a reason, the net proceeds line kept, all audited. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
@WithMockUser(username = "tester", roles = "ADMIN")
class LoanDeductionSetupTest {

    private static final int TYPE = 3; // Back to back First Time Loan

    @Autowired LoanDeductionSetupController deductions;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void ready() {
        assumeTrue(!jdbc.queryForList("SELECT 1 FROM access WHERE AccessUserName = 'tester'").isEmpty(), "no tester account");
        assumeTrue(jdbc.queryForObject("SELECT COUNT(*) FROM loansldeduc WHERE BR_CODE = 1 AND SLC_CODE = 12 AND SLT_CODE = ?", Integer.class, TYPE) > 2, "loan type 3 has no deductions here");
        ph.coop.core.db.Db db = new ph.coop.core.db.Db(jdbc);
        assumeTrue(db.canInsertInto("loansldeduc") && db.canUpdateColumn("loansldeduc", "DESCR"), "V020 (PostgreSQL) or V057 (MySQL) has not been applied");
    }

    private Authentication me() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private static Form form(String description, int order, String kind, Integer cls, Integer type, Integer event, String account, String reason) {
        return new Form(description, order, kind, cls, type, event, account, false, reason);
    }

    private Line named(List<Line> lines, String description) {
        return lines.stream().filter((l) -> l.description().equals(description)).findFirst().orElseThrow();
    }

    @Test void theLinesAreListedWithWhereEachGoes() {
        List<Line> lines = deductions.list(TYPE, me());
        Line net = lines.get(lines.size() - 1);
        assertEquals("NET_PROCEEDS", net.kind());
        assertFalse(net.removable());
        assertFalse(net.editableTarget());
        Line share = named(lines, "Share Capital");
        assertEquals("MEMBER_ACCOUNT", share.kind());
        assertTrue(share.target().startsWith("The member's "), share.target());
        assertEquals("LOAN_CHARGE", named(lines, "Filing fee/Service fee").kind());
        Line insurance = named(lines, "CLIMBS Loan Insurance");
        assertEquals("CHART_ACCOUNT", insurance.kind());
        assertTrue(insurance.target().contains("2010503"));
        var o = deductions.options(TYPE, me());
        assertTrue(o.memberTypes().stream().anyMatch((t) -> t.classId() == 31 && t.typeCode() == 1));
        assertTrue(o.charges().stream().anyMatch((c) -> c.event() == 31));
        assertTrue(o.charges().stream().noneMatch((c) -> c.event() == 11), "principal is not a charge");
    }

    @Test void aLineIsAddedChangedAndRemoved() {
        int before = deductions.list(TYPE, me()).size();
        List<Line> added = deductions.add(TYPE, form("Test mortuary aid", 8, "CHART_ACCOUNT", null, null, null, "2010504", "new benefit"), me());
        assertEquals(before + 1, added.size());
        Line line = named(added, "Test mortuary aid");
        assertEquals("CHART_ACCOUNT", line.kind());
        assertEquals(8, line.order());
        assertEquals("PROMPT_AMOUNT (\"Enter Amount for Test mortuary aid: \", \"0\", false)", line.formula());
        assertEquals("NET_PROCEEDS", added.get(added.size() - 1).kind(), "net proceeds stays last");
        var row = jdbc.queryForMap("SELECT DeducGroup, IsAutoLoad FROM loansldeduc WHERE ID = ?", line.id());
        assertEquals(jdbc.queryForObject("SELECT MIN(DeducGroup) FROM loansldeduc WHERE SLC_CODE = 12 AND SLT_CODE = ? AND ID <> ?", Integer.class, TYPE, line.id()), ((Number) row.values().toArray()[0]).intValue());
        assertEquals(1, ((Number) row.values().toArray()[1]).intValue());

        // it now goes to the member's share capital instead, under another name and order; the formula is left alone
        List<Line> changed = deductions.change(TYPE, line.id(), form("Test capital build-up", 9, "MEMBER_ACCOUNT", 31, 1, null, null, "goes to share capital"), me());
        Line now = named(changed, "Test capital build-up");
        assertEquals("MEMBER_ACCOUNT", now.kind());
        assertEquals(31, now.classId());
        assertEquals(9, now.order());
        assertEquals(line.formula(), now.formula());
        // saving the same again writes nothing
        long audits = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action LIKE 'loan.type.deduction.%'", Long.class);
        deductions.change(TYPE, line.id(), form("Test capital build-up", 9, "MEMBER_ACCOUNT", 31, 1, null, null, "nothing"), me());
        assertEquals(audits, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action LIKE 'loan.type.deduction.%'", Long.class));

        assertEquals(before, deductions.remove(TYPE, line.id(), "not offered after all", me()).size());
        List<String> actions = jdbc.queryForList("SELECT action FROM ecis_audit_log WHERE entity = 'loan_type' AND entity_id = '1/12/3' AND action LIKE 'loan.type.deduction.%' ORDER BY id", String.class);
        assertEquals(List.of("loan.type.deduction.add", "loan.type.deduction.change", "loan.type.deduction.remove"), actions.subList(actions.size() - 3, actions.size()));
    }

    @Test void whatALinePointsAtMustExist() {
        assertThrows(ResponseStatusException.class, () -> deductions.add(TYPE, form("Test bad", 8, "CHART_ACCOUNT", null, null, null, "201", "try"), me()), "a heading");
        assertThrows(ResponseStatusException.class, () -> deductions.add(TYPE, form("Test bad", 8, "CHART_ACCOUNT", null, null, null, "2999999", "try"), me()));
        assertThrows(ResponseStatusException.class, () -> deductions.add(TYPE, form("Test bad", 8, "MEMBER_ACCOUNT", 31, 250, null, null, "try"), me()), "no such share type");
        assertThrows(ResponseStatusException.class, () -> deductions.add(TYPE, form("Test bad", 8, "LOAN_CHARGE", null, null, 77, null, "try"), me()), "the type has no link for it");
        assertThrows(ResponseStatusException.class, () -> deductions.add(TYPE, form("share capital", 8, "CHART_ACCOUNT", null, null, null, "2010504", "try"), me()), "a description is used once");
        assertThrows(ResponseStatusException.class, () -> deductions.add(TYPE, form("Test bad", 99, "CHART_ACCOUNT", null, null, null, "2010504", "try"), me()), "99 is net proceeds");
        assertThrows(ResponseStatusException.class, () -> deductions.add(TYPE, form("Test bad", 8, "CHART_ACCOUNT", null, null, null, "2010504", " "), me()), "a reason is needed");
        assertThrows(ResponseStatusException.class, () -> deductions.list(250, me()), "no such loan type");
    }

    @Test void theNetProceedsLineStaysAndKeepsItsPlace() {
        List<Line> lines = deductions.list(TYPE, me());
        Line net = lines.get(lines.size() - 1);
        assertThrows(ResponseStatusException.class, () -> deductions.remove(TYPE, net.id(), "try", me()));
        // only its wording can change; a kind sent with it is ignored
        List<Line> changed = deductions.change(TYPE, net.id(), form("Net amount released", 5, "CHART_ACCOUNT", null, null, null, "2010504", "clearer wording"), me());
        Line now = changed.get(changed.size() - 1);
        assertEquals("Net amount released", now.description());
        assertEquals("NET_PROCEEDS", now.kind());
        assertEquals(net.order(), now.order());
    }

    @Test @WithMockUser(username = "tester", roles = "ACCOUNTANT") void onlyAdministratorsMayChangeDeductions() {
        assertThrows(AccessDeniedException.class, () -> deductions.list(TYPE, me()));
        assertThrows(AccessDeniedException.class, () -> deductions.remove(TYPE, 1, "try", me()));
    }
}
