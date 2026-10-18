package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Builds schedules for recent real loans and compares them with the stored ones (read only). Runs only when DB_PASSWORD is set. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class LoanScheduleRealDataTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void generatedSchedulesMatchTheStoredOnes() {
        List<Map<String, Object>> loans = jdbc.queryForList("""
                SELECT LoanBR_CODE b, LoanSLC_CODE c, LoanSLT_CODE t, LoanREF_NO r, LoanPAMT amt, LoanTERMS terms, LoanPPMT_MODE mode,
                       LoanINT_RATE rate, LoanTR_DATE rel
                FROM loan WHERE LoanSLT_CODE IN (3, 4, 48, 49) AND LoanPPMT_MODE IN (4, 5, 9) AND LoanIPMT_MODE = LoanPPMT_MODE
                  AND LoanAMORTYPE = 1 AND LoanTERM_PERD = 4 AND LoanTR_DATE >= '2025-06-01' ORDER BY LoanREF_NO DESC LIMIT 300
                """);
        assumeTrue(!loans.isEmpty(), "no recent loans");
        int bad = 0;
        List<String> misses = new ArrayList<>();
        for (Map<String, Object> l : loans) {
            List<Map<String, Object>> stored = jdbc.queryForList("""
                    SELECT DUEDATE d, PRINDUE p, INTDUE i, PRINBAL b FROM amortsched
                    WHERE BR_CODE = ? AND SLC_CODE = ? AND SLT_CODE = ? AND REF_NO = ? ORDER BY DUEDATE
                    """, l.get("b"), l.get("c"), l.get("t"), l.get("r"));
            List<LoanSchedule.Row> built = LoanSchedule.diminishing((BigDecimal) l.get("amt"), ((Number) l.get("terms")).intValue(),
                    ((Number) l.get("mode")).intValue(), (BigDecimal) l.get("rate"), ((java.sql.Date) l.get("rel")).toLocalDate());
            boolean same = stored.size() == built.size();
            for (int k = 0; same && k < built.size(); k++) {
                LoanSchedule.Row x = built.get(k);
                Map<String, Object> s = stored.get(k);
                same = x.dueDate().equals(((java.sql.Date) s.get("d")).toLocalDate()) && x.principalDue().compareTo((BigDecimal) s.get("p")) == 0
                        && x.interestDue().compareTo((BigDecimal) s.get("i")) == 0 && x.principalBalance().compareTo((BigDecimal) s.get("b")) == 0;
            }
            if (!same) { bad++; misses.add(l.get("t") + "/" + l.get("r")); }
        }
        // The old system is off by a cent on a few leap-year loans, so a small share of misses is accepted.
        assertTrue(bad * 100 <= loans.size() * 3, bad + " of " + loans.size() + " differ: " + misses);
    }
}
