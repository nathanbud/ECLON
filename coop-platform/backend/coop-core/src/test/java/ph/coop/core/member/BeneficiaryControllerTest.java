package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** A member's beneficiaries come back with their relationship and kind, principal first. Read only, against the database. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.audit.seal.enabled=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class BeneficiaryControllerTest {

    @Autowired BeneficiaryController beneficiaries;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aMemberWithBeneficiariesGetsThemWithRelationshipAndKind() {
        var row = jdbc.queryForList("SELECT BenefBR_CODE, ClientIDBenef FROM benef GROUP BY BenefBR_CODE, ClientIDBenef HAVING COUNT(*) >= 2 LIMIT 1");
        assumeTrue(!row.isEmpty(), "no member with two beneficiaries");
        int branch = ((Number) row.get(0).get("BenefBR_CODE")).intValue(), client = ((Number) row.get(0).get("ClientIDBenef")).intValue();
        var list = beneficiaries.list(branch, client);
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM benef WHERE BenefBR_CODE = ? AND ClientIDBenef = ?", Integer.class, branch, client), list.size());
        assertTrue(list.stream().allMatch((b) -> b.type() != null), "the kind is named");
        for (int i = 1; i < list.size(); i++) assertTrue(list.get(i - 1).typeCode() <= list.get(i).typeCode(), "principal beneficiaries come first");
    }

    @Test
    void aMemberWithNoBeneficiariesGetsAnEmptyList() {
        assertTrue(beneficiaries.list(1, 999999999).isEmpty());
    }
}
