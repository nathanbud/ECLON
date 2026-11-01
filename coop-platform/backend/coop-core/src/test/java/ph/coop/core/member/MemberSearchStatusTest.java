package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** A name search lists active members only unless inactive ones are asked for; a member ID finds the member whatever the status. Read only, against the database. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.audit.seal.enabled=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class MemberSearchStatusTest {

    @Autowired MemberController controller;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aNameSearchListsActiveMembersUnlessInactiveAreAskedFor() {
        var closed = jdbc.queryForList("SELECT LName, ClientIDBrCode, ClientID FROM client WHERE AccountStatus = 3 AND LName REGEXP '^[A-Za-z]{5,}$' LIMIT 1");
        assumeTrue(!closed.isEmpty(), "no closed member with a plain last name");
        String last = String.valueOf(closed.get(0).get("LName"));
        var active = controller.search(last, 0, 100, false);
        var all = controller.search(last, 0, 100, true);
        assertTrue(active.getContent().stream().allMatch((m) -> Integer.valueOf(1).equals(m.getAccountStatus())), "only active members by default");
        assertTrue(all.getTotalElements() > active.getTotalElements(), "turning the switch on adds the closed member");
        assertTrue(all.getContent().stream().anyMatch((m) -> !Integer.valueOf(1).equals(m.getAccountStatus())));
    }

    @Test
    void aFirstNameOrLastNameStartFindsTheMemberAndAFullPageSaysThereIsMore() {
        var common = jdbc.queryForList("SELECT FName, COUNT(*) c FROM client WHERE AccountStatus = 1 AND FName REGEXP '^[A-Za-z]{4,}$' GROUP BY FName HAVING c > 30 ORDER BY c DESC LIMIT 1");
        assumeTrue(!common.isEmpty(), "no first name shared by more than 30 active members");
        String first = String.valueOf(common.get(0).get("FName")).toLowerCase();
        var page = controller.search(first.substring(0, first.length() - 1), 0, 8, false);
        assertEquals(8, page.getContent().size());
        assertTrue(!page.isLast(), "a full page says there are more matches");
        assertTrue(page.getContent().stream().allMatch((m) -> m.getFirstName().toLowerCase().startsWith(first.substring(0, first.length() - 1)) || m.getLastName().toLowerCase().startsWith(first.substring(0, first.length() - 1))
            || (m.getMiddleName() != null && m.getMiddleName().toLowerCase().contains(first.substring(0, first.length() - 1)))));
        for (int i = 1; i < page.getContent().size(); i++) {
            var a = page.getContent().get(i - 1);
            var b = page.getContent().get(i);
            assertTrue(a.getLastName().compareToIgnoreCase(b.getLastName()) <= 0, "sorted by last name");
        }
        assertEquals(8, page.getContent().stream().map((m) -> m.getId().clientId()).distinct().count(), "no repeats");
    }

    @Test
    void aWordInTheMiddleOfAnAccountNameStillFindsTheMemberThroughTheBroadSearch() {
        var row = jdbc.queryForList("SELECT AccountName FROM client WHERE AccountStatus = 1 AND AccountName REGEXP '^[A-Za-z]+ [A-Za-z]{6,} [A-Za-z]+$' LIMIT 1");
        assumeTrue(!row.isEmpty(), "no suitable account name");
        String middle = String.valueOf(row.get(0).get("AccountName")).split(" ")[1].toLowerCase();
        var found = controller.search(middle, 0, 25, false);
        assertTrue(found.getTotalElements() > 0, "the fallback search finds a word that is not the start of a name");
    }

    @Test
    void aMemberIdFindsTheMemberWhateverTheStatus() {
        var closed = jdbc.queryForList("SELECT ClientIDBrCode, ClientID FROM client WHERE AccountStatus = 3 LIMIT 1");
        assumeTrue(!closed.isEmpty(), "no closed member");
        int branch = ((Number) closed.get(0).get("ClientIDBrCode")).intValue(), id = ((Number) closed.get(0).get("ClientID")).intValue();
        var found = controller.search(branch + "-" + id, 0, 25, false);
        assertEquals(1, found.getContent().stream().filter((m) -> m.getId().clientId() == id).count());
    }
}
