package ph.coop.core.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** What a failure is turned into: the cause in plain words and what to do, never the values or the SQL. Pure, no database. */
class ErrorExplainerTest {

    private static Throwable db(String state, String message) {
        return new DataIntegrityViolationException("PreparedStatementCallback; SQL [INSERT INTO ecis_audit_log (action) VALUES (?)]; " + message, new SQLException(message, state));
    }

    @Test void aValueTooLongSaysTheLimitAndHowToFixIt() {
        // the real failure from 2026-11-20: saving a user setting whose audit action was longer than 40 characters
        var e = ErrorExplainer.explain(db("22001", "ERROR: value too long for type character varying(40)"));
        assertEquals(400, e.status());
        assertEquals("VALUE_TOO_LONG", e.code());
        assertTrue(e.problem().contains("40 characters"), e.problem());
        assertTrue(e.fix().contains("column needs to be wider"), e.fix());
        assertFalse((e.problem() + e.fix()).contains("INSERT"), "never the SQL");
    }

    @Test void aDuplicateNamesTheConstraintButNotTheValues() {
        var e = ErrorExplainer.explain(db("23505", "ERROR: duplicate key value violates unique constraint \"pk_client\"\n  Detail: Key (clientid)=(1234) already exists."));
        assertEquals(409, e.status());
        assertTrue(e.problem().contains("pk_client"), e.problem());
        assertFalse(e.problem().contains("1234"), "never the values");
    }

    @Test void aMissingTableOrColumnPointsToTheDatabaseChanges() {
        var t = ErrorExplainer.explain(db("42P01", "ERROR: relation \"ecis_sl_control\" does not exist"));
        assertEquals("MISSING_TABLE", t.code());
        assertTrue(t.problem().contains("ecis_sl_control") && t.fix().contains("Apply them now"), t.problem() + t.fix());
        var c = ErrorExplainer.explain(db("42703", "ERROR: column \"accesscandoloandeduction\" does not exist"));
        assertEquals("MISSING_COLUMN", c.code());
        assertTrue(c.problem().contains("accesscandoloandeduction"));
    }

    @Test void aMissingPermissionSaysWhichTable() {
        var e = ErrorExplainer.explain(db("42501", "ERROR: permission denied for table access"));
        assertEquals("NO_DATABASE_PERMISSION", e.code());
        assertTrue(e.problem().contains("access"), e.problem());
    }

    @Test void requiredValuesLinksDeadlocksAndTimeoutsEachSaySomethingUseful() {
        assertEquals("MISSING_VALUE", ErrorExplainer.explain(db("23502", "ERROR: null value in column \"lname\" violates not-null constraint")).code());
        assertEquals("LINKED_RECORD", ErrorExplainer.explain(db("23503", "ERROR: insert or update violates foreign key constraint \"fk_x\"")).code());
        assertEquals("BUSY", ErrorExplainer.explain(db("40P01", "ERROR: deadlock detected")).code());
        assertEquals("TOO_SLOW", ErrorExplainer.explain(db("57014", "ERROR: canceling statement due to statement timeout")).code());
        assertEquals("DATABASE_UNREACHABLE", ErrorExplainer.explain(new SQLException("Connection refused", "08001")).code());
    }

    @Test void anythingElseIsNamedByItsKindAndFirstLine() {
        var e = ErrorExplainer.explain(new IllegalStateException("No branch is set up for this user\nmore detail on the next line"));
        assertEquals(500, e.status());
        assertEquals("UNEXPECTED", e.code());
        assertTrue(e.problem().contains("IllegalStateException: No branch is set up for this user"), e.problem());
        assertFalse(e.problem().contains("next line"));
        assertTrue(e.fix().contains("reference"));
    }

    @Test void theFirstLineDropsTheSqlAndTheDetail() {
        assertEquals("PreparedStatementCallback; bad grammar", ErrorExplainer.firstLine("PreparedStatementCallback; bad grammar SQL [SELECT secret FROM x]"));
        assertEquals("duplicate key", ErrorExplainer.firstLine("duplicate key Detail: Key (a)=(1)"));
    }

    @Test void referencesAreShortAndReadable() {
        assertTrue(ServerErrors.reference().matches("E-\\d{6}-[A-HJ-NP-Z2-9]{4}"));
    }
}
