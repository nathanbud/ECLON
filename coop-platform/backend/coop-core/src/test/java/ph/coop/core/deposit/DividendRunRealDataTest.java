package ph.coop.core.deposit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Recomputes the February 2026 dividend and patronage refund run (module 6 type 54, cut-off 2026-02-21, 8% and 8.23%) for every member and compares each member's total
 * to what the old system credited. Read only. Runs only when DB_PASSWORD is set and the real run is in this database.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class DividendRunRealDataTest {

    @Autowired DividendRun run;
    @Autowired JdbcTemplate jdbc;

    @Test
    void recomputesTheMemberTotalsOfThe2026Run() {
        Map<Integer, BigDecimal> posted = new HashMap<>();
        jdbc.query("SELECT ClientIDDT, -SUM(DTAMT) FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = 2026 AND DTTR_MODULE = 6 AND DTTR_CODE = 54 AND ClientIDDT IS NOT NULL GROUP BY ClientIDDT",
            rs -> { posted.put(rs.getInt(1), rs.getBigDecimal(2)); });
        assumeTrue(posted.size() > 1000, "the real 2026 run is not in this database");
        DividendRun.Plan plan = run.plan(1, LocalDate.of(2026, 2, 21),
            new DividendRun.Params(new BigDecimal("8"), new BigDecimal("8.23"), new BigDecimal("902.17"), new BigDecimal("864.06"), null));
        int exact = 0, same = 0;
        for (DividendRun.Member m : plan.members()) {
            BigDecimal old = posted.get(m.clientId());
            if (old != null) same++;
            if (old != null && old.subtract(m.total()).abs().compareTo(new BigDecimal("0.01")) <= 0) exact++;
        }
        System.out.println("DIVIDEND RUN: " + plan.members().size() + " members computed, " + posted.size() + " posted, same members " + same + ", total within a cent " + exact + ", held " + plan.held().size());
        assertTrue(exact * 100L >= posted.size() * 99L, "only " + exact + " of " + posted.size() + " members within a cent");
        assertTrue(same * 100L >= posted.size() * 99L, "the members differ: " + same + " of " + posted.size());
    }
}
