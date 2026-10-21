package ph.coop.core.frontoffice;

import java.math.BigDecimal;
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
import ph.coop.core.deposit.SavingsInterestRun;
import ph.coop.core.deposit.SavingsInterestRun.Credit;
import ph.coop.core.deposit.SavingsInterestRun.Plan;
import ph.coop.core.frontoffice.FrontOfficeAccess.Perms;
import ph.coop.core.frontoffice.MemberLedgerPoster.MemberLine;
import ph.coop.core.frontoffice.MemberLedgerPoster.Person;

/**
 * The quarterly "Interest on Deposits (Computer Generated) - A.D.B." (Interest, module 8 type 53; documentation/savings-interest-map.md). Preview is read only.
 * Encoding writes the vouchers the old system writes (per savings type, up to 7,000 members each, one expense debit per department) with every line tagged 2;
 * a second person approves and a third posts them, which writes the member ledger rows and the general ledger lines dated the quarter end.
 * A quarter that already has interest vouchers cannot be encoded again. There is no single-person exemption for this run.
 */
@RestController
@RequestMapping("/api/front-office/savings-interest")
class SavingsInterestController {

    private static final int MODULE = 8, CODE = 53, PENDING = 2, POSTED = 1, CHUNK = 7000;

    record TypeRow(int typeCode, BigDecimal rate, int accounts, BigDecimal total, String expenseAccount, int vouchers) {}

    record HeldRow(int clientId, int typeCode, int refNo, BigDecimal interest, String reason) {}

    record Summary(String quarterEnd, String from, boolean alreadyEncoded, List<TypeRow> types, int accounts, BigDecimal total, int heldCount, List<HeldRow> held) {}

    record Encode(String quarterEnd) {}

    record VoucherRow(int year, long controlNo, String documentNo, int typeCode, int lines, BigDecimal total, int encodedBy, Integer approvedBy, String status) {}

    private final FrontOfficeAccess access;
    private final MemberLedgerPoster poster;
    private final SavingsInterestRun run;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Db db;

    SavingsInterestController(FrontOfficeAccess access, MemberLedgerPoster poster, SavingsInterestRun run, JdbcTemplate jdbc, PlatformTransactionManager txm, Clock clock, Db db) {
        this.access = access;
        this.poster = poster;
        this.run = run;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.clock = clock;
        this.db = db;
    }

    private static void requireFind(Perms p) {
        if (!p.canFind(CODE)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot look up interest vouchers.");
    }

    private static void requireNew(Perms p) {
        if (!p.canNew(CODE)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot create interest vouchers.");
    }

    private LocalDate quarterEnd(String text) {
        LocalDate to;
        try {
            to = LocalDate.parse(text);
            SavingsInterestRun.quarterStart(to);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the last day of a quarter, such as 2026-06-30.");
        }
        if (!to.isBefore(LocalDate.now(clock))) throw new ResponseStatusException(HttpStatus.CONFLICT, "That quarter has not ended yet.");
        return to;
    }

    private int vouchersOf(int branch, LocalDate to) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM transsum WHERE TR_BRCODE = ? AND TR_YEAR = ? AND TR_MODULE = ? AND TR_CODE = ? AND TR_DATE = ?", Integer.class, branch, to.getYear(), MODULE, CODE, to.toString());
        return n == null ? 0 : n;
    }

    @GetMapping("/preview")
    Summary preview(@RequestParam String quarterEnd, Authentication auth) {
        Perms perms = access.of(auth);
        requireFind(perms);
        LocalDate to = quarterEnd(quarterEnd);
        Plan plan = run.plan(perms.branch(), to);
        return summary(plan, vouchersOf(perms.branch(), to) > 0);
    }

    private Summary summary(Plan plan, boolean already) {
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
            types.add(new TypeRow(t, plan.rates().get(t), n, sum.get(t), SavingsInterestRun.expenseAccount(t), (n + CHUNK - 1) / CHUNK));
            total = total.add(sum.get(t));
        }
        List<HeldRow> held = plan.held().stream().limit(50).map((h) -> new HeldRow(h.clientId(), h.typeCode(), h.refNo(), h.interest(), h.reason())).toList();
        return new Summary(plan.to().toString(), plan.from().toString(), already, types, plan.credits().size(), total, plan.held().size(), held);
    }

    /** Writes the vouchers of a quarter that has none yet, every line waiting (tag 2), encoded by the caller. */
    @PostMapping("/encode")
    List<VoucherRow> encode(@RequestBody Encode in, Authentication auth) {
        Perms perms = access.of(auth);
        requireNew(perms);
        Person p = poster.person(auth.getName());
        LocalDate to = quarterEnd(in.quarterEnd());
        return tx.execute((st) -> {
            if (vouchersOf(p.branch(), to) > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Interest vouchers already exist for this quarter, so it cannot be run again.");
            Plan plan = run.plan(p.branch(), to);
            if (plan.credits().isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "No account earns interest for this quarter.");
            LocalDateTime now = LocalDateTime.now(clock).withNano(0);
            int year = to.getYear();
            List<VoucherRow> out = new ArrayList<>();
            Map<Integer, List<Credit>> byType = new LinkedHashMap<>();
            for (Credit c : plan.credits()) byType.computeIfAbsent(c.typeCode(), k -> new ArrayList<>()).add(c);
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
                              DTSLE_CODE, DTCTS_NUM, DTSL_DATE, DTADJ_FLAG, DTCOCI_TYPE, DTAMT, DTENCDBY, DTUPD_TAG, DTCHECKISRELEASE, DTISTRANSIT, DTISUNDENTIFIED)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 0, 0, 0)
                            """;
                    List<Object[]> rows = new ArrayList<>();
                    Map<Integer, BigDecimal> perDept = new LinkedHashMap<>();
                    int seq = 1;
                    for (Credit c : part) {
                        String gl = glCache.computeIfAbsent(type + "/" + c.status(), k -> poster.glAccount(p.branch(), 22, type, 11, c.status()));
                        rows.add(new Object[] {p.branch(), year, MODULE, CODE, ctl, seq++, c.dept(), gl, c.clientId(), 22, type, c.refNo(), 11, c.status(), to.toString(), 1, c.interest().negate(), p.id(), PENDING});
                        perDept.merge(c.dept(), c.interest(), BigDecimal::add);
                    }
                    for (Map.Entry<Integer, BigDecimal> d : perDept.entrySet()) {
                        rows.add(new Object[] {p.branch(), year, MODULE, CODE, ctl, seq++, d.getKey(), SavingsInterestRun.expenseAccount(type), null, null, null, null, null, null, null, null, d.getValue(), p.id(), PENDING});
                    }
                    jdbc.batchUpdate(sql, rows);
                    out.add(new VoucherRow(year, ctl, docNo, type, rows.size(), part.stream().map(Credit::interest).reduce(BigDecimal.ZERO, BigDecimal::add), p.id(), null, "WAITING FOR APPROVAL"));
                }
            }
            audit(auth, "savings.interest.encode", p, to.toString(), out.size() + " vouchers, " + plan.credits().size() + " accounts, " + plan.held().size() + " held back");
            return out;
        });
    }

    private String explanation(Plan plan, int type) {
        String name = jdbc.queryForList("SELECT SLTypeM_DESC FROM sltype WHERE SLTypeBR_CODE = 1 AND SLTypeSLC_CODE = 22 AND SLTypeSLT_CODE = ?", String.class, type).stream().findFirst().orElse("type " + type);
        DateTimeFormatter f = DateTimeFormatter.ofPattern("MM/dd/yyyy");
        return "Interest on Deposits (Computer Generated) - A.D.B. Computed for the period :: " + plan.from().format(f) + " to " + plan.to().format(f) + " :: Parameters: " + name
            + " Int. Rate: " + plan.rates().get(type).setScale(2).toPlainString() + "% p.a. W/Tax Rate: 0.0000% p.a. MinADB: 500.0000 IntExpAcct: " + SavingsInterestRun.expenseAccount(type);
    }

    @GetMapping("/vouchers")
    List<VoucherRow> vouchers(@RequestParam String quarterEnd, Authentication auth) {
        Perms perms = access.of(auth);
        requireFind(perms);
        LocalDate to = quarterEnd(quarterEnd);
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
            }, perms.branch(), to.getYear(), MODULE, CODE, to.toString());
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
        if (lines.isEmpty()) { problems.add("No such voucher."); return problems; }
        if (lines.stream().anyMatch((l) -> l.tag() == POSTED)) problems.add("This voucher is already posted.");
        else if (lines.stream().anyMatch((l) -> l.tag() != PENDING)) problems.add("The lines of this voucher are not waiting to be posted.");
        if (lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add).signum() != 0) problems.add("The voucher does not balance.");
        if (lines.stream().anyMatch((l) -> l.slc() != null && (l.slc() != 22 || l.event() == null || l.event() != 11 || l.client() == null || l.slt() == null || l.ref() == null))) problems.add("This is not an interest voucher.");
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
            if (lines.get(0).approver() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This voucher is already approved.");
            if (lines.get(0).encoder() == p.id()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You encoded this voucher, so someone else must approve it.");
            jdbc.update("UPDATE transdt SET DTAPPRVBY = ? WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = ? AND DTTR_CODE = ? AND DTTR_CTLNO = ? AND DTUPD_TAG = ?",
                p.id(), p.branch(), year, MODULE, CODE, controlNo, PENDING);
            audit(auth, "savings.interest.approve", p, year + "/" + controlNo, "approved by " + p.id());
            return row(p.branch(), year, controlNo);
        });
    }

    @PostMapping("/{year}/{controlNo}/post")
    VoucherRow post(@PathVariable int year, @PathVariable long controlNo, Authentication auth) {
        Perms perms = access.of(auth);
        requireNew(perms);
        Person p = poster.person(auth.getName());
        return tx.execute((st) -> {
            List<Line> lines = lines(p.branch(), year, controlNo, true);
            List<String> problems = problems(lines);
            if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, String.join(" ", problems));
            Integer approver = lines.get(0).approver();
            if (approver == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This voucher has not been approved yet.");
            if (approver == p.id() || lines.get(0).encoder() == p.id()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The person who encoded or approved an interest voucher cannot post it.");
            List<Map<String, Object>> head = jdbc.queryForList("SELECT TR_DATE, TR_DOCNO FROM transsum WHERE TR_BRCODE = ? AND TR_YEAR = ? AND TR_MODULE = ? AND TR_CODE = ? AND TR_CTLNO = ?", p.branch(), year, MODULE, CODE, controlNo);
            LocalDate day = ((java.sql.Date) head.get(0).get("TR_DATE")).toLocalDate();
            String docNo = (String) head.get(0).get("TR_DOCNO");
            Timestamp stamp = Timestamp.valueOf(LocalDateTime.now(clock).withNano(0));
            int updated = jdbc.update("UPDATE transdt SET DTPOSTBY = ?, DTUPD_TAG = ? WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = ? AND DTTR_CODE = ? AND DTTR_CTLNO = ? AND DTUPD_TAG = ?",
                p.id(), POSTED, p.branch(), year, MODULE, CODE, controlNo, PENDING);
            if (updated != lines.size()) throw new ResponseStatusException(HttpStatus.CONFLICT, "The voucher changed while posting; try again.");
            for (Line l : lines) {
                if (l.slc() != null) {
                    poster.writeLedgerRow(p.branch(), l.client(), new MemberLine(22, l.slt(), l.ref(), 11, l.cts() == null ? 99 : l.cts(), l.account(), l.amount()), day, CODE, controlNo, docNo);
                }
                poster.writeGlLine(p.branch(), l.account(), day, CODE, l.dept(), l.amount(), stamp);
            }
            audit(auth, "savings.interest.post", p, year + "/" + controlNo, lines.size() + " lines doc " + docNo);
            return row(p.branch(), year, controlNo);
        });
    }

    private VoucherRow row(int branch, int year, long ctl) {
        List<Line> lines = lines(branch, year, ctl, false);
        Line first = lines.get(0);
        Integer type = lines.stream().filter((l) -> l.slt() != null).map(Line::slt).findFirst().orElse(0);
        String status = lines.stream().anyMatch((l) -> l.tag() == POSTED) ? "POSTED" : first.approver() == null ? "WAITING FOR APPROVAL" : "APPROVED";
        String docNo = String.format("%08d", ctl);
        return new VoucherRow(year, ctl, docNo, type, lines.size(), lines.stream().map(Line::amount).filter((a) -> a.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add), first.encoder(), first.approver(), status);
    }

    private void audit(Authentication auth, String action, Person p, String entity, String detail) {
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value) VALUES (?, ?, ?, 'transsum', ?, ?)",
            Timestamp.valueOf(LocalDateTime.now(clock).withNano(0)), auth.getName(), action, p.branch() + "/" + MODULE + "/" + CODE + "/" + entity, detail);
    }
}
