package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Date;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The KYC review list and the withdrawal policy, on the real database, rolled back: the list's SQL gives each member the same status as {@link KycRules#status},
 * the counts cover every active member, and the policy refuses a withdrawal only when it is on and the record is missing, incomplete or expired.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class KycReviewTest {

    @Autowired KycReviewController review;
    @Autowired KycGate gate;
    @Autowired MembershipPolicyController policy;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 11);
    private List<Map<String, Object>> members;

    @BeforeEach
    void ready() {
        assumeTrue(db.allTablesExist("ecis_member_kyc", "ecis_setting"), "the KYC and settings tables are not installed");
        // members with no KYC record yet, so nothing has to be deleted (the app's database user may not delete)
        members = jdbc.queryForList("SELECT c.ClientIDBrCode AS b, c.ClientID AS c FROM client c WHERE c.AccountStatus = 1 AND NOT EXISTS "
            + "(SELECT 1 FROM ecis_member_kyc k WHERE k.branch_code = c.ClientIDBrCode AND k.client_id = c.ClientID) ORDER BY c.ClientIDBrCode, c.ClientID LIMIT 5");
        assumeTrue(members.size() == 5, "needs five active members without a KYC record");
    }

    private int b(int i) { return ((Number) members.get(i).get("b")).intValue(); }
    private int c(int i) { return ((Number) members.get(i).get("c")).intValue(); }

    private void put(int i, String idType, LocalDate expiry, String funds, LocalDate review) {
        jdbc.update("INSERT INTO ecis_member_kyc (branch_code, client_id, id_type, id_number, id_expiry, source_of_funds, pep, risk, next_review, updated_by, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, 'itest', ?)", b(i), c(i), idType, "P1234567A", expiry == null ? null : Date.valueOf(expiry), funds, "LOW",
            review == null ? null : Date.valueOf(review), java.sql.Timestamp.valueOf(TODAY.atStartOfDay()));
    }

    /** Members 1 to 4 get a complete, an incomplete (blank source of funds), an expired and an overdue record; member 0 has none. */
    private void fill() {
        put(1, "Passport", TODAY.plusYears(3), "Salary", TODAY.plusYears(2));
        put(2, "Passport", TODAY.plusYears(3), "  ", TODAY.plusYears(2));
        put(3, "Passport", TODAY.minusDays(1), "Salary", TODAY.plusYears(2));
        put(4, "Passport", TODAY.plusYears(3), "Salary", TODAY.minusDays(1));
    }

    private String listedAs(int i) {
        for (String s : KycReviewController.STATUSES) {
            int page = 0;
            while (true) {
                var r = review.review(s, b(i), page, KycReviewController.MAX_SIZE, TODAY);
                if (r.rows().stream().anyMatch((row) -> row.clientId() == c(i))) return s;
                if (r.rows().size() < r.size()) break;
                page++;
            }
        }
        return null;
    }

    @Test
    void theListGivesEachMemberTheSameStatusAsTheRules() {
        fill();
        String[] expected = {"NONE", "COMPLETE", "INCOMPLETE", "EXPIRED", "REVIEW_DUE"};
        for (int i = 0; i < 5; i++) {
            assertEquals(expected[i], listedAs(i), "member " + i);
            assertEquals(expected[i], gate.status(b(i), c(i), TODAY).name(), "the gate reads the same status for member " + i);
        }
    }

    @Test
    void theCountsCoverEveryActiveMemberOfTheBranch() {
        var r = review.review("EXPIRED", b(0), 0, 10, TODAY);
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM client WHERE AccountStatus = 1 AND ClientIDBrCode = ?", Integer.class, b(0));
        assertEquals(active, r.counts().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(r.counts().get("EXPIRED"), r.total());
        assertThrows(ResponseStatusException.class, () -> review.review("SOMETHING", null, 0, 10, TODAY));
    }

    @Test
    void theWithdrawalPolicyIsOffUntilSwitchedOn_thenRefusesOnlyMissingIncompleteOrExpired() {
        fill();
        var admin = new TestingAuthenticationToken("admin", "x", "ROLE_ADMIN");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(admin);
        policy.set(new MembershipPolicyController.Change(null, false, "Test"), admin);
        for (int i = 0; i < 5; i++) assertNull(gate.withdrawalProblem(b(i), c(i), TODAY), "off: nothing is refused");

        policy.set(new MembershipPolicyController.Change(null, true, "Bylaws require KYC"), admin);
        assertTrue(policy.get().kycBlocksWithdrawals());
        assertNotNull(gate.withdrawalProblem(b(0), c(0), TODAY), "no record");
        assertNull(gate.withdrawalProblem(b(1), c(1), TODAY), "complete");
        assertNotNull(gate.withdrawalProblem(b(2), c(2), TODAY), "incomplete");
        assertNotNull(gate.withdrawalProblem(b(3), c(3), TODAY), "expired");
        assertNull(gate.withdrawalProblem(b(4), c(4), TODAY), "an overdue review only warns");

        String logged = jdbc.queryForObject("SELECT reason FROM ecis_audit_log WHERE action = 'membership.policy.changed' AND entity_id = ? ORDER BY id DESC LIMIT 1",
            String.class, KycGate.BLOCK_WITHDRAWALS);
        assertEquals("Bylaws require KYC", logged);
    }
}
