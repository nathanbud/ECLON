package ph.coop.core.inbox;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ph.coop.core.db.Db;
import ph.coop.core.workflow.WorkflowEngine;

/**
 * Everything waiting on the signed-in person, in one list: profile changes, member requests and member lists for a membership officer; journals, memos and penalty waivers for an
 * accountant; purchase requests and orders for a purchase approver; stock adjustments for a stock approver; any extra workflow step for their role; and, from the old system's own
 * rights, loan applications at the steps they may do, release vouchers (Cash Voucher), dividend distributions and vouchers (Dividends), time deposit accrual vouchers, supervisor
 * requests for the head cashier, and a business day to start or end. Each item says where to go to decide it ({@code view} and {@code tab} name a screen of the desktop app). It never
 * lists a person's own items, and it only reads: deciding happens on the item's own screen, with that screen's rules.
 */
@RestController
@RequestMapping("/api/inbox")
class InboxController implements ph.coop.core.connection.OnConnectionSwitch {

    /** {@code kind} is a short code for the sort of work; {@code view} and {@code tab} say which screen decides it. */
    record Item(String kind, String label, String summary, String by, String at, String view, String tab) {}

    record Inbox(List<Item> items, int total, Map<String, Integer> byKind) {}

    private static final int PER_SOURCE = 100;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final WorkflowEngine workflow;
    private final ph.coop.core.loan.LoanSteps steps;
    private final ph.coop.core.businessday.BusinessDay businessDay;
    private final java.time.Clock clock;
    private final Map<String, Boolean> known = new ConcurrentHashMap<>();

    /** Loan applications older than this (by their date) are not listed, as on the Loan Module's list. */
    static final int LOAN_DAYS = 60;

    /** The person in the old user list: their id, branch, loan steps, business day and head cashier switches, and New on the transaction types that matter here. */
    private record Staff(int id, int branch, InboxRules.LoanRights loans, boolean startDay, boolean endDay, boolean head, boolean frontOffice, java.util.Set<Integer> mayNew) {}

    private final ph.coop.core.member.MembershipPolicyController policy;

    InboxController(JdbcTemplate jdbc, Db db, WorkflowEngine workflow, ph.coop.core.loan.LoanSteps steps, ph.coop.core.businessday.BusinessDay businessDay, java.time.Clock clock,
                    ph.coop.core.member.MembershipPolicyController policy) {
        this.policy = policy;
        this.jdbc = jdbc;
        this.db = db;
        this.workflow = workflow;
        this.steps = steps;
        this.businessDay = businessDay;
        this.clock = clock;
    }

    /** A Cash or Check Voucher that is not a loan release, share withdrawal or time deposit payout (the same test as the front office's voucher list). */
    private static final String GENERAL = """
             AND NOT EXISTS (SELECT 1 FROM transdt r WHERE r.DTTR_BRCODE = s.TR_BRCODE AND r.DTTR_YEAR = s.TR_YEAR AND r.DTTR_MODULE = s.TR_MODULE
                   AND r.DTTR_CODE = s.TR_CODE AND r.DTTR_CTLNO = s.TR_CTLNO AND r.DTSLC_CODE IN (12, 24, 31) AND r.DTSLE_CODE IN (11, 71) AND r.DTAMT > 0)
            """;

    private Staff staff(String username) {
        List<Staff> r = jdbc.query("SELECT AccessID, AccessBR_CODE, COALESCE(AccessCanAddLoanApplication, 0), COALESCE(AccessCanVerifyLoanApplication, 0), COALESCE(AccessCanApproveLoanApplication, 0), "
                + steps.deductionFlagSql("") + ", COALESCE(AccessStartDay, 0), COALESCE(AccessEndDay, 0), COALESCE(AccessIsHeadCashier, 0), COALESCE(AccessCanAccessFrontOffice, 0) "
                + "FROM access WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0 ORDER BY AccessID LIMIT 1",
            (rs, i) -> new Staff(rs.getInt(1), rs.getInt(2), new InboxRules.LoanRights(rs.getInt(6) == 1, rs.getInt(4) == 1, rs.getInt(5) == 1), rs.getInt(7) == 1, rs.getInt(8) == 1,
                rs.getInt(9) == 1, rs.getInt(10) == 1, new java.util.HashSet<>()), username);
        if (r.isEmpty()) return null;
        Staff s = r.get(0);
        jdbc.query("SELECT TR_CODE FROM access_trans WHERE BR_CODE = ? AND USERID = ? AND CAN_NEW = 1 AND TR_CODE IN (21, 22, 52, 54) ORDER BY TR_CODE",
            (rs) -> { s.mayNew().add(rs.getInt(1)); }, s.branch(), s.id());
        return s;
    }

    /** Vouchers of a kind encoded but not yet posted (lines tagged 2) this year and last, with who encoded and approved them, for the voucher steps. */
    private List<Item> vouchers(Staff st, int module, int code, String what, String view, String tab) {
        return vouchers(st, module, code, what, view, tab, "");
    }

    /** As above, narrowed by {@code where} (SQL on the header {@code s}, starting with AND): releases and general vouchers of type 21 are told apart. */
    private List<Item> vouchers(Staff st, int module, int code, String what, String view, String tab, String where) {
        int year = java.time.LocalDate.now(clock).getYear();
        return jdbc.query("""
                SELECT s.TR_YEAR, s.TR_CTLNO, s.TR_DOCNO, s.DATETIMEADDED, MAX(d.DTENCDBY), MAX(d.DTAPPRVBY), SUM(CASE WHEN d.DTAMT > 0 THEN d.DTAMT ELSE 0 END)
                FROM transsum s JOIN transdt d ON d.DTTR_BRCODE = s.TR_BRCODE AND d.DTTR_YEAR = s.TR_YEAR AND d.DTTR_MODULE = s.TR_MODULE AND d.DTTR_CODE = s.TR_CODE AND d.DTTR_CTLNO = s.TR_CTLNO
                WHERE s.TR_BRCODE = ? AND s.TR_YEAR >= ? AND s.TR_MODULE = ? AND s.TR_CODE = ? AND d.DTUPD_TAG = 2
                """ + where + """
                GROUP BY s.TR_BRCODE, s.TR_YEAR, s.TR_CTLNO, s.TR_DOCNO, s.DATETIMEADDED ORDER BY s.TR_YEAR, s.TR_CTLNO LIMIT 100
                """, (rs, i) -> {
            String step = InboxRules.voucherStep(rs.getInt(5), (Integer) rs.getObject(6, Integer.class), st.id());
            if (step == null) return null;
            boolean approve = step.equals("APPROVE");
            return new Item(what.toUpperCase().replace(' ', '_') + "_" + step, (approve ? "Approve " : "Post ") + what, "Voucher " + rs.getString(3).trim() + " · " + money(rs.getBigDecimal(7)),
                nameOf(st.branch(), rs.getInt(5)), iso(rs.getTimestamp(4)), view, tab);
        }, st.branch(), year - 1, module, code).stream().filter(java.util.Objects::nonNull).toList();
    }

    private String nameOf(int branch, int accessId) {
        return jdbc.queryForList("SELECT CONCAT_WS(' ', AccessFName, AccessLName) FROM access WHERE AccessBR_CODE = ? AND AccessID = ? ORDER BY AccessID LIMIT 1", String.class, branch, accessId)
            .stream().findFirst().map(String::trim).orElse(null);
    }

    private static String money(java.math.BigDecimal v) {
        return v == null ? "" : new java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ENGLISH)).format(v);
    }

    @Override
    public void connectionSwitched() {
        known.clear();
    }

    @GetMapping
    Inbox inbox(Authentication auth) {
        String me = auth.getName();
        var roles = InboxRules.roles(auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
        InboxRules.Sources s = InboxRules.sourcesFor(roles);
        List<Item> out = new ArrayList<>();

        if (s.membership() && has("ecis_member_change")) {
            out.addAll(jdbc.query("""
                    SELECT ch.requested_by, ch.requested_at, COALESCE(NULLIF(c.AccountName, ''), CONCAT_WS(', ', c.LName, c.FName)), ch.client_id
                    FROM ecis_member_change ch LEFT JOIN client c ON c.ClientIDBrCode = ch.branch_code AND c.ClientID = ch.client_id
                    WHERE ch.status = 'PENDING' AND LOWER(ch.requested_by) <> LOWER(?) ORDER BY ch.requested_at LIMIT 100
                    """, (rs, i) -> new Item("CHANGE", "Profile change", "Changes to " + clean(rs.getString(3), "member " + rs.getInt(4)), rs.getString(1), iso(rs.getTimestamp(2)), "approvals", null), me));
        }
        if (s.membership() && has("ecis_member_request")) {
            // Never one's own request, except one's own registrations while a super administrator lets Membership Officers register directly.
            int ownRegistrations = roles.contains("MEMBERSHIP_OFFICER") && policy.officerRegistersDirectly() ? 1 : 0;
            out.addAll(jdbc.query("""
                    SELECT kind, summary, requested_by, requested_at FROM ecis_member_request
                    WHERE status = 'PENDING' AND batch_id IS NULL AND (LOWER(requested_by) <> LOWER(?) OR (? = 1 AND kind = 'REGISTER')) ORDER BY requested_at LIMIT 100
                    """, (rs, i) -> new Item("MEMBER_REQUEST", requestLabel(rs.getString(1)), rs.getString(2), rs.getString(3), iso(rs.getTimestamp(4)), "approvals", null), me, ownRegistrations));
        }
        if (s.accounting() && has("ecis_journal")) {
            out.addAll(jdbc.query("""
                    SELECT status, reference, description, created_by, created_at, approved_at FROM ecis_journal
                    WHERE (status = 'FOR_REVIEW' AND LOWER(created_by) <> LOWER(?)) OR status = 'APPROVED' ORDER BY id LIMIT 100
                    """, (rs, i) -> {
                boolean review = "FOR_REVIEW".equals(rs.getString(1));
                return new Item(review ? "JOURNAL_REVIEW" : "JOURNAL_POST", review ? "Journal to review" : "Journal ready to post", rs.getString(2) + (rs.getString(3) == null ? "" : " - " + rs.getString(3)), rs.getString(4),
                    iso(rs.getTimestamp(review ? 5 : 6)), "backoffice", "General journal");
            }, me));
        }
        if (s.accounting() && has("ecis_memo")) {
            out.addAll(jdbc.query("""
                    SELECT status, reference, description, created_by, approved_by, created_at, approved_at FROM ecis_memo
                    WHERE status IN ('FOR_REVIEW', 'APPROVED') ORDER BY id LIMIT 100
                    """, (rs, i) -> {
                boolean review = "FOR_REVIEW".equals(rs.getString(1));
                String maker = rs.getString(4), approver = rs.getString(5);
                if (review ? InboxRules.own(maker, me) : !InboxRules.mayPostMemo(maker, approver, me)) return null;
                return new Item(review ? "MEMO_REVIEW" : "MEMO_POST", review ? "Memo to review" : "Memo ready to post", rs.getString(2) + " - " + rs.getString(3), maker, iso(rs.getTimestamp(review ? 6 : 7)), "backoffice", "Debit/credit memos");
            }).stream().filter(java.util.Objects::nonNull).toList());
        }
        if (s.purchasing() && has("ecis_purchase_request")) {
            out.addAll(jdbc.query("""
                    SELECT request_no, purpose, requested_by, created_at FROM ecis_purchase_request
                    WHERE status = 'SUBMITTED' AND LOWER(requested_by) <> LOWER(?) ORDER BY id LIMIT 100
                    """, (rs, i) -> new Item("PURCHASE_REQUEST", "Purchase request", rs.getString(1) + " - " + rs.getString(2), rs.getString(3), iso(rs.getTimestamp(4)), "procurement", "Requests"), me));
        }
        if (s.purchasing() && has("ecis_purchase_order")) {
            out.addAll(jdbc.query("""
                    SELECT order_no, total, created_by, created_at FROM ecis_purchase_order
                    WHERE status = 'DRAFT' AND LOWER(created_by) <> LOWER(?) ORDER BY id LIMIT 100
                    """, (rs, i) -> new Item("PURCHASE_ORDER", "Purchase order", rs.getString(1) + " - " + rs.getBigDecimal(2).toPlainString(), rs.getString(3), iso(rs.getTimestamp(4)), "procurement", "Orders"), me));
        }
        if (s.stock() && has("ecis_stock_adjustment")) {
            out.addAll(jdbc.query("""
                    SELECT adjust_no, reason, requested_by, created_at FROM ecis_stock_adjustment
                    WHERE status = 'SUBMITTED' AND LOWER(requested_by) <> LOWER(?) ORDER BY id LIMIT 100
                    """, (rs, i) -> new Item("STOCK_ADJUSTMENT", "Stock adjustment", rs.getString(1) + " - " + rs.getString(2), rs.getString(3), iso(rs.getTimestamp(4)), "inventory", "Adjustments"), me));
        }
        Staff st = staff(me);
        boolean admin = roles.contains("ADMIN"), accountant = roles.contains("ACCOUNTANT"), officer = roles.contains("MEMBERSHIP_OFFICER");

        // member lists uploaded in Advanced mode, decided as a whole by another Membership Officer
        if (officer && has("ecis_member_import")) {
            out.addAll(jdbc.query("""
                    SELECT file_name, ok_rows, uploaded_by, uploaded_at FROM ecis_member_import WHERE status = 'PENDING' AND LOWER(uploaded_by) <> LOWER(?) ORDER BY id LIMIT 100
                    """, (rs, i) -> new Item("MEMBER_LIST", "Member list", rs.getString(1) + " · " + rs.getInt(2) + " members", rs.getString(3), iso(rs.getTimestamp(4)), "approvals", null), me));
        }

        // loan applications at the steps this person may do (Loan Module)
        if (st != null && (st.loans().deduct() || st.loans().verify() || st.loans().approve()) && has("loanapp")) {
            java.time.LocalDate since = java.time.LocalDate.now(clock).minusDays(LOAN_DAYS);
            out.addAll(jdbc.query("SELECT a.STATUSID, " + steps.waitingSql("a") + ", COALESCE(a.ENCDBY, 0), a.DOC_NO, CONCAT_WS(', ', k.LName, k.FName), a.TR_DATE, "
                    + "(SELECT CONCAT_WS(' ', e.AccessFName, e.AccessLName) FROM access e WHERE e.AccessBR_CODE = a.BR_CODE AND e.AccessID = a.ENCDBY ORDER BY e.AccessID LIMIT 1), l.LoanPAMT "
                    + "FROM loanapp a JOIN loan l ON l.LoanBR_CODE = a.BR_CODE AND l.LoanSLC_CODE = a.SLC_CODE AND l.LoanSLT_CODE = a.SLT_CODE AND l.LoanREF_NO = a.REF_NO "
                    + "LEFT JOIN client k ON k.ClientIDBrCode = a.BR_CODE AND k.ClientID = a.CLIENTID "
                    + "WHERE a.BR_CODE = ? AND a.STATUSID IN (1, 2) AND a.TR_DATE >= ? ORDER BY a.ID LIMIT 100",
                (rs, i) -> {
                    String kind = InboxRules.loanStep(rs.getInt(1), rs.getInt(2) > 0, rs.getInt(3), st.id(), st.loans());
                    if (kind == null) return null;
                    String label = switch (kind) {
                        case "LOAN_DEDUCTION" -> "Loan: do the deductions";
                        case "LOAN_VERIFY" -> "Loan: verify";
                        default -> "Loan: approve";
                    };
                    java.sql.Date d = rs.getDate(6);
                    return new Item(kind, label, clean(rs.getString(5), "member") + " · " + money(rs.getBigDecimal(8)) + " · " + rs.getString(4), rs.getString(7),
                        d == null ? null : d.toLocalDate().atStartOfDay().toString(), "loans", null);
                }, st.branch(), java.sql.Date.valueOf(since)).stream().filter(java.util.Objects::nonNull).toList());
        }

        // release vouchers (Cash Voucher, type 21: loan releases, share withdrawals, time deposit pay-outs) approved by one person and posted by another
        if (st != null && st.frontOffice() && st.mayNew().contains(21)) {
            out.addAll(vouchers(st, 2, 21, "release voucher", "frontoffice", null, "AND NOT (1 = 1 " + GENERAL + ")"));
            // general Cash Vouchers (an expense to pay someone), and Check Vouchers paid by the cooperative's cheque: the same three people
            out.addAll(vouchers(st, 2, 21, "cash voucher", "frontoffice", null, GENERAL));
        }
        if (st != null && st.frontOffice() && st.mayNew().contains(22)) out.addAll(vouchers(st, 2, 22, "check voucher", "frontoffice", null, GENERAL));
        // dividend vouchers, and time deposit accruals, approved by a second person and posted by a third
        if (st != null && st.mayNew().contains(54)) out.addAll(vouchers(st, 6, 54, "dividend voucher", "dividends", null));
        if (st != null && st.mayNew().contains(52)) out.addAll(vouchers(st, 8, 52, "accrual voucher", "frontoffice", null));

        // dividend distributions: to review (not the preparer), and approved ones whose vouchers are not made yet
        if ((admin || (st != null && st.mayNew().contains(54))) && has("ecis_distribution_batch")) {
            out.addAll(jdbc.query("""
                    SELECT status, year, total, members, prepared_by, COALESCE(submitted_at, prepared_at), decided_at FROM ecis_distribution_batch
                    WHERE (status = 'SUBMITTED' AND LOWER(prepared_by) <> LOWER(?)) OR status = 'APPROVED' ORDER BY id LIMIT 100
                    """, (rs, i) -> {
                boolean review = "SUBMITTED".equals(rs.getString(1));
                if (!review && st == null) return null;
                return new Item(review ? "DISTRIBUTION_REVIEW" : "DISTRIBUTION_ENCODE", review ? "Dividends to review" : "Dividend vouchers to make",
                    rs.getInt(2) + " distribution · " + money(rs.getBigDecimal(3)) + " to " + rs.getInt(4) + " members", rs.getString(5), iso(rs.getTimestamp(review ? 6 : 7)), "dividends", null);
            }, me).stream().filter(java.util.Objects::nonNull).toList());
        }

        // supervisor requests (over a limit, a rate, a penalty, a returned cheque, a penalty waiver), never one's own
        if (has("ecis_fo_override") && (admin || accountant || (st != null && st.head()))) {
            int myId = st == null ? -1 : st.id();
            int branch = st == null ? 1 : st.branch(); // the bootstrap administrator works in branch 1
            out.addAll(jdbc.query("""
                    SELECT o.kind, o.description, o.amount, o.requested_at, o.requested_by,
                      (SELECT CONCAT_WS(' ', a.AccessFName, a.AccessLName) FROM access a WHERE a.AccessID = o.requested_by ORDER BY a.AccessID LIMIT 1)
                    FROM ecis_fo_override o WHERE o.status = 'PENDING' AND o.branch_code = ? ORDER BY o.id LIMIT 100
                    """, (rs, i) -> {
                String kind = rs.getString(1);
                if (rs.getInt(5) == myId || !InboxRules.decidesOverride(kind, st != null && st.head(), accountant, admin)) return null;
                boolean waiver = "PENALTY_WAIVER".equals(kind);
                return new Item(waiver ? "PENALTY_WAIVER" : "SUPERVISOR", waiver ? "Penalty waiver" : "Supervisor approval",
                    clean(rs.getString(2), kind) + (rs.getBigDecimal(3) == null ? "" : " · " + money(rs.getBigDecimal(3))), rs.getString(6), iso(rs.getTimestamp(4)),
                    waiver ? "backoffice" : "frontoffice", waiver ? "Penalty waivers" : null);
            }, branch).stream().filter(java.util.Objects::nonNull).toList());
        }

        // the business day: nothing posts until it is started, and a day left open past its date is still waiting to be ended
        if (businessDay.inUse()) {
            int branch = st == null ? 1 : st.branch();
            java.util.Optional<java.time.LocalDate> open = businessDay.openDay(branch);
            if (open.isEmpty() && (admin || (st != null && st.startDay()))) {
                out.add(new Item("BUSINESS_DAY_START", "Start the business day", "No business day is open, so nothing can be posted.", null, null, "businessday", null));
            } else if (open.isPresent() && open.get().isBefore(businessDay.calendarDate()) && (admin || (st != null && st.endDay()))) {
                out.add(new Item("BUSINESS_DAY_END", "End the business day", "The business day " + open.get() + " is still open; postings are dated on it.", null, open.get().atStartOfDay().toString(), "businessday", null));
            }
        }

        if (has("ecis_workflow_instance")) {
            for (WorkflowEngine.Waiting w : workflow.waitingFor(roles, me)) {
                out.add(new Item("WORKFLOW", w.label(), w.description() + " (your step: " + w.step() + ")", w.requestedBy(), w.since() == null ? null : w.since().toString(), "approvals", null));
            }
        }

        // longest waiting first, so nothing is forgotten at the bottom
        out.sort(Comparator.comparing((Item i) -> i.at() == null ? "9999" : i.at()));
        Map<String, Integer> byKind = new java.util.TreeMap<>();
        for (Item i : out) byKind.merge(i.kind(), 1, Integer::sum);
        return new Inbox(out, out.size(), byKind);
    }

    /** Just the number, for the badge on the home page and the side menu. */
    @GetMapping("/count")
    Map<String, Integer> count(Authentication auth) {
        return Map.of("count", inbox(auth).total());
    }

    private boolean has(String table) {
        return known.computeIfAbsent(table, db::tableExists);
    }

    private static String requestLabel(String kind) {
        return switch (kind) {
            case "REGISTER" -> "New member";
            case "CLOSE" -> "Close membership";
            case "REACTIVATE" -> "Reactivate membership";
            case "BENEF" -> "Beneficiary change";
            default -> "Member request";
        };
    }

    private static String clean(String s, String otherwise) {
        return s == null || s.isBlank() ? otherwise : s;
    }

    private static String iso(Timestamp t) {
        if (t == null) return null;
        LocalDateTime d = t.toLocalDateTime();
        return d.toString();
    }
}
