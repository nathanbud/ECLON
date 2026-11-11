package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import ph.coop.core.member.StreetRules.Seen;

class StreetRulesTest {

    @Test
    void cityNamesAreComparedWhateverWayTheyWereWritten() {
        assertEquals(StreetRules.place("City of Baguio"), StreetRules.place("BAGUIO CITY"));
        assertEquals(StreetRules.place("Baguio"), StreetRules.place("  city of  baguio "));
        assertEquals("LA TRINIDAD", StreetRules.place("La Trinidad"));
        assertEquals("", StreetRules.place(null));
    }

    @Test
    void placeholdersAndPastedAddressesAreNotStreets() {
        for (String junk : new String[] {".", "NA", "n/a", "No Details", "**INPUT NOT ACCURATE", "ABATAN, BUGUIAS, BENGUET", "12345", "", "  "}) assertFalse(StreetRules.usable(junk), junk);
        assertFalse(StreetRules.usable("X".repeat(61)));
        assertTrue(StreetRules.usable("Magsaysay Ave."));
        assertTrue(StreetRules.usable("Km 5"));
    }

    @Test
    void mostUsedFirstAndSpellingsOfCaseAndSpacingAreOne() {
        List<Seen> seen = List.of(
            new Seen("Magsaysay Ave", "LA TRINIDAD", "BENGUET"), new Seen("MAGSAYSAY  AVE", null, null), new Seen("magsaysay ave", "La Trinidad", "Benguet"),
            new Seen("Wagner Road", "LA TRINIDAD", "BENGUET"), new Seen("Alno Road", "LA TRINIDAD", "BENGUET"), new Seen("NA", "LA TRINIDAD", "BENGUET"));
        assertEquals(List.of("MAGSAYSAY AVE", "ALNO ROAD", "WAGNER ROAD"), StreetRules.suggestions(seen, "La Trinidad", "Benguet"));
    }

    @Test
    void aRecordFromAnotherCityIsLeftOutButOneWithNoCityStays() {
        List<Seen> seen = List.of(new Seen("Session Road", "BAGUIO", "BENGUET"), new Seen("Wagner Road", "LA TRINIDAD", "BENGUET"), new Seen("Alno Road", "", ""), new Seen("Other St", "BAGUIO", "ABRA"));
        assertEquals(List.of("ALNO ROAD", "SESSION ROAD"), StreetRules.suggestions(seen, "City of Baguio", "Benguet"));
    }

    @Test
    void theListIsCapped() {
        List<Seen> many = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++) many.add(new Seen("Street " + (1000 + i), null, null));
        assertEquals(StreetRules.MAX_SUGGESTIONS, StreetRules.suggestions(many, "", "").size());
    }
}
