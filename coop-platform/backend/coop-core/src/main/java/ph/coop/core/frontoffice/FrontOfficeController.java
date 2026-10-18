package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.frontoffice.FrontOfficeAccess.Perms;

/** The front office window: what it offers this person, and read-only lookup of transactions already in the legacy ledger. */
@RestController
@RequestMapping("/api/front-office")
class FrontOfficeController {

    record TypeInfo(int id, String desc, boolean canFind, boolean canNew) {}

    record Tab(String key, String label, List<TypeInfo> types) {}

    record Denom(int id, String kind, String description, BigDecimal amount) {}

    /** The person's own limits from the legacy setup, so the window can warn before an amount goes over them. */
    record Setup(String user, String systemDate, boolean canAccess, int branch, List<Tab> tabs, List<Denom> denominations,
                 BigDecimal maxWithdrawal, BigDecimal disbursementLimit, Integer defaultDept, String cashAccount, String cashAccountDesc) {}

    record Account(String code, String description) {}

    record Summary(int year, int module, int code, long ctlNo, String docNo, String type, String date, String time, Integer clientId,
                   String clientName, String batchNo, String explanation, BigDecimal total, String refNo) {}

    record Entry(int seq, Integer dept, String accountCode, String description, String accountNo, BigDecimal debit, BigDecimal credit, String reference) {}

    record Cheque(int seq, String type, String checkNo, Integer bank, String checkDate, BigDecimal amount, String payee, boolean cleared) {}

    record Cash(String kind, String description, BigDecimal denomination, BigDecimal qty, BigDecimal amount) {}

    record Allocation(String label, BigDecimal amount) {}

    record Detail(Summary header, Integer dept, List<Entry> entries, List<Cheque> cheques, List<Cash> cash, List<Allocation> allocations,
                  BigDecimal debit, BigDecimal credit, BigDecimal difference) {}

    record ClientAccount(int slc, int slt, int refNo, String number, String type, String kind, String glCode, String glDesc) {}

    /** One column of the totals: what a teller started the day with, took in and paid out, and what is still pending. */
    record Column(BigDecimal beginning, BigDecimal in, BigDecimal out, BigDecimal pendingIn, BigDecimal pendingOut, BigDecimal ending) {}

    /** known is false (with the reason) when the person has no teller or cashier number to total. */
    record TellerTotals(boolean known, String reason, Integer number, String role, String person, String date, Column cash, Column coci, Column total) {}

    /** The ledger account a teller's cash goes through: "Cash on hand - tellers". One place to change it. */
    static final String TELLER_CASH_ACCOUNT = "10003";

    private static final int MAX_ROWS = 200;

    private final JdbcTemplate jdbc;
    private final FrontOfficeAccess access;

    FrontOfficeController(JdbcTemplate jdbc, FrontOfficeAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    /** The tabs, the transaction types in each (with what this person may do), the cash denominations, and today's date. */
    @GetMapping("/setup")
    Setup setup(Authentication auth) {
        Perms p = access.of(auth);
        Map<Integer, String> names = access.typeNames();
        List<Tab> tabs = new ArrayList<>();
        for (FrontOfficeAccess.TabDef t : FrontOfficeAccess.TABS) {
            tabs.add(new Tab(t.key(), t.label(), t.types().stream().map((id) -> new TypeInfo(id, names.getOrDefault(id, "Type " + id), p.canFind(id), p.canNew(id))).toList()));
        }
        List<Denom> denoms = jdbc.query("SELECT ID, TYPE, DESCR, AMOUNT FROM denomtype ORDER BY AMOUNT DESC", (rs, i) -> new Denom(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4)));
        List<Object[]> own = jdbc.query("SELECT AccessMaxWithdrawal, AccessDisbursementLimit, AccessDepartment FROM access WHERE AccessUserName = ? LIMIT 1",
            (rs, i) -> new Object[] {rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getObject(3)}, auth.getName());
        Object[] o = own.isEmpty() ? new Object[] {null, null, null} : own.get(0);
        return new Setup(auth.getName(), LocalDate.now().toString(), p.access(), p.branch(), tabs, denoms,
            (BigDecimal) o[0], (BigDecimal) o[1], o[2] == null ? null : ((Number) o[2]).intValue(), TELLER_CASH_ACCOUNT, cashAccountName(p.branch()));
    }

    /** Accounts of the chart that can be used on an entry. */
    @GetMapping("/accounts")
    List<Account> accounts(@RequestParam(defaultValue = "") String q, Authentication auth) {
        Perms p = access.of(auth);
        String text = q.trim().replace("%", "").replace("_", "");
        return jdbc.query("""
                SELECT COAID, COADesc FROM coa
                WHERE COABR_CODE = ? AND COALESCE(COAisdisabled, 0) = 0 AND (COAID LIKE ? OR COADesc LIKE ?)
                ORDER BY COAID LIMIT 30
                """,
            (rs, i) -> new Account(rs.getString(1), rs.getString(2)), p.branch(), text + "%", "%" + text + "%");
    }

    /** Transactions on a date, for Find and the Summary tab. */
    @GetMapping("/transactions")
    List<Summary> transactions(@RequestParam String date, @RequestParam(required = false) List<Integer> types,
                               @RequestParam(defaultValue = "") String q, Authentication auth) {
        Perms p = access.of(auth);
        LocalDate day = parse(date);
        List<Integer> allowed = access.findable(p, types);
        if (allowed.isEmpty()) return List.of();

        String text = q.trim().replace("%", "").replace("_", "");
        // The year range lets the database use the table's key instead of reading every year; a transaction is
        // normally booked in the year of its date, and one year either side covers back- and future-dating.
        StringBuilder sql = new StringBuilder(SUMMARY_SQL).append(" WHERE t.TR_BRCODE = ? AND t.TR_YEAR BETWEEN ? AND ? AND t.TR_DATE = ? AND t.TR_CODE IN (")
            .append(String.join(",", allowed.stream().map((x) -> "?").toList())).append(")");
        List<Object> args = new ArrayList<>(List.of(p.branch(), day.getYear() - 1, day.getYear() + 1, day.toString()));
        args.addAll(allowed);
        if (!text.isEmpty()) {
            if (text.matches("\\d{1,9}")) {
                sql.append(" AND (t.TR_CTLNO = ? OR t.TR_CLIENTID = ?)");
                args.add(Long.parseLong(text));
                args.add(Long.parseLong(text));
            } else {
                sql.append(" AND (t.EXPLANATION LIKE ? OR t.CLIENTNAME LIKE ? OR c.LName LIKE ? OR c.FName LIKE ? OR t.TR_DOCNO LIKE ?)");
                for (int i = 0; i < 5; i++) args.add("%" + text + "%");
            }
        }
        sql.append(" ORDER BY t.TR_TIME DESC, t.TR_CTLNO DESC LIMIT ").append(MAX_ROWS);
        return jdbc.query(sql.toString(), (rs, i) -> summary(rs), args.toArray());
    }

    /** One transaction in full: header, entries, cheques, cash denominations, where the money went, and totals. */
    @GetMapping("/transactions/{year}/{module}/{code}/{ctlNo}")
    ResponseEntity<Detail> detail(@PathVariable int year, @PathVariable int module, @PathVariable int code, @PathVariable long ctlNo, Authentication auth) {
        Perms p = access.of(auth);
        access.requireFind(p, code);
        List<Summary> head = jdbc.query(SUMMARY_SQL + " WHERE t.TR_BRCODE = ? AND t.TR_YEAR = ? AND t.TR_MODULE = ? AND t.TR_CODE = ? AND t.TR_CTLNO = ?",
            (rs, i) -> summary(rs), p.branch(), year, module, code, ctlNo);
        if (head.isEmpty()) return ResponseEntity.notFound().build();

        List<Object[]> lines = new ArrayList<>();
        List<Entry> entries = jdbc.query("""
                SELECT d.DTSEQ_NO, d.DTDEPT, d.DTACCT_CODE, COALESCE(s.SLTypeM_DESC, c.COADesc), d.DTREF_NO, d.DTAMT, d.DTREFERENCE, d.DTSLC_CODE, s.SLTypeM_DESC
                FROM transdt d
                LEFT JOIN coa c ON c.COABR_CODE = d.DTTR_BRCODE AND c.COAID = d.DTACCT_CODE
                LEFT JOIN sltype s ON s.SLTypeBR_CODE = d.DTTR_BRCODE AND s.SLTypeSLC_CODE = d.DTSLC_CODE AND s.SLTypeSLT_CODE = d.DTSLT_CODE
                WHERE d.DTTR_BRCODE = ? AND d.DTTR_YEAR = ? AND d.DTTR_MODULE = ? AND d.DTTR_CODE = ? AND d.DTTR_CTLNO = ?
                ORDER BY d.DTSEQ_NO
                """,
            (rs, i) -> {
                BigDecimal amt = rs.getBigDecimal(6) == null ? BigDecimal.ZERO : rs.getBigDecimal(6);
                // Legacy convention: a positive amount is a debit, a negative one a credit.
                lines.add(new Object[] {rs.getObject(8), rs.getString(9), amt});
                return new Entry(rs.getInt(1), (Integer) rs.getObject(2), rs.getString(3), rs.getString(4), rs.getObject(5) == null ? null : rs.getString(5),
                    amt.signum() > 0 ? amt : BigDecimal.ZERO, amt.signum() < 0 ? amt.negate() : BigDecimal.ZERO, rs.getString(7));
            }, p.branch(), year, module, code, ctlNo);

        BigDecimal debit = entries.stream().map(Entry::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = entries.stream().map(Entry::credit).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Cheque> cheques = jdbc.query("""
                SELECT k.CKSEQ_NO, COALESCE(ct.COCITYPEDesc, ''), k.CKCHECKNO, k.CKBANK_CODE, k.CKCHECKDATE, k.CKAMT, k.CKPAYEE, k.CKISCLEARED
                FROM transck k LEFT JOIN cocitype ct ON ct.COCITYPEID = k.CKCOCI_TYPE
                WHERE k.CKTR_BRCODE = ? AND k.CKTR_YEAR = ? AND k.CKTR_MODULE = ? AND k.CKTR_CODE = ? AND k.CKTR_CTLNO = ? ORDER BY k.CKSEQ_NO
                """,
            (rs, i) -> new Cheque(rs.getInt(1), rs.getString(2), rs.getString(3), (Integer) rs.getObject(4), rs.getString(5), rs.getBigDecimal(6), rs.getString(7), rs.getInt(8) == 1),
            p.branch(), year, module, code, ctlNo);

        List<Cash> cash = jdbc.query("""
                SELECT t.TYPE, t.DESCR, t.AMOUNT, SUM(d.QTY)
                FROM denomsum s JOIN denomdtl d ON d.DENOMSUMID = s.ID JOIN denomtype t ON t.ID = d.DENOMTYPEID
                WHERE s.BR_CODE = ? AND s.TR_CODE = ? AND s.TR_CTLNO = ?
                GROUP BY t.ID, t.TYPE, t.DESCR, t.AMOUNT ORDER BY t.AMOUNT DESC
                """,
            (rs, i) -> new Cash(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(3).multiply(rs.getBigDecimal(4))),
            p.branch(), code, ctlNo);

        Integer dept = entries.isEmpty() ? null : entries.get(0).dept();
        return ResponseEntity.ok(new Detail(head.get(0), dept, entries, cheques, cash, allocations(lines), debit, credit, debit.subtract(credit)));
    }

    /**
     * A member's accounts (loans, savings, share capital and so on) with the ledger account each one posts to. The ledger
     * account is the one the old setup names for the account's principal balance.
     */
    @GetMapping("/clients/{clientId}/accounts")
    List<ClientAccount> clientAccounts(@PathVariable int clientId, Authentication auth) {
        Perms p = access.of(auth);
        List<Object[]> rows = jdbc.query("""
                SELECT m.MSLC_CODE, m.MSLT_CODE, m.MREF_NO, m.MREF_NOX, s.SLTypeM_DESC, cl.SLClassDesc,
                       (SELECT g.GLControlACCT_CODE FROM glcontrol g
                         WHERE g.GLControlBR_CODE = m.MBR_CODE AND g.GLControlSLC_CODE = m.MSLC_CODE AND g.GLControlSLT_CODE = m.MSLT_CODE AND g.GLControlSLE_CODE = 11
                         ORDER BY g.GLControlCTS_NUM LIMIT 1)
                FROM slmastersum m
                LEFT JOIN sltype s ON s.SLTypeBR_CODE = m.MBR_CODE AND s.SLTypeSLC_CODE = m.MSLC_CODE AND s.SLTypeSLT_CODE = m.MSLT_CODE
                LEFT JOIN slclass cl ON cl.SLClassID = m.MSLC_CODE
                WHERE m.MBR_CODE = ? AND m.MCLIENTID = ? AND m.MSLC_CODE NOT IN (41, 51)
                ORDER BY m.MSLC_CODE, m.MSLT_CODE, m.MREF_NO LIMIT 60
                """,
            (rs, i) -> new Object[] {rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)}, p.branch(), clientId);

        Map<String, String> glNames = new java.util.HashMap<>();
        List<String> codes = rows.stream().map((r) -> (String) r[6]).filter(java.util.Objects::nonNull).distinct().toList();
        if (!codes.isEmpty()) {
            List<Object> args = new ArrayList<>();
            args.add(p.branch());
            args.addAll(codes);
            jdbc.query("SELECT COAID, COADesc FROM coa WHERE COABR_CODE = ? AND COAID IN (" + String.join(",", codes.stream().map((c) -> "?").toList()) + ")",
                (rs) -> { glNames.put(rs.getString(1), rs.getString(2)); }, args.toArray());
        }
        return rows.stream().map((r) -> new ClientAccount((int) r[0], (int) r[1], (int) r[2], (String) r[3], (String) r[4] == null ? "Account" : (String) r[4],
            (String) r[5] == null ? "" : (String) r[5], (String) r[6], r[6] == null ? null : glNames.get((String) r[6]))).toList();
    }

    /**
     * The teller's (or cashier's) totals for a date, as in the old summary tab. A teller's money is the entries on cash on hand
     * (CASH) and on cheques not yet banked (COCI) that carry their teller number (or cashier number). Beginning balance is
     * everything before the date; IN and OUT are the date's debits and credits; pending is what has been entered at the front
     * office but not posted yet. Ending balance is beginning plus IN minus OUT.
     */
    @GetMapping("/teller-totals")
    TellerTotals tellerTotals(@RequestParam String date, Authentication auth) {
        Perms p = access.of(auth);
        LocalDate day = parse(date);
        if (p.all()) return none("The administrator account has no cash drawer.", day);

        List<Object[]> me = jdbc.query("SELECT AccessTellerNumber, COALESCE(AccessCurrAssign, ''), AccessFName, AccessMName, AccessLName, COALESCE(AccessIsHeadCashier, 0) FROM access WHERE AccessUserName = ? LIMIT 1",
            (rs, i) -> new Object[] {rs.getObject(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6) == 1}, auth.getName());
        Integer number = me.isEmpty() || me.get(0)[0] == null ? null : ((Number) me.get(0)[0]).intValue();
        if (number == null || number == 0) return none("No teller number is set up for your account.", day);
        String assignment = (String) me.get(0)[1];
        // Tellers are drawer type 53 and cashiers 52 in the old setup. The head cashier, and anyone who is a cashier and not a teller, works from a cashier drawer.
        List<Integer> events = HandoverController.eventsOf((Boolean) me.get(0)[5], assignment);
        if (events.isEmpty()) return none("Your account is not set up as a cashier or teller.", day);
        int event = events.get(0);
        boolean cashier = event == 52;

        Map<String, BigDecimal[]> by = new LinkedHashMap<>();
        by.put("cash", zeros());
        by.put("coci", zeros());
        String d = day.toString();
        // Posted entries: everything before the date is the beginning balance; the date's debits are IN and its credits OUT.
        // A reversed entry and its reversing entry both count, so they cancel out.
        jdbc.query("""
                SELECT CASE WHEN d.DTSLT_CODE = 1 THEN 'cash' ELSE 'coci' END,
                  SUM(CASE WHEN t.TR_DATE < ? THEN d.DTAMT ELSE 0 END),
                  SUM(CASE WHEN t.TR_DATE = ? AND d.DTAMT > 0 THEN d.DTAMT ELSE 0 END),
                  SUM(CASE WHEN t.TR_DATE = ? AND d.DTAMT < 0 THEN -d.DTAMT ELSE 0 END)
                FROM transdt d
                JOIN transsum t ON t.TR_BRCODE = d.DTTR_BRCODE AND t.TR_YEAR = d.DTTR_YEAR AND t.TR_MODULE = d.DTTR_MODULE AND t.TR_CODE = d.DTTR_CODE AND t.TR_CTLNO = d.DTTR_CTLNO
                WHERE d.DTTR_BRCODE = ? AND d.DTSLC_CODE = 11 AND d.DTSLT_CODE IN (1, 3, 97) AND d.DTSLE_CODE = ? AND d.DTCTS_NUM = ? AND t.TR_DATE <= ?
                GROUP BY 1
                """,
            (rs) -> {
                BigDecimal[] v = by.get(rs.getString(1));
                for (int i = 0; i < 3; i++) v[i] = v[i].add(rs.getBigDecimal(i + 2) == null ? BigDecimal.ZERO : rs.getBigDecimal(i + 2));
            }, d, d, d, p.branch(), event, number, d);

        // Pending: entries made at the front office but not posted yet (the old system keeps them in transfo).
        jdbc.query("""
                SELECT CASE WHEN FOSLT_CODE = 1 THEN 'cash' ELSE 'coci' END,
                  SUM(CASE WHEN FOAMT > 0 THEN FOAMT ELSE 0 END), SUM(CASE WHEN FOAMT < 0 THEN -FOAMT ELSE 0 END)
                FROM transfo
                WHERE FOTR_BRCODE = ? AND FOSLC_CODE = 11 AND FOSLT_CODE IN (1, 3, 97) AND FOSLE_CODE = ? AND FOCTS_NUM = ?
                GROUP BY 1
                """,
            (rs) -> {
                BigDecimal[] v = by.get(rs.getString(1));
                v[3] = v[3].add(rs.getBigDecimal(2) == null ? BigDecimal.ZERO : rs.getBigDecimal(2));
                v[4] = v[4].add(rs.getBigDecimal(3) == null ? BigDecimal.ZERO : rs.getBigDecimal(3));
            }, p.branch(), event, number);
        // Cash handed over and waiting for the other person to count it (see cash handover).
        try {
            jdbc.query("""
                    SELECT SUM(CASE WHEN to_event = ? AND to_number = ? THEN amount ELSE 0 END), SUM(CASE WHEN from_event = ? AND from_number = ? THEN amount ELSE 0 END)
                    FROM ecis_cash_handover WHERE branch_code = ? AND status = 'PENDING'
                    """,
                (rs) -> {
                    BigDecimal[] v = by.get("cash");
                    v[3] = v[3].add(rs.getBigDecimal(1) == null ? BigDecimal.ZERO : rs.getBigDecimal(1));
                    v[4] = v[4].add(rs.getBigDecimal(2) == null ? BigDecimal.ZERO : rs.getBigDecimal(2));
                }, event, number, event, number, p.branch());
        } catch (org.springframework.dao.DataAccessException e) {
            // The handover table has not been created yet, so nothing is pending from it.
        }

        Column cash = column(by.get("cash"));
        Column coci = column(by.get("coci"));
        BigDecimal[] sum = zeros();
        for (BigDecimal[] v : by.values()) for (int i = 0; i < 5; i++) sum[i] = sum[i].add(v[i]);
        String middle = me.get(0)[3] == null || ((String) me.get(0)[3]).isBlank() ? null : ((String) me.get(0)[3]).trim().charAt(0) + ".";
        String name = java.util.stream.Stream.of((String) me.get(0)[2], middle, (String) me.get(0)[4]).filter((x) -> x != null && !x.isBlank()).map(String::trim).collect(java.util.stream.Collectors.joining(" ")).toLowerCase();
        String person = (cashier ? "Cashier " : "Teller ") + number + " : " + auth.getName() + " - " + name;
        return new TellerTotals(true, null, number, cashier ? "Cashier" : "Teller", person, d, cash, coci, column(sum));
    }

    private static BigDecimal[] zeros() {
        BigDecimal[] z = new BigDecimal[5];
        java.util.Arrays.fill(z, BigDecimal.ZERO);
        return z;
    }

    private static Column column(BigDecimal[] v) {
        return new Column(v[0], v[1], v[2], v[3], v[4], v[0].add(v[1]).subtract(v[2]));
    }

    private static TellerTotals none(String reason, LocalDate day) {
        Column z = column(zeros());
        return new TellerTotals(false, reason, null, null, null, day.toString(), z, z, z);
    }

    /** Posted transactions for the same member on the same date with the same amount, to catch a double entry. */
    @GetMapping("/duplicates")
    List<Summary> duplicates(@RequestParam int clientId, @RequestParam String date, @RequestParam BigDecimal amount, Authentication auth) {
        Perms p = access.of(auth);
        LocalDate day = parse(date);
        List<Integer> allowed = access.findable(p, null);
        if (allowed.isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder(SUMMARY_SQL).append(" WHERE t.TR_BRCODE = ? AND t.TR_YEAR BETWEEN ? AND ? AND t.TR_DATE = ? AND t.TR_CLIENTID = ? AND t.TR_CODE IN (")
            .append(String.join(",", allowed.stream().map((x) -> "?").toList())).append(") ORDER BY t.TR_TIME DESC LIMIT 20");
        List<Object> args = new ArrayList<>(List.of(p.branch(), day.getYear() - 1, day.getYear() + 1, day.toString(), clientId));
        args.addAll(allowed);
        return jdbc.query(sql.toString(), (rs, i) -> summary(rs), args.toArray()).stream().filter((t) -> t.total().compareTo(amount) == 0).toList();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static final String SUMMARY_SQL = """
        SELECT t.TR_YEAR, t.TR_MODULE, t.TR_CODE, t.TR_CTLNO, t.TR_DOCNO, tt.TransTypeDesc, t.TR_DATE, t.TR_TIME, t.TR_CLIENTID,
               COALESCE(NULLIF(t.CLIENTNAME, ''), NULLIF(c.AccountName, ''), CONCAT_WS(', ', c.LName, c.FName)),
               t.BATCH_NO, t.EXPLANATION,
               (SELECT COALESCE(SUM(GREATEST(d.DTAMT, 0)), 0) FROM transdt d
                 WHERE d.DTTR_BRCODE = t.TR_BRCODE AND d.DTTR_YEAR = t.TR_YEAR AND d.DTTR_MODULE = t.TR_MODULE AND d.DTTR_CODE = t.TR_CODE AND d.DTTR_CTLNO = t.TR_CTLNO),
               t.TR_REFNO
        FROM transsum t
        JOIN transtype tt ON tt.TransTypeID = t.TR_CODE
        LEFT JOIN client c ON c.ClientIDBrCode = t.TR_BRCODE AND c.ClientID = t.TR_CLIENTID
        """;

    private static Summary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Summary(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
            (Integer) rs.getObject(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getBigDecimal(13), rs.getString(14));
    }

    /** Where the money went, by kind of account. Cash and income/expense lines are not allocations to a member's account. */
    private static List<Allocation> allocations(List<Object[]> lines) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (String k : List.of("Loan Accounts", "A/R Accounts", "Pledges", "Share Capital", "Savings Deposit", "Time Deposit", "Other Accounts")) out.put(k, BigDecimal.ZERO);
        for (Object[] l : lines) {
            Object slc = l[0];
            if (slc == null) continue;
            int cls = ((Number) slc).intValue();
            if (cls == 11 || cls == 41 || cls == 51) continue; // cash, income, expense
            String type = l[1] == null ? "" : l[1].toString().toLowerCase();
            String bucket = type.contains("pledge") ? "Pledges"
                : cls == 12 ? "Loan Accounts" : cls == 13 ? "A/R Accounts" : cls == 31 ? "Share Capital"
                : cls == 22 ? "Savings Deposit" : cls == 24 ? "Time Deposit" : "Other Accounts";
            out.merge(bucket, ((BigDecimal) l[2]).abs(), BigDecimal::add);
        }
        return out.entrySet().stream().map((e) -> new Allocation(e.getKey(), e.getValue())).toList();
    }

    private String cashAccountName(int branch) {
        List<String> n = jdbc.query("SELECT COADesc FROM coa WHERE COABR_CODE = ? AND COAID = ?", (rs, i) -> rs.getString(1), branch, TELLER_CASH_ACCOUNT);
        return n.isEmpty() ? "Cash on hand - tellers" : n.get(0);
    }

    private static LocalDate parse(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The date is not valid.");
        }
    }
}
