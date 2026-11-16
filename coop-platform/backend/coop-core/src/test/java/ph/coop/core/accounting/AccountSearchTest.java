package ph.coop.core.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** The account picker's search: only accounts that take postings, found by number or by part of the name. Read only. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class AccountSearchTest {

    @Autowired AccountController accounts;
    @Autowired JdbcTemplate jdbc;

    @Test
    void findsPostingAccountsByNumberOrNameAndNeverSummaryOrSwitchedOffOnes() {
        List<AccountController.Postable> byNumber = accounts.search(1, "30198");
        assertEquals("30198", byNumber.get(0).code());
        List<AccountController.Postable> byName = accounts.search(1, "undivided NET");
        assertTrue(byName.stream().anyMatch(a -> a.code().equals("30198")), "name search ignores case and finds a part of the name");
        List<AccountController.Postable> all = accounts.search(1, "");
        assertTrue(all.size() <= 30);
        for (AccountController.Postable a : all) {
            String tag = jdbc.queryForObject("SELECT COAacct_tag FROM coa WHERE COABR_CODE = 1 AND COAID = ?", String.class, a.code());
            Integer off = jdbc.queryForObject("SELECT COALESCE(COAisdisabled, 0) FROM coa WHERE COABR_CODE = 1 AND COAID = ?", Integer.class, a.code());
            assertEquals("D", tag, a.code() + " is a summary account");
            assertEquals(0, off, a.code() + " is switched off");
        }
        assertFalse(accounts.search(1, "100%").stream().anyMatch(a -> a.code().contains("%")), "wildcards typed by the user are taken out");
    }
}
