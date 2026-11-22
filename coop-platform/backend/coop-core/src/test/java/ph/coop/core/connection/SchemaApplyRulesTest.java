package ph.coop.core.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaApplyRulesTest {

    @Test
    void onlyADatabaseThatIsBehindWithAnAdministratorAndAReason() {
        assertEquals(List.of(), SchemaApplyRules.problems("BEHIND", "coop_bgempc", "coop_admin", "secret", "Offices screen"));
        assertTrue(SchemaApplyRules.problems("UP_TO_DATE", "coop", "coop_admin", "secret", "Offices").get(0).contains("nothing to apply"));
        assertTrue(SchemaApplyRules.problems("NEWER", "coop", "coop_admin", "secret", "Offices").get(0).contains("update this server"));
        assertTrue(SchemaApplyRules.problems("NOT_CHECKED", "iaccs", "coop_admin", "secret", "Offices").get(0).contains("not a PostgreSQL"));
        assertTrue(SchemaApplyRules.problems("NO_LOG", "x", "coop_admin", "secret", "Offices").get(0).contains("no record"));
        List<String> p = SchemaApplyRules.problems("BEHIND", "coop_bgempc", " ", "", "x");
        assertEquals(3, p.size(), p.toString());
        assertTrue(p.get(1).contains("not kept"));
    }

    @Test
    void appliesLowestNumberFirst() {
        assertEquals(List.of("V009__x.sql", "V035__offices.sql", "V100__later.sql"),
            SchemaApplyRules.order(List.of("V100 V100__later.sql", "V035 V035__offices.sql", "V009 V009__x.sql")));
    }

    @Test
    void failuresSayWhatHappenedWithoutTheDetails() {
        assertTrue(SchemaApplyRules.failure("28P01", "The change", 0).contains("refused that administrator"));
        String denied = SchemaApplyRules.failure("42501", "V035__offices.sql", 1);
        assertTrue(denied.contains("V035__offices.sql") && denied.contains("1 change was applied before it"), denied);
        assertTrue(SchemaApplyRules.failure("08001", "The change", 0).contains("could not be reached"));
        String other = SchemaApplyRules.failure("42P01", "V036__x.sql", 0);
        assertTrue(other.contains("rolled back") && other.contains("No change was applied"), other);
        assertFalse(other.contains("42P01"), "no codes for the person");
    }
}
