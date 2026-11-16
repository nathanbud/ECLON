package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks and figures for cheques taken at the window, from the old Cash Receipt (documentation/cheque-receipt-posting-map.md). No database
 * access here. A cheque's number is 15 digits (shorter numbers are padded with zeros on the left), its date is at most 179 days old
 * (the oldest in the real data) and at most 30 days ahead (the real data goes to 9; the limit is this program's own), and it has a clearing
 * kind (chktype: Local 3 days, Regional 7, Out of Town 15, Foreign 45, On Us and Good Check 0).
 */
public final class ChequeRules {

    private ChequeRules() {}

    static final int MAX_AGE_DAYS = 180;
    static final int MAX_AHEAD_DAYS = 30;
    static final int NUMBER_LENGTH = 15;

    /** A cheque as typed at the window. */
    record Input(String number, Integer bankCode, LocalDate date, Integer kind, BigDecimal amount) {}

    /** A checked cheque, ready to post: the 15-digit number and the clearing days of its kind. */
    record Cheque(String number, int bankCode, LocalDate date, int kind, BigDecimal amount, int clearingDays) {}

    /** The 15-digit form of a typed number, or null when it is not 1 to 15 digits. */
    /**
     * SQL for the money in a savings account that came by cheque and has not cleared: the account's credit rows paid by cheque whose
     * receipt cheque is still marked not cleared in transck. The old system's own rule for withholding it is not known, so the app
     * treats it like a hold: it counts in the balance but not in the available amount. {@code s} is the sd table alias.
     */
    public static String unclearedSql(String s) {
        return "(SELECT COALESCE(-SUM(u.AMT), 0) FROM sldtl u WHERE u.SL_BRCODE = " + s + ".SDBR_CODE AND u.SL_CLIENTID = " + s + ".ClientIDSD AND u.SLC_CODE = " + s + ".SDSLC_CODE"
            + " AND u.SLT_CODE = " + s + ".SDSLT_CODE AND u.REF_NO = " + s + ".SDREF_NO AND u.SLE_CODE = 11 AND u.COCI_TYPE = 2"
            + " AND EXISTS (SELECT 1 FROM transck k WHERE k.CKTR_BRCODE = u.SL_BRCODE AND k.CKTR_YEAR = EXTRACT(YEAR FROM u.TR_DATE) AND k.CKTR_CODE = u.TR_CODE"
            + " AND k.CKTR_CTLNO = u.TR_CTLNO AND k.CKCHECKNO = u.CHECKNO AND k.CKCOCI_TYPE = 2 AND COALESCE(k.CKISCLEARED, 0) = 0))";
    }

    static String number(String typed) {
        String t = typed == null ? "" : typed.trim();
        if (!t.matches("[0-9]{1,15}")) return null;
        return "0".repeat(NUMBER_LENGTH - t.length()) + t;
    }

    /**
     * Days shown on the member's ledger row: the kind's clearing days and two more (3 days is 5, 7 days is 9, as in the real receipts),
     * counted down each day until the cheque clears; a kind with 0 days clears at once.
     */
    static int ledgerDays(int kindDays) {
        return kindDays > 0 ? kindDays + 2 : 0;
    }

    /** Problems with one cheque, empty when it is fine. {@code kindDays} is null when the kind is not in chktype, {@code bankKnown} says if the bank exists. */
    static List<String> check(Input c, int position, LocalDate today, Integer kindDays, boolean bankKnown) {
        String who = "Cheque " + position;
        List<String> p = new ArrayList<>();
        if (number(c.number()) == null) p.add(who + ": the cheque number must be 1 to 15 digits.");
        if (c.bankCode() == null || !bankKnown) p.add(who + ": choose the bank the cheque is drawn on.");
        if (c.kind() == null || kindDays == null) p.add(who + ": choose the kind of cheque (local, regional, out of town...).");
        if (c.amount() == null || c.amount().signum() <= 0) p.add(who + ": enter an amount above zero.");
        else if (c.amount().stripTrailingZeros().scale() > 2) p.add(who + ": the amount can have at most 2 decimal places.");
        if (c.date() == null) p.add(who + ": enter the date on the cheque.");
        else {
            long age = ChronoUnit.DAYS.between(c.date(), today);
            if (age > MAX_AGE_DAYS) p.add(who + ": the cheque is dated " + c.date() + ", more than " + MAX_AGE_DAYS + " days ago (stale).");
            if (-age > MAX_AHEAD_DAYS) p.add(who + ": the cheque is dated " + c.date() + ", more than " + MAX_AHEAD_DAYS + " days ahead.");
        }
        return p;
    }

    /** The same bank and number twice on one receipt. */
    static List<String> repeats(List<Input> cheques) {
        List<String> p = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < cheques.size(); i++) {
            String n = number(cheques.get(i).number());
            if (n != null && cheques.get(i).bankCode() != null && !seen.add(cheques.get(i).bankCode() + "/" + n)) p.add("Cheque " + (i + 1) + ": the same cheque is listed twice.");
        }
        return p;
    }
}
