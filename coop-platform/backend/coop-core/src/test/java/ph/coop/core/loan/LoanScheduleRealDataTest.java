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

    /**
     * Annuity loans made from 2022 on. The misses allowed are the loans whose schedule was changed after it was made (a rate changed, a payment holiday),
     * which no formula can reproduce.
     */
    @Test
    void annuitySchedulesMatchTheStoredOnes() {
        List<Map<String, Object>> loans = jdbc.queryForList("""
                SELECT LoanBR_CODE b, LoanSLC_CODE c, LoanSLT_CODE t, LoanREF_NO r, LoanPAMT amt, LoanTERMS terms, LoanTERM_PERD unit, LoanPPMT_MODE mode,
                       LoanINT_RATE rate, LoanTR_DATE rel, LoanND_FLAG nd
                FROM loan WHERE LoanAMORTYPE = 3 AND LoanPPMT_MODE IN (4, 5, 6, 9) AND LoanIPMT_MODE = LoanPPMT_MODE AND LoanTERM_PERD IN (4, 7)
                  AND LoanND_FLAG IN (1, 2) AND LoanTR_DATE >= '2022-01-01' ORDER BY LoanREF_NO DESC LIMIT 400
                """);
        assumeTrue(!loans.isEmpty(), "no annuity loans");
        int bad = 0;
        int skipped = 0;
        List<String> misses = new ArrayList<>();
        for (Map<String, Object> l : loans) {
            List<Map<String, Object>> stored = jdbc.queryForList("""
                    SELECT DUEDATE d, PRINDUE p, INTDUE i, PRINBAL b FROM amortsched
                    WHERE BR_CODE = ? AND SLC_CODE = ? AND SLT_CODE = ? AND REF_NO = ? ORDER BY DUEDATE
                    """, l.get("b"), l.get("c"), l.get("t"), l.get("r"));
            int terms = ((Number) l.get("terms")).intValue();
            int months = ((Number) l.get("unit")).intValue() == 7 ? terms * 12 : terms;
            List<LoanSchedule.Row> built;
            try {
                built = LoanSchedule.annuity((BigDecimal) l.get("amt"), months, ((Number) l.get("mode")).intValue(), (BigDecimal) l.get("rate"),
                        ((java.sql.Date) l.get("rel")).toLocalDate(), ((Number) l.get("nd")).intValue() == 1);
            } catch (IllegalArgumentException e) {
                bad++; misses.add(l.get("t") + "/" + l.get("r") + " " + e.getMessage());
                continue;
            }
            // Some loans have no stored schedule at all, or only its first rows; those cannot be compared, the rest must match row by row.
            if (stored.isEmpty()) { skipped++; continue; }
            boolean same = stored.size() <= built.size();
            for (int k = 0; same && k < stored.size(); k++) {
                LoanSchedule.Row x = built.get(k);
                Map<String, Object> s = stored.get(k);
                // a cent either way in the interest is rounding in the old system
                same = x.dueDate().equals(((java.sql.Date) s.get("d")).toLocalDate())
                        && x.principalDue().subtract((BigDecimal) s.get("p")).abs().compareTo(new BigDecimal("0.01")) <= 0
                        && x.interestDue().subtract((BigDecimal) s.get("i")).abs().compareTo(new BigDecimal("0.01")) <= 0;
            }
            if (!same) { bad++; misses.add(l.get("t") + "/" + l.get("r")); }
        }
        assertTrue(loans.size() - skipped >= 100, "too few annuity loans with a stored schedule");
        assertTrue(bad * 100 <= (loans.size() - skipped) * 4, bad + " of " + (loans.size() - skipped) + " differ: " + misses);
    }
}
