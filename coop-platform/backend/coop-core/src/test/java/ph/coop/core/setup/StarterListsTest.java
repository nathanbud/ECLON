package ph.coop.core.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The starter setup's pure rules and the package it ships: CSV as psql writes it, conversion to column kinds, key comparison, and a complete catalog. */
class StarterListsTest {

    @Test
    void anEmptyUnquotedFieldIsMissingWhileQuotesKeepAnEmptyText() {
        List<List<String>> rows = StarterLists.csv("id,desc,note\n11,Current,\"\"\n12,,\n");
        assertEquals(List.of("id", "desc", "note"), rows.get(0));
        assertEquals(Arrays.asList("11", "Current", ""), rows.get(1));
        assertEquals(Arrays.asList("12", null, null), rows.get(2));
    }

    @Test
    void quotedFieldsKeepCommasQuotesAndLineBreaks() {
        List<List<String>> rows = StarterLists.csv("﻿a,b\r\n1,\"12xx,13xx,\"\r\n2,\"say \"\"hi\"\"\nthere\"\r\n");
        assertEquals(List.of("a", "b"), rows.get(0), "a byte order mark is dropped");
        assertEquals(List.of("1", "12xx,13xx,"), rows.get(1));
        assertEquals(List.of("2", "say \"hi\"\nthere"), rows.get(2));
        assertThrows(IllegalArgumentException.class, () -> StarterLists.csv("a\n\"open"));
    }

    @Test
    void fieldsBecomeTheirColumnsKind() {
        assertEquals(7L, StarterLists.convert("7", Types.INTEGER));
        assertEquals(new BigDecimal("1000.00"), StarterLists.convert("1000.00", Types.NUMERIC));
        assertEquals(Date.valueOf("2020-01-14"), StarterLists.convert("2020-01-14", Types.DATE));
        assertEquals(Timestamp.valueOf("2020-01-14 08:30:00"), StarterLists.convert("2020-01-14 08:30:00+08", Types.TIMESTAMP), "a time zone is dropped");
        assertEquals(Timestamp.valueOf("2020-01-14 00:00:00"), StarterLists.convert("2020-01-14", Types.TIMESTAMP));
        assertEquals(true, StarterLists.convert("t", Types.BOOLEAN));
        assertEquals(1L, StarterLists.convert("true", Types.SMALLINT), "a PostgreSQL true into a MySQL tinyint");
        assertEquals("", StarterLists.convert("", Types.VARCHAR));
        assertNull(StarterLists.convert(null, Types.INTEGER));
        assertNull(StarterLists.convert("", Types.INTEGER));
    }

    @Test
    void keysCompareAsTheDatabasesDo() {
        assertEquals(StarterLists.key(List.of(1)), StarterLists.key(List.of(new BigDecimal("1.00"))));
        assertEquals(StarterLists.key(List.of(1L, "Male ")), StarterLists.key(List.of(1, "male")));
        assertNotEquals(StarterLists.key(List.of(1, 2)), StarterLists.key(List.of(12)));
        assertNotEquals(StarterLists.key(Arrays.asList((Object) null)), StarterLists.key(List.of("")));
    }

    @Test
    void theCatalogIsReadInOrderAndABadLineIsRefused() {
        List<StarterLists.Entry> c = StarterLists.catalog("# comment\n\nloanclass|Loans|Loan classes\ngender|Members|Genders\n");
        assertEquals(List.of("loanclass", "gender"), c.stream().map(StarterLists.Entry::table).toList());
        assertThrows(IllegalArgumentException.class, () -> StarterLists.catalog("gender|Members"));
    }

    @Test
    void everyListInTheCatalogShipsWithItsFileAndNothingElseDoes() throws Exception {
        List<StarterLists.Entry> c = StarterLists.catalog(StarterSetupController.resource("starter/lists.txt"));
        Set<String> tables = c.stream().map(StarterLists.Entry::table).collect(Collectors.toSet());
        assertEquals(c.size(), tables.size(), "a list is named twice");
        Set<String> files;
        try (Stream<Path> s = Files.list(Path.of("src", "main", "resources", "starter", "lists"))) {
            files = s.map((p) -> p.getFileName().toString().replaceFirst("\\.csv$", "")).collect(Collectors.toCollection(HashSet::new));
        }
        assertEquals(tables, files);
        for (StarterLists.Entry e : c) {
            List<List<String>> rows = StarterLists.csv(StarterSetupController.resource("starter/lists/" + e.table() + ".csv"));
            assertTrue(rows.size() > 1, e.table() + " has a header and rows");
            for (List<String> r : rows) assertEquals(rows.get(0).size(), r.size(), e.table() + ": every row has every column");
            assertTrue(!e.group().isBlank() && !e.label().isBlank(), e.table() + " has a group and words");
        }
        List<String> order = c.stream().map(StarterLists.Entry::table).toList();
        assertTrue(order.indexOf("loanclass") < order.indexOf("loanpurpose"), "a loan purpose points to its loan class");
        for (String t : List.of("cts_type", "sle_type", "gender", "civilstat", "slclass", "term", "paymentmode", "denomtype"))
            assertTrue(tables.contains(t), t + " is one of the lists");
    }
}
