package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Passbook printing without the database (PassbookRulesTest): which entries go on which line of which page, where a line sits on the paper, and
 * whether a layout makes sense. A passbook's position is the old system's passbk row: the last line printed and the last ledger entry printed. The
 * next entry goes on the next line; a full page continues at line 1 of the next page. A new passbook (position line 0 with entries printed before)
 * starts with a "balance forwarded" line carrying the balance over.
 */
public final class PassbookRules {

    private PassbookRules() {}

    /** Where the lines go, in inches from the top left corner of the open passbook (ecis_passbook_layout). */
    public record Layout(double pageWidth, double pageHeight, int linesPerPage, double firstLine, double lineHeight, int foldAfter, double foldGap,
                         double fontSize, double dateX, double referenceX, double depositRight, double withdrawalRight, double balanceRight,
                         double tcX, double tellerX, double shiftX, double shiftY) {}

    /** Where a passbook stands: its number, the last line printed (0: nothing on the current passbook yet), the last ledger entry printed (0: none). */
    public record Position(String serial, int lastLine, int lastSeq, LocalDate lastDate, BigDecimal balance) {}

    /** One ledger entry of the account (savings, event 11). {@code amount} as the ledger keeps it: negative = deposit; balance = after it. */
    public record Entry(int seq, LocalDate date, String reference, BigDecimal amount, BigDecimal balance, String tc, String teller) {}

    /** One printed line. {@code seq} is null on the balance forwarded line. */
    public record Line(int line, Integer seq, String date, String reference, String deposit, String withdrawal, String balance, String tc, String teller) {}

    /** One page to print: its lines, from {@code firstLine}; {@code newPage} when it starts on a fresh page; {@code lastSeq} the last entry on it. */
    public record Page(int number, boolean newPage, int firstLine, int lastLine, int lastSeq, List<Line> lines) {}

    public static final String FORWARD = "BAL FWD";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ENGLISH);

    public static String date(LocalDate d) {
        return d == null ? "" : d.format(DATE);
    }

    public static String money(BigDecimal v) {
        if (v == null) return "";
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        return f.format(v);
    }

    /** The line the next entry goes on: the one after the last, or 1 on a fresh page when the page is full. */
    public static int nextLine(int lastLine, int perPage) {
        return lastLine >= perPage ? 1 : lastLine + 1;
    }

    /** The unprinted entries laid out page by page from the position. Empty when nothing is waiting. */
    public static List<Page> plan(Position pos, List<Entry> unprinted, int perPage) {
        List<Page> pages = new ArrayList<>();
        if (unprinted.isEmpty()) return pages;
        List<Line> pending = new ArrayList<>();
        // the line items in order, with their entry (null for the forward line)
        List<Object[]> items = new ArrayList<>();
        if (pos.lastLine() == 0 && pos.lastSeq() > 0) items.add(new Object[] {null, pos.balance()});
        for (Entry e : unprinted) items.add(new Object[] {e, null});
        int line = nextLine(pos.lastLine(), perPage);
        boolean fresh = pos.lastLine() == 0 || pos.lastLine() >= perPage;
        int first = line, lastSeq = pos.lastSeq();
        for (Object[] it : items) {
            if (line > perPage) {
                pages.add(new Page(pages.size() + 1, fresh, first, perPage, lastSeq, List.copyOf(pending)));
                pending.clear();
                line = 1;
                first = 1;
                fresh = true;
            }
            Entry e = (Entry) it[0];
            if (e == null) {
                pending.add(new Line(line, null, "", FORWARD, "", "", money((BigDecimal) it[1]), "", ""));
            } else {
                boolean in = e.amount().signum() < 0;
                pending.add(new Line(line, e.seq(), date(e.date()), e.reference(), in ? money(e.amount().negate()) : "", in ? "" : money(e.amount()),
                    money(e.balance()), e.tc(), e.teller()));
                lastSeq = e.seq();
            }
            line++;
        }
        pages.add(new Page(pages.size() + 1, fresh, first, line - 1, lastSeq, List.copyOf(pending)));
        return pages;
    }

    /** The line's text baseline, in inches from the top edge: below the fold, the fold's gap is added. */
    public static double baseline(Layout l, int line) {
        return l.firstLine() + (line - 1) * l.lineHeight() + (line > l.foldAfter() ? l.foldGap() : 0) + l.shiftY();
    }

    /** What is wrong with a layout, if anything: every line must be on the page and the columns in order inside it. */
    public static List<String> problems(Layout l) {
        List<String> out = new ArrayList<>();
        if (l.pageWidth() < 2 || l.pageWidth() > 14 || l.pageHeight() < 2 || l.pageHeight() > 14) out.add("The page must be between 2 and 14 inches each way.");
        if (l.linesPerPage() < 1 || l.linesPerPage() > 80) out.add("A page holds between 1 and 80 lines.");
        if (l.lineHeight() < 0.08 || l.lineHeight() > 1) out.add("The line height must be between 0.08 and 1 inch.");
        if (l.fontSize() < 5 || l.fontSize() > 14) out.add("The type size must be between 5 and 14 points.");
        if (l.foldAfter() < 0 || l.foldAfter() > l.linesPerPage()) out.add("The fold must come after a line of the page (0 when there is none).");
        if (l.firstLine() < 0 || l.foldGap() < 0) out.add("The top margin and the fold gap cannot be negative.");
        if (out.isEmpty() && baseline(l, l.linesPerPage()) > l.pageHeight()) {
            out.add("The last line falls " + String.format(Locale.ENGLISH, "%.2f", baseline(l, l.linesPerPage())) + " inches down, below the page (" + l.pageHeight() + " inches).");
        }
        double[] order = {l.dateX(), l.referenceX(), l.depositRight(), l.withdrawalRight(), l.balanceRight(), l.tcX(), l.tellerX()};
        for (int i = 1; i < order.length; i++) {
            if (order[i] <= order[i - 1]) { out.add("The columns must run left to right: Date, Reference, Deposit, Withdrawal, Balance, TC, Teller No."); break; }
        }
        if (l.dateX() + l.shiftX() < 0 || l.tellerX() + l.shiftX() >= l.pageWidth()) out.add("Every column must be on the page.");
        return out;
    }
}
