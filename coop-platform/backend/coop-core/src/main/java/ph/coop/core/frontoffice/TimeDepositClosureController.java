package ph.coop.core.frontoffice;


import ph.coop.core.audit.AuditLog;import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;
import ph.coop.core.frontoffice.FrontOfficeAccess.Perms;
import ph.coop.core.frontoffice.MemberLedgerPoster.Client;
import ph.coop.core.frontoffice.MemberLedgerPoster.Person;
import ph.coop.core.frontoffice.TimeDepositClosureRules.Amounts;

/**
 * Pays out or renews a matured time deposit, always on a person's request (nothing runs by itself): it encodes the back-office Cash Voucher (module 2, type 21,
 * every line tagged 2) the old system wrote, mapped in documentation/time-deposit-map.md. The voucher then waits on the Disburse tab, where {@link LoanReleaseController}
 * has it approved and posted by two other people. Payout: the certificate, its accrued interest and the interest still to expense are debited and the member's
 * vouchers-payable account is credited. Renewal: the same debits, a new certificate credited with the money (less what the member takes out and the renewal
 * fee, credited to 442202) and the part taken out to vouchers payable. The new certificate's rows (td, slmastersum) are made when the voucher is encoded.
 */
@RestController
@RequestMapping("/api/front-office/time-deposit-closures")
class TimeDepositClosureController {

    private static final int MODULE = 2, CODE = 21, PENDING = 2, CLASS_TD = 24, PAYABLE = 29, PRINCIPAL = 11, ACCRUED = 71, ACTIVE = 15;

    /**
     * newTypeCode (the certificate a Lock In is renewed into) and interestYears (Lock In: years of interest paid, 1 by default) are only read for Lock In certificates.
     * A certificate that has not matured is pre-terminated: pretermRate (Short Term, 1% by default) or interestPaid (Lock In, 0 by default) say what interest is paid.
     */
    record Request(int clientId, int typeCode, int refNo, String mode, Integer termDays, BigDecimal ratePercent, BigDecimal withdraw, Boolean renewalFee, String explanation,
                   Integer newTypeCode, Integer interestYears, BigDecimal pretermRate, BigDecimal interestPaid) {
        Request(int clientId, int typeCode, int refNo, String mode, Integer termDays, BigDecimal ratePercent, BigDecimal withdraw, Boolean renewalFee, String explanation) {
            this(clientId, typeCode, refNo, mode, termDays, ratePercent, withdraw, renewalFee, explanation, null, null, null, null);
        }

        Request(int clientId, int typeCode, int refNo, String mode, Integer termDays, BigDecimal ratePercent, BigDecimal withdraw, Boolean renewalFee, String explanation,
                Integer newTypeCode, Integer interestYears) {
            this(clientId, typeCode, refNo, mode, termDays, ratePercent, withdraw, renewalFee, explanation, newTypeCode, interestYears, null, null);
        }
    }

    /** newPrincipal, newMaturity, fee and withdraw are filled for a renewal. */
    record Quote(String mode, int clientId, String clientName, int typeCode, String type, int refNo, String certificate, LocalDate maturity, int termDays, BigDecimal ratePercent,
                 BigDecimal principal, BigDecimal interest, BigDecimal accrued, BigDecimal expense, BigDecimal due, BigDecimal newPrincipal, LocalDate newMaturity, BigDecimal fee,
                 BigDecimal withdraw, List<Integer> terms, List<String> problems, boolean ready, boolean lockIn, int interestYears, int maxYears, int newTypeCode,
                 boolean preterm, BigDecimal terminationFee, BigDecimal pretermRate, int daysHeld) {}

    record Encoded(int branchCode, int year, long controlNo, String documentNo, String mode, int clientId, String clientName, BigDecimal due, Integer newCertificate, BigDecimal newPrincipal,
                   BigDecimal paidToPayable) {}

    private record Cert(int status, BigDecimal rate, int termDays, LocalDate maturity, String certificate, String type, BigDecimal balance, BigDecimal accrued, boolean hasLedger, LocalDate placed) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final Clock clock;
    private final FrontOfficeAccess access;
    private final MemberLedgerPoster poster;
    private final TransactionTemplate tx;
    private final ph.coop.core.member.KycGate kyc;

    TimeDepositClosureController(JdbcTemplate jdbc, Db db, Clock clock, FrontOfficeAccess access, MemberLedgerPoster poster, PlatformTransactionManager txm,
                                 ph.coop.core.member.KycGate kyc) {
        this.kyc = kyc;
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
        this.access = access;
        this.poster = poster;
        this.tx = new TransactionTemplate(txm);
    }

    private Cert cert(int branch, Request r) {
        List<Cert> found = jdbc.query("""
                SELECT d.TDSTATUS, d.TDINT_RATE, d.TDTERMS, d.TDMAT_DATE, d.TDCERT_NUM, t.SLTypeM_DESC,
                  (SELECT COALESCE(-SUM(l.AMT), 0) FROM sldtl l WHERE l.SL_BRCODE = d.TDBR_CODE AND l.SL_CLIENTID = d.ClientIDTD AND l.SLC_CODE = d.TDSLC_CODE AND l.SLT_CODE = d.TDSLT_CODE AND l.REF_NO = d.TDREF_NO AND l.SLE_CODE = 11),
                  (SELECT COALESCE(-SUM(l.AMT), 0) FROM sldtl l WHERE l.SL_BRCODE = d.TDBR_CODE AND l.SL_CLIENTID = d.ClientIDTD AND l.SLC_CODE = d.TDSLC_CODE AND l.SLT_CODE = d.TDSLT_CODE AND l.REF_NO = d.TDREF_NO AND l.SLE_CODE = 71),
                  (SELECT COUNT(*) FROM sldtl l WHERE l.SL_BRCODE = d.TDBR_CODE AND l.SL_CLIENTID = d.ClientIDTD AND l.SLC_CODE = d.TDSLC_CODE AND l.SLT_CODE = d.TDSLT_CODE AND l.REF_NO = d.TDREF_NO),
                  d.TDTR_DATE
                FROM td d LEFT JOIN sltype t ON t.SLTypeBR_CODE = d.TDBR_CODE AND t.SLTypeSLC_CODE = d.TDSLC_CODE AND t.SLTypeSLT_CODE = d.TDSLT_CODE
                WHERE d.TDBR_CODE = ? AND d.ClientIDTD = ? AND d.TDSLC_CODE = ? AND d.TDSLT_CODE = ? AND d.TDREF_NO = ?
                """, (rs, i) -> new Cert(rs.getInt(1), rs.getBigDecimal(2), rs.getInt(3), rs.getDate(4) == null ? null : rs.getDate(4).toLocalDate(), rs.getString(5), rs.getString(6),
                rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getLong(9) > 0, rs.getDate(10) == null ? null : rs.getDate(10).toLocalDate()), branch, r.clientId(), CLASS_TD, r.typeCode(), r.refNo());
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That client has no such time deposit.");
        return found.get(0);
    }

    private Integer payableAccount(int branch, int clientId) {
        return jdbc.queryForList("SELECT MREF_NO FROM slmastersum WHERE MBR_CODE = ? AND MCLIENTID = ? AND MSLC_CODE = ? AND MSLT_CODE = 1 ORDER BY MREF_NO DESC LIMIT 1",
            Integer.class, branch, clientId, PAYABLE).stream().findFirst().orElse(null);
    }

    private boolean voucherWaiting(int branch, int year, Request r) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM transdt WHERE DTTR_BRCODE = ? AND DTTR_YEAR >= ? AND DTTR_MODULE = ? AND DTTR_CODE = ? AND DTUPD_TAG = ? AND ClientIDDT = ?
                  AND DTSLC_CODE = ? AND DTSLT_CODE = ? AND DTREF_NO = ? AND DTSLE_CODE IN (?, ?) AND DTAMT > 0
                """, Integer.class, branch, year - 1, MODULE, CODE, PENDING, r.clientId(), CLASS_TD, r.typeCode(), r.refNo(), PRINCIPAL, ACCRUED);
        return n != null && n > 0;
    }

    /** Terms in use for the type: up to 365 days for Short Term; Lock In terms are the ones used at least three times (a few odd ones exist). */
    private List<Integer> knownTerms(int branch, int typeCode) {
        if (TimeDepositClosureRules.isLockIn(typeCode))
            return jdbc.queryForList("SELECT TDTERMS FROM td WHERE TDBR_CODE = ? AND TDSLC_CODE = ? AND TDSLT_CODE = ? AND TDTR_DATE <= ? AND TDTERMS > 365 GROUP BY TDTERMS HAVING COUNT(*) >= 3 ORDER BY TDTERMS",
                Integer.class, branch, CLASS_TD, typeCode, java.sql.Date.valueOf(LocalDate.now(clock)));
        return jdbc.queryForList("SELECT DISTINCT TDTERMS FROM td WHERE TDBR_CODE = ? AND TDSLC_CODE = ? AND TDSLT_CODE = ? AND TDTR_DATE <= ? AND TDTERMS > 0 ORDER BY TDTERMS",
            Integer.class, branch, CLASS_TD, typeCode, java.sql.Date.valueOf(LocalDate.now(clock))).stream().filter((d) -> d <= 365).toList();
    }

    private int newType(Request r) {
        return TimeDepositClosureRules.isLockIn(r.typeCode()) && r.newTypeCode() != null ? r.newTypeCode() : r.typeCode();
    }

    /** What the voucher would be, and why it cannot be encoded when it cannot. */
    @PostMapping("/quote")
    Quote quote(@RequestBody Request req, Authentication auth) {
        Perms perms = access.of(auth);
        access.requireNew(perms, ShareWithdrawalRules.CASH_VOUCHER);
        Person p = poster.person(auth.getName());
        return build(p, req);
    }

    private Quote build(Person p, Request r) {
        LocalDate today = LocalDate.now(clock);
        Cert c = cert(p.branch(), r);
        Client client = poster.client(p.branch(), r.clientId());
        boolean renew = "RENEW".equalsIgnoreCase(r.mode());
        BigDecimal balance = c.balance();
        boolean lockIn = TimeDepositClosureRules.isLockIn(r.typeCode());
        boolean pre = c.maturity() != null && c.maturity().isAfter(today);
        int daysHeld = c.placed() == null ? 0 : (int) java.time.temporal.ChronoUnit.DAYS.between(c.placed(), today);
        int years = lockIn ? (r.interestYears() == null ? 1 : r.interestYears()) : 0;
        Amounts a = pre ? TimeDepositClosureRules.pretermAmounts(r.typeCode(), balance, daysHeld, c.accrued(), r.pretermRate(), r.interestPaid()) : lockIn ? TimeDepositClosureRules.lockInAmounts(balance, c.rate(), years, c.accrued()) : TimeDepositClosureRules.amounts(balance, c.rate(), c.termDays(), c.accrued());
        String status = c.status() == 17 ? "CLOSED" : !c.hasLedger() ? "NO_LEDGER" : balance.compareTo(new BigDecimal("0.005")) < 0 ? "CLOSED" : c.maturity() != null && c.maturity().isBefore(today) ? "MATURED" : "ACTIVE";
        List<String> problems = new ArrayList<>(TimeDepositClosureRules.problems(r.typeCode(), status, c.maturity(), today, a, payableAccount(p.branch(), r.clientId()) != null, voucherWaiting(p.branch(), today.getYear(), r)));
        if (client.status() == 3 || client.status() == 98 || client.status() == 99) problems.add("This client's account is closed, disapproved or not yet approved.");
        problems.addAll(pre ? TimeDepositClosureRules.pretermProblems(r.typeCode(), c.rate(), r.pretermRate(), r.interestPaid(), c.accrued(), c.placed(), today)
            : TimeDepositClosureRules.yearsProblems(r.typeCode(), years, c.termDays()));
        int nt = newType(r);
        List<Integer> terms = knownTerms(p.branch(), nt);
        BigDecimal newPrincipal = null, fee = null, withdraw = null;
        LocalDate newMaturity = null;
        if (renew) {
            // a Lock In renewal rolls the principal and pays the interest out unless the clerk says otherwise; the old vouchers charge no renewal fee on it
            BigDecimal asked = r.withdraw() == null ? (lockIn ? a.interest() : BigDecimal.ZERO) : r.withdraw();
            withdraw = asked.setScale(Math.max(2, asked.scale()), RoundingMode.UNNECESSARY);
            boolean charge = !lockIn && (pre ? Boolean.TRUE.equals(r.renewalFee()) : r.renewalFee() == null || r.renewalFee());
            if (nt < 1 || nt > 4 || jdbc.queryForList("SELECT 1 FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ?", Integer.class, p.branch(), CLASS_TD, nt).isEmpty())
                problems.add("That kind of certificate does not exist.");
            int term = r.termDays() == null ? c.termDays() : r.termDays();
            BigDecimal rate = r.ratePercent() == null ? c.rate() : r.ratePercent();
            BigDecimal min = jdbc.queryForList("SELECT COALESCE(SLTypeMIN_BAL, 0) FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ?", BigDecimal.class, p.branch(), CLASS_TD, nt)
                .stream().findFirst().orElse(null);
            problems.addAll(TimeDepositClosureRules.renewalProblems(a, withdraw, charge, min, term, rate, terms));
            var rn = TimeDepositClosureRules.renewal(a, withdraw, charge);
            newPrincipal = rn.newPrincipal();
            fee = rn.fee();
            newMaturity = today.plusDays(Math.max(term, 0));
        }
        // a pay-out pays the member; a renewal does only when part of it is taken out
        if (!renew || (withdraw != null && withdraw.signum() > 0)) {
            String kycProblem = kyc.withdrawalProblem(p.branch(), r.clientId(), today);
            if (kycProblem != null) problems.add(kycProblem);
        }
        return new Quote(renew ? "RENEW" : "PAYOUT", r.clientId(), client.name(), r.typeCode(), c.type(), r.refNo(), c.certificate(), c.maturity(), renew && r.termDays() != null ? r.termDays() : c.termDays(),
            renew && r.ratePercent() != null ? r.ratePercent() : c.rate(), a.principal(), a.interest(), a.accrued(), a.expense(), a.due(), newPrincipal, newMaturity, fee, withdraw, terms, problems, problems.isEmpty(), lockIn, years, lockIn ? TimeDepositClosureRules.maxYears(c.termDays()) : 0, nt,
            pre, a.termFee(), pre && !lockIn ? (r.pretermRate() == null ? TimeDepositClosureRules.SHORT_TERM_RATE : r.pretermRate()) : null, daysHeld);
    }

    private static final String LINE = """
            INSERT INTO transdt (DTTR_BRCODE, DTTR_YEAR, DTTR_MODULE, DTTR_CODE, DTTR_CTLNO, DTSEQ_NO, DTDEPT, DTACCT_CODE, ClientIDDT, DTSLC_CODE, DTSLT_CODE, DTREF_NO,
              DTSLE_CODE, DTCTS_NUM, DTSL_DATE, DTADJ_FLAG, DTAMT, DTENCDBY, DTUPD_TAG, DTCHECKISRELEASE, DTISTRANSIT, DTISUNDENTIFIED)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 0, 0, 0)
            """;

    /** The Lock In interest withdrawal: what it would be, and why it cannot be encoded when it cannot. */
    record InterestRequest(int clientId, int typeCode, int refNo, BigDecimal amount, String explanation) {}

    record InterestQuote(int clientId, String clientName, int typeCode, String type, int refNo, String certificate, BigDecimal balance, BigDecimal ratePercent, LocalDate placed,
                         int completedYears, BigDecimal yearInterest, BigDecimal withdrawn, BigDecimal available, BigDecimal accrued, BigDecimal amount, BigDecimal fromAccrual,
                         BigDecimal expense, List<String> problems, boolean ready) {}

    /**
     * What was already withdrawn: the accrual debited (event 71), plus the part of earlier withdrawals that went to 50105 because the accrual was short, which the
     * certificate's own ledger does not show; those are read back from the audit rows this app wrote.
     */
    private BigDecimal withdrawnInterest(int branch, InterestRequest r) {
        BigDecimal extra = BigDecimal.ZERO;
        java.util.regex.Pattern expense = java.util.regex.Pattern.compile(" expense ([0-9]+(?:[.][0-9]+)?) ");
        for (String v : jdbc.queryForList("SELECT new_value FROM ecis_audit_log WHERE action = 'td.interest.encode' AND new_value LIKE ?", String.class, "client " + r.clientId() + " certificate " + r.typeCode() + "/" + r.refNo() + " %")) {
            java.util.regex.Matcher m = expense.matcher(v);
            if (m.find()) extra = extra.add(new BigDecimal(m.group(1)));
        }
        return extra.add(withdrawnAccrual(branch, r));
    }

    private BigDecimal withdrawnAccrual(int branch, InterestRequest r) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = ? AND SL_CLIENTID = ? AND SLC_CODE = ? AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = ? AND AMT > 0",
            BigDecimal.class, branch, r.clientId(), CLASS_TD, r.typeCode(), r.refNo(), ACCRUED);
    }

    @PostMapping("/interest/quote")
    InterestQuote interestQuote(@RequestBody InterestRequest req, Authentication auth) {
        access.requireNew(access.of(auth), ShareWithdrawalRules.CASH_VOUCHER);
        return interestBuild(poster.person(auth.getName()), req);
    }

    private InterestQuote interestBuild(Person p, InterestRequest r) {
        LocalDate today = LocalDate.now(clock);
        Request asClosure = new Request(r.clientId(), r.typeCode(), r.refNo(), "PAYOUT", null, null, null, null, null);
        Cert c = cert(p.branch(), asClosure);
        Client client = poster.client(p.branch(), r.clientId());
        BigDecimal balance = c.balance();
        String status = c.status() == 17 ? "CLOSED" : !c.hasLedger() ? "NO_LEDGER" : balance.compareTo(new BigDecimal("0.005")) < 0 ? "CLOSED" : c.maturity() != null && !c.maturity().isAfter(today) ? "MATURED" : "ACTIVE";
        BigDecimal withdrawn = withdrawnInterest(p.branch(), r);
        BigDecimal year = c.rate() == null ? BigDecimal.ZERO : TimeDepositClosureRules.yearInterest(balance, c.rate());
        BigDecimal available = c.rate() == null ? BigDecimal.ZERO : TimeDepositClosureRules.interestAvailable(balance, c.rate(), c.placed(), today, withdrawn);
        BigDecimal amount = r.amount() == null ? available.min(year) : r.amount();
        var amounts = TimeDepositClosureRules.interestAmounts(amount.signum() > 0 ? amount : BigDecimal.ZERO, c.accrued());
        List<String> problems = new ArrayList<>(TimeDepositClosureRules.interestProblems(r.typeCode(), status, c.maturity(), today, amount, available, payableAccount(p.branch(), r.clientId()) != null,
            voucherWaiting(p.branch(), today.getYear(), asClosure)));
        if (client.status() == 3 || client.status() == 98 || client.status() == 99) problems.add("This client's account is closed, disapproved or not yet approved.");
        String kycProblem = kyc.withdrawalProblem(p.branch(), r.clientId(), today);
        if (kycProblem != null) problems.add(kycProblem);
        return new InterestQuote(r.clientId(), client.name(), r.typeCode(), c.type(), r.refNo(), c.certificate(), balance, c.rate(), c.placed(), TimeDepositClosureRules.completedYears(c.placed(), today),
            year, withdrawn, available, c.accrued(), amount, amounts.fromAccrual(), amounts.expense(), problems, problems.isEmpty());
    }

    /** Encodes the voucher: the accrued interest and 50105 debited, the member's vouchers-payable account credited. The certificate itself is not touched. */
    @PostMapping("/interest")
    Encoded interestEncode(@RequestBody InterestRequest req, Authentication auth) {
        access.requireNew(access.of(auth), ShareWithdrawalRules.CASH_VOUCHER);
        Person p = poster.person(auth.getName());
        if (p.dept() <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Your account has no department set, so this cannot be encoded.");
        if (!db.canInsertInto("transsum", "transdt"))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Writing vouchers is not set up on this database.");
        return tx.execute((st) -> {
            InterestQuote q = interestBuild(p, req);
            if (!q.problems().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", q.problems()));
            int branch = p.branch();
            LocalDateTime now = LocalDateTime.now(clock).withNano(0);
            LocalDate day = now.toLocalDate();
            int year = day.getYear();
            long ctl = db.nextControlNumber(branch, MODULE, CODE, year);
            String docNo = SharePaymentRules.documentNo(null, ctl);
            String text = req.explanation() == null || req.explanation().isBlank() ? "LOCK IN INTEREST WITHDRAWAL" : req.explanation().trim();
            text = text.substring(0, Math.min(text.length(), 200));
            jdbc.update("""
                    INSERT INTO transsum (TR_BRCODE, TR_YEAR, TR_MODULE, TR_CODE, TR_CTLNO, TR_DOCNO, TR_DATE, TR_TIME, TR_CLIENTID, BATCH_NO, EXPLANATION, IS_FO, DATETIMEADDED)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                    """, branch, year, MODULE, CODE, ctl, docNo, day.toString(), now.format(DateTimeFormatter.ofPattern("HH:mm:ss")), req.clientId(), day.format(DateTimeFormatter.BASIC_ISO_DATE), text, Timestamp.valueOf(now));
            int[] seq = {0};
            if (q.fromAccrual().signum() > 0)
                jdbc.update(LINE, branch, year, MODULE, CODE, ctl, seq[0]++, p.dept(), poster.glAccount(branch, CLASS_TD, req.typeCode(), ACCRUED, 99), req.clientId(), CLASS_TD, req.typeCode(), req.refNo(),
                    ACCRUED, 99, day.toString(), q.fromAccrual(), p.id(), PENDING);
            if (q.expense().signum() > 0)
                jdbc.update(LINE, branch, year, MODULE, CODE, ctl, seq[0]++, p.dept(), TimeDepositClosureRules.INTEREST_EXPENSE, null, null, null, null, null, null, day.toString(), q.expense(), p.id(), PENDING);
            Integer payable = payableAccount(branch, req.clientId());
            jdbc.update(LINE, branch, year, MODULE, CODE, ctl, seq[0]++, p.dept(), poster.glAccount(branch, PAYABLE, 1, PRINCIPAL, ACTIVE), req.clientId(), PAYABLE, 1, payable, PRINCIPAL, ACTIVE, day.toString(),
                q.amount().negate(), p.id(), PENDING);
            AuditLog.write(jdbc, Timestamp.valueOf(now), auth.getName(), "td.interest.encode", "transsum", branch + "/" + year + "/" + MODULE + "/" + CODE + "/" + ctl, null, "client " + req.clientId() + " certificate " + req.typeCode() + "/" + req.refNo() + " interest " + q.amount().toPlainString()
                    + " from accrual " + q.fromAccrual().toPlainString() + " expense " + q.expense().toPlainString() + " doc " + docNo, null);
            return new Encoded(branch, year, ctl, docNo, "INTEREST", req.clientId(), q.clientName(), q.amount(), null, null, q.amount());
        });
    }

    private static String pretermText(Quote q) {
        return q.lockIn() ? "PRE-TERMINATION OF LOCK IN, " + q.daysHeld() + " DAYS, " + q.terminationFee().toPlainString() + " TERMINATION FEE"
            : "PRE-TERMINATION OF TD @ " + q.pretermRate().stripTrailingZeros().toPlainString() + "% INT, " + q.daysHeld() + " DAYS";
    }

    @PostMapping
    Encoded encode(@RequestBody Request req, Authentication auth) {
        Perms perms = access.of(auth);
        access.requireNew(perms, ShareWithdrawalRules.CASH_VOUCHER);
        Person p = poster.person(auth.getName());
        if (p.dept() <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Your account has no department set, so this cannot be encoded.");
        if (!db.canInsertInto("transsum", "transdt", "td", "slmastersum"))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Renewing time deposits is not set up yet. An administrator has to run database/migrations/V027_time_deposit_placement.sql.");
        return tx.execute((st) -> {
            Quote q = build(p, req);
            if (!q.problems().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", q.problems()));
            boolean renew = "RENEW".equals(q.mode());
            int branch = p.branch();
            LocalDateTime now = LocalDateTime.now(clock).withNano(0);
            LocalDate day = now.toLocalDate();
            int year = day.getYear();
            long ctl = db.nextControlNumber(branch, MODULE, CODE, year);
            String docNo = SharePaymentRules.documentNo(null, ctl);
            String text = req.explanation() == null || req.explanation().isBlank()
                ? (q.preterm() ? pretermText(q) : renew ? "RENEWAL OF MATURED " + (q.lockIn() ? "LOCK IN" : "TD") + " " + q.termDays() + " DAYS @ " + q.ratePercent().stripTrailingZeros().toPlainString() + "%" + (q.withdraw().signum() > 0 ? "; " + q.withdraw().toPlainString() + " WITHDRAWAL" : "") : (q.lockIn() ? "MATURED LOCK IN WITHDRAWAL, " + q.interestYears() + " YEARS INTEREST" : "MATURED TD WITHDRAWAL"))
                : req.explanation().trim();
            text = text.substring(0, Math.min(text.length(), 200));
            jdbc.update("""
                    INSERT INTO transsum (TR_BRCODE, TR_YEAR, TR_MODULE, TR_CODE, TR_CTLNO, TR_DOCNO, TR_DATE, TR_TIME, TR_CLIENTID, BATCH_NO, EXPLANATION, IS_FO, DATETIMEADDED)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                    """, branch, year, MODULE, CODE, ctl, docNo, day.toString(), now.format(DateTimeFormatter.ofPattern("HH:mm:ss")), req.clientId(), day.format(DateTimeFormatter.BASIC_ISO_DATE), text, Timestamp.valueOf(now));

            int[] seq = {0};
            // a ledger line (class, type, ref, event, status) or, with class 0, a plain general ledger line
            java.util.function.BiConsumer<Object[], BigDecimal> line = (spec, amount) -> {
                boolean plain = (int) spec[0] == 0;
                jdbc.update(LINE, branch, year, MODULE, CODE, ctl, seq[0]++, p.dept(), spec[4], plain ? null : req.clientId(), plain ? null : spec[0], plain ? null : spec[1], plain ? null : spec[2],
                    plain ? null : spec[3], plain ? null : spec[5], day.toString(), amount, p.id(), PENDING);
            };
            String tdGl = poster.glAccount(branch, CLASS_TD, req.typeCode(), PRINCIPAL, ACTIVE);
            int nt = q.newTypeCode();
            line.accept(new Object[] {CLASS_TD, req.typeCode(), req.refNo(), PRINCIPAL, tdGl, ACTIVE}, q.principal());
            if (q.accrued().signum() > 0) line.accept(new Object[] {CLASS_TD, req.typeCode(), req.refNo(), ACCRUED, poster.glAccount(branch, CLASS_TD, req.typeCode(), ACCRUED, 99), 99}, q.accrued());
            if (q.expense().signum() != 0) line.accept(new Object[] {0, 0, 0, 0, TimeDepositClosureRules.INTEREST_EXPENSE, 0}, q.expense());
            if (q.terminationFee().signum() > 0) line.accept(new Object[] {0, 0, 0, 0, TimeDepositClosureRules.TERMINATION_FEE_ACCOUNT, 0}, q.terminationFee().negate());
            Integer payable = payableAccount(branch, req.clientId());
            String payableGl = poster.glAccount(branch, PAYABLE, 1, PRINCIPAL, ACTIVE);
            Integer newRef = null;
            BigDecimal toPayable;
            if (renew) {
                List<Integer> top = jdbc.queryForList("SELECT TDREF_NO FROM td WHERE TDBR_CODE = ? AND TDSLC_CODE = ? AND TDSLT_CODE = ? ORDER BY TDREF_NO DESC LIMIT 1 FOR UPDATE", Integer.class, branch, CLASS_TD, nt);
                newRef = (top.isEmpty() ? 0 : top.get(0)) + 1;
                jdbc.update("INSERT INTO slmastersum (MBR_CODE, MSLC_CODE, MSLT_CODE, MREF_NO, MCLIENTID) VALUES (?, ?, ?, ?, ?)", branch, CLASS_TD, nt, newRef, req.clientId());
                jdbc.update("""
                        INSERT INTO td (TDBR_CODE, TDSLC_CODE, TDSLT_CODE, TDREF_NO, ClientIDTD, TDPAMT, TDTR_DATE, TDSTATUS, TDINT_RATE, TDTERMS, TDTERM_PERD, TDMAT_DATE, TDCERT_NUM)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                        """, branch, CLASS_TD, nt, newRef, req.clientId(), q.newPrincipal(), day.toString(), ACTIVE, q.ratePercent(), q.termDays(), q.newMaturity().toString(), String.valueOf(newRef));
                line.accept(new Object[] {CLASS_TD, nt, newRef, PRINCIPAL, poster.glAccount(branch, CLASS_TD, nt, PRINCIPAL, ACTIVE), ACTIVE}, q.newPrincipal().negate());
                if (q.fee().signum() > 0) line.accept(new Object[] {0, 0, 0, 0, TimeDepositPlacementRules.FEE_ACCOUNT, 0}, q.fee().negate());
                toPayable = q.withdraw();
                if (toPayable.signum() > 0) line.accept(new Object[] {PAYABLE, 1, payable, PRINCIPAL, payableGl, ACTIVE}, toPayable.negate());
            } else {
                toPayable = q.due();
                line.accept(new Object[] {PAYABLE, 1, payable, PRINCIPAL, payableGl, ACTIVE}, toPayable.negate());
            }
            AuditLog.write(jdbc, Timestamp.valueOf(now), auth.getName(), q.preterm() ? "td.pretermination.encode" : renew ? "td.renewal.encode" : "td.payout.encode", "transsum", branch + "/" + year + "/" + MODULE + "/" + CODE + "/" + ctl, null, "client " + req.clientId() + " certificate " + req.typeCode() + "/" + req.refNo() + " principal " + q.principal().toPlainString() + " interest " + q.interest().toPlainString()
                    + (q.lockIn() ? " lock in years " + q.interestYears() : "") + (renew ? " new certificate " + nt + "/" + newRef + " " + q.newPrincipal().toPlainString() : "") + " doc " + docNo, null);
            return new Encoded(branch, year, ctl, docNo, q.mode(), req.clientId(), q.clientName(), q.due(), newRef, q.newPrincipal(), toPayable);
        });
    }
}
