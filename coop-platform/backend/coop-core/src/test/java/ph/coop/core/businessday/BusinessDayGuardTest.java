package ph.coop.core.businessday;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Date;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * While no business day is open, the transaction screens are for looking only: their changes are refused through the real security chain with the
 * business day's reason, looking and quotes still answer, and once a day is open the same request gets past the guard. Rolled back; a day already
 * open in the database is closed inside the test's own transaction first.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-pass", "coop.drawer-snapshot.on-start=false", "coop.business-day.required=true"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class BusinessDayGuardTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired BusinessDay businessDay;

    @BeforeEach
    void noDayOpen() {
        assumeTrue(businessDay.available(), "no business day table in this database");
        jdbc.update("UPDATE ecis_business_day SET status = 'CLOSED', closed_by = 'itest', closed_at = CURRENT_TIMESTAMP WHERE branch_code = 1 AND status = 'OPEN'");
    }

    private org.springframework.test.web.servlet.ResultActions send(String path) throws Exception {
        return mvc.perform(post(path).with(httpBasic("itest", "itest-pass")).contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    @Test void transactionsAreRefusedWithTheReason() throws Exception {
        for (String path : new String[] {"/api/journals", "/api/memos", "/api/front-office/savings/deposits", "/api/pos/sales", "/api/inventory/movements", "/api/front-office/drafts"}) {
            send(path).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(BusinessDayGuard.CODE))
                .andExpect(jsonPath("$.message").value(BusinessDay.NOT_STARTED));
        }
    }

    @Test void lookingAndChecksStillAnswer() throws Exception {
        mvc.perform(get("/api/journals?branchCode=1").with(httpBasic("itest", "itest-pass"))).andExpect(status().isOk());
        // a withdrawal check only looks; whatever it answers about the empty request, it is not the business day refusal
        int code = send("/api/front-office/savings/withdrawals/check").andReturn().getResponse().getStatus();
        String body = send("/api/front-office/savings/withdrawals/check").andReturn().getResponse().getContentAsString();
        assertNotEquals(true, code == 409 && body.contains(BusinessDayGuard.CODE), "a check must not be refused for the business day");
    }

    @Test void onceADayIsOpenTheGuardLetsItThrough() throws Exception {
        // business days are never deleted (the app may only add and close them): reopen today's if there is one, else add it (rolled back either way)
        Date today = Date.valueOf(LocalDate.now());
        if (jdbc.update("UPDATE ecis_business_day SET status = 'OPEN', closed_by = NULL, closed_at = NULL WHERE branch_code = 1 AND business_date = ?", today) == 0) {
            jdbc.update("INSERT INTO ecis_business_day (branch_code, business_date, status, opened_by, opened_at) VALUES (1, ?, 'OPEN', 'itest', CURRENT_TIMESTAMP)", today);
        }
        // the empty journal is refused for what it lacks, not for the business day
        String body = send("/api/journals").andReturn().getResponse().getContentAsString();
        assertNotEquals(true, body.contains(BusinessDayGuard.CODE), "refused for the business day although one is open: " + body);
    }
}
