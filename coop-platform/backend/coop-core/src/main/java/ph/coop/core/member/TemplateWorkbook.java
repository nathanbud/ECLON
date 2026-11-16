package ph.coop.core.member;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the blank member list as an Excel (.xlsx) workbook without a spreadsheet library: a "Members" sheet with the column names and one example row, and a "Guide" sheet that says what
 * each column is and lists the names that may be written for Gender and Civil status. The Members sheet is the first sheet, which is the one {@link SpreadsheetReader} reads.
 */
public final class TemplateWorkbook {

    private TemplateWorkbook() {}

    static final List<String> COLUMNS = List.of("LName", "FName", "MName", "SName", "DateOfBirth", "Gender", "CivilStatus", "MemberType", "CellNum", "EmailAddress",
        "ResAddStreet", "ResAddBarangay", "ResAddCity", "ResAddProvince", "ResAddZipCode", "Profession", "BusName", "BusJobTitle");

    private static final List<String> EXAMPLE = List.of("Reyes", "Ana", "Santos", "", "1990-02-03", "Female", "Single", "Regular", "09171234567", "ana@example.com",
        "12 Rizal St", "Poblacion", "Baguio City", "Benguet", "2600", "Teacher", "", "");

    private static final List<String[]> NOTES = List.of(
        new String[] {"LName", "Required", "Last name."},
        new String[] {"FName", "Required", "First name."},
        new String[] {"MName / SName", "", "Middle name and suffix (Jr., III)."},
        new String[] {"DateOfBirth", "Required", "Written 2026-04-24, or an Excel date. The member must be at least 18."},
        new String[] {"Gender", "Required", "A name from the list on this sheet, or its code."},
        new String[] {"CivilStatus", "Required", "A name from the list on this sheet, or its code."},
        new String[] {"MemberType", "", "Regular or Associate. Regular if left empty."},
        new String[] {"CellNum, EmailAddress, Res..., Profession, Bus...", "", "Optional profile details."},
        new String[] {"Other columns", "", "Any other column named like a profile field is filled in too; columns the system does not know are ignored."},
        new String[] {"Not in a list", "", "Photos and fingerprints are added afterwards on each member."});

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String colName(int i) {
        String s = "";
        for (int n = i + 1; n > 0; n = (n - 1) / 26) s = (char) ('A' + (n - 1) % 26) + s;
        return s;
    }

    private static String cell(int row, int col, String text, boolean bold) {
        if (text == null || text.isEmpty()) return "";
        return "<c r=\"" + colName(col) + row + "\" t=\"inlineStr\"" + (bold ? " s=\"1\"" : "") + "><is><t xml:space=\"preserve\">" + esc(text) + "</t></is></c>";
    }

    private static String sheet(List<List<String>> rows, int boldRows, int[] widths) {
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        x.append("<cols>");
        for (int i = 0; i < widths.length; i++) x.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"").append(widths[i]).append("\" customWidth=\"1\"/>");
        x.append("</cols><sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            x.append("<row r=\"").append(r + 1).append("\">");
            for (int c = 0; c < rows.get(r).size(); c++) x.append(cell(r + 1, c, rows.get(r).get(c), r < boldRows));
            x.append("</row>");
        }
        return x.append("</sheetData></worksheet>").toString();
    }

    static byte[] build(Map<Integer, String> genders, Map<Integer, String> civilStatuses) {
        List<List<String>> members = List.of(COLUMNS, EXAMPLE);

        List<List<String>> guide = new ArrayList<>();
        guide.add(List.of("Column", "Required?", "What to write"));
        for (String[] n : NOTES) guide.add(List.of(n));
        guide.add(List.of());
        guide.add(List.of("Gender names", "Code"));
        genders.forEach((code, name) -> guide.add(List.of(name == null ? "" : name, Integer.toString(code))));
        guide.add(List.of());
        guide.add(List.of("Civil status names", "Code"));
        civilStatuses.forEach((code, name) -> guide.add(List.of(name == null ? "" : name, Integer.toString(code))));

        int[] memberWidths = new int[COLUMNS.size()];
        java.util.Arrays.fill(memberWidths, 16);
        return workbook("Members", members, memberWidths, guide, new int[] {38, 12, 90});
    }

    /** A two-sheet workbook: the list to fill in (first row bold) under the given name, and a Guide sheet. */
    public static byte[] workbook(String firstName, List<List<String>> first, int[] firstWidths, List<List<String>> guide, int[] guideWidths) {
        String types = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
            + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            + "<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>";
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>";
        String workbook = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"" + esc(firstName) + "\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"Guide\" sheetId=\"2\" r:id=\"rId2\"/></sheets></workbook>";
        String workbookRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
            + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>";
        String styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
            + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
            + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>"
            + "</styleSheet>";

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream z = new ZipOutputStream(out)) {
                put(z, "[Content_Types].xml", types);
                put(z, "_rels/.rels", rels);
                put(z, "xl/workbook.xml", workbook);
                put(z, "xl/_rels/workbook.xml.rels", workbookRels);
                put(z, "xl/styles.xml", styles);
                put(z, "xl/worksheets/sheet1.xml", sheet(first, 1, firstWidths));
                put(z, "xl/worksheets/sheet2.xml", sheet(guide, 1, guideWidths));
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }
}
