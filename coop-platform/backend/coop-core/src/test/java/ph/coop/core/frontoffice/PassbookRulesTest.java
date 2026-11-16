package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ph.coop.core.frontoffice.PassbookRules.Entry;
import ph.coop.core.frontoffice.PassbookRules.Layout;
import ph.coop.core.frontoffice.PassbookRules.Page;
import ph.coop.core.frontoffice.PassbookRules.Position;

class PassbookRulesTest {

    /** The default layout of the foldable savings passbook (V028). */
    static final Layout DEFAULT = new Layout(5.5, 8, 31, 0.55, 0.2, 16, 0.35, 8, 0.15, 0.85, 2.65, 3.6, 4.65, 4.75, 5.1, 0, 0);

    private static List<Entry> entries(int fromSeq, int n) {
        List<Entry> out = new ArrayList<>();
        BigDecimal bal = new BigDecimal("1000.00");
        for (int i = 0; i < n; i++) {
            BigDecimal amt = i % 2 == 0 ? new BigDecimal("-500.00") : new BigDecimal("200.00");
            bal = bal.subtract(amt);
            out.add(new Entry(fromSeq + i, LocalDate.of(2026, 11, 2 + i), "*00" + (100 + i), amt, bal, i % 2 == 0 ? "CR" : "SW", "7"));
        }
        return out;
    }

    @Test
    void continuesOnTheNextLineAndTurnsThePageWhenItIsFull() {
        Position pos = new Position("00008567", 28, 70, LocalDate.of(2026, 4, 10), new BigDecimal("1000.00"));
        List<Page> pages = PassbookRules.plan(pos, entries(71, 5), 31);
        assertEquals(2, pages.size());
        assertEquals(29, pages.get(0).firstLine());
        assertEquals(31, pages.get(0).lastLine());
        assertFalse(pages.get(0).newPage(), "the first page goes on the page already in the passbook");
        assertEquals(73, pages.get(0).lastSeq());
        assertEquals(1, pages.get(1).firstLine());
        assertTrue(pages.get(1).newPage());
        assertEquals(List.of(1, 2), pages.get(1).lines().stream().map(PassbookRules.Line::line).toList());
        assertEquals(75, pages.get(1).lastSeq());
    }

    @Test
    void writesDepositsAndWithdrawalsInTheirColumnsWithTheRunningBalance() {
        var lines = PassbookRules.plan(new Position("1", 3, 9, null, new BigDecimal("1000.00")), entries(10, 2), 31).get(0).lines();
        var in = lines.get(0);
        assertEquals(4, in.line());
        assertEquals("11/02/26", in.date());
        assertEquals("500.00", in.deposit());
        assertEquals("", in.withdrawal());
        assertEquals("1,500.00", in.balance());
        assertEquals("CR", in.tc());
        var out = lines.get(1);
        assertEquals("", out.deposit());
        assertEquals("200.00", out.withdrawal());
        assertEquals("1,300.00", out.balance());
    }

    @Test
    void aFullPageStartsTheNextOnLineOne() {
        Page p = PassbookRules.plan(new Position("1", 31, 9, null, BigDecimal.TEN), entries(10, 1), 31).get(0);
        assertEquals(1, p.firstLine());
        assertTrue(p.newPage());
        // a passbook from the old system that went past this layout's last line
        assertEquals(1, PassbookRules.plan(new Position("1", 33, 9, null, BigDecimal.TEN), entries(10, 1), 31).get(0).firstLine());
    }

    @Test
    void aNewPassbookStartsWithTheBalanceForwarded() {
        Page p = PassbookRules.plan(new Position("00009999", 0, 70, null, new BigDecimal("376036.96")), entries(71, 2), 31).get(0);
        assertTrue(p.newPage());
        assertEquals(1, p.firstLine());
        var fwd = p.lines().get(0);
        assertNull(fwd.seq());
        assertEquals(PassbookRules.FORWARD, fwd.reference());
        assertEquals("376,036.96", fwd.balance());
        assertEquals(2, p.lines().get(1).line());
        assertEquals(3, p.lastLine());
        // an account with nothing printed ever starts at its first entry, without a forward line
        assertEquals(71, PassbookRules.plan(new Position("1", 0, 0, null, BigDecimal.ZERO), entries(71, 1), 31).get(0).lines().get(0).seq());
    }

    @Test
    void nothingWaitingMeansNothingToPrint() {
        assertTrue(PassbookRules.plan(new Position("1", 5, 9, null, BigDecimal.TEN), List.of(), 31).isEmpty());
    }

    @Test
    void linesBelowTheFoldSkipTheFold() {
        assertEquals(0.55, PassbookRules.baseline(DEFAULT, 1), 1e-9);
        assertEquals(0.55 + 15 * 0.2, PassbookRules.baseline(DEFAULT, 16), 1e-9);
        assertEquals(0.55 + 16 * 0.2 + 0.35, PassbookRules.baseline(DEFAULT, 17), 1e-9);
        assertTrue(PassbookRules.baseline(DEFAULT, 31) < 8, "the default layout fits the 8-inch page");
    }

    @Test
    void theDefaultLayoutIsSoundAndBadOnesAreRefused() {
        assertEquals(List.of(), PassbookRules.problems(DEFAULT));
        Layout tooLong = new Layout(5.5, 8, 40, 0.55, 0.2, 16, 0.35, 8, 0.15, 0.85, 2.65, 3.6, 4.65, 4.75, 5.1, 0, 0);
        assertTrue(PassbookRules.problems(tooLong).get(0).contains("below the page"), PassbookRules.problems(tooLong).toString());
        Layout crossed = new Layout(5.5, 8, 31, 0.55, 0.2, 16, 0.35, 8, 0.15, 0.85, 3.7, 3.6, 4.65, 4.75, 5.1, 0, 0);
        assertTrue(PassbookRules.problems(crossed).get(0).contains("left to right"));
    }

    @Test
    void thePdfIsThePassbooksSize() throws Exception {
        byte[] pdf = PassbookPdf.page(DEFAULT, PassbookRules.plan(new Position("1", 3, 9, null, BigDecimal.TEN), entries(10, 3), 31).get(0).lines());
        assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        try (var doc = new com.lowagie.text.pdf.PdfReader(pdf)) {
            var size = doc.getPageSize(1);
            assertEquals(5.5 * 72, size.getWidth(), 0.01);
            assertEquals(8 * 72, size.getHeight(), 0.01);
            String text = new com.lowagie.text.pdf.parser.PdfTextExtractor(doc).getTextFromPage(1);
            assertTrue(text.contains("11/02/26") && text.contains("1,500.00"), text);
        }
        assertEquals("%PDF", new String(PassbookPdf.testPage(DEFAULT), 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
    }
}
