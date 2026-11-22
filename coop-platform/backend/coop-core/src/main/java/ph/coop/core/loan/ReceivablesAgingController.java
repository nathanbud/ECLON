package ph.coop.core.loan;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.loan.LoanRules.Arrears;
import ph.coop.core.loan.LoanRules.Due;
import ph.coop.core.loan.LoanWorksheet.ScheduleDate;
import ph.coop.core.loan.ReceivableAccrual.Accrual;
import ph.coop.core.loan.ReceivableAccrual.Entry;
import ph.coop.core.loan.ReceivablesAging.Account;
import ph.coop.core.loan.ReceivablesAging.Basis;
import ph.coop.core.loan.ReceivablesAging.Line;
import ph.coop.core.loan.ReceivablesAging.Spec;

/**
 * Aging of receivables (Reports > Receivables): the old system's AGING OF RECEIVABLES FILTER rebuilt. Reads the subsidiary ledger of the receivable classes (12 loans,
 * 13 accounts receivable, 25 investment deposit plan; event 11 is the principal, so the balance is the plain sum), the account rows ({@code loan}, {@code ar}), the
 * repayment schedule ({@code amortsched}) for days late, and the client for the groupings. Read only; administrators and accountants, like the loan reports; every run is
 * audited as {@code report.run} with its choices. The ageing and grouping are in {@link ReceivablesAging}.
 */
@RestController
@RequestMapping("/api/reports/receivables-aging")
class ReceivablesAgingController {

    static final String REPORT_ID = "receivables-aging";
    private static final int BRANCH = 1, EVENT_PRINCIPAL = 11;
    static final List<Integer> CLASSES = List.of(12, 13, 25);

    record TypeOption(int code, String name) {}

    record ClassOption(int code, String name, List<TypeOption> types) {}

    record Choice(String key, String label) {}

    record Options(LocalDate cutOff, List<ClassOption> classes, List<Choice> groupings, List<Choice> sorts, List<String> buckets) {}

    /** What the screen sends. Types are "class:type" (e.g. "12:3"); an empty list means all. Dates and amounts are optional filters. */
    record Request(LocalDate cutOff, String basis, Integer parDays, List<Integer> classes, List<String> types,
                   LocalDate releasedFrom, LocalDate releasedTo, LocalDate maturityFrom, LocalDate maturityTo, LocalDate lastTransFrom, LocalDate lastTransTo,
                   BigDecimal minBalance, BigDecimal maxBalance, Long minDays, Long maxDays, List<String> groupBy, String sort, Boolean totalsOnly,
                   Boolean accruedInterest, Boolean penalty, Boolean owingOnly) {

        /** The shape without the Accrued Interest tab. */
        Request(LocalDate cutOff, String basis, Integer parDays, List<Integer> classes, List<String> types,
                LocalDate releasedFrom, LocalDate releasedTo, LocalDate maturityFrom, LocalDate maturityTo, LocalDate lastTransFrom, LocalDate lastTransTo,
                BigDecimal minBalance, BigDecimal maxBalance, Long minDays, Long maxDays, List<String> groupBy, String sort, Boolean totalsOnly) {
            this(cutOff, basis, parDays, classes, types, releasedFrom, releasedTo, maturityFrom, maturityTo, lastTransFrom, lastTransTo, minBalance, maxBalance, minDays, maxDays,
                groupBy, sort, totalsOnly, false, false, false);
        }

        boolean withInterest() {
            return Boolean.TRUE.equals(accruedInterest);
        }

        boolean withPenalty() {
            return withInterest() && Boolean.TRUE.equals(penalty);
        }
    }

    /** accrual and penalty say which of the Accrued Interest tab's columns were worked out. */
    record Response(LocalDate cutOff, String basis, int parDays, List<String> buckets, List<String> groupBy, List<Line> lines, String note, LocalDateTime ranAt, String ranBy,
                    boolean accrual, boolean penalty) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ph.coop.core.db.Db db;

    /** A computed run with what its files print above and below the table: the choices in words. */
    record Computed(Request request, Response response, List<String[]> choices, String described, long accounts, long at) {}

    /** Each person's last run, so Excel and PDF of what is on screen come at once instead of reading the ledgers again (10 minutes, same choices only). */
    private final Map<String, Computed> lastRun = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long KEEP_MS = 10 * 60_000L;

    ReceivablesAgingController(JdbcTemplate jdbc, Clock clock, ph.coop.core.db.Db db) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.db = db;
    }

    static boolean may(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_ACCOUNTANT"));
    }

    private static void check(Authentication auth) {
        if (!may(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only administrators and accountants can run the aging of receivables.");
    }

    @GetMapping("/options")
    Options options(Authentication auth) {
        check(auth);
        Map<Integer, String> classNames = lookup("SELECT SLClassID, SLClassDesc FROM slclass");
        Map<Integer, List<TypeOption>> types = new HashMap<>();
        jdbc.query("SELECT SLTypeSLC_CODE, SLTypeSLT_CODE, SLTypeM_DESC FROM sltype WHERE SLTypeBR_CODE = ? ORDER BY SLTypeSLC_CODE, SLTypeSLT_CODE",
            (rs) -> { types.computeIfAbsent(rs.getInt(1), k -> new ArrayList<>()).add(new TypeOption(rs.getInt(2), rs.getString(3))); }, BRANCH);
        List<ClassOption> classes = new ArrayList<>();
        for (int c : CLASSES) classes.add(new ClassOption(c, classNames.getOrDefault(c, "Class " + c), types.getOrDefault(c, List.of())));
        List<Choice> groupings = ReceivablesAging.GROUPINGS.entrySet().stream().map((e) -> new Choice(e.getKey(), e.getValue())).toList();
        List<Choice> sorts = ReceivablesAging.SORTS.entrySet().stream().map((e) -> new Choice(e.getKey(), e.getValue())).toList();
        return new Options(LocalDate.now(clock), classes, groupings, sorts, ReceivablesAging.BUCKETS);
    }

    @PostMapping
    Response run(@RequestBody Request req, Authentication auth) {
        check(auth);
        Computed c = compute(req, auth.getName());
        lastRun.put(auth.getName(), c);
        AuditLog.write(jdbc, auth.getName(), "report.run", "report", REPORT_ID, null, c.described() + " -> " + c.accounts() + " accounts", null);
        return c.response();
    }

    /** The report as an Excel workbook (groups fold in Excel's outline) or a PDF on legal paper, landscape; written to the export log and the audit log. */
    @PostMapping("/{kind:pdf|xlsx}")
    ResponseEntity<byte[]> download(@PathVariable String kind, @RequestBody Request req, Authentication auth) {
        check(auth);
        Computed c = lastRun.get(auth.getName());
        if (c == null || !c.request().equals(req) || System.currentTimeMillis() - c.at() > KEEP_MS) {
            c = compute(req, auth.getName());
            lastRun.put(auth.getName(), c);
            AuditLog.write(jdbc, auth.getName(), "report.run", "report", REPORT_ID, null, c.described() + " -> " + c.accounts() + " accounts", null);
        }
        ReceivablesAgingDocuments.Header header = header();
        boolean pdf = kind.equals("pdf");
        byte[] bytes = pdf ? ReceivablesAgingDocuments.pdf(c.response(), c.choices(), header) : ReceivablesAgingDocuments.xlsx(c.response(), c.choices(), header);
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update("INSERT INTO ecis_data_export (at, username, export_type, subject, row_count, purpose) VALUES (?, ?, 'REPORT', ?, ?, ?)",
            java.sql.Timestamp.valueOf(now), auth.getName(), "Aging of receivables as of " + c.response().cutOff(), c.response().lines().size(), "Aging of receivables (" + kind + ")");
        AuditLog.write(jdbc, auth.getName(), "report.export", "report", REPORT_ID, null, kind + ": " + c.described() + " -> " + c.accounts() + " accounts", null);
        String name = "aging-of-receivables-" + c.response().cutOff() + "." + kind;
        return ResponseEntity.ok().contentType(pdf ? MediaType.APPLICATION_PDF : XLSX).header("Content-Disposition", "attachment; filename=\"" + name + "\"").body(bytes);
    }

    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    /** The cooperative's name and the office, printed at the top of the files. */
    ReceivablesAgingDocuments.Header header() {
        String coop = null;
        for (String[] src : new String[][] {{"coopsystem_report_header", "SELECT coop_name FROM coopsystem_report_header"}, {"ecis_branding", "SELECT coop_name FROM ecis_branding"}}) {
            if (coop != null || !db.tableExists(src[0])) continue;
            List<String> n = jdbc.query(src[1], (rs, i) -> rs.getString(1));
            if (!n.isEmpty() && n.get(0) != null && !n.get(0).isBlank()) coop = n.get(0).trim();
        }
        List<String[]> office = jdbc.query("SELECT BranchesDesc, Address, ACRONYM FROM branches WHERE BranchesID = ?",
            (rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}, BRANCH);
        String[] o = office.isEmpty() ? new String[3] : office.get(0);
        if (coop == null) coop = o[2] == null || o[2].isBlank() ? "Cooperative" : o[2].trim();
        return new ReceivablesAgingDocuments.Header(coop, o[0] == null ? "" : o[0].trim(), o[1] == null ? "" : o[1].trim());
    }

    /** One run without the audit row (the caller writes it). */
    Computed compute(Request req, String user) {
        LocalDate cutOff = req.cutOff() == null ? LocalDate.now(clock) : req.cutOff();
        Basis basis;
        try {
            basis = req.basis() == null ? Basis.PAR : Basis.valueOf(req.basis().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Age by PAR or by maturity.");
        }
        Spec spec;
        try {
            spec = new Spec(basis, req.parDays() == null ? 30 : req.parDays(), req.groupBy() == null ? List.of() : req.groupBy(), req.sort(), Boolean.TRUE.equals(req.totalsOnly()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        List<Integer> classes = req.classes() == null || req.classes().isEmpty() ? List.of(12, 13) : req.classes();
        for (int c : classes) if (!CLASSES.contains(c)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Class " + c + " is not a receivable.");
        Set<Long> types = new HashSet<>();
        for (String t : req.types() == null ? List.<String>of() : req.types()) {
            String[] p = t.split(":");
            try {
                types.add(typeKey(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())));
            } catch (RuntimeException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give an SL type as class:type, such as 12:3.");
            }
        }
        rangeCheck(req.releasedFrom(), req.releasedTo(), "date released");
        rangeCheck(req.maturityFrom(), req.maturityTo(), "maturity date");
        rangeCheck(req.lastTransFrom(), req.lastTransTo(), "last transaction date");

        if (Boolean.TRUE.equals(req.owingOnly()) && !req.withInterest()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Turn on accrued interest to list only the accounts that owe interest or penalty.");
        int[] noSchedule = {0};
        List<Account> all = load(cutOff, classes, types, noSchedule, req.withInterest(), req.withPenalty());
        List<Account> kept = new ArrayList<>();
        for (Account a : all) if (keep(a, req, basis, cutOff)) kept.add(a);
        List<Line> lines = ReceivablesAging.build(kept, spec, cutOff);

        StringBuilder note = new StringBuilder("Principal outstanding (ledger event 11) on ").append(cutOff).append(". ");
        note.append(basis == Basis.PAR
            ? "Aged by how late the oldest unpaid instalment is; at risk = late " + spec.parDays() + " days or more (PAR " + spec.parDays() + ")."
            : "Aged by days past the maturity date; at risk = past maturity by " + spec.parDays() + " days or more.");
        if (req.withInterest()) {
            note.append(" Interest is worked out as on the interest and penalty worksheet: it accrues on the principal balance at the account's yearly rate for the calendar days")
                .append(" (365-day year) up to the cut-off, less interest paid; interest taken in advance at release (unearned interest) counts as paid.");
            if (req.withPenalty()) note.append(" Penalty is an estimate (the worksheet's rule, not yet confirmed against the old system): the yearly penalty rate on the late principal, less penalty paid or waived.");
        }
        if (basis == Basis.PAR && noSchedule[0] > 0) note.append(" ").append(noSchedule[0]).append(" account(s) have no repayment schedule and count as current; age by maturity to see them.");
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        Response response = new Response(cutOff, basis.name(), spec.parDays(), ReceivablesAging.BUCKETS, spec.groupBy(), lines, note.toString(), now, user, req.withInterest(), req.withPenalty());
        return new Computed(req, response, choices(req, cutOff, basis, spec, classes), describe(req, cutOff, basis, spec, classes), kept.size(), System.currentTimeMillis());
    }

    /** The choices of a run in words, for the files (the screen shows its own). */
    private List<String[]> choices(Request r, LocalDate cutOff, Basis basis, Spec spec, List<Integer> classes) {
        Map<Integer, String> classNames = lookup("SELECT SLClassID, SLClassDesc FROM slclass");
        Map<Long, String> typeNames = new HashMap<>();
        jdbc.query("SELECT SLTypeSLC_CODE, SLTypeSLT_CODE, SLTypeM_DESC FROM sltype WHERE SLTypeBR_CODE = ?", (rs) -> { typeNames.put(typeKey(rs.getInt(1), rs.getInt(2)), rs.getString(3)); }, BRANCH);
        List<String[]> out = new ArrayList<>();
        out.add(new String[] {"Cut-off date", cutOff.toString()});
        out.add(new String[] {"Aged by", basis == Basis.PAR ? "PAR @ " + spec.parDays() + " days (days the oldest unpaid instalment is late)" : "Maturity @ " + spec.parDays() + " days (days past the maturity date)"});
        out.add(new String[] {"SL class", String.join(", ", classes.stream().map((c) -> classNames.getOrDefault(c, "Class " + c)).toList())});
        List<String> types = r.types() == null ? List.of() : r.types().stream().map((t) -> {
            String[] p = t.split(":");
            return typeNames.getOrDefault(typeKey(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())), t);
        }).toList();
        out.add(new String[] {"SL type", types.isEmpty() ? "All" : String.join(", ", types)});
        range(out, "Date released", r.releasedFrom(), r.releasedTo());
        range(out, "Maturity date", r.maturityFrom(), r.maturityTo());
        range(out, "Last transaction", r.lastTransFrom(), r.lastTransTo());
        if (r.minBalance() != null || r.maxBalance() != null) out.add(new String[] {"Outstanding", between(r.minBalance(), r.maxBalance())});
        if (r.minDays() != null || r.maxDays() != null) out.add(new String[] {basis == Basis.PAR ? "Days late" : "Days past maturity", between(r.minDays(), r.maxDays())});
        out.add(new String[] {"Grouped by", spec.groupBy().isEmpty() ? "None" : String.join(" > ", spec.groupBy().stream().map(ReceivablesAging.GROUPINGS::get).toList())});
        out.add(new String[] {"Accounts sorted by", ReceivablesAging.SORTS.get(spec.sort())});
        out.add(new String[] {"Show totals only", spec.totalsOnly() ? "Yes" : "No"});
        out.add(new String[] {"Accrued interest", !r.withInterest() ? "Not worked out" : r.withPenalty() ? "Interest and penalty (estimate)" : "Interest"});
        if (Boolean.TRUE.equals(r.owingOnly())) out.add(new String[] {"Only accounts owing interest or penalty", "Yes"});
        return out;
    }

    private static void range(List<String[]> out, String label, LocalDate from, LocalDate to) {
        if (from == null && to == null) return;
        out.add(new String[] {label, (from == null ? "any" : from.toString()) + " to " + (to == null ? "any" : to.toString())});
    }

    private static String between(Object min, Object max) {
        return (min == null ? "any" : min.toString()) + " to " + (max == null ? "any" : max.toString());
    }

    private static void rangeCheck(LocalDate from, LocalDate to, String what) {
        if (from != null && to != null && to.isBefore(from)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The " + what + " range ends before it starts.");
    }

    private static boolean between(LocalDate d, LocalDate from, LocalDate to) {
        if (from == null && to == null) return true;
        if (d == null) return false;
        return (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to));
    }

    static boolean keep(Account a, Request r, Basis basis, LocalDate cutOff) {
        if (!between(a.released(), r.releasedFrom(), r.releasedTo())) return false;
        if (!between(a.maturity(), r.maturityFrom(), r.maturityTo())) return false;
        if (!between(a.lastTrans(), r.lastTransFrom(), r.lastTransTo())) return false;
        if (r.minBalance() != null && a.balance().compareTo(r.minBalance()) < 0) return false;
        if (r.maxBalance() != null && a.balance().compareTo(r.maxBalance()) > 0) return false;
        long days = ReceivablesAging.age(a, basis, cutOff);
        if (r.minDays() != null && days < r.minDays()) return false;
        if (r.maxDays() != null && days > r.maxDays()) return false;
        if (Boolean.TRUE.equals(r.owingOnly()) && a.interestDue().signum() <= 0 && a.penaltyDue().signum() <= 0) return false;
        return true;
    }

    private static String describe(Request r, LocalDate cutOff, Basis basis, Spec spec, List<Integer> classes) {
        return "cutOff=" + cutOff + ", basis=" + basis + " @" + spec.parDays() + ", classes=" + classes + ", types=" + (r.types() == null ? "all" : r.types())
            + ", groupBy=" + spec.groupBy() + ", sort=" + spec.sort() + ", totalsOnly=" + spec.totalsOnly()
            + (r.releasedFrom() != null || r.releasedTo() != null ? ", released=" + r.releasedFrom() + ".." + r.releasedTo() : "")
            + (r.maturityFrom() != null || r.maturityTo() != null ? ", maturity=" + r.maturityFrom() + ".." + r.maturityTo() : "")
            + (r.lastTransFrom() != null || r.lastTransTo() != null ? ", lastTrans=" + r.lastTransFrom() + ".." + r.lastTransTo() : "")
            + (r.minBalance() != null || r.maxBalance() != null ? ", balance=" + r.minBalance() + ".." + r.maxBalance() : "")
            + (r.minDays() != null || r.maxDays() != null ? ", days=" + r.minDays() + ".." + r.maxDays() : "")
            + (r.withInterest() ? ", accruedInterest" + (r.withPenalty() ? "+penalty" : "") + (Boolean.TRUE.equals(r.owingOnly()) ? ", owingOnly" : "") : "");
    }

    private static long typeKey(int slClass, int slType) {
        return slClass * 100_000L + slType;
    }

    private record Key(int client, int slClass, int slType, int ref) {}

    private static Integer intOrNull(ResultSet rs, int i) throws SQLException {
        Object o = rs.getObject(i);
        return o instanceof Number n ? n.intValue() : null;
    }

    private static LocalDate dateOrNull(ResultSet rs, int i) throws SQLException {
        java.sql.Date d = rs.getDate(i);
        return d == null ? null : d.toLocalDate();
    }

    private Map<Integer, String> lookup(String sql, Object... args) {
        Map<Integer, String> out = new HashMap<>();
        jdbc.query(sql, (rs) -> {
            Integer id = intOrNull(rs, 1);
            if (id != null && rs.getString(2) != null) out.put(id, rs.getString(2).trim());
        }, args);
        return out;
    }

    private static String word(Map<Integer, String> names, Integer id) {
        return id == null ? null : names.getOrDefault(id, String.valueOf(id));
    }

    private static String marks(int n) {
        return String.join(", ", Collections.nCopies(n, "?"));
    }

    /** Every receivable account with principal outstanding on the cut-off date, with what the groupings need. */
    private List<Account> load(LocalDate cutOff, List<Integer> classes, Set<Long> types, int[] noSchedule, boolean interest, boolean penalty) {
        List<Object> args = new ArrayList<>();
        args.add(BRANCH);
        args.addAll(classes);
        args.add(cutOff);
        Map<Key, Object[]> ledger = new HashMap<>();
        jdbc.query("SELECT SL_CLIENTID, SLC_CODE, SLT_CODE, REF_NO, SUM(CASE WHEN SLE_CODE = " + EVENT_PRINCIPAL + " THEN AMT ELSE 0 END),"
            + " SUM(CASE WHEN SLE_CODE = " + EVENT_PRINCIPAL + " AND AMT > 0 THEN AMT ELSE 0 END), MAX(TR_DATE)"
            + " FROM sldtl WHERE SL_BRCODE = ? AND SLC_CODE IN (" + marks(classes.size()) + ") AND TR_DATE <= ?"
            + " GROUP BY SL_CLIENTID, SLC_CODE, SLT_CODE, REF_NO HAVING SUM(CASE WHEN SLE_CODE = " + EVENT_PRINCIPAL + " THEN AMT ELSE 0 END) > 0.004", (rs) -> {
                Key k = new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4));
                if (types.isEmpty() || types.contains(typeKey(k.slClass(), k.slType()))) ledger.put(k, new Object[] {rs.getBigDecimal(5), rs.getBigDecimal(6), dateOrNull(rs, 7)});
            }, args.toArray());
        if (ledger.isEmpty()) return List.of();

        // the account rows: release and maturity dates and the loan-side groupings
        Map<Key, Object[]> info = new HashMap<>();
        jdbc.query("""
                SELECT ClientIDLoan, LoanSLC_CODE, LoanSLT_CODE, LoanREF_NO, LoanTR_DATE, LoanMAT_DATE, LoanSTATUS, LoanLoanClass, LoanPurpose, LoanCollType, LoanPPMT_MODE, LoanCollectType, LoanMClass,
                       LoanINT_RATE, LoanPEN_RATE
                FROM loan WHERE LoanBR_CODE = ?
                UNION ALL
                SELECT ClientIDAR, ARSLC_CODE, ARSLT_CODE, ARREF_NO, ARTR_DATE, ARMAT_DATE, ARSTATUS, ARClass, ARPurpose, ARCollType, ARPPMT_MODE, ARCollectType, ARMClass,
                       ARINT_RATE, ARPEN_RATE
                FROM ar WHERE ARBR_CODE = ?
                """, (rs) -> {
                Key k = new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4));
                if (ledger.containsKey(k)) info.put(k, new Object[] {dateOrNull(rs, 5), dateOrNull(rs, 6), intOrNull(rs, 7), intOrNull(rs, 8), intOrNull(rs, 9), intOrNull(rs, 10), intOrNull(rs, 11), intOrNull(rs, 12), intOrNull(rs, 13),
                    rs.getBigDecimal(14), rs.getBigDecimal(15)});
            }, BRANCH, BRANCH);

        // the repayment schedule, for how late the oldest unpaid instalment is
        Map<Key, List<Due>> dues = new HashMap<>();
        Map<Key, List<ScheduleDate>> balances = new HashMap<>();
        jdbc.query("SELECT CLIENTID, SLC_CODE, SLT_CODE, REF_NO, DUEDATE, COALESCE(PRINDUE, 0), COALESCE(INTDUE, 0), PRINBAL FROM amortsched WHERE BR_CODE = ? AND SLC_CODE IN (" + marks(classes.size()) + ")"
            + " ORDER BY CLIENTID, SLC_CODE, SLT_CODE, REF_NO, DUEDATE", (rs) -> {
                Key k = new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4));
                if (!ledger.containsKey(k) || rs.getDate(5) == null) return;
                LocalDate due = rs.getDate(5).toLocalDate();
                dues.computeIfAbsent(k, x -> new ArrayList<>()).add(new Due(due, rs.getBigDecimal(6), rs.getBigDecimal(7)));
                if (interest && rs.getBigDecimal(8) != null) balances.computeIfAbsent(k, x -> new ArrayList<>()).add(new ScheduleDate(due, rs.getBigDecimal(8)));
            }, prepend(BRANCH, classes));

        // the Accrued Interest tab: every principal, interest and penalty row of the accounts, in posting order, for the worksheet's walk
        Map<Key, List<Entry>> entries = new HashMap<>();
        if (interest) {
            List<Object> a = new ArrayList<>();
            a.add(BRANCH);
            a.addAll(classes);
            a.add(cutOff);
            jdbc.query("SELECT SL_CLIENTID, SLC_CODE, SLT_CODE, REF_NO, TR_DATE, TR_CODE, TR_CTLNO, TR_DOCNO, SLE_CODE, AMT FROM sldtl WHERE SL_BRCODE = ? AND SLC_CODE IN (" + marks(classes.size()) + ")"
                + " AND SLE_CODE IN (11, 22, 23, 41, 42) AND TR_DATE <= ? ORDER BY TR_DATE, SEQNO", (rs) -> {
                    Key k = new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4));
                    if (ledger.containsKey(k)) entries.computeIfAbsent(k, x -> new ArrayList<>()).add(new Entry(rs.getDate(5).toLocalDate(), rs.getInt(6), rs.getString(7), rs.getString(8), rs.getInt(9), rs.getBigDecimal(10)));
                }, a.toArray());
        }

        // the clients behind the accounts, and the words for every code
        Set<Integer> clientIds = new HashSet<>();
        for (Key k : ledger.keySet()) clientIds.add(k.client());
        Map<Integer, Object[]> clients = new HashMap<>();
        jdbc.query("SELECT ClientID, COALESCE(NULLIF(AccountName, ''), CONCAT_WS(', ', LName, FName)), ClientDept, Gender, DOSRITag, Geographical, EmpCode, AccountStatus, ClientType, Collector"
            + " FROM client WHERE ClientIDBrCode = ?", (rs) -> {
                int id = rs.getInt(1);
                if (clientIds.contains(id)) clients.put(id, new Object[] {rs.getString(2), intOrNull(rs, 3), intOrNull(rs, 4), intOrNull(rs, 5), intOrNull(rs, 6), intOrNull(rs, 7), intOrNull(rs, 8), intOrNull(rs, 9), intOrNull(rs, 10)});
            }, BRANCH);
        Map<Integer, String> classNames = lookup("SELECT SLClassID, SLClassDesc FROM slclass");
        Map<Long, String> typeNames = new HashMap<>();
        jdbc.query("SELECT SLTypeSLC_CODE, SLTypeSLT_CODE, SLTypeM_DESC FROM sltype WHERE SLTypeBR_CODE = ?", (rs) -> { typeNames.put(typeKey(rs.getInt(1), rs.getInt(2)), rs.getString(3)); }, BRANCH);
        Map<Integer, String> status = lookup("SELECT CTS_TypeCode, CTS_TypeDesc FROM cts_type");
        Map<Integer, String> loanClass = lookup("SELECT LoanClassID, LoanClassDesc FROM loanclass");
        Map<Integer, String> purpose = lookup("SELECT LoanPurposeID, LoanPurposeDesc FROM loanpurpose");
        Map<Integer, String> collateral = lookup("SELECT CollateralTypeID, CollateralTypeDesc FROM collateraltype");
        Map<Integer, String> payMode = lookup("SELECT PaymentModeID, PaymentModeDesc FROM paymentmode");
        Map<Integer, String> collectType = lookup("SELECT CollectTypeID, CollectTypeDesc FROM collecttype");
        Map<Integer, String> borrower = lookup("SELECT BorrowerClassID, BorrowerClassDesc FROM borrowerclass");
        Map<Integer, String> dept = lookup("SELECT DeptID, DeptDesc FROM dept");
        Map<Integer, String> gender = lookup("SELECT GenderID, GenderDesc FROM gender");
        Map<Integer, String> dosri = lookup("SELECT ID, Desc1 FROM dosritag");
        Map<Integer, String> employer = lookup("SELECT EmpCodeID, EmpCodeDesc FROM empcode");
        Map<Integer, String> acctStatus = lookup("SELECT AcctStatID, AcctStatDesc FROM acctstat");
        Map<Integer, String> clientType = lookup("SELECT ClientTypeID, ClientTypeDesc FROM clienttype");
        Map<Integer, String> collector = lookup("SELECT CollChartID, CollChartDesc FROM collchart WHERE CollChartBR_CODE = ?", BRANCH);
        Map<Integer, String> geo = new HashMap<>();
        Map<Integer, Integer> geoCollector = new HashMap<>();
        jdbc.query("SELECT GeoID, GeoDesc, GeoCollChartID FROM geo WHERE GeoBR_CODE = ?", (rs) -> {
            geo.put(rs.getInt(1), rs.getString(2));
            Integer c = intOrNull(rs, 3);
            if (c != null) geoCollector.put(rs.getInt(1), c);
        }, BRANCH);

        List<Account> out = new ArrayList<>();
        for (Map.Entry<Key, Object[]> e : ledger.entrySet()) {
            Key k = e.getKey();
            BigDecimal balance = (BigDecimal) e.getValue()[0], released = (BigDecimal) e.getValue()[1];
            List<Due> schedule = dues.get(k);
            Arrears ar;
            if (schedule == null || schedule.isEmpty()) {
                noSchedule[0]++;
                ar = new Arrears(BigDecimal.ZERO, 0, null);
            } else {
                ar = LoanRules.arrears(schedule, released.subtract(balance), cutOff);
            }
            Object[] i = info.getOrDefault(k, new Object[11]);
            Object[] c = clients.getOrDefault(k.client(), new Object[9]);
            Map<String, String> g = new HashMap<>();
            g.put("slclass", classNames.getOrDefault(k.slClass(), "Class " + k.slClass()));
            g.put("sltype", typeNames.getOrDefault(typeKey(k.slClass(), k.slType()), "Type " + k.slType()));
            g.put("slstatus", word(status, (Integer) i[2]));
            g.put("loanclass", word(loanClass, (Integer) i[3]));
            g.put("purpose", word(purpose, (Integer) i[4]));
            g.put("collateral", word(collateral, (Integer) i[5]));
            g.put("paymode", word(payMode, (Integer) i[6]));
            g.put("collecttype", word(collectType, (Integer) i[7]));
            g.put("borrower", word(borrower, (Integer) i[8]));
            g.put("dept", word(dept, (Integer) c[1]));
            g.put("gender", word(gender, (Integer) c[2]));
            g.put("dosri", word(dosri, (Integer) c[3]));
            g.put("geo", word(geo, (Integer) c[4]));
            g.put("employer", word(employer, (Integer) c[5]));
            g.put("acctstatus", word(acctStatus, (Integer) c[6]));
            g.put("clienttype", word(clientType, (Integer) c[7]));
            // the client's own collector, else the one assigned to the client's area
            Integer coll = (Integer) c[8];
            if (coll == null && c[4] != null) coll = geoCollector.get((Integer) c[4]);
            g.put("collector", word(collector, coll));
            String name = c[0] == null ? "Member " + k.client() : ((String) c[0]).trim();
            Accrual acc = interest
                ? ReceivableAccrual.of((LocalDate) i[0], (BigDecimal) i[9], (BigDecimal) i[10], entries.getOrDefault(k, List.of()), balances.getOrDefault(k, List.of()), cutOff, penalty)
                : Accrual.NONE;
            out.add(new Account(k.client(), name, k.slClass(), k.slType(), g.get("sltype"), k.ref(), balance, ar.pastDuePrincipal(), ar.daysPastDue(),
                (LocalDate) i[0], (LocalDate) i[1], (LocalDate) e.getValue()[2], g, acc.interestDue(), acc.interestAhead(), acc.penaltyDue()));
        }
        return out;
    }

    private static Object[] prepend(Object first, List<?> rest) {
        List<Object> a = new ArrayList<>();
        a.add(first);
        a.addAll(rest);
        return a.toArray();
    }
}
