package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;
import ph.coop.core.deposit.TimeDepositRules;
import ph.coop.core.frontoffice.FrontOfficeAccess.Perms;
import ph.coop.core.frontoffice.MemberLedgerPoster.Client;
import ph.coop.core.frontoffice.MemberLedgerPoster.Direction;
import ph.coop.core.frontoffice.MemberLedgerPoster.MemberLine;
import ph.coop.core.frontoffice.MemberLedgerPoster.Person;
import ph.coop.core.frontoffice.MemberLedgerPoster.Posted;
import ph.coop.core.frontoffice.TimeDepositPlacementRules.TermRates;

/**
 * Places a new time deposit certificate with cash, cheques or both, as the old window did (a Cash Receipt, module 3 type 10; mapped in
 * documentation/time-deposit-map.md): the cash line is the amount plus the certificate fee, the certificate account is credited as ledger event 11 and the
 * fee goes to 442202; the certificate itself is a new row of {@code td} (and of {@code slmastersum}, which the ledger needs first), numbered one above the
 * highest of its type. The rate is typed; one outside what has been used for that term in the last year needs a supervisor (override kind TD_RATE).
 */
@RestController
@RequestMapping("/api/front-office/time-deposits")
class TimeDepositPlacementController {

    static final String RATE_KIND = "TD_RATE";
    private static final int CLASS_TD = 24;
    private static final int ACTIVE = 15;

    record TypeSetup(int typeCode, String name, BigDecimal minAmount, List<TermRates> terms) {}

    record Setup(List<TypeSetup> types, BigDecimal certificateFee) {}

    /** certificate: whether the certificate fee is charged. cheques is the part of the payment (amount plus fee) paid by cheque. */
    record Request(int clientId, int typeCode, int termDays, BigDecimal ratePercent, BigDecimal amount, boolean certificate, String explanation, String receiptNo,
                   boolean confirmDuplicate, List<ChequeRules.Input> cheques) {}

    record Check(List<String> problems, LocalDate maturity, BigDecimal estimatedInterest, BigDecimal fee, BigDecimal total, String rateDescription, String rateApproval, boolean ready) {}

    record Receipt(int branchCode, int year, long controlNo, String documentNo, String date, int clientId, String clientName, String type, int certificate, LocalDate maturity,
                   int termDays, BigDecimal ratePercent, BigDecimal amount, BigDecimal fee, BigDecimal total, String cashier) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final Clock clock;
    private final FrontOfficeAccess access;
    private final MemberLedgerPoster poster;
    private final OverrideController overrides;
    private final TransactionTemplate tx;

    TimeDepositPlacementController(JdbcTemplate jdbc, Db db, Clock clock, FrontOfficeAccess access, MemberLedgerPoster poster, OverrideController overrides, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
        this.access = access;
        this.poster = poster;
        this.overrides = overrides;
        this.tx = new TransactionTemplate(txm);
    }

    // ---- what can be placed -------------------------------------------------------------------------------------------------

    /** The terms used for a type in the year up to the latest placement, with the rates used for each (a rate counts when at least three certificates have it). */
    private Map<Integer, TermRates> termsOf(int branch, int typeCode) {
        // one old certificate is dated in 2027, so only dates up to today count as the latest placement
        java.sql.Date last = jdbc.queryForObject("SELECT MAX(TDTR_DATE) FROM td WHERE TDBR_CODE = ? AND TDTR_DATE <= ?", java.sql.Date.class, branch, java.sql.Date.valueOf(LocalDate.now(clock)));
        LocalDate latest = last == null ? null : last.toLocalDate();
        LocalDate from = (latest == null ? LocalDate.now(clock) : latest).minusDays(365);
        Map<Integer, List<Object[]>> byTerm = new LinkedHashMap<>();
        jdbc.query("""
                SELECT TDTERMS, TDINT_RATE, COUNT(*) FROM td WHERE TDBR_CODE = ? AND TDSLC_CODE = ? AND TDSLT_CODE = ? AND TDTR_DATE >= ? AND TDTR_DATE <= ? AND TDINT_RATE > 0
                GROUP BY TDTERMS, TDINT_RATE HAVING COUNT(*) >= 3 ORDER BY TDTERMS, TDINT_RATE
                """, (rs) -> { byTerm.computeIfAbsent(rs.getInt(1), (k) -> new ArrayList<>()).add(new Object[] {rs.getBigDecimal(2), rs.getLong(3)}); },
            branch, CLASS_TD, typeCode, java.sql.Date.valueOf(from), java.sql.Date.valueOf(latest == null ? LocalDate.now(clock) : latest));
        Map<Integer, TermRates> out = new LinkedHashMap<>();
        byTerm.forEach((days, rates) -> {
            BigDecimal min = rates.get(0)[0] == null ? BigDecimal.ZERO : (BigDecimal) rates.get(0)[0], max = (BigDecimal) rates.get(rates.size() - 1)[0], usual = min;
            long most = -1;
            for (Object[] r : rates) if ((Long) r[1] > most) { most = (Long) r[1]; usual = (BigDecimal) r[0]; }
            out.put(days, new TermRates(days, min, max, usual));
        });
        return out;
    }

    @GetMapping("/setup")
    Setup setup(Authentication auth) {
        Perms perms = access.of(auth);
        List<TypeSetup> types = jdbc.query("""
                SELECT SLTypeSLT_CODE, SLTypeM_DESC, COALESCE(SLTypeMIN_BAL, 0) FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND COALESCE(SLTypeIsDisabled, 0) = 0
                ORDER BY SLTypeSLT_CODE
                """, (rs, i) -> new TypeSetup(rs.getInt(1), rs.getString(2), rs.getBigDecimal(3), new ArrayList<>(termsOf(perms.branch(), rs.getInt(1)).values())), perms.branch(), CLASS_TD);
        return new Setup(types.stream().filter((t) -> !t.terms().isEmpty()).toList(), TimeDepositPlacementRules.CERTIFICATE_FEE);
    }

    // ---- checking and asking ------------------------------------------------------------------------------------------------

    private record Ctx(Perms perms, Person p, Client client, BigDecimal minAmount, boolean typeEnabled, Map<Integer, TermRates> terms) {}

    private Ctx context(Request req, Authentication auth) {
        Perms perms = access.of(auth);
        access.requireNew(perms, SharePaymentRules.CASH_RECEIPT);
        Person p = poster.person(auth.getName());
        Client c = poster.client(p.branch(), req.clientId());
        List<BigDecimal> min = jdbc.query("SELECT COALESCE(SLTypeMIN_BAL, 0) FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ? AND COALESCE(SLTypeIsDisabled, 0) = 0",
            (rs, i) -> rs.getBigDecimal(1), p.branch(), CLASS_TD, req.typeCode());
        return new Ctx(perms, p, c, min.isEmpty() ? null : min.get(0), !min.isEmpty(), termsOf(p.branch(), req.typeCode()));
    }

    private static String rateDescription(Request req) {
        return "TD RATE " + req.typeCode() + " " + req.termDays() + "d " + (req.ratePercent() == null ? "" : req.ratePercent().stripTrailingZeros().toPlainString()) + "%";
    }

    private String approvalStatus(Person p, Request req) {
        if (!db.tableExists("ecis_fo_override") || !db.columnExists("ecis_fo_override", "used_ref")) return "NONE";
        List<String> latest = jdbc.queryForList("""
                SELECT status FROM ecis_fo_override WHERE branch_code = ? AND kind = ? AND requested_by = ? AND amount = ? AND description = ? AND used_ref IS NULL ORDER BY id DESC LIMIT 1
                """, String.class, p.branch(), RATE_KIND, p.id(), req.ratePercent().setScale(2, RoundingMode.HALF_UP), rateDescription(req));
        return latest.isEmpty() ? "NONE" : latest.get(0);
    }

    private Check evaluate(Request req, Ctx x) {
        BigDecimal amount = req.amount() == null ? null : req.amount().setScale(Math.max(2, req.amount().scale()), RoundingMode.UNNECESSARY);
        List<String> problems = new ArrayList<>(TimeDepositPlacementRules.check(amount, x.minAmount(), req.termDays(), req.ratePercent(), x.terms(), x.client().status(), x.typeEnabled()));
        BigDecimal fee = req.certificate() ? TimeDepositPlacementRules.CERTIFICATE_FEE : BigDecimal.ZERO;
        BigDecimal total = amount == null ? fee : amount.add(fee);
        TermRates known = x.terms().get(req.termDays());
        boolean needs = problems.isEmpty() && TimeDepositPlacementRules.rateNeedsApproval(req.ratePercent(), known);
        String approval = needs ? approvalStatus(x.p(), req) : "NOT_NEEDED";
        LocalDate maturity = req.termDays() > 0 ? TimeDepositPlacementRules.maturity(LocalDate.now(clock), req.termDays()) : null;
        BigDecimal estimate = problems.isEmpty() ? TimeDepositRules.termInterest(amount, req.ratePercent(), req.termDays()) : null;
        boolean ready = problems.isEmpty() && (!needs || "APPROVED".equals(approval));
        return new Check(problems, maturity, estimate, fee, total, needs ? rateDescription(req) : null, approval, ready);
    }

    @PostMapping("/check")
    Check check(@RequestBody Request req, Authentication auth) {
        return evaluate(req, context(req, auth));
    }

    /** Ask a supervisor to allow a rate outside what has been used for the term. */
    @PostMapping("/ask")
    OverrideController.Override ask(@RequestBody Request req, Authentication auth) {
        Ctx x = context(req, auth);
        Check c = evaluate(req, x);
        if (!c.problems().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", c.problems()));
        if (c.rateDescription() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This rate is one that has been used, so no approval is needed.");
        return overrides.ask(new OverrideController.Request(null, RATE_KIND, c.rateDescription(), req.ratePercent().setScale(2, RoundingMode.HALF_UP), null), auth);
    }

    // ---- posting ------------------------------------------------------------------------------------------------------------

    @PostMapping
    Receipt place(@RequestBody Request req, Authentication auth) {
        if (!db.canInsertInto("td", "slmastersum", "sldtl", "transsum", "transdt", "gltrans", "slsum"))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Placing time deposits is not set up yet. An administrator has to run database/migrations/V027_time_deposit_placement.sql.");
        Ctx x = context(req, auth);
        Person p = x.p();
        return tx.execute((st) -> {
            Check c = evaluate(req, x);
            if (!c.problems().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", c.problems()));
            long[] spent = null;
            if (c.rateDescription() != null) {
                List<long[]> found = jdbc.query("""
                        SELECT id, decided_by FROM ecis_fo_override WHERE branch_code = ? AND kind = ? AND requested_by = ? AND amount = ? AND description = ?
                          AND status = 'APPROVED' AND used_ref IS NULL ORDER BY id LIMIT 1 FOR UPDATE
                        """, (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}, p.branch(), RATE_KIND, p.id(), req.ratePercent().setScale(2, RoundingMode.HALF_UP), c.rateDescription());
                if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "A supervisor has to allow this rate first: " + c.rateDescription() + ".");
                spent = found.get(0);
            }
            BigDecimal amount = req.amount().setScale(2, RoundingMode.HALF_UP);
            BigDecimal fee = c.fee();
            List<ChequeRules.Cheque> cheques = poster.cheques(req.cheques(), req.confirmDuplicate());
            LocalDate day = LocalDate.now(clock);

            // the next certificate number of this type; the lock keeps two cashiers from taking the same one
            List<Integer> top = jdbc.queryForList("SELECT TDREF_NO FROM td WHERE TDBR_CODE = ? AND TDSLC_CODE = ? AND TDSLT_CODE = ? ORDER BY TDREF_NO DESC LIMIT 1 FOR UPDATE",
                Integer.class, p.branch(), CLASS_TD, req.typeCode());
            int ref = (top.isEmpty() ? 0 : top.get(0)) + 1;
            jdbc.update("INSERT INTO slmastersum (MBR_CODE, MSLC_CODE, MSLT_CODE, MREF_NO, MCLIENTID) VALUES (?, ?, ?, ?, ?)", p.branch(), CLASS_TD, req.typeCode(), ref, req.clientId());
            jdbc.update("""
                    INSERT INTO td (TDBR_CODE, TDSLC_CODE, TDSLT_CODE, TDREF_NO, ClientIDTD, TDPAMT, TDTR_DATE, TDSTATUS, TDINT_RATE, TDTERMS, TDTERM_PERD, TDMAT_DATE, TDCERT_NUM)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                    """, p.branch(), CLASS_TD, req.typeCode(), ref, req.clientId(), amount, day.toString(), ACTIVE, req.ratePercent(), req.termDays(), c.maturity().toString(), String.valueOf(ref));

            String account = poster.glAccount(p.branch(), CLASS_TD, req.typeCode(), 11, ACTIVE);
            List<MemberLine> lines = new ArrayList<>();
            lines.add(new MemberLine(CLASS_TD, req.typeCode(), ref, 11, ACTIVE, account, amount.negate()));
            if (fee.signum() > 0) lines.add(new MemberLine(0, 0, 0, 0, 0, TimeDepositPlacementRules.FEE_ACCOUNT, fee.negate()));
            String explanation = req.explanation() == null || req.explanation().isBlank() ? "NEW TD " + req.termDays() + " DAYS" : req.explanation();
            Posted r = poster.postVoucher(Direction.IN, p, req.clientId(), explanation, req.receiptNo(), req.confirmDuplicate(), lines, "td.placement.post", auth.getName(),
                "certificate " + req.typeCode() + "/" + ref + " amount " + amount.toPlainString() + " fee " + fee.toPlainString() + " term " + req.termDays() + " days rate " + req.ratePercent().stripTrailingZeros().toPlainString()
                    + (cheques.isEmpty() ? "" : " cheques " + MemberLedgerPoster.describe(cheques)), cheques);
            if (spent != null) {
                String used = r.branch() + "/" + r.year() + "/3/10/" + r.controlNo();
                jdbc.update("UPDATE ecis_fo_override SET used_ref = ? WHERE id = ?", used, spent[0]);
                jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value) VALUES (NOW(), ?, 'td.rate.override.used', 'fo_override', ?, ?)",
                    auth.getName(), String.valueOf(spent[0]), "allowed by user " + spent[1] + " for " + used + " " + c.rateDescription());
            }
            String type = jdbc.queryForList("SELECT SLTypeM_DESC FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ?", String.class, p.branch(), CLASS_TD, req.typeCode())
                .stream().findFirst().orElse("Time deposit");
            return new Receipt(r.branch(), r.year(), r.controlNo(), r.documentNo(), r.date().toString(), req.clientId(), x.client().name(), type, ref, c.maturity(), req.termDays(),
                req.ratePercent(), amount, fee, amount.add(fee), p.name());
        });
    }
}
