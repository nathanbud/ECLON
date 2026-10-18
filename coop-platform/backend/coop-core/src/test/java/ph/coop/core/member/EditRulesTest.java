package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EditRulesTest {

    private static final Map<Integer, String> GENDER = Map.of(1, "Male", 2, "Female");

    @Test void blankClearsTheField() {
        assertNull(EditRules.normalize("Email", "text", null, 50, "   "));
        assertNull(EditRules.normalize("Gender", "text", GENDER, null, ""));
    }

    @Test void textIsTrimmedAndLengthChecked() {
        assertEquals("Osting", EditRules.normalize("Last name", "text", null, 20, "  Osting "));
        assertThrows(IllegalArgumentException.class, () -> EditRules.normalize("Last name", "text", null, 5, "Osting"));
    }

    @Test void codedFieldsOnlyAcceptKnownCodes() {
        assertEquals("2", EditRules.normalize("Gender", "text", GENDER, null, "2"));
        assertThrows(IllegalArgumentException.class, () -> EditRules.normalize("Gender", "text", GENDER, null, "9"));
        assertThrows(IllegalArgumentException.class, () -> EditRules.normalize("Gender", "text", GENDER, null, "Male"));
    }

    @Test void datesMustBeRealDates() {
        assertEquals("1992-09-19", EditRules.normalize("Date of birth", "date", null, null, "1992-09-19"));
        assertEquals("1992-09-19", EditRules.normalize("Date of birth", "date", null, null, "1992-09-19 00:00:00"));
        assertThrows(IllegalArgumentException.class, () -> EditRules.normalize("Date of birth", "date", null, null, "1992-13-40"));
    }

    @Test void yearsAreBounded() {
        assertEquals("2015", EditRules.normalize("Since", "year", null, null, "2015"));
        assertThrows(IllegalArgumentException.class, () -> EditRules.normalize("Since", "year", null, null, "1800"));
        assertThrows(IllegalArgumentException.class, () -> EditRules.normalize("Since", "year", null, null, "soon"));
    }

    @Test void moneyAllowsTwoDecimalsAndCommas() {
        assertEquals("12500.50", EditRules.normalize("Income", "money", null, null, "12,500.50"));
        assertThrows(IllegalArgumentException.class, () -> EditRules.normalize("Income", "money", null, null, "1.234"));
        assertThrows(IllegalArgumentException.class, () -> EditRules.normalize("Income", "money", null, null, "lots"));
    }

    @Test void unchangedValuesAreRecognisedAcrossFormats() {
        assertTrue(EditRules.same("text", false, null, null));
        assertTrue(EditRules.same("text", false, "", null));
        assertTrue(EditRules.same("money", false, "0", "0.00"));
        assertTrue(EditRules.same("text", true, "02", "2"));
        assertTrue(EditRules.same("date", false, "1992-09-19 00:00:00.0", "1992-09-19"));
        assertFalse(EditRules.same("text", false, "A", "B"));
        assertFalse(EditRules.same("text", false, "A", null));
    }

    @Test void timestampsFromDatabaseAndClientCompareEqual() {
        assertEquals(EditRules.stamp("2023-09-12 10:15:00.0"), EditRules.stamp("2023-09-12T10:15:00"));
        assertNull(EditRules.stamp(null));
    }
}
