package ph.coop.core.accounting.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import ph.coop.core.accounting.journal.JournalController.LineIn;
import ph.coop.core.accounting.journal.JournalController.NewJournal;

/**
 * Posts a real journal through the real code and checks the General Journal it writes into the legacy ledger
 * (transsum module 1 / type 1, transdt, gltrans), then rolls everything back so nothing stays behind. It uses two
 * existing enabled legacy users as maker and checker without changing them, and a made-up period in year 2099
 * (also rolled back). Runs only when DB_PASSWORD is set; needs the V001, V008, V009 and V010 migrations and the
 * V006 ledger grants.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class JournalPostingTest {

    private static final LocalDate DAY = LocalDate.of(2099, 1, 15);
    private static final int YEAR = 2099;

    @Autowired JournalController journals;
    @Autowired PeriodController periods;
    @Autowired JdbcTemplate jdbc;

    private int branch;
    private int makerId, checkerId;
    private String maker, checker;

    @BeforeEach
    void setUp() {
        List<Map<String, Object>> u = jdbc.queryForList("""
                SELECT AccessID, AccessUserName, AccessBR_CODE FROM access
                WHERE COALESCE(AccessIsDisabled, 0) = 0 AND AccessLevel <> 'SUPERADMIN' AND AccessBR_CODE = 1 AND COALESCE(AccessDepartment, 0) > 0
                ORDER BY AccessID LIMIT 2
                """);
        assumeTrue(u.size() == 2, "need two enabled legacy users in branch 1");
        makerId = ((Number) u.get(0).get("AccessID")).intValue();
        maker = (String) u.get(0).get("AccessUserName");
        checkerId = ((Number) u.get(1).get("AccessID")).intValue();
        checker = (String) u.get(1).get("AccessUserName");
        branch = ((Number) u.get(0).get("AccessBR_CODE")).intValue();
        Integer accounts = jdbc.queryForObject("SELECT COUNT(*) FROM coa WHERE COABR_CODE = ? AND COAID IN ('50105', '2010201')", Integer.class, branch);
        assumeTrue(accounts != null && accounts == 2, "test accounts 50105 and 2010201 not in the chart");
        periods.create(new PeriodController.NewPeriod(branch, "ITEST 2099-01", LocalDate.of(YEAR, 1, 1), LocalDate.of(YEAR, 1, 31)), as(maker));
    }

    private static Authentication as(String name) {
        Authentication a = new TestingAuthenticationToken(name, null, "ROLE_ACCOUNTANT");
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    private long newJournal(String ref, BigDecimal amount) {
        return journals.create(new NewJournal(branch, ref, DAY, "integration test", List.of(
                new LineIn("50105", amount, BigDecimal.ZERO), new LineIn("2010201", BigDecimal.ZERO, amount))), as(maker)).id();
    }

    @Test
    void postingWritesAGeneralJournalIntoTheLegacyLedger() {
        long id = newJournal("ITEST-1", new BigDecimal("1.00"));
        journals.submit(id, as(maker));
        journals.approve(id, as(checker));
        journals.post(id, as(checker));

        var sum = jdbc.queryForMap("SELECT tr_year, tr_ctlno, status FROM ecis_journal WHERE id = ?", id);
        assertEquals("POSTED", sum.get("status"));
        assertEquals(YEAR, ((Number) sum.get("tr_year")).intValue());
        long ctl = ((Number) sum.get("tr_ctlno")).longValue();

        Map<String, Object> header = jdbc.queryForMap(
                "SELECT TR_DOCNO, TR_DATE, IS_FO, EXPLANATION FROM transsum WHERE TR_BRCODE = ? AND TR_YEAR = ? AND TR_MODULE = 1 AND TR_CODE = 1 AND TR_CTLNO = ?", branch, YEAR, ctl);
        assertEquals(String.format("*%07d", ctl), header.get("TR_DOCNO"));
        assertEquals(DAY.toString(), header.get("TR_DATE").toString());
        assertEquals(0, ((Number) header.get("IS_FO")).intValue());

        List<Map<String, Object>> lines = jdbc.queryForList("""
                SELECT DTSEQ_NO, DTACCT_CODE, DTAMT, DTENCDBY, DTAPPRVBY, DTPOSTBY FROM transdt
                WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = 1 AND DTTR_CODE = 1 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO
                """, branch, YEAR, ctl);
        assertEquals(2, lines.size());
        assertEquals("50105", lines.get(0).get("DTACCT_CODE"));
        assertEquals(0, new BigDecimal("1.00").compareTo((BigDecimal) lines.get(0).get("DTAMT")), "debit is positive");
        assertEquals("2010201", lines.get(1).get("DTACCT_CODE"));
        assertEquals(0, new BigDecimal("-1.00").compareTo((BigDecimal) lines.get(1).get("DTAMT")), "credit is negative");
        for (Map<String, Object> l : lines) {
            assertEquals(makerId, ((Number) l.get("DTENCDBY")).intValue());
            assertEquals(checkerId, ((Number) l.get("DTAPPRVBY")).intValue());
            assertEquals(checkerId, ((Number) l.get("DTPOSTBY")).intValue());
        }

        List<Map<String, Object>> gl = jdbc.queryForList("""
                SELECT GLTransACCT_CODE, GLTransDR_AMT, GLTransCR_AMT FROM gltrans
                WHERE GLTransBR_CODE = ? AND GLTransTR_DATE = ? AND GLTransTR_CODE = 1 ORDER BY GLTransACCT_CODE
                """, branch, DAY);
        assertEquals(2, gl.size());
        BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
        for (Map<String, Object> g : gl) {
            dr = dr.add((BigDecimal) g.get("GLTransDR_AMT"));
            cr = cr.add((BigDecimal) g.get("GLTransCR_AMT"));
        }
        assertEquals(0, dr.add(cr).signum(), "ledger debits and credits cancel out");
        assertEquals(0, new BigDecimal("1.00").compareTo(dr));

        Integer audit = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'JOURNAL_LEDGER_POST' AND entity_id = ?",
                Integer.class, branch + "/" + YEAR + "/1/1/" + ctl);
        assertEquals(1, audit);
    }

    @Test
    void aReversalPostsAsASecondJournalAndNetsToZero() {
        long id = newJournal("ITEST-2", new BigDecimal("5.50"));
        journals.submit(id, as(maker));
        journals.approve(id, as(checker));
        journals.post(id, as(checker));
        long rev = journals.reverse(id, new JournalController.Reason("integration test"), as(maker)).id();
        journals.submit(rev, as(maker));
        journals.approve(rev, as(checker));
        journals.post(rev, as(checker));

        // The reversal is dated the day it is made, so it is its own General Journal in that year, with every line swapped.
        var link = jdbc.queryForMap("SELECT reverses_id, tr_year, tr_ctlno FROM ecis_journal WHERE id = ?", rev);
        assertEquals(id, ((Number) link.get("reverses_id")).longValue());
        List<Map<String, Object>> lines = jdbc.queryForList("""
                SELECT DTACCT_CODE, DTAMT FROM transdt
                WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = 1 AND DTTR_CODE = 1 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO
                """, branch, ((Number) link.get("tr_year")).intValue(), ((Number) link.get("tr_ctlno")).longValue());
        assertEquals(2, lines.size());
        assertEquals("50105", lines.get(0).get("DTACCT_CODE"));
        assertEquals(0, new BigDecimal("-5.50").compareTo((BigDecimal) lines.get(0).get("DTAMT")), "the debit became a credit");
        assertEquals(0, new BigDecimal("5.50").compareTo((BigDecimal) lines.get(1).get("DTAMT")), "the credit became a debit");
        Integer originals = jdbc.queryForObject("SELECT COUNT(*) FROM transsum WHERE TR_BRCODE = ? AND TR_YEAR = ? AND TR_MODULE = 1 AND TR_CODE = 1", Integer.class, branch, YEAR);
        assertEquals(1, originals);
    }

    @Test
    void theCreatorCannotApproveTheirOwnJournal() {
        long id = newJournal("ITEST-3", new BigDecimal("1.00"));
        journals.submit(id, as(maker));
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> journals.approve(id, as(maker)));
        assertEquals(409, e.getStatusCode().value());
    }

    @Test
    void aJournalOutsideAnOpenPeriodIsNotPosted() {
        long id = journals.create(new NewJournal(branch, "ITEST-4", LocalDate.of(2098, 6, 1), "no period", List.of(
                new LineIn("50105", BigDecimal.ONE, BigDecimal.ZERO), new LineIn("2010201", BigDecimal.ZERO, BigDecimal.ONE))), as(maker)).id();
        journals.submit(id, as(maker));
        journals.approve(id, as(checker));
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> journals.post(id, as(checker)));
        assertEquals(409, e.getStatusCode().value());
        assertNotNull(e.getReason());
    }
}
