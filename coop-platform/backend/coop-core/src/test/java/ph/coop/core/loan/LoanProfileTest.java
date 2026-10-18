package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads the profile of real loans (nothing is written). Runs only when DB_PASSWORD is set. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class LoanProfileTest {

    @Autowired LoanProfileController profiles;
    @Autowired JdbcTemplate jdbc;

    @Test
    void profileCarriesTheSetupOfTheSampleScreenLoan() {
        // the loan on the old Loans Profile sample screen: client 28711, back-to-back first-time loan, 36 months monthly at 8%
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT LoanSLT_CODE t, LoanREF_NO r FROM loan WHERE LoanBR_CODE = 1 AND ClientIDLoan = 28711 AND LoanSLC_CODE = 12 ORDER BY LoanTR_DATE DESC LIMIT 1");
        assumeTrue(!rows.isEmpty(), "client 28711 has no loan");
        LoanProfileController.Profile p = profiles.profile(1, 28711, ((Number) rows.get(0).get("t")).intValue(), ((Number) rows.get(0).get("r")).intValue());
        assertEquals("Osting", p.header().lastName());
        assertEquals("01-0028711", p.header().clientNo());
        assertNotNull(p.setup().loanType());
        assertNotNull(p.setup().paymentMode());
        assertTrue(p.setup().principal().signum() > 0);
    }

    @Test
    void storedSchedulesOfRecentLoansMatchTheOldRules() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT ClientIDLoan c, LoanSLT_CODE t, LoanREF_NO r FROM loan WHERE LoanBR_CODE = 1 AND LoanSLC_CODE = 12 AND LoanSLT_CODE IN (48, 49) AND LoanPPMT_MODE = 4
                  AND LoanAMORTYPE = 1 AND LoanTR_DATE >= '2025-09-01' ORDER BY LoanREF_NO DESC LIMIT 60
                """);
        assumeTrue(rows.size() > 10, "not enough recent loans");
        int checked = 0, bad = 0;
        for (Map<String, Object> r : rows) {
            LoanProfileController.ScheduleCheck c = profiles.profile(1, ((Number) r.get("c")).intValue(), ((Number) r.get("t")).intValue(), ((Number) r.get("r")).intValue()).scheduleCheck();
            if (c.checked()) { checked++; if (!c.matches()) bad++; }
        }
        assertTrue(checked > 0);
        assertTrue(bad * 100 <= checked * 5, bad + " of " + checked + " stored schedules differ");
    }
}
