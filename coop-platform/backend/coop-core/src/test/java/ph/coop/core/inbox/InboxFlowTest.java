package ph.coop.core.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;

/** Plants work waiting for approval and checks who is shown what, against the database and rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.audit.seal.enabled=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class InboxFlowTest {

    @Autowired InboxController inbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private static Authentication as(String user, String... roles) {
        return new TestingAuthenticationToken(user, "x", roles);
    }

    @BeforeEach
    void plant() {
        assumeTrue(db.tableExists("ecis_memo") && db.canInsertInto("ecis_memo", "ecis_purchase_request", "ecis_member_request"), "the tables or grants are missing");
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO ecis_journal (branch_code, reference, journal_date, description, status, created_by, created_at) VALUES (1, 'ZZINBOX-J1', ?, 'inbox test', 'FOR_REVIEW', 'zz_maker', ?)", LocalDate.now(), now);
        jdbc.update("INSERT INTO ecis_memo (branch_code, reference, memo_date, description, status, created_by, created_at) VALUES (1, 'ZZINBOX-M1', ?, 'inbox test review', 'FOR_REVIEW', 'zz_maker', ?)", LocalDate.now(), now);
        jdbc.update("INSERT INTO ecis_memo (branch_code, reference, memo_date, description, status, created_by, created_at, approved_by, approved_at) VALUES (1, 'ZZINBOX-M2', ?, 'inbox test post', 'APPROVED', 'zz_maker', ?, 'zz_checker', ?)", LocalDate.now(), now, now);
        jdbc.update("INSERT INTO ecis_purchase_request (branch_code, request_no, request_date, purpose, status, requested_by, created_at) VALUES (1, 'ZZPR-1', ?, 'inbox test', 'SUBMITTED', 'zz_maker', ?)", LocalDate.now(), now);
        jdbc.update("INSERT INTO ecis_member_request (branch_code, kind, summary, payload, status, requested_by, requested_at) VALUES (1, 'CLOSE', 'ZZINBOX close', '{}', 'PENDING', 'zz_maker', ?)", now);
    }

    private boolean has(InboxController.Inbox box, String kind, String text) {
        return box.items().stream().anyMatch((i) -> i.kind().equals(kind) && i.summary().contains(text));
    }

    @Test
    void anAccountantSeesJournalsAndMemosWaitingOnThemButNotOnTheMaker() {
        var box = inbox.inbox(as("zz_other", "ROLE_ACCOUNTANT"));
        assertTrue(has(box, "JOURNAL_REVIEW", "ZZINBOX-J1"));
        assertTrue(has(box, "MEMO_REVIEW", "ZZINBOX-M1"));
        assertTrue(has(box, "MEMO_POST", "ZZINBOX-M2"));
        assertFalse(has(box, "PURCHASE_REQUEST", "ZZPR-1"), "an accountant does not approve purchases");
        assertFalse(has(box, "MEMBER_REQUEST", "ZZINBOX"), "an accountant does not decide member requests");
        var memo = box.items().stream().filter((i) -> i.summary().contains("ZZINBOX-M1")).findFirst().orElseThrow();
        assertEquals("accounting", memo.view());
        assertEquals("Memos", memo.tab());
    }

    @Test
    void theMakerIsNotShownTheirOwnWorkAndTheApproverCannotPostWhatTheyApproved() {
        var maker = inbox.inbox(as("zz_maker", "ROLE_ACCOUNTANT", "ROLE_PURCHASE_APPROVER", "ROLE_MEMBERSHIP_OFFICER"));
        assertFalse(has(maker, "JOURNAL_REVIEW", "ZZINBOX-J1"));
        assertFalse(has(maker, "MEMO_REVIEW", "ZZINBOX-M1"));
        assertFalse(has(maker, "MEMO_POST", "ZZINBOX-M2"));
        assertFalse(has(maker, "PURCHASE_REQUEST", "ZZPR-1"));
        assertFalse(has(maker, "MEMBER_REQUEST", "ZZINBOX"));

        var checker = inbox.inbox(as("zz_checker", "ROLE_ACCOUNTANT"));
        assertTrue(has(checker, "MEMO_REVIEW", "ZZINBOX-M1"), "the approver of one memo may review another");
        assertFalse(has(checker, "MEMO_POST", "ZZINBOX-M2"), "the approver cannot post their own approval");
    }

    @Test
    void aPurchaseApproverAndAMembershipOfficerSeeTheirOwnKinds() {
        assertTrue(has(inbox.inbox(as("zz_other", "ROLE_PURCHASE_APPROVER")), "PURCHASE_REQUEST", "ZZPR-1"));
        var officer = inbox.inbox(as("zz_other", "ROLE_MEMBERSHIP_OFFICER"));
        assertTrue(has(officer, "MEMBER_REQUEST", "ZZINBOX close"));
        assertEquals("approvals", officer.items().stream().filter((i) -> i.summary().contains("ZZINBOX close")).findFirst().orElseThrow().view());
    }

    @Test
    void aPersonWithNoApprovingRoleSeesNothingAndTheCountMatches() {
        var nobody = as("zz_other", "ROLE_POS_CASHIER");
        assertTrue(inbox.inbox(nobody).items().isEmpty());
        assertEquals(0, inbox.count(nobody).get("count"));
        var accountant = as("zz_other", "ROLE_ACCOUNTANT");
        assertEquals(inbox.inbox(accountant).total(), inbox.count(accountant).get("count"));
        assertEquals(inbox.inbox(accountant).items().size(), inbox.inbox(accountant).total());
    }

    @Test
    void theLongestWaitingComesFirst() {
        var items = inbox.inbox(as("zz_other", "ROLE_ACCOUNTANT", "ROLE_ADMIN", "ROLE_MEMBERSHIP_OFFICER")).items();
        for (int i = 1; i < items.size(); i++) {
            String a = items.get(i - 1).at() == null ? "9999" : items.get(i - 1).at(), b = items.get(i).at() == null ? "9999" : items.get(i).at();
            assertTrue(a.compareTo(b) <= 0, "oldest first");
        }
    }
}
