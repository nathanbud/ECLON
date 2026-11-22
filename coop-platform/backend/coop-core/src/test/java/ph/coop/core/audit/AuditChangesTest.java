package ph.coop.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ph.coop.core.audit.AuditChanges.Change;

/** Reading an audit row's "before" and "after" as a list of changed fields. No database. */
class AuditChangesTest {

    private static List<Change> of(String action, String prev, String next) {
        return AuditChanges.of(action, prev, next, null);
    }

    @Test void twoJsonObjectsListOnlyTheFieldsThatDiffer() {
        List<Change> c = of("account.type.update", "{\"SLTypeINT_RATE\":1.00,\"SLTypeMIN_BAL\":500,\"SLTypeM_DESC\":\"SD - Regular\"}",
            "{\"SLTypeINT_RATE\":1.75,\"SLTypeMIN_BAL\":500.00,\"SLTypeM_DESC\":\"SD - Regular\"}");
        assertEquals(1, c.size(), "500 and 500.00 are the same; the name did not change");
        assertEquals(new Change("SLTypeINT_RATE", "Interest rate", "1.00", "1.75"), c.get(0));
    }

    @Test void somethingNewOrRemovedListsEveryField() {
        List<Change> made = of("chart.account.create", null, "{\"name\":\"Petty cash\",\"summary\":false,\"parent\":\"1000\",\"glOnly\":true}");
        assertEquals(List.of(
            new Change("name", "Name", null, "Petty cash"),
            new Change("summary", "Heading", null, "No"),
            new Change("parent", "Under heading", null, "1000"),
            new Change("glOnly", "Takes journal entries", null, "Yes")), made);
        List<Change> gone = of("loan.type.link.remove", "{\"event\":75,\"account\":\"2011603\"}", "");
        assertEquals(new Change("account", "Account", "2011603", null), gone.get(1));
    }

    @Test void aFieldPresentOnOneSideOnlyIsShownAsAddedOrCleared() {
        List<Change> c = of("x.update", "{\"a\":1}", "{\"a\":1,\"b\":null,\"c\":\"new\"}");
        assertEquals(List.of(new Change("c", "C", null, "new")), c, "null to missing is no change");
        List<Change> cleared = of("x.update", "{\"maxBalance\":250000}", "{\"maxBalance\":null}");
        assertEquals(List.of(new Change("maxBalance", "Max balance", "250000", null)), cleared);
    }

    @Test void aMemberProfileChangeNamesItsColumn() {
        List<Change> c = AuditChanges.of("UPDATE:ResAddStreet", "SAMOYAO CRUZ", "Samoyao", Map.of("ResAddStreet", "Street")::get);
        assertEquals(List.of(new Change("ResAddStreet", "Street", "SAMOYAO CRUZ", "Samoyao")), c);
        // a value that looks like JSON is still one value on such a row
        assertEquals(1, of("UPDATE:Remarks", "{\"x\":1}", "{\"x\":2}").size());
    }

    @Test void plainTextIsOneValueAndNothingIsNothing() {
        assertEquals(List.of(new Change("", "Value", "Second (localhost:5440/coop_blank)", "Server startup setting")),
            of("connection.switch", "Second (localhost:5440/coop_blank)", "Server startup setting"));
        assertEquals(List.of(new Change("", "Value", null, "as of 2026-11-11")), of("loan.worksheet.pdf", null, "as of 2026-11-11"));
        assertTrue(of("x", null, " ").isEmpty());
        // JSON on one side and text on the other cannot be paired by field
        assertEquals(1, of("x", "{\"a\":1}", "plain").size());
        // a broken JSON text is shown as it is
        assertEquals("{\"a\":", of("x", null, "{\"a\":").get(0).after());
    }

    @Test void nestedValuesAreShownCompactAndNamesAreTakenApart() {
        List<Change> c = of("cash.drawer.add", null, "{\"links\":[{\"type\":1,\"account\":\"10101\"}],\"clientType\":2}");
        assertEquals("[{\"type\":1,\"account\":\"10101\"}]", c.get(0).after());
        assertEquals("Member type", c.get(1).label());
        assertEquals("Last deposit or withdrawal", AuditChanges.label("lastActivity", null));
        assertEquals("Value", AuditChanges.label("", null));
        assertNull(AuditChanges.show(null));
        assertEquals("0.00", AuditChanges.show(com.fasterxml.jackson.databind.node.DecimalNode.valueOf(new java.math.BigDecimal("0.00"))));
    }
}
