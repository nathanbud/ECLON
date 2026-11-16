package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.frontoffice.PassbookRules.Entry;
import ph.coop.core.frontoffice.PassbookRules.Layout;
import ph.coop.core.frontoffice.PassbookRules.Line;
import ph.coop.core.frontoffice.PassbookRules.Page;
import ph.coop.core.frontoffice.PassbookRules.Position;

/**
 * Savings passbook printing (Front Office > Passbook). The old system's passbk row says where an account's passbook stands; the entries after its last
 * printed one are laid out from the next line (PassbookRules) and printed one page at a time (PassbookPdf). The teller confirms a page came out right and
 * only then does the row move on, so a jam or a cancelled print changes nothing. Reprints, a new passbook and corrections of the position are logged in
 * ecis_passbook_print; a new passbook and a correction also need the head cashier or an administrator and a reason. Nothing here posts to the ledger.
 */
@RestController
@RequestMapping("/api/front-office/passbooks")
class PassbookController {

    /** Savings: ledger class 22, and its passbook kind in passbk. */
    static final int SAVINGS = 22;
    private static final int PRINCIPAL = 11;
    private static final int MAX_ENTRIES = 300;

    record AccountRow(int typeCode, String typeName, int refNo, BigDecimal balance, String serial, Integer lastLine, String lastDate, int waiting) {}

    record PositionOut(String serial, int lastLine, int lastSeq, String lastDate, String balance) {}

    record Detail(int clientId, String clientName, int typeCode, String typeName, int refNo, BigDecimal balance, PositionOut position, int waiting,
                  List<Page> pages, List<String> warnings, int linesPerPage, boolean mayCorrect, List<Logged> history) {}

    record Logged(String at, String action, String username, String serial, Integer fromSeq, Integer toSeq, Integer firstLine, Integer lastLine, String reason) {}

    record Confirm(Integer expectedSeq, Integer expectedLine, Integer lastSeq) {}

    record Reprint(Integer fromSeq, Integer toSeq, Integer firstLine, String reason) {}

    record PositionIn(String serial, Boolean newPassbook, Integer lastLine, Integer lastSeq, String reason) {}

    record LayoutIn(Layout layout, String reason) {}

    private record Who(int branch, boolean access, boolean mayCorrect, String username) {}

    private final JdbcTemplate jdbc;
    private final FrontOfficeAccess access;
    private final TransactionTemplate tx;
    private final Clock clock;

    PassbookController(JdbcTemplate jdbc, FrontOfficeAccess access, PlatformTransactionManager txm, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.tx = new TransactionTemplate(txm);
        this.clock = clock;
    }

    // ---- reading ----------------------------------------------------------------------------------------------------

    /** The member's savings accounts, each with where its passbook stands and how many entries wait to be printed. */
    @GetMapping("/accounts")
    List<AccountRow> accounts(@RequestParam int clientId, Authentication auth) {
        Who w = who(auth);
        List<Object[]> accounts = jdbc.query("""
                SELECT s.SDSLT_CODE, t.SLTypeM_DESC, s.SDREF_NO FROM sd s
                LEFT JOIN sltype t ON t.SLTypeBR_CODE = s.SDBR_CODE AND t.SLTypeSLC_CODE = s.SDSLC_CODE AND t.SLTypeSLT_CODE = s.SDSLT_CODE
                WHERE s.SDBR_CODE = ? AND s.ClientIDSD = ? AND s.SDSLC_CODE = ?
                ORDER BY s.SDSLT_CODE, s.SDREF_NO
                """, (rs, i) -> new Object[] {rs.getInt(1), trim(rs.getString(2)), rs.getInt(3)}, w.branch(), clientId, SAVINGS);
        List<AccountRow> out = new ArrayList<>();
        for (Object[] a : accounts) {
            int type = (int) a[0], ref = (int) a[2];
            Position pos = position(w.branch(), clientId, type, ref);
            out.add(new AccountRow(type, (String) a[1], ref, balanceThrough(w.branch(), clientId, type, ref, null), pos == null ? null : pos.serial(),
                pos == null ? null : pos.lastLine(), pos == null || pos.lastDate() == null ? null : pos.lastDate().toString(),
                waiting(w.branch(), clientId, type, ref, pos == null ? 0 : pos.lastSeq())));
        }
        return out;
    }

    @GetMapping("/{clientId}/{typeCode}/{refNo}")
    Detail detail(@PathVariable int clientId, @PathVariable int typeCode, @PathVariable int refNo, Authentication auth) {
        Who w = who(auth);
        return detail(w, clientId, typeCode, refNo);
    }

    private Detail detail(Who w, int clientId, int typeCode, int refNo) {
        Map<String, Object> acct = account(w.branch(), clientId, typeCode, refNo);
        Position pos = position(w.branch(), clientId, typeCode, refNo);
        Layout layout = layout();
        List<String> warnings = new ArrayList<>();
        List<Page> pages = List.of();
        int waiting = 0;
        if (pos == null) {
            warnings.add("This account has no passbook on record yet. The head cashier or an administrator records its passbook number first (New passbook).");
        } else {
            BigDecimal ledgerAt = balanceThrough(w.branch(), clientId, typeCode, refNo, pos.lastSeq());
            if (pos.lastSeq() > 0 && pos.balance() != null && pos.balance().compareTo(ledgerAt) != 0) {
                warnings.add("The passbook's last printed balance (" + PassbookRules.money(pos.balance()) + ") differs from the ledger at that entry (" + PassbookRules.money(ledgerAt)
                    + "). The lines printed now carry the ledger's balance.");
            }
            List<Entry> entries = entries(w.branch(), clientId, typeCode, refNo, pos.lastSeq(), ledgerAt);
            waiting = entries.size();
            pages = PassbookRules.plan(new Position(pos.serial(), pos.lastLine(), pos.lastSeq(), pos.lastDate(), ledgerAt), entries, layout.linesPerPage());
            if (waiting == MAX_ENTRIES) warnings.add("Only the first " + MAX_ENTRIES + " entries are shown; print them and the rest follow.");
        }
        BigDecimal balance = balanceThrough(w.branch(), clientId, typeCode, refNo, null);
        return new Detail(clientId, (String) acct.get("name"), typeCode, (String) acct.get("type"), refNo, balance,
            pos == null ? null : new PositionOut(pos.serial(), pos.lastLine(), pos.lastSeq(), pos.lastDate() == null ? null : pos.lastDate().toString(), PassbookRules.money(pos.balance())),
            waiting, pages, warnings, layout.linesPerPage(), w.mayCorrect(), history(w.branch(), clientId, typeCode, refNo));
    }

    // ---- printing ---------------------------------------------------------------------------------------------------

    /** The next page to print (the first of the plan), as a PDF the size of the passbook. Printing it changes nothing until it is confirmed. */
    @GetMapping("/{clientId}/{typeCode}/{refNo}/next-page.pdf")
    ResponseEntity<byte[]> nextPage(@PathVariable int clientId, @PathVariable int typeCode, @PathVariable int refNo, Authentication auth) {
        Who w = who(auth);
        Detail d = detail(w, clientId, typeCode, refNo);
        if (d.pages().isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, d.position() == null ? d.warnings().get(0) : "Nothing is waiting to be printed in this passbook.");
        return pdf(PassbookPdf.page(layout(), d.pages().get(0).lines()), "passbook-" + clientId + "-" + refNo + ".pdf");
    }

    /**
     * The teller says the next page came out right: the passbook moves on to its last line and entry. Refused (409) when the passbook moved since the
     * page was planned (someone else printed), so a page is never counted twice.
     */
    @PostMapping("/{clientId}/{typeCode}/{refNo}/confirm")
    Detail confirm(@PathVariable int clientId, @PathVariable int typeCode, @PathVariable int refNo, @RequestBody Confirm in, Authentication auth) {
        Who w = who(auth);
        if (in == null || in.lastSeq() == null || in.expectedSeq() == null || in.expectedLine() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say which page was printed.");
        tx.executeWithoutResult((st) -> {
            Position pos = lockPosition(w.branch(), clientId, typeCode, refNo);
            if (pos == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This account has no passbook on record.");
            if (pos.lastSeq() != in.expectedSeq() || pos.lastLine() != in.expectedLine()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The passbook moved on since this page was prepared (someone else printed it). Look again before printing.");
            }
            BigDecimal ledgerAt = balanceThrough(w.branch(), clientId, typeCode, refNo, pos.lastSeq());
            List<Page> pages = PassbookRules.plan(new Position(pos.serial(), pos.lastLine(), pos.lastSeq(), pos.lastDate(), ledgerAt),
                entries(w.branch(), clientId, typeCode, refNo, pos.lastSeq(), ledgerAt), layout().linesPerPage());
            if (pages.isEmpty() || pages.get(0).lastSeq() != in.lastSeq()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The page to print changed (a new entry came in). Look again before printing.");
            }
            Page p = pages.get(0);
            Line last = p.lines().get(p.lines().size() - 1);
            Integer fromSeq = p.lines().stream().map(Line::seq).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            LocalDate lastDate = jdbc.queryForObject("SELECT TR_DATE FROM sldtl WHERE SL_BRCODE = ? AND SL_CLIENTID = ? AND SLC_CODE = ? AND SLT_CODE = ? AND REF_NO = ? AND SEQNO = ?",
                java.sql.Date.class, w.branch(), clientId, SAVINGS, typeCode, refNo, p.lastSeq()).toLocalDate();
            BigDecimal balance = balanceThrough(w.branch(), clientId, typeCode, refNo, p.lastSeq());
            jdbc.update("UPDATE passbk SET PASSBKLINENUM = ?, PASSBKSEQNO = ?, PASSBKLTR_DATE = ?, PASSBKBAL = ? WHERE PASSBKBR_CODE = ? AND PASSBKCLIENTID = ? AND PASSBKSLC_CODE = ? AND PASSBKSLT_CODE = ? AND PASSBKREF_NO = ?",
                last.line(), p.lastSeq(), java.sql.Date.valueOf(lastDate), balance, w.branch(), clientId, SAVINGS, typeCode, String.valueOf(refNo));
            log(w, clientId, typeCode, refNo, "PRINT", pos.serial(), fromSeq, p.lastSeq(), p.firstLine(), last.line(), null);
        });
        return detail(w, clientId, typeCode, refNo);
    }

    /** Prints entries already printed again (a jammed or smudged page), from the line chosen. The passbook does not move; the reprint is logged. */
    @PostMapping("/{clientId}/{typeCode}/{refNo}/reprint.pdf")
    ResponseEntity<byte[]> reprint(@PathVariable int clientId, @PathVariable int typeCode, @PathVariable int refNo, @RequestBody Reprint in, Authentication auth) {
        Who w = who(auth);
        Position pos = position(w.branch(), clientId, typeCode, refNo);
        if (pos == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This account has no passbook on record.");
        if (in == null || in.fromSeq() == null || in.toSeq() == null || in.firstLine() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the entries and the line to start on.");
        String reason = reason(in.reason());
        if (in.toSeq() > pos.lastSeq() || in.fromSeq() > in.toSeq()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only entries already printed can be reprinted; print new ones with Print.");
        Layout l = layout();
        BigDecimal before = balanceBefore(w.branch(), clientId, typeCode, refNo, in.fromSeq());
        List<Entry> entries = entries(w.branch(), clientId, typeCode, refNo, in.fromSeq() - 1, before).stream().filter((e) -> e.seq() <= in.toSeq()).toList();
        if (entries.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "There are no entries in that range.");
        if (in.firstLine() < 1 || in.firstLine() + entries.size() - 1 > l.linesPerPage()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, entries.size() + " entries starting on line " + in.firstLine() + " do not fit on a page of " + l.linesPerPage() + " lines.");
        }
        List<Page> one = PassbookRules.plan(new Position(pos.serial(), in.firstLine() - 1, entries.get(0).seq() - 1, null, before), entries, l.linesPerPage());
        log(w, clientId, typeCode, refNo, "REPRINT", pos.serial(), entries.get(0).seq(), entries.get(entries.size() - 1).seq(), in.firstLine(), in.firstLine() + entries.size() - 1, reason);
        AuditLog.write(jdbc, w.username(), "passbook.reprint", "passbk", key(w.branch(), clientId, typeCode, refNo), null,
            "entries " + in.fromSeq() + "-" + in.toSeq() + " from line " + in.firstLine(), reason);
        return pdf(PassbookPdf.page(l, one.get(0).lines()), "passbook-reprint-" + clientId + "-" + refNo + ".pdf");
    }

    /**
     * Records a new passbook (its number; printing starts on line 1 with the balance carried forward) or corrects where the passbook stands (after a
     * page was confirmed by mistake, or to match the paper). The head cashier or an administrator, with a reason.
     */
    @PostMapping("/{clientId}/{typeCode}/{refNo}/position")
    Detail position(@PathVariable int clientId, @PathVariable int typeCode, @PathVariable int refNo, @RequestBody PositionIn in, Authentication auth) {
        Who w = who(auth);
        if (!w.mayCorrect()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the head cashier or an administrator may record a new passbook or correct its position.");
        if (in == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say what to change.");
        String reason = reason(in.reason());
        account(w.branch(), clientId, typeCode, refNo);
        Layout l = layout();
        tx.executeWithoutResult((st) -> {
            Position pos = lockPosition(w.branch(), clientId, typeCode, refNo);
            boolean fresh = Boolean.TRUE.equals(in.newPassbook()) || pos == null;
            String serial = in.serial() == null ? null : in.serial().trim();
            if (fresh && (serial == null || serial.isEmpty())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type the new passbook's number.");
            if (serial != null && serial.length() > 20) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The passbook number is at most 20 characters.");
            int top = topSeq(w.branch(), clientId, typeCode, refNo);
            int lastSeq = in.lastSeq() != null ? in.lastSeq() : pos != null ? pos.lastSeq() : top;
            if (lastSeq < 0 || lastSeq > top) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The last printed entry must be between 0 and " + top + ".");
            if (lastSeq > 0 && !seqExists(w.branch(), clientId, typeCode, refNo, lastSeq)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Entry " + lastSeq + " is not in this account's ledger.");
            int lastLine = fresh ? 0 : in.lastLine() == null ? pos.lastLine() : in.lastLine();
            if (lastLine < 0 || lastLine > l.linesPerPage()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The last printed line must be between 0 and " + l.linesPerPage() + ".");
            String useSerial = serial != null && !serial.isEmpty() ? serial : pos.serial();
            BigDecimal balance = balanceThrough(w.branch(), clientId, typeCode, refNo, lastSeq);
            List<java.sql.Date> d = lastSeq == 0 ? List.of() : jdbc.queryForList("SELECT TR_DATE FROM sldtl WHERE SL_BRCODE = ? AND SL_CLIENTID = ? AND SLC_CODE = ? AND SLT_CODE = ? AND REF_NO = ? AND SEQNO = ?",
                java.sql.Date.class, w.branch(), clientId, SAVINGS, typeCode, refNo, lastSeq);
            java.sql.Date lastDate = d.isEmpty() ? null : d.get(0);
            if (pos == null) {
                jdbc.update("INSERT INTO passbk (PASSBKBR_CODE, PASSBKCLIENTID, PASSBKSLC_CODE, PASSBKSLT_CODE, PASSBKREF_NO, PASSBKPBTYPE, PASSBKSERNUM, PASSBKLINENUM, PASSBKLTR_DATE, PASSBKBAL, PASSBKSEQNO) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    w.branch(), clientId, SAVINGS, typeCode, String.valueOf(refNo), SAVINGS, useSerial, lastLine, lastDate, balance, lastSeq);
            } else {
                jdbc.update("UPDATE passbk SET PASSBKSERNUM = ?, PASSBKLINENUM = ?, PASSBKSEQNO = ?, PASSBKLTR_DATE = ?, PASSBKBAL = ? WHERE PASSBKBR_CODE = ? AND PASSBKCLIENTID = ? AND PASSBKSLC_CODE = ? AND PASSBKSLT_CODE = ? AND PASSBKREF_NO = ?",
                    useSerial, lastLine, lastSeq, lastDate, balance, w.branch(), clientId, SAVINGS, typeCode, String.valueOf(refNo));
            }
            String before = pos == null ? null : "passbook " + pos.serial() + ", line " + pos.lastLine() + ", entry " + pos.lastSeq();
            String after = "passbook " + useSerial + ", line " + lastLine + ", entry " + lastSeq;
            log(w, clientId, typeCode, refNo, fresh ? "NEW_PASSBOOK" : "POSITION", useSerial, null, lastSeq, null, lastLine, reason);
            AuditLog.write(jdbc, w.username(), fresh ? "passbook.new" : "passbook.position", "passbk", key(w.branch(), clientId, typeCode, refNo), before, after, reason);
        });
        return detail(w, clientId, typeCode, refNo);
    }

    // ---- layout -----------------------------------------------------------------------------------------------------

    @GetMapping("/layout")
    Layout getLayout(Authentication auth) {
        who(auth);
        return layout();
    }

    @PutMapping("/layout")
    Layout putLayout(@RequestBody LayoutIn in, Authentication auth) {
        Who w = who(auth);
        if (!admin(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an administrator may change the passbook layout.");
        if (in == null || in.layout() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send the layout.");
        String reason = reason(in.reason());
        Layout l = in.layout();
        List<String> problems = PassbookRules.problems(l);
        if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", problems));
        Layout before = layout();
        jdbc.update("""
                UPDATE ecis_passbook_layout SET page_width = ?, page_height = ?, lines_per_page = ?, first_line = ?, line_height = ?, fold_after = ?, fold_gap = ?, font_size = ?,
                  date_x = ?, reference_x = ?, deposit_right = ?, withdrawal_right = ?, balance_right = ?, tc_x = ?, teller_x = ?, shift_x = ?, shift_y = ?, updated_by = ?, updated_at = ?
                WHERE passbook_kind = ?
                """, l.pageWidth(), l.pageHeight(), l.linesPerPage(), l.firstLine(), l.lineHeight(), l.foldAfter(), l.foldGap(), l.fontSize(), l.dateX(), l.referenceX(),
            l.depositRight(), l.withdrawalRight(), l.balanceRight(), l.tcX(), l.tellerX(), l.shiftX(), l.shiftY(), w.username(), Timestamp.valueOf(LocalDateTime.now(clock)), SAVINGS);
        AuditLog.write(jdbc, w.username(), "passbook.layout", "ecis_passbook_layout", String.valueOf(SAVINGS), before.toString(), l.toString(), reason);
        return layout();
    }

    /** A test page with every line and column marked, to put through the printer with a real passbook. With a layout sent, that one is tried before saving. */
    @PostMapping("/layout/test.pdf")
    ResponseEntity<byte[]> testPage(@RequestBody(required = false) Layout trial, Authentication auth) {
        who(auth);
        Layout l = trial == null ? layout() : trial;
        List<String> problems = PassbookRules.problems(l);
        if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", problems));
        return pdf(PassbookPdf.testPage(l), "passbook-test-page.pdf");
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private Who who(Authentication auth) {
        FrontOfficeAccess.Perms p = access.of(auth);
        if (!p.access()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot use the front office.");
        List<Integer> head = jdbc.queryForList("SELECT COALESCE(AccessIsHeadCashier, 0) FROM access WHERE AccessUserName = ? ORDER BY AccessID LIMIT 1", Integer.class, auth.getName());
        boolean mayCorrect = admin(auth) || (!head.isEmpty() && head.get(0) == 1);
        return new Who(p.branch(), true, mayCorrect, auth.getName());
    }

    private static boolean admin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch((a) -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private static String reason(String r) {
        String s = r == null ? "" : r.trim();
        if (s.length() < 5) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the reason (at least 5 letters).");
        return s.substring(0, Math.min(s.length(), 300));
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String key(int branch, int clientId, int type, int ref) {
        return branch + "/" + clientId + "/" + SAVINGS + "/" + type + "/" + ref;
    }

    private Map<String, Object> account(int branch, int clientId, int typeCode, int refNo) {
        List<Map<String, Object>> a = jdbc.queryForList("""
                SELECT t.SLTypeM_DESC AS type, CONCAT_WS(', ', c.LName, c.FName) AS name FROM sd s
                JOIN client c ON c.ClientIDBrCode = s.SDBR_CODE AND c.ClientID = s.ClientIDSD
                LEFT JOIN sltype t ON t.SLTypeBR_CODE = s.SDBR_CODE AND t.SLTypeSLC_CODE = s.SDSLC_CODE AND t.SLTypeSLT_CODE = s.SDSLT_CODE
                WHERE s.SDBR_CODE = ? AND s.ClientIDSD = ? AND s.SDSLC_CODE = ? AND s.SDSLT_CODE = ? AND s.SDREF_NO = ?
                """, branch, clientId, SAVINGS, typeCode, refNo);
        if (a.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "There is no such savings account.");
        Map<String, Object> m = new java.util.HashMap<>(a.get(0));
        m.put("type", trim((String) m.get("type")));
        m.put("name", trim((String) m.get("name")));
        return m;
    }

    private Position position(int branch, int clientId, int typeCode, int refNo) {
        List<Position> p = jdbc.query("""
                SELECT PASSBKSERNUM, COALESCE(PASSBKLINENUM, 0), COALESCE(PASSBKSEQNO, 0), PASSBKLTR_DATE, PASSBKBAL FROM passbk
                WHERE PASSBKBR_CODE = ? AND PASSBKCLIENTID = ? AND PASSBKSLC_CODE = ? AND PASSBKSLT_CODE = ? AND PASSBKREF_NO = ?
                """, (rs, i) -> new Position(trim(rs.getString(1)), rs.getInt(2), rs.getInt(3), rs.getDate(4) == null ? null : rs.getDate(4).toLocalDate(), rs.getBigDecimal(5)),
            branch, clientId, SAVINGS, typeCode, String.valueOf(refNo));
        return p.isEmpty() ? null : p.get(0);
    }

    private Position lockPosition(int branch, int clientId, int typeCode, int refNo) {
        jdbc.query("SELECT PASSBKSEQNO FROM passbk WHERE PASSBKBR_CODE = ? AND PASSBKCLIENTID = ? AND PASSBKSLC_CODE = ? AND PASSBKSLT_CODE = ? AND PASSBKREF_NO = ? FOR UPDATE",
            (rs) -> { }, branch, clientId, SAVINGS, typeCode, String.valueOf(refNo));
        return position(branch, clientId, typeCode, refNo);
    }

    /** The account's balance after entry {@code seq} (all entries when null). Credits are negative in the ledger. */
    private BigDecimal balanceThrough(int branch, int clientId, int typeCode, int refNo, Integer seq) {
        BigDecimal b = jdbc.queryForObject("SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = ? AND SL_CLIENTID = ? AND SLC_CODE = ? AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = ?"
            + (seq == null ? "" : " AND SEQNO <= " + seq), BigDecimal.class, branch, clientId, SAVINGS, typeCode, refNo, PRINCIPAL);
        return b == null ? BigDecimal.ZERO : b;
    }

    private BigDecimal balanceBefore(int branch, int clientId, int typeCode, int refNo, int seq) {
        return balanceThrough(branch, clientId, typeCode, refNo, seq - 1);
    }

    private int topSeq(int branch, int clientId, int typeCode, int refNo) {
        Integer n = jdbc.queryForObject("SELECT COALESCE(MAX(SEQNO), 0) FROM sldtl WHERE SL_BRCODE = ? AND SL_CLIENTID = ? AND SLC_CODE = ? AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = ?",
            Integer.class, branch, clientId, SAVINGS, typeCode, refNo, PRINCIPAL);
        return n == null ? 0 : n;
    }

    private boolean seqExists(int branch, int clientId, int typeCode, int refNo, int seq) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sldtl WHERE SL_BRCODE = ? AND SL_CLIENTID = ? AND SLC_CODE = ? AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = ? AND SEQNO = ?",
            Integer.class, branch, clientId, SAVINGS, typeCode, refNo, PRINCIPAL, seq);
        return n != null && n > 0;
    }

    private int waiting(int branch, int clientId, int typeCode, int refNo, int afterSeq) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sldtl WHERE SL_BRCODE = ? AND SL_CLIENTID = ? AND SLC_CODE = ? AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = ? AND SEQNO > ?",
            Integer.class, branch, clientId, SAVINGS, typeCode, refNo, PRINCIPAL, afterSeq);
        return n == null ? 0 : n;
    }

    /** The account's entries after {@code afterSeq}, in order, with the running balance from {@code balanceBefore}, the transaction code and the teller number. */
    private List<Entry> entries(int branch, int clientId, int typeCode, int refNo, int afterSeq, BigDecimal balanceBefore) {
        List<Object[]> rows = jdbc.query("""
                SELECT l.SEQNO, l.TR_DATE, l.TR_DOCNO, l.AMT, l.TR_CODE, l.TR_CTLNO, y.TransTypeDesc2 FROM sldtl l
                LEFT JOIN transtype y ON y.TransTypeID = l.TR_CODE
                WHERE l.SL_BRCODE = ? AND l.SL_CLIENTID = ? AND l.SLC_CODE = ? AND l.SLT_CODE = ? AND l.REF_NO = ? AND l.SLE_CODE = ? AND l.SEQNO > ?
                ORDER BY l.SEQNO LIMIT ?
                """, (rs, i) -> new Object[] {rs.getInt(1), rs.getDate(2).toLocalDate(), trim(rs.getString(3)), rs.getBigDecimal(4), rs.getInt(5), rs.getLong(6), trim(rs.getString(7))},
            branch, clientId, SAVINGS, typeCode, refNo, PRINCIPAL, afterSeq, MAX_ENTRIES);
        List<Entry> out = new ArrayList<>();
        BigDecimal bal = balanceBefore;
        for (Object[] r : rows) {
            BigDecimal amt = (BigDecimal) r[3];
            bal = bal.subtract(amt);
            LocalDate d = (LocalDate) r[1];
            out.add(new Entry((int) r[0], d, r[2] == null ? "" : (String) r[2], amt, bal, r[6] == null ? "" : (String) r[6], teller(branch, d.getYear(), (int) r[4], (long) r[5])));
        }
        return out;
    }

    /** The teller number of whoever encoded the voucher behind an entry; blank for vouchers encoded by someone without one (interest runs, journals). */
    private String teller(int branch, int year, int code, long ctl) {
        List<Integer> n = jdbc.queryForList("""
                SELECT a.AccessTellerNumber FROM transdt d JOIN access a ON a.AccessID = d.DTENCDBY AND a.AccessBR_CODE = d.DTTR_BRCODE
                WHERE d.DTTR_BRCODE = ? AND d.DTTR_YEAR = ? AND d.DTTR_CODE = ? AND d.DTTR_CTLNO = ? ORDER BY d.DTSEQ_NO LIMIT 1
                """, Integer.class, branch, year, code, ctl);
        return n.isEmpty() || n.get(0) == null || n.get(0) <= 0 ? "" : String.valueOf(n.get(0));
    }

    Layout layout() {
        List<Layout> l = jdbc.query("""
                SELECT page_width, page_height, lines_per_page, first_line, line_height, fold_after, fold_gap, font_size, date_x, reference_x, deposit_right, withdrawal_right,
                  balance_right, tc_x, teller_x, shift_x, shift_y FROM ecis_passbook_layout WHERE passbook_kind = ?
                """, (rs, i) -> new Layout(rs.getDouble(1), rs.getDouble(2), rs.getInt(3), rs.getDouble(4), rs.getDouble(5), rs.getInt(6), rs.getDouble(7), rs.getDouble(8),
            rs.getDouble(9), rs.getDouble(10), rs.getDouble(11), rs.getDouble(12), rs.getDouble(13), rs.getDouble(14), rs.getDouble(15), rs.getDouble(16), rs.getDouble(17)), SAVINGS);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Passbook printing is not set up in this database yet. An administrator has to apply the passbook migration.");
        return l.get(0);
    }

    private List<Logged> history(int branch, int clientId, int typeCode, int refNo) {
        return jdbc.query("""
                SELECT at, action, username, serial, from_seq, to_seq, first_line, last_line, reason FROM ecis_passbook_print
                WHERE branch_code = ? AND client_id = ? AND slc_code = ? AND slt_code = ? AND ref_no = ? ORDER BY id DESC LIMIT 15
                """, (rs, i) -> new Logged(rs.getTimestamp(1).toLocalDateTime().toString().replace('T', ' ').substring(0, 16), rs.getString(2), rs.getString(3), rs.getString(4),
            (Integer) rs.getObject(5, Integer.class), (Integer) rs.getObject(6, Integer.class), (Integer) rs.getObject(7, Integer.class), (Integer) rs.getObject(8, Integer.class), rs.getString(9)),
            branch, clientId, SAVINGS, typeCode, String.valueOf(refNo));
    }

    private void log(Who w, int clientId, int typeCode, int refNo, String action, String serial, Integer fromSeq, Integer toSeq, Integer firstLine, Integer lastLine, String reason) {
        jdbc.update("INSERT INTO ecis_passbook_print (branch_code, client_id, slc_code, slt_code, ref_no, action, serial, from_seq, to_seq, first_line, last_line, username, at, reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            w.branch(), clientId, SAVINGS, typeCode, String.valueOf(refNo), action, serial, fromSeq, toSeq, firstLine, lastLine, w.username(), Timestamp.valueOf(LocalDateTime.now(clock)), reason);
    }

    private static ResponseEntity<byte[]> pdf(byte[] bytes, String name) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"").body(bytes);
    }
}
