package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class SpreadsheetReaderTest {

    @Test
    void csvHandlesQuotesSemicolonsBlankLinesAndTheByteOrderMark() {
        String text = "﻿LName,FName,Note\r\nReyes,Ana,\"said \"\"hi\"\", then left\"\r\n\r\nCruz,Ben,\r\n";
        List<SpreadsheetReader.Row> rows = SpreadsheetReader.read("m.csv", text.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, rows.size());
        assertEquals("said \"hi\", then left", rows.get(0).values().get("Note"));
        assertEquals(2, rows.get(0).line());
        assertEquals(4, rows.get(1).line());
        assertNull(rows.get(1).values().get("Note"));

        var semi = SpreadsheetReader.read("m.csv", "LName;FName\nReyes;Ana\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("Ana", semi.get(0).values().get("FName"));
    }

    @Test
    void emptyOrHeaderlessFilesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> SpreadsheetReader.read("m.csv", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> SpreadsheetReader.read("m.csv", ",,\n".getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] xlsx(String sharedStrings, String sheet) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
            z.write(sharedStrings.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            z.write(sheet.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void xlsxReadsSharedStringsNumbersAndSkippedColumns() throws IOException {
        String shared = "<sst><si><t>LName</t></si><si><t>DateOfBirth</t></si><si><t>Reyes</t></si><si><t>Note</t></si><si><t>ok</t></si></sst>";
        String sheet = "<worksheet><sheetData>"
            + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"D1\" t=\"s\"><v>3</v></c></row>"
            + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\"><v>32905</v></c><c r=\"D2\" t=\"s\"><v>4</v></c></row>"
            + "</sheetData></worksheet>";
        List<SpreadsheetReader.Row> rows = SpreadsheetReader.read("m.xlsx", xlsx(shared, sheet));
        assertEquals(1, rows.size());
        assertEquals("Reyes", rows.get(0).values().get("LName"));
        assertEquals("32905", rows.get(0).values().get("DateOfBirth"));
        assertEquals("ok", rows.get(0).values().get("Note"));
        assertEquals("1990-01-01", SpreadsheetReader.excelDate("32874"));
        assertNull(SpreadsheetReader.excelDate("1990-01-01"));
    }

    @Test
    void aFileThatIsNotAWorkbookIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SpreadsheetReader.read("m.xlsx", "not a zip".getBytes(StandardCharsets.UTF_8)));
    }
}
