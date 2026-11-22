package ph.coop.core.frontoffice;


import ph.coop.core.accounting.close.PostingPeriods;
import ph.coop.core.audit.AuditLog;import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;
import ph.coop.core.deposit.TimeDepositAccrualRun;
import ph.coop.core.deposit.TimeDepositAccrualRun.Credit;
import ph.coop.core.deposit.TimeDepositAccrualRun.Group;
import ph.coop.core.deposit.TimeDepositAccrualRun.Plan;
import ph.coop.core.frontoffice.FrontOfficeAccess.Perms;
import ph.coop.core.frontoffice.MemberLedgerPoster.MemberLine;
import ph.coop.core.frontoffice.MemberLedgerPoster.Person;

/**
 * The time deposit "Accrued Interest on Deposits (Computer Generated) - A.D.B." (Interest, module 8 type 52; documentation/time-deposit-accrual-map.md).
 * Lock In certificates are accrued to each month end, Short Term certificates once a year to December 31. Preview is read only. Encoding writes the vouchers the
 * old system writes (one per certificate type, a credit of ledger event 71 for each certificate and one interest expense debit per department) with every line
 * tagged 2; a second person approves and a third posts, which writes the certificate ledger rows and the general ledger lines dated the period end. A type that
 * already has a voucher for that date cannot be run again. Nothing runs by itself: a person starts each run.
 */
@RestController
@RequestMapping("/api/front-office/time-deposit-accruals")
class TimeDepositAccrualController {

    private static final int MODULE = 8, CODE = 52, PENDING = 2, POSTED = 1, CHUNK = 7000, EVENT = 71, STATUS = 99;

    record TypeRow(int typeCode, String type, int certificates, BigDecimal total, boolean alreadyEncoded, int vouchers) {}

    record Summary(String group, String accrualType, String from, String to, List<TypeRow> types, int certificates, BigDecimal total, int withoutDepartment) {}

    record Encode(String group, String to, List<Integer> types) {}

    record VoucherRow(int year, long controlNo, String documentNo, int typeCode, int lines, BigDecimal total, int encodedBy, Integer approvedBy, String status) {}

    private final FrontOfficeAccess access;

    private final ph.coop.core.businessday.BusinessDay businessDay;
    private final MemberLedgerPoster poster;
    private final TimeDepositAccrualRun run;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Db db;

    TimeDepositAccrualController(FrontOfficeAccess access, MemberLedgerPoster poster, TimeDepositAccrualRun run, JdbcTemplate jdbc, PlatformTransactionManager txm, Clock clock, Db db, ph.coop.core.businessday.BusinessDay businessDay) {

        this.businessDay = businessDay;
        this.access = access;
        this.poster = poster;
        this.run = run;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.clock = clock;
        this.db = db;
    }

    private static void requireFind(Perms p) {
        if (!p.canFind(CODE)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot look up interest accrual vouchers.");
    }

    private static void requireNew(Perms p) {
        if (!p.canNew(CODE)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot create interest accrual vouchers.");
    }

    private Group group(String text) {
        try {
            return Group.valueOf(text);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose LOCK_IN or SHORT_TERM.");
        }
    }

    private LocalDate periodEnd(Group group, String text) {
        LocalDate to;
        try {
            to = LocalDate.parse(text);
            TimeDepositAccrualRun.periodStart(group, to);
        } catch (java.time.format.DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the date as 2026-03-31.");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (to.isAfter(LocalDate.now(clock))) throw new ResponseStatusException(HttpStatus.CONFLICT, "That period has not ended yet.");
        return to;
    }

    /** The certificate types that already have a voucher dated {@code to}. */
    private Set<Integer> encodedTypes(int branch, LocalDate to) {
        return new java.util.HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT x.DTSLT_CODE FROM transsum t JOIN transdt x ON x.DTTR_BRCODE = t.TR_BRCODE AND x.DTTR_YEAR = t.TR_YEAR AND x.DTTR_MODULE = t.TR_MODULE
                  AND x.DTTR_CODE = t.TR_CODE AND x.DTTR_CTLNO = t.TR_CTLNO
                WHERE t.TR_BRCODE = ? AND t.TR_YEAR = ? AND t.TR_MODULE = ? AND t.TR_CODE = ? AND t.TR_DATE = ? AND x.DTSLC_CODE = 24
                """, Integer.class, branch, to.getYear(), MODULE, CODE, to.toString()));
    }

    private String typeName(int type) {
        return jdbc.queryForList("SELECT SLTypeM_DESC FROM sltype WHERE SLTypeBR_CODE = 1 AND SLTypeSLC_CODE = 24 AND SLTypeSLT_CODE = ?", String.class, type).stream().findFirst().orElse("type " + type);
    }

    @GetMapping("/preview")
    Summary preview(@RequestParam String group, @RequestParam String to, Authentication auth) {
        Perms perms = access.of(auth);
        requireFind(perms);
        Group g = group(group);
        LocalDate end = periodEnd(g, to);
        Plan plan = run.plan(perms.branch(), g, end);
        Set<Integer> done = encodedTypes(perms.branch(), end);
        Map<Integer, int[]> count = new LinkedHashMap<>();
        Map<Integer, BigDecimal> sum = new LinkedHashMap<>();
        for (Credit c : plan.credits()) {
            count.computeIfAbsent(c.typeCode(), k -> new int[1])[0]++;
            sum.merge(c.typeCode(), c.interest(), BigDecimal::add);
        }
        List<TypeRow> types = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int t : count.keySet()) {
            int n = count.get(t)[0];
            types.add(new TypeRow(t, typeName(t), n, sum.get(t), done.contains(t), (n + CHUNK - 1) / CHUNK));
            total = total.add(sum.get(t));
        }
        return new Summary(g.name(), g.accrualType, plan.from().toString(), plan.to().toString(), types, plan.credits().size(), total, plan.noDepartment().size());
    }

    /** Writes the vouchers of the chosen types (all of the group when none are named) that have none for this date, every line waiting (tag 2), encoded by the caller. */
    @PostMapping("/encode")
    List<VoucherRow> encode(@RequestBody Encode in, Authentication auth) {
        Perms perms = access.of(auth);
        requireNew(perms);
        Person p = poster.person(auth.getName());
        Group g = group(in.group());
        LocalDate to = periodEnd(g, in.to());
        List<Integer> wanted = in.types() == null || in.types().isEmpty() ? g.types() : in.types();
        if (!g.types().containsAll(wanted)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Those types do not belong to this kind of accrual.");
        return tx.execute((st) -> {
            PostingPeriods.requireOpen(jdbc, p.branch(), to);
            // a run posts on its own period-end date, but only while the branch has a business day open
            businessDay.requireStarted(p.branch());
            Set<Integer> done = encodedTypes(p.branch(), to);
            for (int t : wanted) if (done.contains(t)) throw new ResponseStatusException(HttpStatus.CONFLICT, typeName(t) + " already has an accrual voucher dated " + to + ", so it cannot be run again.");
            Plan plan = run.plan(p.branch(), g, to);
            LocalDateTime now = LocalDateTime.now(clock).withNano(0);
            int year = to.getYear();
            List<VoucherRow> out = new ArrayList<>();
            Map<Integer, List<Credit>> byType = new LinkedHashMap<>();
            for (Credit c : plan.credits()) if (wanted.contains(c.typeCode())) byType.computeIfAbsent(c.typeCode(), k -> new ArrayList<>()).add(c);
            if (byType.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "No certificate earns interest for this period.");
            Map<String, String> glCache = new HashMap<>();
            for (Map.Entry<Integer, List<Credit>> e : byType.entrySet()) {
                int type = e.getKey();
                List<Credit> all = e.getValue();
                for (int from = 0; from < all.size(); from += CHUNK) {
                    List<Credit> part = all.subList(from, Math.min(all.size(), from + CHUNK));
                    long ctl = db.nextControlNumber(p.branch(), MODULE, CODE, year);
                    String docNo = String.format("%08d", ctl);
                    jdbc.update("""
                            INSERT INTO transsum (TR_BRCODE, TR_YEAR, TR_MODULE, TR_CODE, TR_CTLNO, TR_DOCNO, TR_DATE, TR_TIME, BATCH_NO, EXPLANATION, IS_FO, DATETIMEADDED)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                            """, p.branch(), year, MODULE, CODE, ctl, docNo, to.toString(), now.format(DateTimeFormatter.ofPattern("HH:mm:ss")), to.format(DateTimeFormatter.BASIC_ISO_DATE),
                        explanation(plan, type), Timestamp.valueOf(now));
                    String sql = """
                            INSERT INTO transdt (DTTR_BRCODE, DTTR_YEAR, DTTR_MODULE, DTTR_CODE, DTTR_CTLNO, DTSEQ_NO, DTDEPT, DTACCT_CODE, ClientIDDT, DTSLC_CODE, DTSLT_CODE, DTREF_NO,
                              DTSLE_CODE, DTCTS_NUM, DTSL_DATE, DTADJ_FLAG, DTAMT, DTENCDBY, DTUPD_TAG, DTCHECKISRELEASE, DTISTRANSIT, DTISUNDENTIFIED)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0)
                            """;
                    List<Object[]> rows = new ArrayList<>();
                    Map<Integer, BigDecimal> perDept = new TreeMap<>();
                    int seq = 1;
                    String gl = glCache.computeIfAbsent(String.valueOf(type), k -> poster.glAccount(p.branch(), 24, type, EVENT, STATUS));
                    for (Credit c : part) {
                        rows.add(new Object[] {p.branch(), year, MODULE, CODE, ctl, seq++, c.dept(), gl, c.clientId(), 24, type, c.refNo(), EVENT, STATUS, to.toString(), 1, c.interest().negate(), p.id(), PENDING});
                        perDept.merge(c.dept(), c.interest(), BigDecimal::add);
                    }
                    for (Map.Entry<Integer, BigDecimal> d : perDept.entrySet()) {
                        rows.add(new Object[] {p.branch(), year, MODULE, CODE, ctl, seq++, d.getKey(), TimeDepositAccrualRun.EXPENSE_ACCOUNT, null, null, null, null, null, null, null, null, d.getValue(), p.id(), PENDING});
                    }
                    jdbc.batchUpdate(sql, rows);
                    out.add(new VoucherRow(year, ctl, docNo, type, rows.size(), part.stream().map(Credit::interest).reduce(BigDecimal.ZERO, BigDecimal::add), p.id(), null, "WAITING FOR APPROVAL"));
                }
            }
            audit(auth, "td.accrual.encode", p, to.toString(), g + " " + out.size() + " vouchers, " + byType.values().stream().mapToInt(List::size).sum() + " certificates");
            return out;
        });
    }

    /** The explanation the old run wrote: the period, the accrual type, the certificate type and the expense account. */
    private String explanation(Plan plan, int type) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("MM/dd/yyyy");
        String acct = TimeDepositAccrualRun.EXPENSE_ACCOUNT;
        return "Accrued Interest on Deposits (Computer Generated) - A.D.B. Computed for the period :: " + plan.from().format(f) + " to " + plan.to().format(f) + " :: \nParameters:\nAccrual Type: "
            + plan.group().accrualType + "\n" + typeName(type) + "\n    Int. Rate: 0.00% p.a. IntExpAcct: " + acct.substring(0, 3) + "." + acct.substring(3) + " Interest expense on time deposit \n    WTax Rate: 0.00% WTaxAcct:  \n";
    }

    @GetMapping("/vouchers")
    List<VoucherRow> vouchers(@RequestParam String group, @RequestParam String to, Authentication auth) {
        Perms perms = access.of(auth);
        requireFind(perms);
        LocalDate end = periodEnd(group(group), to);
        return jdbc.query("""
                SELECT s.TR_YEAR, s.TR_CTLNO, s.TR_DOCNO,
                  (SELECT COALESCE(MIN(d.DTSLT_CODE), 0) FROM transdt d WHERE d.DTTR_BRCODE = s.TR_BRCODE AND d.DTTR_YEAR = s.TR_YEAR AND d.DTTR_MODULE = s.TR_MODULE AND d.DTTR_CODE = s.TR_CODE AND d.DTTR_CTLNO = s.TR_CTLNO),
                  (SELECT COUNT(*) FROM transdt d WHERE d.DTTR_BRCODE = s.TR_BRCODE AND d.DTTR_YEAR = s.TR_YEAR AND d.DTTR_MODULE = s.TR_MODULE AND d.DTTR_CODE = s.TR_CODE AND d.DTTR_CTLNO = s.TR_CTLNO),
                  (SELECT COALESCE(SUM(d.DTAMT), 0) FROM transdt d WHERE d.DTTR_BRCODE = s.TR_BRCODE AND d.DTTR_YEAR = s.TR_YEAR AND d.DTTR_MODULE = s.TR_MODULE AND d.DTTR_CODE = s.TR_CODE AND d.DTTR_CTLNO = s.TR_CTLNO AND d.DTAMT > 0),
                  (SELECT COALESCE(MAX(d.DTENCDBY), 0) FROM transdt d WHERE d.DTTR_BRCODE = s.TR_BRCODE AND d.DTTR_YEAR = s.TR_YEAR AND d.DTTR_MODULE = s.TR_MODULE AND d.DTTR_CODE = s.TR_CODE AND d.DTTR_CTLNO = s.TR_CTLNO),
                  (SELECT MAX(d.DTAPPRVBY) FROM transdt d WHERE d.DTTR_BRCODE = s.TR_BRCODE AND d.DTTR_YEAR = s.TR_YEAR AND d.DTTR_MODULE = s.TR_MODULE AND d.DTTR_CODE = s.TR_CODE AND d.DTTR_CTLNO = s.TR_CTLNO),
                  (SELECT MIN(d.DTUPD_TAG) FROM transdt d WHERE d.DTTR_BRCODE = s.TR_BRCODE AND d.DTTR_YEAR = s.TR_YEAR AND d.DTTR_MODULE = s.TR_MODULE AND d.DTTR_CODE = s.TR_CODE AND d.DTTR_CTLNO = s.TR_CTLNO)
                FROM transsum s WHERE s.TR_BRCODE = ? AND s.TR_YEAR = ? AND s.TR_MODULE = ? AND s.TR_CODE = ? AND s.TR_DATE = ? ORDER BY s.TR_CTLNO
                """, (rs, i) -> {
                int apr = rs.getInt(8);
                boolean none = rs.wasNull();
                int tag = rs.getInt(9);
                return new VoucherRow(rs.getInt(1), rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getBigDecimal(6), rs.getInt(7), none ? null : apr,
                    tag == POSTED ? "POSTED" : none ? "WAITING FOR APPROVAL" : "APPROVED");
            }, perms.branch(), end.getYear(), MODULE, CODE, end.toString());
    }

    private record Line(int seq, String account, Integer client, Integer slc, Integer slt, Integer ref, Integer event, Integer cts, BigDecimal amount, int dept, int encoder, Integer approver, int tag) {}

    private List<Line> lines(int branch, int year, long ctl, boolean lock) {
        return jdbc.query("""
                SELECT DTSEQ_NO, DTACCT_CODE, ClientIDDT, DTSLC_CODE, DTSLT_CODE, DTREF_NO, DTSLE_CODE, DTCTS_NUM, DTAMT, COALESCE(DTDEPT, 0), COALESCE(DTENCDBY, 0), DTAPPRVBY, COALESCE(DTUPD_TAG, 0)
                FROM transdt WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = ? AND DTTR_CODE = ? AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO
                """ + (lock ? " FOR UPDATE" : ""), (rs, i) -> new Line(rs.getInt(1), rs.getString(2), (Integer) rs.getObject(3), (Integer) rs.getObject(4), (Integer) rs.getObject(5), (Integer) rs.getObject(6),
                (Integer) rs.getObject(7), (Integer) rs.getObject(8), rs.getBigDecimal(9), rs.getInt(10), rs.getInt(11), (Integer) rs.getObject(12), rs.getInt(13)), branch, year, MODULE, CODE, ctl);
    }

    private List<String> problems(List<Line> lines) {
        List<String> problems = new ArrayList<>();
        if (lines.isEmpty()) { problems.add("That voucher was not found. It may have been cancelled or the number mistyped: reload the list and open it from there."); return problems; }
        if (lines.stream().anyMatch((l) -> l.tag() == POSTED)) problems.add("This voucher is already posted.");
        else if (lines.stream().anyMatch((l) -> l.tag() != PENDING)) problems.add("The lines of this voucher are not waiting to be posted.");
        if (lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add).signum() != 0) problems.add("The voucher does not balance.");
        if (lines.stream().anyMatch((l) -> l.slc() != null && (l.slc() != 24 || l.event() == null || l.event() != EVENT || l.client() == null || l.slt() == null || l.ref() == null))) problems.add("This is not an interest accrual voucher.");
        return problems;
    }

    @PostMapping("/{year}/{controlNo}/approve")
    VoucherRow approve(@PathVariable int year, @PathVariable long controlNo, Authentication auth) {
        Perms perms = access.of(auth);
        requireNew(perms);
        Person p = poster.person(auth.getName());
        return tx.execute((st) -> {
            List<Line> lines = lines(p.branch(), year, controlNo, true);
            List<String> problems = problems(lines);
            if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, String.join(" ", problems));
            if (lines.get(0).approver() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This voucher was already approved, so there is nothing to approve. Reload the list to see where it stands; it can now be posted.");
            if (lines.get(0).encoder() == p.id()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You encoded this voucher, so someone else must approve it.");
            jdbc.update("UPDATE transdt SET DTAPPRVBY = ? WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = ? AND DTTR_CODE = ? AND DTTR_CTLNO = ? AND DTUPD_TAG = ?",
                p.id(), p.branch(), year, MODULE, CODE, controlNo, PENDING);
            audit(auth, "td.accrual.approve", p, year + "/" + controlNo, "approved by " + p.id());
            return row(p.branch(), year, controlNo);
        });
    }

    @PostMapping("/{year}/{controlNo}/post")
    VoucherRow post(@PathVariable int year, @PathVariable long controlNo, Authentication auth) {
        Perms perms = access.of(auth);
        requireNew(perms);
        Person p = poster.person(auth.getName());
        businessDay.requireStarted(p.branch());
        return tx.execute((st) -> {
            List<Line> lines = lines(p.branch(), year, controlNo, true);
            List<String> problems = problems(lines);
            if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, String.join(" ", problems));
            Integer approver = lines.get(0).approver();
            if (approver == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This voucher cannot be posted yet because nobody has approved it. Another person with the right to approve vouchers must approve it first.");
            if (approver == p.id() || lines.get(0).encoder() == p.id()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The person who encoded or approved an accrual voucher cannot post it.");
            List<Map<String, Object>> head = jdbc.queryForList("SELECT TR_DATE, TR_DOCNO FROM transsum WHERE TR_BRCODE = ? AND TR_YEAR = ? AND TR_MODULE = ? AND TR_CODE = ? AND TR_CTLNO = ?", p.branch(), year, MODULE, CODE, controlNo);
            LocalDate day = ((java.sql.Date) head.get(0).get("TR_DATE")).toLocalDate();
            String docNo = (String) head.get(0).get("TR_DOCNO");
            Timestamp stamp = Timestamp.valueOf(LocalDateTime.now(clock).withNano(0));
            int updated = jdbc.update("UPDATE transdt SET DTPOSTBY = ?, DTUPD_TAG = ? WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = ? AND DTTR_CODE = ? AND DTTR_CTLNO = ? AND DTUPD_TAG = ?",
                p.id(), POSTED, p.branch(), year, MODULE, CODE, controlNo, PENDING);
            if (updated != lines.size()) throw new ResponseStatusException(HttpStatus.CONFLICT, "The voucher changed while posting; try again.");
            for (Line l : lines) {
                if (l.slc() != null) {
                    // a certificate row has no tender type (null), as in the old data
                    poster.writeLedgerRow(p.branch(), l.client(), new MemberLine(24, l.slt(), l.ref(), EVENT, l.cts() == null ? STATUS : l.cts(), l.account(), l.amount()), day, CODE, controlNo, docNo, null, null);
                }
                poster.writeGlLine(p.branch(), l.account(), day, CODE, l.dept(), l.amount(), stamp);
            }
            audit(auth, "td.accrual.post", p, year + "/" + controlNo, lines.size() + " lines doc " + docNo);
            return row(p.branch(), year, controlNo);
        });
    }

    private VoucherRow row(int branch, int year, long ctl) {
        List<Line> lines = lines(branch, year, ctl, false);
        Line first = lines.get(0);
        Integer type = lines.stream().filter((l) -> l.slt() != null).map(Line::slt).findFirst().orElse(0);
        String status = lines.stream().anyMatch((l) -> l.tag() == POSTED) ? "POSTED" : first.approver() == null ? "WAITING FOR APPROVAL" : "APPROVED";
        return new VoucherRow(year, ctl, String.format("%08d", ctl), type, lines.size(), lines.stream().map(Line::amount).filter((a) -> a.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add), first.encoder(), first.approver(), status);
    }

    private void audit(Authentication auth, String action, Person p, String entity, String detail) {
        AuditLog.write(jdbc, auth.getName(), action, "transsum", p.branch() + "/" + MODULE + "/" + CODE + "/" + entity, null, detail, null);
    }
}
