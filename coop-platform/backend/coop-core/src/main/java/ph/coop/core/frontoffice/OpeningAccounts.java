package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.frontoffice.OpeningBalanceRules.Line;
import ph.coop.core.frontoffice.OpeningBalanceRules.Terms;
import ph.coop.core.loan.LoanSchedule;

/**
 * The loans and time deposits an opening balance batch opens (see {@link OpeningBalanceRules}): checking a line against the database while the batch is
 * prepared, and opening the account when it is posted, in the posting's transaction. A loan gets its `loan` row with the loan type's settings, its remaining
 * schedule in `amortsched` and its `slmastersum` row; a time deposit its `td` row (placement date, rate, term, maturity; its certificate number is its account number) and `slmastersum`
 * row. The money itself is written by the batch's General Journal through the ledger, on the account opened here. Loan and certificate numbers continue the
 * type's own numbering.
 */
@Component
class OpeningAccounts {

    /** What a loan or time deposit line was found to be: the member, the type, the control account(s) it posts to and the status it opens with. */
    record Found(String memberName, String typeName, int status, String control, String accrualControl) {}

    private final JdbcTemplate jdbc;
    private final MemberLedgerPoster poster;

    OpeningAccounts(JdbcTemplate jdbc, MemberLedgerPoster poster) {
        this.jdbc = jdbc;
        this.poster = poster;
    }

    /**
     * Checks a loan or time deposit line: the member is in the branch and not closed, the type exists and is switched on, it has a control account for the status,
     * a loan's schedule can be built from the type's settings. Problems are added to {@code out}; null when the member
     * or the type cannot be found at all.
     */
    Found check(int branch, Line l, LocalDate opening, String at, List<String> out) {
        String what = OpeningBalanceRules.LOAN.equals(l.kind()) ? "loan" : "time deposit";
        MemberLedgerPoster.Client client;
        try {
            client = poster.client(branch, l.clientId());
        } catch (ResponseStatusException e) {
            out.add(at + "member " + l.clientId() + " is not in this branch. Check the member number, or register the member first.");
            return null;
        }
        if (client.status() == 17) out.add(at + client.name() + " (member " + l.clientId() + ") is closed, so no " + what + " can be opened. Reactivate the member first, or take the line out.");
        int slc = OpeningBalanceRules.CLASS.get(l.kind());
        Map<String, Object> type = type(branch, slc, l.typeCode());
        if (type == null) {
            out.add(at + "there is no " + what + " type " + l.typeCode() + ". Check the type number in Settings > Products" + (slc == 12 ? " > Loan products" : "") + ".");
            return null;
        }
        String typeName = text(type.get("SLTypeM_DESC"), what);
        if (num(type.get("SLTypeIsDisabled")) != 0) out.add(at + typeName + " (type " + l.typeCode() + ") is switched off. Switch it on in Settings > Products, or use another type.");
        Terms t = l.terms();
        int status = slc == 12 ? (t != null && t.status() != null ? t.status() : OpeningBalanceRules.LOAN_CURRENT) : OpeningBalanceRules.TD_ACTIVE;
        String control = control(branch, slc, l.typeCode(), 11, status, at, out);
        String accrual = null;
        if (slc == 12) {
            BigDecimal owed = OpeningBalanceRules.amount(l.debit());
            if (t != null && t.terms() != null && t.terms() > 0 && t.start() != null && owed.signum() > 0) {
                try {
                    OpeningBalanceRules.loanSchedule(owed, t, rate(t, type), integer(type.get("SLTypeAMORTYPE")), integer(type.get("SLTypePPMT_MODE")), num(type.get("SLTypeND_FLAG")) == 1);
                } catch (IllegalArgumentException e) {
                    out.add(at + typeName + ": " + e.getMessage());
                }
            }
        } else {
            if (l.accrued().signum() > 0) accrual = control(branch, slc, l.typeCode(), OpeningBalanceRules.ACCRUED_EVENT, OpeningBalanceRules.ACCRUED_STATUS, at, out);
        }
        return new Found(client.name(), typeName, status, control, accrual);
    }

    /** What a posted line opened, for showing it: the member, the type and the control accounts. Nothing is checked. */
    Found describe(int branch, Line l) {
        int slc = OpeningBalanceRules.CLASS.get(l.kind());
        String name = null;
        try {
            name = poster.client(branch, l.clientId()).name();
        } catch (ResponseStatusException e) {
            // shown without it
        }
        Map<String, Object> type = type(branch, slc, l.typeCode());
        Terms t = l.terms();
        int status = slc == 12 ? (t != null && t.status() != null ? t.status() : OpeningBalanceRules.LOAN_CURRENT) : OpeningBalanceRules.TD_ACTIVE;
        return new Found(name, type == null ? null : text(type.get("SLTypeM_DESC"), null), status, quietControl(branch, slc, l.typeCode(), 11, status),
            l.accrued().signum() > 0 ? quietControl(branch, slc, l.typeCode(), OpeningBalanceRules.ACCRUED_EVENT, OpeningBalanceRules.ACCRUED_STATUS) : null);
    }

    /**
     * Opens the loan or certificate a line stands for and returns its number. Called while posting, after every check passed, in the posting's transaction: the
     * last number of the type is locked so two postings cannot take the same one.
     */
    int open(int branch, Line l, LocalDate opening, String reference) {
        int slc = OpeningBalanceRules.CLASS.get(l.kind());
        Map<String, Object> d = type(branch, slc, l.typeCode());
        if (d == null) throw new IllegalStateException("type " + slc + "/" + l.typeCode() + " vanished while posting");
        Terms t = l.terms();
        int ref;
        if (slc == 12) {
            List<Integer> top = jdbc.queryForList("SELECT LoanREF_NO FROM loan WHERE LoanBR_CODE = ? AND LoanSLC_CODE = 12 AND LoanSLT_CODE = ? ORDER BY LoanREF_NO DESC LIMIT 1 FOR UPDATE",
                Integer.class, branch, l.typeCode());
            ref = (top.isEmpty() ? 0 : top.get(0)) + 1;
            openLoan(branch, l, d, t, ref, reference);
        } else {
            List<Integer> top = jdbc.queryForList("SELECT TDREF_NO FROM td WHERE TDBR_CODE = ? AND TDSLC_CODE = 24 AND TDSLT_CODE = ? ORDER BY TDREF_NO DESC LIMIT 1 FOR UPDATE",
                Integer.class, branch, l.typeCode());
            ref = (top.isEmpty() ? 0 : top.get(0)) + 1;
            jdbc.update("INSERT INTO slmastersum (MBR_CODE, MSLC_CODE, MSLT_CODE, MREF_NO, MCLIENTID) VALUES (?, ?, ?, ?, ?)", branch, 24, l.typeCode(), ref, l.clientId());
            // the certificate number is the account number (the database's td trigger sets it so, as the old system did)
            String cert = String.valueOf(ref);
            jdbc.update("""
                    INSERT INTO td (TDBR_CODE, TDSLC_CODE, TDSLT_CODE, TDREF_NO, ClientIDTD, TDPAMT, TDTR_DATE, TDSTATUS, TDINT_RATE, TDTERMS, TDTERM_PERD, TDMAT_DATE, TDCERT_NUM)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                    """, branch, 24, l.typeCode(), ref, l.clientId(), OpeningBalanceRules.amount(l.credit()), t.start().toString(), OpeningBalanceRules.TD_ACTIVE, t.rate(), t.terms(),
                OpeningBalanceRules.maturity(t).toString(), cert);
        }
        return ref;
    }

    private void openLoan(int branch, Line l, Map<String, Object> d, Terms t, int ref, String reference) {
        BigDecimal owed = OpeningBalanceRules.amount(l.debit());
        BigDecimal rate = rate(t, d);
        List<LoanSchedule.Row> rows = OpeningBalanceRules.loanSchedule(owed, t, rate, integer(d.get("SLTypeAMORTYPE")), integer(d.get("SLTypePPMT_MODE")), num(d.get("SLTypeND_FLAG")) == 1);
        LocalDate from = rows.get(0).dueDate(), firstDue = rows.get(1).dueDate(), maturity = rows.get(rows.size() - 1).dueDate();
        int status = t.status() == null ? OpeningBalanceRules.LOAN_CURRENT : t.status();
        String remarks = "Opening balance " + reference + (t.originalAmount() == null ? "" : ", granted " + t.originalAmount().setScale(2, RoundingMode.HALF_UP).toPlainString())
            + (t.grantedOn() == null ? "" : " on " + t.grantedOn());
        jdbc.update("INSERT INTO slmastersum (MBR_CODE, MSLC_CODE, MSLT_CODE, MREF_NO, MCLIENTID) VALUES (?, ?, ?, ?, ?)", branch, 12, l.typeCode(), ref, l.clientId());
        // the same columns a loan application fills in (LoanApplicationController), from the loan type's settings; the principal is what is still owed, over the remaining term
        jdbc.update("""
                INSERT INTO loan (LoanBR_CODE, LoanSLC_CODE, LoanSLT_CODE, LoanREF_NO, ClientIDLoan, LoanLOANCLASS, LoanPURPOSE, LoanPAMT, LoanSTATUS, LoanTR_DATE, LoanTERMS, LoanTERM_PERD,
                  LoanPPMT_MODE, LoanPPMT_MFAC, LoanPPMT_MOD2, LoanINT_RATE, LoanAMORTYPE, LoanDIMB_FREQ, LoanIPMT_MODE, LoanIPMT_MFAC, LoanIPMT_MOD2, LoanPEN_RATE, LoanPEN_RATE_AM,
                  LoanPEN_MODE, LoanPEN_MFAC, LoanPEN_MOD2, LoanDD_FLAG, LoanINT_GP, LoanPRIN_GP, LoanFID_DATE, LoanFPD_DATE, LoanMAT_DATE, LoanND_FLAG, LoanMCLASS, LoanRESTRUCT,
                  LoanCOLLTYPE, LoanCOLLAMT, LoanCOLLDESC, LoanRemarks, LoanCI, LoanXXREF_NO, LoanPENGP, LoanPREPAIDPYMTS, LoanISDISTRIBUTEUNPAIDINT, LoanIsExcludeSundays,
                  LoanIsExcludeHolidays, LoanCOLLECTTYPE, LoanFunder, LoanForceGenAmort, LoanIsFringeBenefit, LoanINSAMT, LoanAmortLock, LoanForceBillingAmort, LoanXSLT_CODE, LoanXREF_NO)
                VALUES (?, 12, ?, ?, ?, 1, NULL, ?, ?, ?, ?, 4, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, NULL, NULL, NULL, ?, 'NA', '0000', ?, ?, 0, 0, 0, ?, 1, 0, 0, 0, 0, 0, NULL, NULL)
                """, branch, l.typeCode(), ref, l.clientId(), owed, status, from, t.terms(),
            d.get("SLTypePPMT_MODE"), d.get("SLTypePPMT_MFAC"), d.get("SLTypePPMT_MOD2"), rate, d.get("SLTypeAMORTYPE"), d.get("SLTypeDIMB_FREQ"), d.get("SLTypeIPMT_MODE"), d.get("SLTypeIPMT_MFAC"),
            d.get("SLTypeIPMT_MOD2"), d.get("SLTypePEN_RATE"), d.get("SLTypePEN_RATE_AM"), d.get("SLTypePEN_MODE"), d.get("SLTypePEN_MFAC"), d.get("SLTypePEN_MOD2"), d.get("SLTypeDD_FLAG"),
            d.get("SLTypeINT_GP"), d.get("SLTypePRIN_GP"), firstDue, firstDue, maturity, d.get("SLTypeND_FLAG"), status == 14 ? 1 : 0, remarks.substring(0, Math.min(remarks.length(), 200)),
            d.get("SLTypePENGP"), d.get("SLTypePREPAIDPYMTS"), d.get("SLTypeCOLLECTTYPE"));
        boolean first = true;
        for (LoanSchedule.Row r : rows) {
            jdbc.update("""
                    INSERT INTO amortsched (BR_CODE, CLIENTID, SLC_CODE, SLT_CODE, REF_NO, DUEDATE, PRINDUE, INTDUE, PRINBAL, PENFLAG, DBFLAG, CBUAMT, SAVAMT, COLLFEE, PRINBAL2, SRVFEE,
                      PRINDUE_PP, INTDUE_PP, PRINBAL_PP, INSDUE) VALUES (?, ?, 12, ?, ?, ?, ?, ?, ?, ?, NULL, 0, 0, 0, ?, 0, ?, ?, ?, 0)
                    """, branch, l.clientId(), l.typeCode(), ref, r.dueDate(), r.principalDue(), r.interestDue(), r.principalBalance(), first ? null : "Y", r.principalBalance(),
                r.principalDue(), r.interestDue(), r.principalBalance());
            first = false;
        }
    }

    private Map<String, Object> type(int branch, int slc, Integer code) {
        if (code == null) return null;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ?", branch, slc, code);
        if (rows.isEmpty()) return null;
        // column names come back in the database's own case (PostgreSQL lower case): look them up without regard to case
        Map<String, Object> out = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        out.putAll(rows.get(0));
        return out;
    }

    private String control(int branch, int slc, int type, int event, int status, String at, List<String> out) {
        try {
            return poster.glAccount(branch, slc, type, event, status);
        } catch (ResponseStatusException e) {
            String which = event == OpeningBalanceRules.ACCRUED_EVENT ? "its accrued interest" : slc == 12 ? "a " + OpeningBalanceRules.LOAN_STATUS.getOrDefault(status, "") + " loan" : "an active certificate";
            out.add(at + "account type " + slc + "/" + type + " has no chart account for " + which + ". An administrator links it in Settings > Products (the account type's ledger links), then check again.");
            return null;
        }
    }

    private String quietControl(int branch, int slc, int type, int event, int status) {
        try {
            return poster.glAccount(branch, slc, type, event, status);
        } catch (ResponseStatusException e) {
            return null;
        }
    }

    private static BigDecimal rate(Terms t, Map<String, Object> type) {
        if (t.rate() != null) return t.rate();
        Object r = type.get("SLTypeINT_RATE");
        return r == null ? BigDecimal.ZERO : new BigDecimal(r.toString());
    }

    private static int num(Object v) {
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static Integer integer(Object v) {
        return v == null ? null : ((Number) v).intValue();
    }

    private static String text(Object v, String fallback) {
        return v == null || v.toString().isBlank() ? fallback : v.toString().trim();
    }
}
