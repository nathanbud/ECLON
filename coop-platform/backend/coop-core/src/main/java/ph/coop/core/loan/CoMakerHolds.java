package ph.coop.core.loan;


import static ph.coop.core.web.ApiErrors.bad;import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The amount each co-maker agreed to cover on a loan application, and the hold that protects it.
 * <p>
 * The amount is recorded with the application ({@code ecis_loan_comaker}) together with the co-maker's own account it will be held on: a savings
 * account (class 22) or share capital (class 31), which is where the old system's holds of this kind sit. The hold itself is written when the
 * release voucher is posted, like the old system's: a {@code hold} row of reason 1 (Collateral), described "CM to &lt;borrower&gt;" and
 * referencing "CV#&lt;voucher number&gt;". An amount can only be held if the account's balance, less holds already on it, covers it, and this is
 * checked again at posting. Releasing a hold when the loan is paid is not built.
 */
@Component
public class CoMakerHolds {

    private static final int SAVINGS = 22, SHARE_CAPITAL = 31, ACTIVE = 15, COLLATERAL = 1;

    /** What the clerk typed for one co-maker. */
    public record Request(int clientId, BigDecimal amount, int slc, int slt, int refNo) {}

    /** An account of a co-maker a hold can go on. */
    public record HoldAccount(int slc, int slt, int refNo, String type, BigDecimal balance, BigDecimal held, BigDecimal available, boolean preferred) {}

    /** A saved co-maker amount, with the hold's state. */
    public record Saved(int clientId, String name, BigDecimal amount, int slc, int slt, int refNo, String accountType, boolean placed) {}

    private final JdbcTemplate jdbc;

    CoMakerHolds(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }


    /** The active accounts of a member of a type the administrator allows holds on (see {@code ecis_hold_setting}), with what is on hold and what is free. */
    public List<HoldAccount> accounts(int branch, int client) {
        List<HoldAccount> out = new ArrayList<>();
        out.addAll(jdbc.query("""
                SELECT s.SDSLC_CODE, s.SDSLT_CODE, s.SDREF_NO, t.SLTypeM_DESC,
                       (SELECT COALESCE(-SUM(d.AMT), 0) FROM sldtl d WHERE d.SL_BRCODE = s.SDBR_CODE AND d.SL_CLIENTID = s.ClientIDSD AND d.SLC_CODE = s.SDSLC_CODE
                          AND d.SLT_CODE = s.SDSLT_CODE AND d.REF_NO = s.SDREF_NO AND d.SLE_CODE = 11),
                       (SELECT COALESCE(SUM(h.HHELD_AMT), 0) FROM hold h WHERE h.HBR_CODE = s.SDBR_CODE AND h.ClientIDH = s.ClientIDSD AND h.HSLC_CODE = s.SDSLC_CODE
                          AND h.HSLT_CODE = s.SDSLT_CODE AND h.HREF_NO = s.SDREF_NO),
                       hs.is_default
                FROM sd s LEFT JOIN sltype t ON t.SLTypeBR_CODE = s.SDBR_CODE AND t.SLTypeSLC_CODE = s.SDSLC_CODE AND t.SLTypeSLT_CODE = s.SDSLT_CODE
                JOIN ecis_hold_setting hs ON hs.branch_code = s.SDBR_CODE AND hs.slc_code = s.SDSLC_CODE AND hs.slt_code = s.SDSLT_CODE AND hs.holdable = 1
                WHERE s.SDBR_CODE = ? AND s.ClientIDSD = ? AND s.SDSLC_CODE = ? AND s.SDSTATUS = ?
                """, (rs, i) -> account(rs), branch, client, SAVINGS, ACTIVE));
        out.addAll(jdbc.query("""
                SELECT s.SCSLC_CODE, s.SCSLT_CODE, s.SCREF_NO, t.SLTypeM_DESC,
                       (SELECT COALESCE(-SUM(d.AMT), 0) FROM sldtl d WHERE d.SL_BRCODE = s.SCBR_CODE AND d.SL_CLIENTID = s.ClientIDSC AND d.SLC_CODE = s.SCSLC_CODE
                          AND d.SLT_CODE = s.SCSLT_CODE AND d.REF_NO = s.SCREF_NO AND d.SLE_CODE = 11),
                       (SELECT COALESCE(SUM(h.HHELD_AMT), 0) FROM hold h WHERE h.HBR_CODE = s.SCBR_CODE AND h.ClientIDH = s.ClientIDSC AND h.HSLC_CODE = s.SCSLC_CODE
                          AND h.HSLT_CODE = s.SCSLT_CODE AND h.HREF_NO = s.SCREF_NO),
                       hs.is_default
                FROM sc s LEFT JOIN sltype t ON t.SLTypeBR_CODE = s.SCBR_CODE AND t.SLTypeSLC_CODE = s.SCSLC_CODE AND t.SLTypeSLT_CODE = s.SCSLT_CODE
                JOIN ecis_hold_setting hs ON hs.branch_code = s.SCBR_CODE AND hs.slc_code = s.SCSLC_CODE AND hs.slt_code = s.SCSLT_CODE AND hs.holdable = 1
                WHERE s.SCBR_CODE = ? AND s.ClientIDSC = ? AND s.SCSLC_CODE = ? AND s.SCSTATUS = ?
                """, (rs, i) -> account(rs), branch, client, SHARE_CAPITAL, ACTIVE));
        return out;
    }

    private static HoldAccount account(java.sql.ResultSet rs) throws java.sql.SQLException {
        BigDecimal balance = rs.getBigDecimal(5), held = rs.getBigDecimal(6);
        BigDecimal free = balance.subtract(held);
        return new HoldAccount(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getString(4), balance, held, free.signum() < 0 ? BigDecimal.ZERO : free, rs.getInt(7) == 1);
    }

    private Optional<HoldAccount> find(int branch, int client, int slc, int slt, int ref) {
        return accounts(branch, client).stream().filter((a) -> a.slc() == slc && a.slt() == slt && a.refNo() == ref).findFirst();
    }

    /** Refuses the request unless every amount belongs to a co-maker of the application and fits in the free balance of their own account. */
    public void validate(int branch, int borrower, List<Integer> coMakers, List<Request> requests) {
        if (requests == null || requests.isEmpty()) return;
        Set<Integer> seen = new HashSet<>();
        for (Request r : requests) {
            if (coMakers == null || !coMakers.contains(r.clientId())) throw bad("Member " + r.clientId() + " is not one of the co-makers.");
            if (r.clientId() == borrower) throw bad("A borrower cannot be their own co-maker.");
            if (!seen.add(r.clientId())) throw bad("Member " + r.clientId() + " is listed twice.");
            if (r.amount() == null || r.amount().signum() <= 0 || r.amount().scale() > 2) throw bad("The amount co-made by member " + r.clientId() + " must be more than zero, with at most 2 decimals.");
            if (r.slc() != SAVINGS && r.slc() != SHARE_CAPITAL) throw bad("A hold can only go on a savings or share capital account.");
            HoldAccount a = find(branch, r.clientId(), r.slc(), r.slt(), r.refNo())
                .orElseThrow(() -> bad("Member " + r.clientId() + " has no active account " + r.slc() + "/" + r.slt() + "/" + r.refNo() + " to hold the amount on."));
            if (r.amount().compareTo(a.available()) > 0) throw bad("Member " + r.clientId() + " has only " + a.available().toPlainString() + " free on that account, not " + r.amount().toPlainString() + ".");
        }
    }

    /** Records the amounts with the application. No hold is written yet. */
    public void save(int branch, long applicationId, List<Request> requests) {
        if (requests == null) return;
        for (Request r : requests) {
            jdbc.update("INSERT INTO ecis_loan_comaker (branch_code, application_id, comaker_client, amount, hold_slc, hold_slt, hold_ref_no) VALUES (?, ?, ?, ?, ?, ?, ?)",
                branch, applicationId, r.clientId(), r.amount(), r.slc(), r.slt(), r.refNo());
        }
    }

    /** The amounts saved for an application. */
    public List<Saved> forApplication(int branch, long applicationId) {
        return jdbc.query("""
                SELECT c.comaker_client, CONCAT_WS(' ', k.FName, k.LName), c.amount, c.hold_slc, c.hold_slt, c.hold_ref_no, t.SLTypeM_DESC, c.placed_at
                FROM ecis_loan_comaker c
                LEFT JOIN client k ON k.ClientIDBrCode = c.branch_code AND k.ClientID = c.comaker_client
                LEFT JOIN sltype t ON t.SLTypeBR_CODE = c.branch_code AND t.SLTypeSLC_CODE = c.hold_slc AND t.SLTypeSLT_CODE = c.hold_slt
                WHERE c.branch_code = ? AND c.application_id = ? ORDER BY c.id
                """, (rs, i) -> new Saved(rs.getInt(1), rs.getString(2), rs.getBigDecimal(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getString(7), rs.getTimestamp(8) != null),
            branch, applicationId);
    }

    /**
     * Writes the holds for the application whose release voucher is being posted. Must run inside the posting transaction. Each amount is checked
     * again; if a co-maker has spent the balance since the application, posting stops with a message saying who.
     */
    public int placeForVoucher(int branch, int year, long controlNo, int userId, LocalDate day) {
        List<long[]> apps = jdbc.query("SELECT ID, CLIENTID FROM loanapp WHERE BR_CODE = ? AND TR_CTLNO = ? AND APPRV_DATE >= ? AND APPRV_DATE < ?",
            (rs, i) -> new long[] { rs.getLong(1), rs.getLong(2) }, branch, controlNo, LocalDate.of(year, 1, 1).toString(), LocalDate.of(year + 1, 1, 1).toString());
        int placed = 0;
        for (long[] app : apps) {
            List<String> borrower = jdbc.query("SELECT CONCAT_WS(' ', FName, LName) FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", (rs, i) -> rs.getString(1), branch, app[1]);
            String text = "CM to " + (borrower.isEmpty() ? app[1] : borrower.get(0));
            List<Object[]> rows = jdbc.query("SELECT id, comaker_client, amount, hold_slc, hold_slt, hold_ref_no FROM ecis_loan_comaker WHERE branch_code = ? AND application_id = ? AND placed_at IS NULL ORDER BY id FOR UPDATE",
                (rs, i) -> new Object[] { rs.getLong(1), rs.getInt(2), rs.getBigDecimal(3), rs.getInt(4), rs.getInt(5), rs.getInt(6) }, branch, app[0]);
            for (Object[] r : rows) {
                int client = (Integer) r[1], slc = (Integer) r[3], slt = (Integer) r[4], ref = (Integer) r[5];
                BigDecimal amount = (BigDecimal) r[2];
                HoldAccount a = find(branch, client, slc, slt, ref).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                    "Co-maker " + client + " no longer has an active account " + slc + "/" + slt + "/" + ref + " that can take a hold, to hold " + amount.toPlainString() + " on."));
                if (amount.compareTo(a.available()) > 0) throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Co-maker " + client + " has only " + a.available().toPlainString() + " free on account " + slc + "/" + slt + "/" + ref + ", not the " + amount.toPlainString() + " agreed.");
                jdbc.update("INSERT INTO hold (HBR_CODE, HSLC_CODE, HSLT_CODE, HREF_NO, ClientIDH, HHELD_AMT, HHOLD_CODE, HHOLDDESCR, HHOLDDATE, HOL_REFNO) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    branch, slc, slt, ref, client, amount, COLLATERAL, text.length() > 200 ? text.substring(0, 200) : text, day.toString(), "CV#" + controlNo);
                jdbc.update("UPDATE ecis_loan_comaker SET placed_at = NOW(), placed_by = ? WHERE id = ?", userId, r[0]);
                placed++;
            }
        }
        return placed;
    }

    /** An amount already on hold on the member's account because they are a co-maker. */
    public record PlacedNotice(BigDecimal amount, int slc, int slt, int refNo, String accountType, String borrower, String reference, java.time.LocalDate since) {}

    /** An amount the member agreed to cover on an application whose release has not been posted yet, so nothing is held for it yet. */
    public record PendingNotice(BigDecimal amount, int slc, int slt, int refNo, String accountType, String borrower, long applicationId) {}

    public record Notice(List<PlacedNotice> placed, List<PendingNotice> pending, BigDecimal totalHeld, BigDecimal totalPending) {}

    /**
     * What the member is bound to as a co-maker: holds placed for it (the old system's own "CM to ..." holds included) and amounts agreed on
     * applications that are not released yet. Shown on the member's share capital and savings tabs.
     */
    public Notice noticeFor(int branch, int client) {
        List<PlacedNotice> placed = jdbc.query("""
                SELECT h.HHELD_AMT, h.HSLC_CODE, h.HSLT_CODE, h.HREF_NO, t.SLTypeM_DESC, h.HHOLDDESCR, h.HOL_REFNO, h.HHOLDDATE
                FROM hold h LEFT JOIN sltype t ON t.SLTypeBR_CODE = h.HBR_CODE AND t.SLTypeSLC_CODE = h.HSLC_CODE AND t.SLTypeSLT_CODE = h.HSLT_CODE
                WHERE h.HBR_CODE = ? AND h.ClientIDH = ? AND h.HHOLD_CODE = ? AND h.HHOLDDESCR LIKE 'CM to %' AND h.HHELD_AMT > 0
                ORDER BY h.HHOLDDATE DESC
                """, (rs, i) -> new PlacedNotice(rs.getBigDecimal(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getString(6).substring(6).trim(), rs.getString(7),
                    rs.getDate(8) == null ? null : rs.getDate(8).toLocalDate()), branch, client, COLLATERAL);
        List<PendingNotice> pending = jdbc.query("""
                SELECT c.amount, c.hold_slc, c.hold_slt, c.hold_ref_no, t.SLTypeM_DESC, CONCAT_WS(' ', k.FName, k.LName), c.application_id
                FROM ecis_loan_comaker c
                JOIN loanapp a ON a.BR_CODE = c.branch_code AND a.ID = c.application_id AND a.STATUSID IN (1, 2, 3)
                LEFT JOIN client k ON k.ClientIDBrCode = a.BR_CODE AND k.ClientID = a.CLIENTID
                LEFT JOIN sltype t ON t.SLTypeBR_CODE = c.branch_code AND t.SLTypeSLC_CODE = c.hold_slc AND t.SLTypeSLT_CODE = c.hold_slt
                WHERE c.branch_code = ? AND c.comaker_client = ? AND c.placed_at IS NULL ORDER BY c.id
                """, (rs, i) -> new PendingNotice(rs.getBigDecimal(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getString(6), rs.getLong(7)), branch, client);
        return new Notice(placed, pending, placed.stream().map(PlacedNotice::amount).reduce(BigDecimal.ZERO, BigDecimal::add),
            pending.stream().map(PendingNotice::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
