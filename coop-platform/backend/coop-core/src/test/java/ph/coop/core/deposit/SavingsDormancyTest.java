package ph.coop.core.deposit;

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
import ph.coop.core.deposit.SavingsDormancyController.Key;
import ph.coop.core.deposit.SavingsDormancyController.MarkIn;
import ph.coop.core.deposit.SavingsDormancyController.ReactivateIn;
import ph.coop.core.deposit.SavingsDormancyController.Row;

/** Dormant savings accounts: listed for review, marked with a reason, reactivated with a reason, audited per account. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
@WithMockUser(username = "tester", roles = "ADMIN")
class SavingsDormancyTest {

    @Autowired SavingsDormancyController dormancy;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void ready() {
        assumeTrue(!jdbc.queryForList("SELECT 1 FROM access WHERE AccessUserName = 'tester'").isEmpty(), "no tester account");
    }

    private Authentication me() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private int status(Row r) {
        return jdbc.queryForObject("SELECT SDSTATUS FROM sd WHERE SDBR_CODE = 1 AND SDSLC_CODE = 22 AND ClientIDSD = ? AND SDSLT_CODE = ? AND SDREF_NO = ?", Integer.class, r.clientId(), r.typeCode(), r.refNo());
    }

    @Test void quietAccountsAreListedMarkedAndReactivated() {
        var review = dormancy.review(me());
        assertTrue(review.types().stream().anyMatch((t) -> t.months() != null), "no savings type has a dormancy period here");
        assumeTrue(!review.rows().isEmpty(), "no account is due in this database");
        Row r = review.rows().get(0);
        assertEquals(15, status(r));
        // every listed account is of a type with a period, and its last deposit or withdrawal is older than that
        for (Row x : review.rows()) {
            int months = review.types().stream().filter((t) -> t.typeCode() == x.typeCode()).findFirst().orElseThrow().months();
            String last = x.lastActivity() != null && (x.opened() == null || x.lastActivity().compareTo(x.opened()) > 0) ? x.lastActivity() : x.opened();
            assertTrue(last.compareTo(java.time.LocalDate.parse(review.asOf()).minusMonths(months).toString()) <= 0, x.clientName() + " " + last);
        }

        Key key = new Key(r.clientId(), r.typeCode(), r.refNo());
        assertThrows(ResponseStatusException.class, () -> dormancy.mark(new MarkIn(List.of(key), " "), me()), "a reason is needed");
        assertThrows(ResponseStatusException.class, () -> dormancy.mark(new MarkIn(List.of(), "why"), me()));
        var marked = dormancy.mark(new MarkIn(List.of(key, key), "yearly dormancy review"), me());
        assertEquals(1, marked.marked(), "the same account listed twice is marked once");
        assertEquals(16, status(r));
        String id = "1/" + r.clientId() + "/" + r.typeCode() + "/" + r.refNo();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'savings.dormant.mark' AND entity = 'savings_account' AND entity_id = ? AND reason = 'yearly dormancy review'", Integer.class, id));

        // marking it again skips it and says so; it is now found among the dormant ones
        var again = dormancy.mark(new MarkIn(List.of(key), "again"), me());
        assertEquals(0, again.marked());
        assertEquals(1, again.skipped().size());
        assertTrue(dormancy.dormant(String.valueOf(r.clientId()), me()).stream().anyMatch((x) -> x.refNo() == r.refNo() && x.typeCode() == r.typeCode()));

        assertThrows(ResponseStatusException.class, () -> dormancy.reactivate(new ReactivateIn(r.clientId(), r.typeCode(), r.refNo(), ""), me()));
        dormancy.reactivate(new ReactivateIn(r.clientId(), r.typeCode(), r.refNo(), "member came to deposit"), me());
        assertEquals(15, status(r));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'savings.dormant.reactivate' AND entity_id = ?", Integer.class, id));
        assertThrows(ResponseStatusException.class, () -> dormancy.reactivate(new ReactivateIn(r.clientId(), r.typeCode(), r.refNo(), "twice"), me()), "it is not dormant any more");

        // just reactivated: the period starts over, so it is not offered again and cannot be marked
        assertFalse(dormancy.review(me()).rows().stream().anyMatch((x) -> x.clientId() == r.clientId() && x.refNo() == r.refNo() && x.typeCode() == r.typeCode()));
        assertEquals(0, dormancy.mark(new MarkIn(List.of(key), "too soon"), me()).marked());
    }

    @Test void nothingButTheStatusOfASavingsAccountCanBeChanged() {
        assumeTrue(jdbc.queryForObject("SELECT COUNT(*) FROM sd WHERE SDSTATUS = 15", Integer.class) > 0, "no active savings account here");
        var a = jdbc.queryForMap("SELECT ClientIDSD AS c, SDSLT_CODE AS t, SDREF_NO AS r FROM sd WHERE SDBR_CODE = 1 AND SDSTATUS = 15 ORDER BY ClientIDSD, SDREF_NO LIMIT 1");
        String where = " WHERE SDBR_CODE = 1 AND SDSLC_CODE = 22 AND ClientIDSD = ? AND SDSLT_CODE = ? AND SDREF_NO = ?";
        // closing an account is not opened up, and no other column may change; the database itself refuses (this must be the last statement: the refusal ends the transaction)
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("UPDATE sd SET SDSTATUS = 17" + where, a.get("c"), a.get("t"), a.get("r")));
    }

    @Test void theMinimumBalanceOfASavingsAccountCannotBeChangedByTheApplication() {
        assumeTrue(jdbc.queryForObject("SELECT COUNT(*) FROM sd WHERE SDSTATUS = 15", Integer.class) > 0, "no active savings account here");
        var a = jdbc.queryForMap("SELECT ClientIDSD AS c, SDSLT_CODE AS t, SDREF_NO AS r FROM sd WHERE SDBR_CODE = 1 AND SDSTATUS = 15 ORDER BY ClientIDSD, SDREF_NO LIMIT 1");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("UPDATE sd SET SDMIN_BAL = 1 WHERE SDBR_CODE = 1 AND SDSLC_CODE = 22 AND ClientIDSD = ? AND SDSLT_CODE = ? AND SDREF_NO = ?",
            a.get("c"), a.get("t"), a.get("r")));
    }

    @Test void theDormantListNeedsANumberOrThreeLetters() {
        assertTrue(dormancy.dormant(" ", me()).isEmpty());
        assertThrows(ResponseStatusException.class, () -> dormancy.dormant("ab", me()));
    }

    @Test @WithMockUser(username = "tester", roles = "MEMBERSHIP_OFFICER") void anOfficerMayReactivateButNotMark() {
        assertThrows(AccessDeniedException.class, () -> dormancy.review(me()));
        assertThrows(AccessDeniedException.class, () -> dormancy.mark(new MarkIn(List.of(new Key(1, 1, 1)), "try"), me()));
        assertTrue(dormancy.dormant("", me()).isEmpty());
    }

    @Test @WithMockUser(username = "tester", roles = "USER") void othersMayNot() {
        assertThrows(AccessDeniedException.class, () -> dormancy.dormant("1", me()));
        assertThrows(AccessDeniedException.class, () -> dormancy.reactivate(new ReactivateIn(1, 1, 1, "try"), me()));
    }
}
