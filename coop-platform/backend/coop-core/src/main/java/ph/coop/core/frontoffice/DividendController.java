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
import ph.coop.core.deposit.DividendRun;
import ph.coop.core.deposit.DividendRun.Credit;
import ph.coop.core.deposit.DividendRun.MemberAmounts;
import ph.coop.core.deposit.DividendRun.Params;
import ph.coop.core.deposit.DividendRun.Plan;
import ph.coop.core.frontoffice.FrontOfficeAccess.Perms;
import ph.coop.core.frontoffice.MemberLedgerPoster.MemberLine;
import ph.coop.core.frontoffice.MemberLedgerPoster.Person;

/**
 * The annual "Dividend and Patronage Refund Transaction (Computer Generated)" (module 6 type 54; documentation/dividend-map.md). Preview is read only.
 * The year is first prepared, reviewed and approved as a stored distribution (DistributionController); encoding then writes exactly its stored credits as the
 * vouchers the old system writes (up to 7,000 member lines each, one debit of the fund account 20126 per voucher) with every line tagged 2; a second person
 * approves and a third posts them, which writes the member ledger rows and the general ledger lines dated the cut-off. A year that already has dividend vouchers
 * cannot be encoded again. There is no single-person exemption for this run.
 */
@RestController
@RequestMapping("/api/front-office/dividends")
class DividendController {

    private static final int MODULE = 6, CODE = 54, PENDING = 2, POSTED = 1, CHUNK = 7000, CTS = 15;

    /**
     * What the accountant typed. Either the two rates (as the old system was run) or, following the specification, the amounts the general assembly allocated to interest on share
     * capital and to patronage refund, with the net surplus and statutory funds they come out of; the rates are then worked out from the allocations.
     */
    record Request(String cutoff, BigDecimal dividendRate, BigDecimal patronageRate, BigDecimal mutualBenefit, BigDecimal hospitalization, List<MemberAmounts> overrides,
                   BigDecimal iscAllocation, BigDecimal prAllocation, BigDecimal netSurplus, List<DividendRun.Statutory> statutory, String approvalRef) {
        /** The rates typed in, as the old system was run. */
        Request(String cutoff, BigDecimal dividendRate, BigDecimal patronageRate, BigDecimal mutualBenefit, BigDecimal hospitalization, List<MemberAmounts> overrides) {
            this(cutoff, dividendRate, patronageRate, mutualBenefit, hospitalization, overrides, null, null, null, null, null);
        }

        Params params() {
            return new Params(dividendRate, patronageRate, mutualBenefit, hospitalization, overrides, iscAllocation, prAllocation, netSurplus, statutory, approvalRef == null ? null : approvalRef.trim());
        }
    }

    record HeldRow(int clientId, BigDecimal total, String reason) {}

    /** The figures of a run. {@code dividendRate} and {@code patronageRate} are the rates used (typed, or worked out from the allocations); the others reconcile the run with them. */
    record Summary(String cutoff, int year, boolean alreadyEncoded, int members, BigDecimal dividend, BigDecimal refund, BigDecimal total, BigDecimal mutualBenefit, BigDecimal hospitalization,
                   BigDecimal savings, BigDecimal shareCapital, int vouchers, int heldCount, List<HeldRow> held,
                   boolean fromAllocation, BigDecimal totalAverageShareMonth, BigDecimal totalQualifiedPatronage, BigDecimal dividendRate, BigDecimal patronageRate,
                   BigDecimal iscAllocation, BigDecimal prAllocation, BigDecimal netSurplus, BigDecimal availableForDistribution, List<String> controls) {}

    record VoucherRow(int year, long controlNo, String documentNo, int lines, BigDecimal total, int encodedBy, Integer approvedBy, String status) {}

    /** Encode the vouchers of an approved distribution. */
    record EncodeIn(Long batchId) {}

    private final FrontOfficeAccess access;

    private final ph.coop.core.businessday.BusinessDay businessDay;
    private final MemberLedgerPoster poster;
    private final DividendRun run;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Db db;

    private final DistributionStore store;

    DividendController(FrontOfficeAccess access, MemberLedgerPoster poster, DividendRun run, JdbcTemplate jdbc, PlatformTransactionManager txm, Clock clock, Db db, ph.coop.core.businessday.BusinessDay businessDay,
                       DistributionStore store) {
        this.store = store;

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
        if (!p.canFind(CODE)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot look up dividend vouchers.");
    }

    private static void requireNew(Perms p) {
        if (!p.canNew(CODE)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot create dividend vouchers.");
    }

    private LocalDate cutoff(String text) {
        LocalDate d;
        try {
            d = LocalDate.parse(text);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the cut-off date, such as 2027-02-21.");
        }
        if (d.getYear() < 2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That cut-off date is too early.");
        if (d.isAfter(LocalDate.now(clock))) throw new ResponseStatusException(HttpStatus.CONFLICT, "That cut-off date has not come yet.");
        return d;
    }

    private Plan plan(int branch, LocalDate cutoff, Params params) {
        try {
            return run.plan(branch, cutoff, params);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private int vouchersOf(int branch, LocalDate cutoff) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM transsum WHERE TR_BRCODE = ? AND TR_YEAR = ? AND TR_MODULE = ? AND TR_CODE = ?", Integer.class, branch, cutoff.getYear(), MODULE, CODE);
        return n == null ? 0 : n;
    }

    @PostMapping("/preview")
    Summary preview(@RequestBody Request in, Authentication auth) {
        Perms perms = access.of(auth);
        requireFind(perms);
        LocalDate cutoff = cutoff(in.cutoff());
        Plan plan = plan(perms.branch(), cutoff, in.params());
        return summary(plan, vouchersOf(perms.branch(), cutoff) > 0);
    }

    private Summary summary(Plan plan, boolean already) {
        BigDecimal dividend = BigDecimal.ZERO, refund = BigDecimal.ZERO, total = BigDecimal.ZERO, mbf = BigDecimal.ZERO, habp = BigDecimal.ZERO, rest = BigDecimal.ZERO, sav = BigDecimal.ZERO, shr = BigDecimal.ZERO;
        for (DividendRun.Member m : plan.members()) {
            dividend = dividend.add(m.dividend());
            refund = refund.add(m.refund());
            total = total.add(m.total());
            mbf = mbf.add(m.mutualBenefit());
            habp = habp.add(m.hospitalization());
        }
        for (Credit c : plan.credits()) {
            if (c.kind().equals("SAVINGS")) sav = sav.add(c.amount());
            else if (c.kind().equals("SHARE_CAPITAL")) shr = shr.add(c.amount());
        }
        List<HeldRow> held = plan.held().stream().limit(50).map((h) -> new HeldRow(h.clientId(), h.total(), h.reason())).toList();
        Params pa = plan.params();
        return new Summary(plan.cutoff().toString(), plan.year(), already, plan.members().size(), dividend, refund, total, mbf, habp, sav, shr, (plan.credits().size() + CHUNK - 1) / CHUNK, plan.held().size(), held,
            pa.fromAllocation(), plan.totalAverageShareMonth(), plan.totalQualifiedPatronage(), plan.dividendRate(), plan.patronageRate(), pa.iscAllocation(), pa.prAllocation(), pa.netSurplus(),
            pa.fromAllocation() ? DividendRun.available(pa.netSurplus(), pa.statutory()) : null, overAllocation(plan, dividend, refund));
    }

    /**
     * The control that a distribution may not exceed what was approved: with allocations, the dividend may not be more than the interest on share capital allocation, nor the refund
     * more than the patronage refund allocation, beyond what rounding each member to the centavo can add (half a centavo a member).
     */
    static List<String> overAllocation(Plan plan, BigDecimal dividend, BigDecimal refund) {
        List<String> out = new ArrayList<>();
        Params pa = plan.params();
        if (!pa.fromAllocation()) return out;
        BigDecimal slack = new BigDecimal("0.005").multiply(BigDecimal.valueOf(plan.members().size())).setScale(2, java.math.RoundingMode.CEILING);
        if (pa.iscAllocation() != null && dividend.compareTo(pa.iscAllocation().add(slack)) > 0) out.add("The interest on share capital to be credited (" + dividend.toPlainString() + ") is more than the allocation (" + pa.iscAllocation().toPlainString() + ").");
        if (pa.prAllocation() != null && refund.compareTo(pa.prAllocation().add(slack)) > 0) out.add("The patronage refund to be credited (" + refund.toPlainString() + ") is more than the allocation (" + pa.prAllocation().toPlainString() + ").");
        return out;
    }

    /**
     * Writes the vouchers of an approved distribution (DistributionController), from its stored credits, every line waiting (tag 2), encoded by the caller. The
     * year may have no dividend vouchers yet. The distribution becomes ENCODED with the voucher numbers.
     */
    @PostMapping("/encode")
    List<VoucherRow> encode(@RequestBody EncodeIn in, Authentication auth) {
        Perms perms = access.of(auth);
        requireNew(perms);
        Person p = poster.person(auth.getName());
        if (p.dept() <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Your account has no department set, so the vouchers cannot be encoded.");
        if (in == null || in.batchId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the approved distribution to encode.");
        return tx.execute((st) -> {
            DistributionStore.Batch b = store.find(in.batchId(), p.branch(), true);
            if (b == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "There is no such distribution.");
            if (!"APPROVED".equals(b.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an approved distribution can be encoded; this one is " + b.status().toLowerCase() + ".");
            LocalDate cutoff = b.cutoff();
            PostingPeriods.requireOpen(jdbc, p.branch(), cutoff);
            // a run posts on its own period-end date, but only while the branch has a business day open
            businessDay.requireStarted(p.branch());
            if (vouchersOf(p.branch(), cutoff) > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Dividend vouchers already exist for " + cutoff.getYear() + ", so it cannot be run again.");
            List<Credit> all = store.credits(b.id());
            if (all.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "This distribution credits no one.");
            LocalDateTime now = LocalDateTime.now(clock).withNano(0);
            int year = cutoff.getYear();
            List<VoucherRow> out = new ArrayList<>();
            Map<String, String> glCache = new HashMap<>();
            String explanation = explanation(cutoff, b.dividendRate(), b.patronageRate(), "ALLOCATION".equals(b.method()) ? b.approvalRef() : null);
            for (int from = 0; from < all.size(); from += CHUNK) {
                List<Credit> part = all.subList(from, Math.min(all.size(), from + CHUNK));
                long ctl = db.nextControlNumber(p.branch(), MODULE, CODE, year);
                String docNo = String.format("%08d", ctl);
                jdbc.update("""
                        INSERT INTO transsum (TR_BRCODE, TR_YEAR, TR_MODULE, TR_CODE, TR_CTLNO, TR_DOCNO, TR_DATE, TR_TIME, BATCH_NO, EXPLANATION, IS_FO, DATETIMEADDED)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                        """, p.branch(), year, MODULE, CODE, ctl, docNo, cutoff.toString(), now.format(DateTimeFormatter.ofPattern("HH:mm:ss")), cutoff.format(DateTimeFormatter.BASIC_ISO_DATE),
                    explanation, Timestamp.valueOf(now));
                String sql = """
                        INSERT INTO transdt (DTTR_BRCODE, DTTR_YEAR, DTTR_MODULE, DTTR_CODE, DTTR_CTLNO, DTSEQ_NO, DTDEPT, DTACCT_CODE, ClientIDDT, DTSLC_CODE, DTSLT_CODE, DTREF_NO,
                          DTSLE_CODE, DTCTS_NUM, DTSL_DATE, DTADJ_FLAG, DTCOCI_TYPE, DTAMT, DTENCDBY, DTUPD_TAG, DTCHECKISRELEASE, DTISTRANSIT, DTISUNDENTIFIED)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 0, 0, 0)
                        """;
                List<Object[]> rows = new ArrayList<>();
                BigDecimal sum = BigDecimal.ZERO;
                int seq = 1;
                for (Credit c : part) {
                    String gl = glCache.computeIfAbsent(c.slc() + "/" + c.typeCode(), k -> poster.glAccount(p.branch(), c.slc(), c.typeCode(), 11, CTS));
                    rows.add(new Object[] {p.branch(), year, MODULE, CODE, ctl, seq++, p.dept(), gl, c.clientId(), c.slc(), c.typeCode(), c.refNo(), 11, CTS, cutoff.toString(), 1, c.amount().negate(), p.id(), PENDING});
                    sum = sum.add(c.amount());
                }
                rows.add(new Object[] {p.branch(), year, MODULE, CODE, ctl, seq, p.dept(), DividendRun.FUND_ACCOUNT, null, null, null, null, null, null, null, null, sum, p.id(), PENDING});
                jdbc.batchUpdate(sql, rows);
                out.add(new VoucherRow(year, ctl, docNo, rows.size(), sum, p.id(), null, "WAITING FOR APPROVAL"));
            }
            store.markEncoded(b.id(), auth.getName(), now, year, out.get(0).controlNo(), out.get(out.size() - 1).controlNo());
            audit(auth, "dividend.encode", p, cutoff.toString(), "distribution " + b.id() + " (" + b.year() + "): " + out.size() + " vouchers, " + b.members() + " members, total "
                + b.total().toPlainString() + "; dividend " + b.dividendRate().toPlainString() + "% patronage " + b.patronageRate().toPlainString() + "%");
            return out;
        });
    }

    private String explanation(LocalDate cutoff, BigDecimal dividendRate, BigDecimal patronageRate, String approvalRef) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("MM/dd/yyyy");
        String fund = jdbc.queryForList("SELECT COADesc FROM coa WHERE COAID = ?", String.class, DividendRun.FUND_ACCOUNT).stream().findFirst().orElse("");
        return "Dividend and Patronage Refund Transaction (Computer Generated)\nTransactions Cut-Off: " + cutoff.format(f)
            + "\nInt. on Share Capital Fund GL Acct: " + DividendRun.FUND_ACCOUNT + " - " + fund
            + "\nPatronage Refund Fund GL Acct: " + DividendRun.FUND_ACCOUNT + " - " + fund
            + "\nDividend Rate: " + dividendRate.toPlainString() + "% Patronage Refund Rate: " + patronageRate.toPlainString() + "%"
            + (approvalRef != null ? "\nAllocation approved by: " + approvalRef : "");
    }

    private Map<Integer, String> names(int branch) {
        Map<Integer, String> out = new HashMap<>();
        jdbc.query("SELECT ClientID, COALESCE(NULLIF(TRIM(AccountName), ''), CONCAT_WS(' ', FName, LName)) FROM client WHERE ClientIDBrCode = ?", (rs) -> { out.put(rs.getInt(1), rs.getString(2)); }, branch);
        return out;
    }

    private static String csv(String v) {
        return v == null ? "" : v.matches(".*[\",\n].*") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    /** The distribution register: one line per member with the working of each amount (guide-style report). Read only. */
    @PostMapping(value = "/register", produces = "text/csv")
    org.springframework.http.ResponseEntity<byte[]> register(@RequestBody Request in, Authentication auth) {
        Perms perms = access.of(auth);
        requireFind(perms);
        LocalDate cutoff = cutoff(in.cutoff());
        Plan plan = plan(perms.branch(), cutoff, in.params());
        Map<Integer, String> names = names(perms.branch());
        StringBuilder sb = new StringBuilder("Member no.,Member,Average share-month,ISC rate %,Interest on share capital,Qualified patronage,PR rate %,Patronage refund,Total distribution,To mutual benefit,To hospitalization,To savings or share capital\r\n");
        for (DividendRun.Member m : plan.members()) {
            sb.append(m.clientId()).append(',').append(csv(names.get(m.clientId()))).append(',').append(m.averageShareMonth().toPlainString()).append(',').append(plan.dividendRate().stripTrailingZeros().toPlainString()).append(',')
                .append(m.dividend().toPlainString()).append(',').append(m.qualifiedPatronage().toPlainString()).append(',').append(plan.patronageRate().stripTrailingZeros().toPlainString()).append(',')
                .append(m.refund().toPlainString()).append(',').append(m.total().toPlainString()).append(',').append(m.mutualBenefit().toPlainString()).append(',').append(m.hospitalization().toPlainString()).append(',')
                .append(m.rest().toPlainString()).append("\r\n");
        }
        audit(auth, "dividend.register", poster.person(auth.getName()), cutoff.toString(), plan.members().size() + " members");
        return org.springframework.http.ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"distribution-register-" + cutoff.getYear() + ".csv\"")
            .contentType(org.springframework.http.MediaType.parseMediaType("text/csv")).body(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    record Statement(int clientId, String name, int year, BigDecimal averageShareMonth, BigDecimal dividendRate, BigDecimal dividend, BigDecimal qualifiedPatronage, BigDecimal patronageRate,
                     BigDecimal refund, BigDecimal total) {}

    /** One member's combined distribution statement: average share-month, interest rate and amount, qualified patronage, refund rate and amount, and the total. Read only. */
    @PostMapping("/statement")
    Statement statement(@RequestBody Request in, @RequestParam int clientId, Authentication auth) {
        Perms perms = access.of(auth);
        requireFind(perms);
        LocalDate cutoff = cutoff(in.cutoff());
        Plan plan = plan(perms.branch(), cutoff, in.params());
        DividendRun.Member m = plan.members().stream().filter((x) -> x.clientId() == clientId).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That member has no dividend or refund in this run."));
        return new Statement(clientId, names(perms.branch()).get(clientId), plan.year(), m.averageShareMonth(), plan.dividendRate(), m.dividend(), m.qualifiedPatronage(), plan.patronageRate(), m.refund(), m.total());
    }

    @GetMapping("/vouchers")
    List<VoucherRow> vouchers(@RequestParam String cutoff, Authentication auth) {
        Perms perms = access.of(auth);
        requireFind(perms);
        LocalDate day = cutoff(cutoff);
        List<Long> controls = jdbc.queryForList("SELECT TR_CTLNO FROM transsum WHERE TR_BRCODE = ? AND TR_YEAR = ? AND TR_MODULE = ? AND TR_CODE = ? ORDER BY TR_CTLNO", Long.class, perms.branch(), day.getYear(), MODULE, CODE);
        List<VoucherRow> out = new ArrayList<>();
        for (long c : controls) out.add(row(perms.branch(), day.getYear(), c));
        return out;
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
        if (lines.stream().anyMatch((l) -> l.slc() != null && ((l.slc() != 22 && l.slc() != 29 && l.slc() != 31) || l.event() == null || l.event() != 11 || l.client() == null || l.slt() == null || l.ref() == null)))
            problems.add("This is not a dividend voucher.");
        if (lines.stream().noneMatch((l) -> DividendRun.FUND_ACCOUNT.equals(l.account()) && l.slc() == null)) problems.add("This is not a dividend voucher.");
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
            audit(auth, "dividend.approve", p, year + "/" + controlNo, "approved by " + p.id());
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
            if (approver == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This voucher has not been approved yet.");
            if (approver == p.id() || lines.get(0).encoder() == p.id()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The person who encoded or approved a dividend voucher cannot post it.");
            List<Map<String, Object>> head = jdbc.queryForList("SELECT TR_DATE, TR_DOCNO FROM transsum WHERE TR_BRCODE = ? AND TR_YEAR = ? AND TR_MODULE = ? AND TR_CODE = ? AND TR_CTLNO = ?", p.branch(), year, MODULE, CODE, controlNo);
            LocalDate day = ((java.sql.Date) head.get(0).get("TR_DATE")).toLocalDate();
            String docNo = (String) head.get(0).get("TR_DOCNO");
            Timestamp stamp = Timestamp.valueOf(LocalDateTime.now(clock).withNano(0));
            int updated = jdbc.update("UPDATE transdt SET DTPOSTBY = ?, DTUPD_TAG = ? WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = ? AND DTTR_CODE = ? AND DTTR_CTLNO = ? AND DTUPD_TAG = ?",
                p.id(), POSTED, p.branch(), year, MODULE, CODE, controlNo, PENDING);
            if (updated != lines.size()) throw new ResponseStatusException(HttpStatus.CONFLICT, "The voucher changed while posting; try again.");
            for (Line l : lines) {
                if (l.slc() != null) {
                    poster.writeLedgerRow(p.branch(), l.client(), new MemberLine(l.slc(), l.slt(), l.ref(), 11, l.cts() == null ? CTS : l.cts(), l.account(), l.amount()), day, CODE, controlNo, docNo);
                }
                poster.writeGlLine(p.branch(), l.account(), day, CODE, l.dept(), l.amount(), stamp);
            }
            audit(auth, "dividend.post", p, year + "/" + controlNo, lines.size() + " lines doc " + docNo);
            return row(p.branch(), year, controlNo);
        });
    }

    private VoucherRow row(int branch, int year, long ctl) {
        List<Line> lines = lines(branch, year, ctl, false);
        if (lines.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such voucher.");
        Line first = lines.get(0);
        String status = lines.stream().anyMatch((l) -> l.tag() == POSTED) ? "POSTED" : first.approver() == null ? "WAITING FOR APPROVAL" : "APPROVED";
        return new VoucherRow(year, ctl, String.format("%08d", ctl), lines.size(), lines.stream().map(Line::amount).filter((a) -> a.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add), first.encoder(), first.approver(), status);
    }

    private void audit(Authentication auth, String action, Person p, String entity, String detail) {
        AuditLog.write(jdbc, auth.getName(), action, "transsum", p.branch() + "/" + MODULE + "/" + CODE + "/" + entity, null, detail, null);
    }
}
