package ph.coop.core.accounting.close;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The month-end review on coop: what the application checks, ticks and explanations audited, sign-off only when complete; the policy. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
@WithMockUser(username = "tester", roles = "ADMIN")
class CloseControllerTest {

    private static final String MONTH = "2025-03";

    @Autowired CloseController close;
    @Autowired CloseReviews reviews;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void ready() {
        assumeTrue(reviews.stored(), "V042 is not applied here");
    }

    @Test void aMonthIsReviewedConfirmedAndSignedOff() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var r = close.review(1, "month", MONTH);
        assertEquals("2025-03-01", r.from().toString());
        assertTrue(r.ended());
        assertFalse(r.complete());
        assertNotNull(r.items().stream().filter((i) -> i.key().equals("BOOKS_BALANCE")).findFirst().orElseThrow().detail());
        assertThrows(ResponseStatusException.class, () -> close.signMonth(1, MONTH, null, auth), "not while items are open");
        // an automatic item found wanting needs an explanation; a manual one needs none
        for (var i : r.items()) {
            if (i.settled()) continue;
            if (i.automatic()) assertThrows(ResponseStatusException.class, () -> close.tick(1, "month", MONTH, i.key(), new CloseController.TickIn(true, "ok"), auth));
            close.tick(1, "month", MONTH, i.key(), new CloseController.TickIn(true, i.automatic() ? "Known difference from the old system, under review" : null), auth);
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'accounting.close.tick' AND entity_id = ?", Integer.class, "1/MONTH/" + MONTH + "/CASH_COUNTED"));
        var signed = close.signMonth(1, MONTH, new CloseController.SignIn("Reviewed with the manager"), auth);
        assertEquals("tester", signed.signedBy());
        assertThrows(ResponseStatusException.class, () -> close.tick(1, "month", MONTH, "CASH_COUNTED", new CloseController.TickIn(false, null), auth), "a signed-off month stays as it is");
        assertThrows(ResponseStatusException.class, () -> close.signMonth(1, MONTH, null, auth), "only once");
    }

    @Test void thePolicyIsSetWithAReasonAndAMonthNotEndedCannotBeSignedOff() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var p = close.setPolicy(new CloseController.PolicyIn(true, 90, "Board wants monthly closing"), auth);
        assertTrue(p.monthlyClose());
        assertEquals(90, p.yearEndDueDays());
        assertTrue(close.status(1).policy().monthlyClose());
        assertThrows(ResponseStatusException.class, () -> close.setPolicy(new CloseController.PolicyIn(null, 0, "too short a due"), auth));
        String thisMonth = java.time.YearMonth.now().toString();
        assertThrows(ResponseStatusException.class, () -> close.signMonth(1, thisMonth, null, auth));
    }

    @Test @WithMockUser(username = "tester", roles = "AUDITOR") void anAuditorReadsTheReviewsButTicksNothing() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(close.review(1, "year", "2025"));
        assertThrows(AccessDeniedException.class, () -> close.tick(1, "month", MONTH, "CASH_COUNTED", new CloseController.TickIn(true, null), auth));
    }
}
