package ph.coop.core.frontoffice;

import ph.coop.core.db.Db;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.frontoffice.FrontOfficeAccess.Perms;
import ph.coop.core.frontoffice.HandoverRules.Drawer;

/**
 * Cash handover: the head cashier takes cash from the vault and gives it to tellers, tellers give it back before the end of the
 * day, and the head cashier puts the rest back in the vault. A hand-off between two people is recorded as pending until the
 * receiver counts it; when the count agrees it is posted to the ledger as a legacy "Cash Transfer" (type 31 or 32), written the
 * way the old window wrote it. Moves to or from the vault are posted at once by the head cashier. Posting only ever adds rows.
 */
@RestController
@RequestMapping("/api/front-office/handover")
class HandoverController {

    /** Everything the app knows about the signed-in person that handover needs. */
    private record Person(int id, int branch, int number, boolean head, String assignment, int dept, String name, String username) {}

    /** limit is the most cash the drawer should hold (null for none); closedAt and variance describe today's count, when there was one. */
    record DrawerInfo(int event, int number, String label, String who, BigDecimal balance, BigDecimal pendingIn, BigDecimal pendingOut,
                      BigDecimal limit, String closedAt, BigDecimal variance, boolean head) {}

    record LimitRow(int event, int number, BigDecimal maxCash) {}

    record CloseReq(int event, Map<Integer, Integer> counts, String reason) {}

    record CloseResult(BigDecimal expected, BigDecimal counted, BigDecimal variance, String closedAt) {}

    /** A cheap summary the screen asks for every few seconds: what is waiting for this person. */
    record Pulse(int incoming, long latestId, String latestFrom, BigDecimal latestAmount, int disputed, int overrides) {}

    record Handover(long id, int trCode, String from, String to, BigDecimal amount, String status, String issuedBy, String issuedAt,
                    String remarks, BigDecimal counted, String note, Long ctlNo, boolean incoming, boolean outgoing) {}

    record Mine(boolean ready, String notReady, List<DrawerInfo> drawers, boolean headCashier, List<Handover> incoming, List<Handover> outgoing, List<Handover> today) {}

    /** requestKey is made once per attempt by the screen, so a repeated click or a retry cannot post twice; overrideReason is needed to return cash to the vault before the day is ready to close. */
    record Issue(int fromEvent, int toEvent, Integer toNumber, BigDecimal amount, Map<Integer, Integer> counts, String remarks, String requestKey, String overrideReason) {}

    record Count(Map<Integer, Integer> counts) {}

    private static final String[] LEDGER_TABLES = {"transsum", "transdt", "gltrans", "denomsum", "denomdtl", "ecis_cash_handover"};

    private final JdbcTemplate jdbc;
    private final Db db;
    private final FrontOfficeAccess access;
    private final TransactionTemplate tx;
    private final DrawerBalances balances;

    HandoverController(JdbcTemplate jdbc, Db db, FrontOfficeAccess access, PlatformTransactionManager txManager, DrawerBalances balances) {
        this.jdbc = jdbc;
        this.db = db;
        this.access = access;
        this.tx = new TransactionTemplate(txManager);
        this.balances = balances;
    }

    // ---- who is asking ------------------------------------------------------------------------------------------------

    private Person me(Authentication auth) {
        List<Person> rows = jdbc.query("""
                SELECT AccessID, AccessBR_CODE, COALESCE(AccessTellerNumber, 0), COALESCE(AccessIsHeadCashier, 0), COALESCE(AccessCurrAssign, ''),
                  COALESCE(AccessDepartment, 0), CONCAT_WS(' ', AccessFName, AccessLName), AccessUserName
                FROM access WHERE AccessUserName = ? LIMIT 1
                """,
            (rs, i) -> new Person(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4) == 1, rs.getString(5), rs.getInt(6), rs.getString(7), rs.getString(8)), auth.getName());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is not a cashier or teller.");
        return rows.get(0);
    }

    /** The kinds of drawer this person works from, the one they mostly use first. */
    static List<Integer> eventsOf(boolean head, String assignment) {
        String a = assignment == null ? "" : assignment.toLowerCase();
        List<Integer> e = new ArrayList<>();
        if (head) e.add(HandoverRules.CASHIER);
        if (a.contains("teller")) e.add(HandoverRules.TELLER);
        if (a.contains("cashier") && !e.contains(HandoverRules.CASHIER)) e.add(HandoverRules.CASHIER);
        return e;
    }

    private List<Drawer> drawersOf(Person p) {
        if (p.number() <= 0) return List.of();
        return eventsOf(p.head(), p.assignment()).stream().map((e) -> new Drawer(e, p.number())).toList();
    }

    // ---- state of the module ------------------------------------------------------------------------------------------

    /** Null when handover can run; otherwise what is missing (the migration has to be run by an administrator). */
    private String notReady() {
        if (!db.tableExists("ecis_cash_handover")) return "Cash handover is not set up yet. An administrator has to run database/migrations/V006_cash_handover.sql.";
        if (!db.canInsertInto(LEDGER_TABLES)) return "The app does not have permission to post to the ledger yet. An administrator has to run database/migrations/V006_cash_handover.sql.";
        return null;
    }

    private BigDecimal cashBalance(int branch, Drawer d) {
        return balances.of(branch, d);
    }

    // ---- limits, today's counts, and the request key (these tables come from V007) -------------------------------------

    private Map<Integer, Map<Integer, BigDecimal>> limits(int branch) {
        Map<Integer, Map<Integer, BigDecimal>> m = new java.util.HashMap<>();
        try {
            jdbc.query("SELECT event, number, max_cash FROM ecis_drawer_limit WHERE branch_code = ?",
                (rs) -> { m.computeIfAbsent(rs.getInt(1), (k) -> new java.util.HashMap<>()).put(rs.getInt(2), rs.getBigDecimal(3)); }, branch);
        } catch (org.springframework.dao.DataAccessException e) {
            // drawer controls are not set up yet: no limits
        }
        return m;
    }

    private BigDecimal limitOf(Map<Integer, Map<Integer, BigDecimal>> limits, Drawer d) {
        BigDecimal l = DrawerRules.limitFor(limits.getOrDefault(d.event(), Map.of()), d.number());
        return l == null || l.signum() <= 0 ? null : l;
    }

    /** The latest count of each drawer today: when it was made and the difference. */
    private Map<Drawer, Object[]> closesToday(int branch) {
        Map<Drawer, Object[]> m = new java.util.HashMap<>();
        try {
            jdbc.query("SELECT event, number, closed_at, variance FROM ecis_drawer_close WHERE branch_code = ? AND tr_date = ? ORDER BY id",
                (rs) -> { m.put(new Drawer(rs.getInt(1), rs.getInt(2)), new Object[] {rs.getTimestamp(3).toLocalDateTime().format(DateTimeFormatter.ofPattern("HH:mm")), rs.getBigDecimal(4)}); },
                branch, LocalDate.now().toString());
        } catch (org.springframework.dao.DataAccessException e) {
            // drawer controls are not set up yet: nothing counted
        }
        return m;
    }

    private boolean hasRequestKey() {
        return db.columnExists("ecis_cash_handover", "request_key");
    }

    /** Why the day is not ready to close, or null when it is: hand-offs not settled, or tellers who worked today and have not counted. */
    private String notReadyToClose(int branch) {
        Integer waiting = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_cash_handover WHERE branch_code = ? AND status IN ('PENDING', 'DISPUTED')", Integer.class, branch);
        List<Integer> active = jdbc.query("""
                SELECT DISTINCT x.DTCTS_NUM FROM transsum t
                JOIN transdt x ON t.TR_BRCODE = x.DTTR_BRCODE AND t.TR_YEAR = x.DTTR_YEAR AND t.TR_MODULE = x.DTTR_MODULE AND t.TR_CODE = x.DTTR_CODE AND t.TR_CTLNO = x.DTTR_CTLNO
                WHERE t.TR_BRCODE = ? AND t.TR_YEAR = ? AND t.TR_DATE = ? AND x.DTSLC_CODE = 11 AND x.DTSLT_CODE = 1 AND x.DTSLE_CODE = ?
                """, (rs, i) -> rs.getInt(1), branch, LocalDate.now().getYear(), LocalDate.now().toString(), HandoverRules.TELLER);
        Map<Drawer, Object[]> closed = closesToday(branch);
        List<String> notClosed = active.stream().map((n) -> new Drawer(HandoverRules.TELLER, n)).filter((d) -> !closed.containsKey(d)).map(Drawer::label).toList();
        return DrawerRules.notReadyToClose(waiting == null ? 0 : waiting, notClosed);
    }

    private BigDecimal pending(int branch, Drawer d, boolean outgoing, boolean exists) {
        if (!exists) return BigDecimal.ZERO;
        String col = outgoing ? "from" : "to";
        BigDecimal v = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM ecis_cash_handover WHERE branch_code = ? AND status = 'PENDING' AND " + col + "_event = ? AND " + col + "_number = ?",
            BigDecimal.class, branch, d.event(), d.number());
        return v == null ? BigDecimal.ZERO : v;
    }

    private Map<Integer, BigDecimal> denomValues() {
        Map<Integer, BigDecimal> m = new LinkedHashMap<>();
        jdbc.query("SELECT ID, AMOUNT FROM denomtype ORDER BY ID", (rs) -> { m.put(rs.getInt(1), rs.getBigDecimal(2)); });
        return m;
    }

    // ---- reading ------------------------------------------------------------------------------------------------------

    /**
     * Every drawer in the branch people can hand cash to. The head cashier also sees each drawer's cash balance and what is
     * still waiting to be accepted; everyone else sees names only.
     */
    @GetMapping("/drawers")
    List<DrawerInfo> drawers(Authentication auth) {
        Person p = me(auth);
        Perms perms = access.of(auth);
        if (!perms.access()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot use the front office.");
        Map<Drawer, List<String>> who = new TreeMap<>(java.util.Comparator.comparingInt(Drawer::event).thenComparingInt(Drawer::number));
        java.util.Set<Integer> headNumbers = new java.util.HashSet<>();
        jdbc.query("""
                SELECT AccessTellerNumber, COALESCE(AccessIsHeadCashier, 0), COALESCE(AccessCurrAssign, ''), CONCAT_WS(' ', AccessFName, AccessLName)
                FROM access WHERE AccessBR_CODE = ? AND AccessTellerNumber > 0 AND COALESCE(AccessIsDisabled, 0) = 0
                """,
            (rs) -> {
                if (rs.getInt(2) == 1) headNumbers.add(rs.getInt(1));
                for (int e : eventsOf(rs.getInt(2) == 1, rs.getString(3))) who.computeIfAbsent(new Drawer(e, rs.getInt(1)), (k) -> new ArrayList<>()).add(rs.getString(4).trim());
            }, p.branch());
        if (p.head()) who.put(new Drawer(HandoverRules.VAULT, HandoverRules.VAULT_NUMBER), List.of("Vault"));

        boolean ready = notReady() == null;
        Map<Drawer, BigDecimal> all = p.head() ? balances.all(p.branch()) : Map.of();
        Map<Integer, Map<Integer, BigDecimal>> lim = limits(p.branch());
        Map<Drawer, Object[]> closed = p.head() ? closesToday(p.branch()) : Map.of();
        List<DrawerInfo> out = new ArrayList<>();
        for (Map.Entry<Drawer, List<String>> e : who.entrySet()) {
            Drawer d = e.getKey();
            Object[] c = closed.get(d);
            out.add(new DrawerInfo(d.event(), d.number(), d.label(), String.join(" / ", e.getValue()),
                p.head() ? all.getOrDefault(d, BigDecimal.ZERO) : null,
                p.head() ? pending(p.branch(), d, false, ready) : null, p.head() ? pending(p.branch(), d, true, ready) : null,
                d.isVault() ? null : limitOf(lim, d), c == null ? null : (String) c[0], c == null ? null : (BigDecimal) c[1],
                d.event() == HandoverRules.CASHIER && headNumbers.contains(d.number())));
        }
        return out;
    }

    /** What is waiting for this person to accept, what they have given that is not accepted yet, and today's hand-offs. */
    @GetMapping("/mine")
    Mine mine(Authentication auth) {
        Person p = me(auth);
        access.of(auth);
        String notReady = notReady();
        List<Drawer> mine = drawersOf(p);
        List<DrawerInfo> infos = new ArrayList<>();
        Map<Integer, Map<Integer, BigDecimal>> lim = limits(p.branch());
        Map<Drawer, Object[]> closed = closesToday(p.branch());
        for (Drawer d : mine) {
            Object[] c = closed.get(d);
            infos.add(new DrawerInfo(d.event(), d.number(), d.label(), p.name(), cashBalance(p.branch(), d), notReady == null ? pending(p.branch(), d, false, true) : BigDecimal.ZERO,
                notReady == null ? pending(p.branch(), d, true, true) : BigDecimal.ZERO, limitOf(lim, d), c == null ? null : (String) c[0], c == null ? null : (BigDecimal) c[1],
                d.event() == HandoverRules.CASHIER && p.head()));
        }
        if (notReady != null && !db.tableExists("ecis_cash_handover"))
            return new Mine(false, notReady, infos, p.head(), List.of(), List.of(), List.of());
        if (mine.isEmpty()) return new Mine(notReady == null, notReady, infos, p.head(), List.of(), List.of(), List.of());

        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(p.branch());
        for (Drawer d : mine) {
            if (where.length() > 0) where.append(" OR ");
            where.append("(h.to_event = ? AND h.to_number = ?) OR (h.from_event = ? AND h.from_number = ?)");
            args.add(d.event()); args.add(d.number()); args.add(d.event()); args.add(d.number());
        }
        args.add(LocalDate.now().toString());
        List<Handover> all = jdbc.query("""
                SELECT h.id, h.tr_code, h.from_event, h.from_number, h.to_event, h.to_number, h.amount, h.status, h.issued_by, a.AccessUserName, h.issued_at,
                  h.remarks, h.counted, h.note, h.tr_ctlno
                FROM ecis_cash_handover h LEFT JOIN access a ON a.AccessID = h.issued_by
                WHERE h.branch_code = ? AND (""" + where + """
                ) AND (h.status IN ('PENDING', 'DISPUTED') OR DATE(h.issued_at) = ?)
                ORDER BY h.id DESC LIMIT 100
                """,
            (rs, i) -> {
                Drawer from = new Drawer(rs.getInt(3), rs.getInt(4));
                Drawer to = new Drawer(rs.getInt(5), rs.getInt(6));
                boolean incoming = mine.contains(to);
                boolean outgoing = mine.contains(from);
                Handover base = new Handover(rs.getLong(1), rs.getInt(2), from.label(), to.label(), rs.getBigDecimal(7), rs.getString(8), rs.getString(10),
                    rs.getTimestamp(11).toLocalDateTime().format(DateTimeFormatter.ofPattern("HH:mm")), rs.getString(12), rs.getBigDecimal(13), rs.getString(14), null, incoming, outgoing);
                long ctl = rs.getLong(15);
                return rs.wasNull() ? base : new Handover(base.id(), base.trCode(), base.from(), base.to(), base.amount(), base.status(), base.issuedBy(), base.issuedAt(),
                    base.remarks(), base.counted(), base.note(), ctl, incoming, outgoing);
            }, args.toArray());
        List<Handover> incoming = all.stream().filter((h) -> h.incoming() && h.status().equals("PENDING")).toList();
        List<Handover> outgoing = all.stream().filter((h) -> h.outgoing() && (h.status().equals("PENDING") || h.status().equals("DISPUTED"))).toList();
        return new Mine(notReady == null, notReady, infos, p.head(), incoming, outgoing, all);
    }

    // ---- writing ------------------------------------------------------------------------------------------------------

    /** Give cash to another drawer. Vault moves are posted at once; hand-offs between people wait for the receiver's count. */
    @PostMapping
    Handover issue(@RequestBody Issue req, Authentication auth) {
        Person p = me(auth);
        Perms perms = access.of(auth);
        String notReady = notReady();
        if (notReady != null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, notReady);

        Drawer from = req.fromEvent() == HandoverRules.VAULT ? new Drawer(HandoverRules.VAULT, HandoverRules.VAULT_NUMBER) : new Drawer(req.fromEvent(), p.number());
        if (!from.isVault() && !drawersOf(p).contains(from)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have that drawer.");
        Drawer to = req.toEvent() == HandoverRules.VAULT ? new Drawer(HandoverRules.VAULT, HandoverRules.VAULT_NUMBER)
            : new Drawer(req.toEvent(), req.toNumber() == null ? 0 : req.toNumber());
        if (!to.isVault() && !drawerExists(p.branch(), to)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "There is no " + to.label() + ".");

        int trCode = HandoverRules.trCode(from);
        access.requireNew(perms, trCode);

        Map<Integer, Integer> counts = req.counts() == null ? Map.of() : req.counts();
        List<String> problems = HandoverRules.check(from, to, req.amount(), counts, denomValues(), p.head());
        if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", problems));
        if (p.dept() <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Your account has no department set, so the transfer cannot be posted.");

        if (!from.isVault()) {
            BigDecimal free = cashBalance(p.branch(), from).subtract(pending(p.branch(), from, true, true));
            if (req.amount().compareTo(free) > 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Your drawer has " + free.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " free to give. That is less than the amount.");
        }
        String remarks = req.remarks() == null || req.remarks().isBlank() ? null : req.remarks().trim().substring(0, Math.min(req.remarks().trim().length(), 200));

        // The same attempt sent twice (a double click, a retry) returns the first result instead of posting again.
        String key = req.requestKey() == null || req.requestKey().isBlank() || !hasRequestKey() ? null : req.requestKey().trim().substring(0, Math.min(req.requestKey().trim().length(), 40));
        if (key != null) {
            List<Long> same = jdbc.query("SELECT id FROM ecis_cash_handover WHERE request_key = ?", (rs, i) -> rs.getLong(1), key);
            if (!same.isEmpty()) return one(same.get(0), p);
        }

        // Returning cash to the vault ends the day: everything must be settled and every teller who worked must have counted, unless the head cashier gives a reason.
        if (to.isVault()) {
            String blocked = notReadyToClose(p.branch());
            if (blocked != null) {
                String why = req.overrideReason() == null ? "" : req.overrideReason().trim();
                if (why.length() < 5) throw new ResponseStatusException(HttpStatus.CONFLICT, blocked);
                jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value, reason) VALUES (NOW(), ?, 'eod.override', 'cash_handover', ?, ?, ?)",
                    p.username(), p.branch() + "/" + LocalDate.now(), blocked, why.substring(0, Math.min(why.length(), 250)));
            }
        }
        long id;
        try {
            id = issueAndPost(p, trCode, from, to, req.amount(), counts, remarks, key);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            List<Long> same = key == null ? List.of() : jdbc.query("SELECT id FROM ecis_cash_handover WHERE request_key = ?", (rs, i) -> rs.getLong(1), key);
            if (same.isEmpty()) throw e;
            return one(same.get(0), p);
        }
        return one(id, p);
    }

    private long issueAndPost(Person p, int trCode, Drawer from, Drawer to, BigDecimal amount, Map<Integer, Integer> counts, String remarks, String key) {
        BigDecimal reqAmount = amount;
        long id = tx.execute((st) -> {
            long newId = insertHandover(p, trCode, from, to, reqAmount, counts, remarks, key);
            if (HandoverRules.postsAtOnce(from, to)) {
                long ctl = postToLedger(p, from.isVault() ? null : p, to.isVault() ? null : p, p, from, to, reqAmount, counts, remarks, trCode, newId);
                jdbc.update("UPDATE ecis_cash_handover SET status = 'POSTED', settled_by = ?, settled_at = ?, counted = ?, tr_year = ?, tr_ctlno = ? WHERE id = ? AND status = 'PENDING'",
                    p.id(), Timestamp.valueOf(LocalDateTime.now()), reqAmount, LocalDate.now().getYear(), ctl, newId);
            }
            return newId;
        });
        return id;
    }

    // ---- limits, drawer count, pulse ----------------------------------------------------------------------------------

    /** The cash limits that are set. Number 0 is the limit for every drawer of that kind that has none of its own. */
    @GetMapping("/limits")
    List<LimitRow> limitRows(Authentication auth) {
        Person p = me(auth);
        List<LimitRow> out = new ArrayList<>();
        limits(p.branch()).forEach((event, byNumber) -> byNumber.forEach((number, max) -> out.add(new LimitRow(event, number, max))));
        out.sort(java.util.Comparator.comparingInt(LimitRow::event).thenComparingInt(LimitRow::number));
        return out;
    }

    /** The head cashier sets the most cash a drawer should hold. Zero removes the limit. */
    @org.springframework.web.bind.annotation.PutMapping("/limits")
    List<LimitRow> setLimit(@RequestBody LimitRow row, Authentication auth) {
        Person p = me(auth);
        access.of(auth);
        if (!p.head()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the head cashier sets drawer limits.");
        if ((row.event() != HandoverRules.CASHIER && row.event() != HandoverRules.TELLER) || row.number() < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a cashier or teller drawer.");
        BigDecimal max = row.maxCash() == null || row.maxCash().signum() < 0 ? BigDecimal.ZERO : row.maxCash().setScale(2, java.math.RoundingMode.HALF_UP);
        try {
            db.upsert("ecis_drawer_limit",
                new java.util.LinkedHashMap<>(java.util.Map.of("branch_code", p.branch(), "event", row.event(), "number", row.number())),
                new java.util.LinkedHashMap<>(java.util.Map.of("max_cash", max, "updated_by", p.id(), "updated_at", Timestamp.valueOf(LocalDateTime.now()))));
        } catch (org.springframework.dao.DataAccessException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Drawer limits are not set up yet. An administrator has to run database/migrations/V007_drawer_controls.sql.");
        }
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value) VALUES (NOW(), ?, 'drawer.limit', 'drawer', ?, ?)",
            p.username(), new Drawer(row.event(), row.number()).label(), max.toPlainString());
        return limitRows(auth);
    }

    /**
     * The end-of-day count of a drawer. The person counts first and sees the ledger's figure only after submitting; if the count does not
     * match, a reason is needed and the difference is recorded.
     */
    @PostMapping("/close")
    CloseResult close(@RequestBody CloseReq req, Authentication auth) {
        Person p = me(auth);
        access.of(auth);
        Drawer d = new Drawer(req.event(), p.number());
        if (!drawersOf(p).contains(d)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have that drawer.");
        Map<Integer, BigDecimal> faces = denomValues();
        Map<Integer, Integer> counts = req.counts() == null ? Map.of() : req.counts();
        for (Map.Entry<Integer, Integer> e : counts.entrySet())
            if (!faces.containsKey(e.getKey()) || e.getValue() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The count has a note or coin that is not valid.");
        BigDecimal counted = HandoverRules.total(counts, faces);
        BigDecimal expected = balances.of(p.branch(), d);
        BigDecimal variance = DrawerRules.variance(expected, counted);
        String reason = req.reason() == null || req.reason().isBlank() ? null : req.reason().trim().substring(0, Math.min(req.reason().trim().length(), 250));
        if (variance.signum() != 0 && (reason == null || reason.length() < 5))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The count does not match the ledger. Count again, or give a reason for the difference.");
        StringBuilder json = new StringBuilder("{");
        counts.entrySet().stream().filter((e) -> e.getValue() > 0).forEach((e) -> json.append(json.length() > 1 ? "," : "").append('"').append(e.getKey()).append("\":").append(e.getValue()));
        json.append('}');
        LocalDateTime now = LocalDateTime.now();
        try {
            jdbc.update("INSERT INTO ecis_drawer_close (branch_code, tr_date, event, number, closed_by, expected, counted, variance, counts, reason, closed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                p.branch(), now.toLocalDate().toString(), d.event(), d.number(), p.id(), expected, counted, variance, json.toString(), reason, Timestamp.valueOf(now));
        } catch (org.springframework.dao.DataAccessException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Drawer counts are not set up yet. An administrator has to run database/migrations/V007_drawer_controls.sql.");
        }
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value, reason) VALUES (NOW(), ?, 'drawer.close', 'drawer', ?, ?, ?)",
            p.username(), d.label(), "expected " + expected.toPlainString() + ", counted " + counted.toPlainString() + ", difference " + variance.toPlainString(), reason);
        return new CloseResult(expected, counted, variance, now.format(DateTimeFormatter.ofPattern("HH:mm")));
    }

    /** What is waiting for this person right now. Cheap enough to ask every few seconds. */
    @GetMapping("/pulse")
    Pulse pulse(Authentication auth) {
        Person p = me(auth);
        List<Drawer> mine = drawersOf(p);
        int incoming = 0;
        long latest = 0;
        String from = null;
        BigDecimal amount = null;
        int disputed = 0;
        try {
            for (Drawer d : mine) {
                incoming += jdbc.queryForObject("SELECT COUNT(*) FROM ecis_cash_handover WHERE branch_code = ? AND status = 'PENDING' AND to_event = ? AND to_number = ?", Integer.class, p.branch(), d.event(), d.number());
                disputed += jdbc.queryForObject("SELECT COUNT(*) FROM ecis_cash_handover WHERE branch_code = ? AND status = 'DISPUTED' AND from_event = ? AND from_number = ?", Integer.class, p.branch(), d.event(), d.number());
                List<Object[]> last = jdbc.query("SELECT id, from_event, from_number, amount FROM ecis_cash_handover WHERE branch_code = ? AND status = 'PENDING' AND to_event = ? AND to_number = ? ORDER BY id DESC LIMIT 1",
                    (rs, i) -> new Object[] {rs.getLong(1), new Drawer(rs.getInt(2), rs.getInt(3)).label(), rs.getBigDecimal(4)}, p.branch(), d.event(), d.number());
                if (!last.isEmpty() && (Long) last.get(0)[0] > latest) { latest = (Long) last.get(0)[0]; from = (String) last.get(0)[1]; amount = (BigDecimal) last.get(0)[2]; }
            }
        } catch (org.springframework.dao.DataAccessException e) {
            // handover is not set up yet: nothing is waiting
        }
        int overrides = 0;
        if (p.head() || auth.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals("ROLE_ADMIN"))) {
            try {
                Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_fo_override WHERE branch_code = ? AND status = 'PENDING'", Integer.class, p.branch());
                overrides = n == null ? 0 : n;
            } catch (org.springframework.dao.DataAccessException e) {
                // overrides are not set up yet
            }
        }
        return new Pulse(incoming, latest, from, amount, disputed, overrides);
    }

    /** Rebuilds the running drawer balances (about ten seconds). The head cashier can use this if the balances ever look wrong. */
    @PostMapping("/snapshot/refresh")
    Map<String, Integer> refreshSnapshot(Authentication auth) {
        Person p = me(auth);
        if (!p.head()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the head cashier can rebuild the balances.");
        return Map.of("drawers", balances.refresh(p.branch()));
    }

    /** The receiver counts the cash. If the count agrees it is posted; if not, the hand-off is marked as disputed and nothing is posted. */
    @PostMapping("/{id}/accept")
    Handover accept(@PathVariable long id, @RequestBody Count body, Authentication auth) {
        Person p = me(auth);
        access.of(auth);
        String notReady = notReady();
        if (notReady != null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, notReady);
        Map<Integer, BigDecimal> faces = denomValues();
        Map<Integer, Integer> counts = body.counts() == null ? Map.of() : body.counts();
        for (Map.Entry<Integer, Integer> e : counts.entrySet())
            if (!faces.containsKey(e.getKey()) || e.getValue() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The count has a note or coin that is not valid.");
        BigDecimal counted = HandoverRules.total(counts, faces);

        tx.executeWithoutResult((st) -> {
            Map<String, Object> h = jdbc.queryForMap("SELECT * FROM ecis_cash_handover WHERE id = ? AND branch_code = ? FOR UPDATE", id, p.branch());
            if (!"PENDING".equals(h.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "This hand-off is already " + String.valueOf(h.get("status")).toLowerCase() + ".");
            Drawer from = new Drawer(((Number) h.get("from_event")).intValue(), ((Number) h.get("from_number")).intValue());
            Drawer to = new Drawer(((Number) h.get("to_event")).intValue(), ((Number) h.get("to_number")).intValue());
            if (!drawersOf(p).contains(to)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This cash is for " + to.label() + ", not for you.");
            BigDecimal amount = (BigDecimal) h.get("amount");
            if (counted.compareTo(amount) != 0) {
                jdbc.update("UPDATE ecis_cash_handover SET status = 'DISPUTED', settled_by = ?, settled_at = ?, counted = ?, note = ? WHERE id = ? AND status = 'PENDING'",
                    p.id(), Timestamp.valueOf(LocalDateTime.now()), counted, "Counted " + counted.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " instead of " + amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(), id);
                return;
            }
            Person giver = personById(((Number) h.get("issued_by")).intValue());
            int trCode = ((Number) h.get("tr_code")).intValue();
            long ctl = postToLedger(giver, giver, p, giver, from, to, amount, counts, (String) h.get("remarks"), trCode, id);
            jdbc.update("UPDATE ecis_cash_handover SET status = 'POSTED', settled_by = ?, settled_at = ?, counted = ?, tr_year = ?, tr_ctlno = ? WHERE id = ? AND status = 'PENDING'",
                p.id(), Timestamp.valueOf(LocalDateTime.now()), counted, LocalDate.now().getYear(), ctl, id);
        });
        return one(id, p);
    }

    /** The giver takes back a hand-off that has not been accepted, or the receiver declines it. Nothing was posted, so nothing is reversed. */
    @PostMapping("/{id}/cancel")
    Handover cancel(@PathVariable long id, Authentication auth) {
        Person p = me(auth);
        access.of(auth);
        Map<String, Object> h = jdbc.queryForMap("SELECT * FROM ecis_cash_handover WHERE id = ? AND branch_code = ?", id, p.branch());
        Drawer to = new Drawer(((Number) h.get("to_event")).intValue(), ((Number) h.get("to_number")).intValue());
        boolean issuer = ((Number) h.get("issued_by")).intValue() == p.id();
        if (!issuer && !drawersOf(p).contains(to)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the giver or the receiver can cancel this.");
        int n = jdbc.update("UPDATE ecis_cash_handover SET status = 'CANCELLED', settled_by = ?, settled_at = ?, note = ? WHERE id = ? AND status IN ('PENDING', 'DISPUTED')",
            p.id(), Timestamp.valueOf(LocalDateTime.now()), issuer ? "Taken back by the giver" : "Declined by the receiver", id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "This hand-off is already settled.");
        return one(id, p);
    }

    // ---- the printable slip -------------------------------------------------------------------------------------------

    record SlipLine(String kind, String description, BigDecimal face, int qty, BigDecimal amount) {}

    record Slip(long id, int trCode, String type, String docNo, String date, String time, String from, String fromWho, String to, String toWho,
                BigDecimal amount, String status, String remarks, String issuedBy, String acceptedBy, BigDecimal counted, String note, List<SlipLine> lines) {}

    /** Everything needed to print a hand-off slip: who gave, who received, the control number and the count by note and coin. */
    @GetMapping("/{id}/slip")
    Slip slip(@PathVariable long id, Authentication auth) {
        Person p = me(auth);
        access.of(auth);
        List<Map<String, Object>> found = jdbc.queryForList("SELECT * FROM ecis_cash_handover WHERE id = ? AND branch_code = ?", id, p.branch());
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "There is no such hand-off.");
        Map<String, Object> h = found.get(0);
        Drawer from = new Drawer(((Number) h.get("from_event")).intValue(), ((Number) h.get("from_number")).intValue());
        Drawer to = new Drawer(((Number) h.get("to_event")).intValue(), ((Number) h.get("to_number")).intValue());
        int issuer = ((Number) h.get("issued_by")).intValue();
        Number settler = (Number) h.get("settled_by");
        List<Drawer> mine = drawersOf(p);
        if (!p.head() && issuer != p.id() && !mine.contains(from) && !mine.contains(to))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This hand-off is not yours.");

        String status = (String) h.get("status");
        int trCode = ((Number) h.get("tr_code")).intValue();
        Number ctl = (Number) h.get("tr_ctlno");
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        if ("POSTED".equals(status) && ctl != null) {
            // The count that was posted: what the receiver counted.
            jdbc.query("SELECT d.DENOMTYPEID, d.QTY FROM denomsum s JOIN denomdtl d ON d.DENOMSUMID = s.ID WHERE s.BR_CODE = ? AND s.TR_CODE = ? AND s.TR_CTLNO = ?",
                (rs) -> { counts.put(rs.getInt(1), rs.getBigDecimal(2).intValue()); }, p.branch(), trCode, ctl.longValue());
        } else {
            try {
                Map<String, Integer> raw = new com.fasterxml.jackson.databind.ObjectMapper().readValue((String) h.get("denominations"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Integer>>() {});
                raw.forEach((k, v) -> counts.put(Integer.valueOf(k), v));
            } catch (java.io.IOException e) {
                // an unreadable count is shown as no lines rather than failing the slip
            }
        }
        List<SlipLine> lines = new ArrayList<>();
        jdbc.query("SELECT ID, TYPE, DESCR, AMOUNT FROM denomtype ORDER BY AMOUNT DESC", (rs) -> {
            int qty = counts.getOrDefault(rs.getInt(1), 0);
            if (qty > 0) lines.add(new SlipLine(rs.getString(2), rs.getString(3), rs.getBigDecimal(4), qty, rs.getBigDecimal(4).multiply(BigDecimal.valueOf(qty))));
        });
        Object at = "POSTED".equals(status) && h.get("settled_at") != null ? h.get("settled_at") : h.get("issued_at");
        // Depending on the driver a date-time comes back as a Timestamp or a LocalDateTime.
        LocalDateTime stamp = at instanceof Timestamp t ? t.toLocalDateTime() : (LocalDateTime) at;
        return new Slip(id, trCode, trCode == 31 ? "Cash Transfer From" : "Cash Transfer To", ctl == null ? null : String.format("*%07d", ctl.longValue()),
            stamp.toLocalDate().toString(), stamp.format(DateTimeFormatter.ofPattern("HH:mm")),
            from.label(), whoIs(p.branch(), from), to.label(), whoIs(p.branch(), to), (BigDecimal) h.get("amount"), status, (String) h.get("remarks"),
            nameOf(issuer), settler == null ? null : nameOf(settler.intValue()), (BigDecimal) h.get("counted"), (String) h.get("note"), lines);
    }

    private String nameOf(int accessId) {
        return jdbc.query("SELECT CONCAT_WS(' ', AccessFName, AccessLName) FROM access WHERE AccessID = ? LIMIT 1", (rs, i) -> rs.getString(1), accessId).stream().findFirst().orElse("");
    }

    /** The people who work from a drawer, for the slip. */
    private String whoIs(int branch, Drawer d) {
        if (d.isVault()) return "Vault";
        List<String> names = new ArrayList<>();
        jdbc.query("SELECT COALESCE(AccessIsHeadCashier, 0), COALESCE(AccessCurrAssign, ''), CONCAT_WS(' ', AccessFName, AccessLName) FROM access WHERE AccessBR_CODE = ? AND AccessTellerNumber = ? AND COALESCE(AccessIsDisabled, 0) = 0",
            (rs) -> { if (eventsOf(rs.getInt(1) == 1, rs.getString(2)).contains(d.event())) names.add(rs.getString(3).trim()); }, branch, d.number());
        return String.join(" / ", names);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private boolean drawerExists(int branch, Drawer d) {
        return jdbc.query("SELECT AccessTellerNumber, COALESCE(AccessIsHeadCashier, 0), COALESCE(AccessCurrAssign, '') FROM access WHERE AccessBR_CODE = ? AND AccessTellerNumber = ? AND COALESCE(AccessIsDisabled, 0) = 0",
            (rs, i) -> eventsOf(rs.getInt(2) == 1, rs.getString(3)).contains(d.event()), branch, d.number()).stream().anyMatch((b) -> b);
    }

    private Person personById(int id) {
        return jdbc.query("""
                SELECT AccessID, AccessBR_CODE, COALESCE(AccessTellerNumber, 0), COALESCE(AccessIsHeadCashier, 0), COALESCE(AccessCurrAssign, ''),
                  COALESCE(AccessDepartment, 0), CONCAT_WS(' ', AccessFName, AccessLName), AccessUserName
                FROM access WHERE AccessID = ? LIMIT 1
                """,
            (rs, i) -> new Person(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4) == 1, rs.getString(5), rs.getInt(6), rs.getString(7), rs.getString(8)), id).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "The person who gave this cash no longer exists."));
    }

    private long insertHandover(Person p, int trCode, Drawer from, Drawer to, BigDecimal amount, Map<Integer, Integer> counts, String remarks, String requestKey) {
        StringBuilder json = new StringBuilder("{");
        counts.entrySet().stream().filter((e) -> e.getValue() > 0).forEach((e) -> json.append(json.length() > 1 ? "," : "").append('"').append(e.getKey()).append("\":").append(e.getValue()));
        json.append('}');
        String sql = """
                INSERT INTO ecis_cash_handover (branch_code, tr_code, from_event, from_number, to_event, to_number, amount, denominations, remarks, status, issued_by, issued_at"""
                + (requestKey == null ? "" : ", request_key") + """
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?""" + (requestKey == null ? "" : ", ?") + ")";
        List<Object> args = new java.util.ArrayList<>(List.of(p.branch(), trCode, from.event(), from.number(), to.event(), to.number(), amount, json.toString()));
        args.add(remarks);
        args.add(p.id());
        args.add(Timestamp.valueOf(LocalDateTime.now()));
        if (requestKey != null) args.add(requestKey);
        return db.insertReturningId(sql, "id", args.toArray());
    }

    /**
     * Writes one cash transfer to the ledger exactly as the old window did: a header in transsum, two entries in transdt (the giver's
     * credit and the receiver's debit, both on cash on hand), the matching lines in gltrans, and who gave and received with the
     * count by note and coin in denomsum and denomdtl. Must run inside a transaction. Returns the new control number.
     */
    private long postToLedger(Person encoder, Person fromUser, Person toUser, Person poster, Drawer from, Drawer to, BigDecimal amount,
                              Map<Integer, Integer> counts, String remarks, int trCode, long handoverId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDate day = now.toLocalDate();
        int branch = encoder.branch();
        int module = 4;
        // Control numbers run on across years for each transfer type, so take the next after the highest. The lock keeps two people from taking the same one.
        long ctl = db.nextControlNumber(branch, module, trCode, day.getYear());
        String explanation = ("Cash transfer: " + from.label() + " to " + to.label() + (remarks == null ? "" : " - " + remarks));
        explanation = explanation.substring(0, Math.min(explanation.length(), 200));

        jdbc.update("""
                INSERT INTO transsum (TR_BRCODE, TR_YEAR, TR_MODULE, TR_CODE, TR_CTLNO, TR_DOCNO, TR_DATE, TR_TIME, BATCH_NO, EXPLANATION, IS_FO, DATETIMEADDED)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                """,
            branch, day.getYear(), module, trCode, ctl, String.format("*%07d", ctl), day.toString(), now.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            day.format(DateTimeFormatter.BASIC_ISO_DATE), explanation, Timestamp.valueOf(now.withNano(0)));

        // The receiving person is recorded as the approver; the old window leaves it empty when the head cashier moves cash to or from the vault.
        Integer approver = toUser == null || toUser.id() == encoder.id() ? null : toUser.id();
        BigDecimal[] amounts = {amount.negate(), amount};
        Drawer[] side = {from, to};
        Drawer[] other = {to, from};
        for (int seq = 0; seq < 2; seq++) {
            String acct = HandoverRules.account(side[seq].event());
            jdbc.update("""
                    INSERT INTO transdt (DTTR_BRCODE, DTTR_YEAR, DTTR_MODULE, DTTR_CODE, DTTR_CTLNO, DTSEQ_NO, DTDEPT, DTACCT_CODE, DTSLC_CODE, DTSLT_CODE, DTREF_NO,
                      DTSLE_CODE, DTCTS_NUM, DTCOCI_TYPE, DTAMT, DTENCDBY, DTAPPRVBY, DTPOSTBY, DTUPD_TAG, DTSLE_CODE2, DTCTS_NUM2, DTCHECKISRELEASE, DTISTRANSIT, DTISUNDENTIFIED)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 11, 1, 1, ?, ?, 1, ?, ?, ?, ?, 1, ?, ?, 0, 0, 0)
                    """,
                branch, day.getYear(), module, trCode, ctl, seq, encoder.dept(), acct, side[seq].event(), side[seq].number(), amounts[seq],
                encoder.id(), approver, poster.id(), other[seq].event(), other[seq].number());
            jdbc.update("""
                    INSERT INTO gltrans (GLTransBR_CODE, GLTransACCT_CODE, GLTransTR_DATE, GLTransTR_CODE, GLTransDEPT, GLTransDR_AMT, GLTransCR_AMT,
                      GLTransDR_BALB4, GLTransCR_BALB4, GLTransSUM_BALB4, GLTransSYNC, GLTransLASTUPDATED)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 1, ?)
                    """,
                branch, acct, day.toString(), trCode, encoder.dept(), seq == 1 ? amount : BigDecimal.ZERO, seq == 0 ? amount.negate() : BigDecimal.ZERO, Timestamp.valueOf(now.withNano(0)));
        }

        long denomSum = db.insertReturningId("INSERT INTO denomsum (BR_CODE, TR_CODE, TR_CTLNO, FROM_USERID, TO_USERID) VALUES (?, ?, ?, ?, ?)", "ID",
            branch, trCode, ctl, fromUser == null ? null : fromUser.id(), toUser == null ? null : toUser.id());
        for (int typeId : denomValues().keySet())
            jdbc.update("INSERT INTO denomdtl (DENOMSUMID, DENOMTYPEID, QTY) VALUES (?, ?, ?)", denomSum, typeId, counts.getOrDefault(typeId, 0));

        balances.changed();
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value) VALUES (NOW(), ?, 'cash.transfer.post', 'transsum', ?, ?)",
            (toUser != null ? toUser : poster).username(), branch + "/" + day.getYear() + "/" + module + "/" + trCode + "/" + ctl,
            from.label() + " -> " + to.label() + " " + amount.toPlainString() + " (handover " + handoverId + ")");
        return ctl;
    }

    private Handover one(long id, Person viewer) {
        return jdbc.queryForObject("""
                SELECT h.id, h.tr_code, h.from_event, h.from_number, h.to_event, h.to_number, h.amount, h.status, a.AccessUserName, h.issued_at, h.remarks, h.counted, h.note, h.tr_ctlno
                FROM ecis_cash_handover h LEFT JOIN access a ON a.AccessID = h.issued_by WHERE h.id = ?
                """,
            (rs, i) -> {
                Handover base = new Handover(rs.getLong(1), rs.getInt(2), new Drawer(rs.getInt(3), rs.getInt(4)).label(), new Drawer(rs.getInt(5), rs.getInt(6)).label(), rs.getBigDecimal(7), rs.getString(8),
                    rs.getString(9), rs.getTimestamp(10).toLocalDateTime().format(DateTimeFormatter.ofPattern("HH:mm")), rs.getString(11), rs.getBigDecimal(12), rs.getString(13), null, false, false);
                long ctl = rs.getLong(14);
                return rs.wasNull() ? base : new Handover(base.id(), base.trCode(), base.from(), base.to(), base.amount(), base.status(), base.issuedBy(), base.issuedAt(),
                    base.remarks(), base.counted(), base.note(), ctl, false, false);
            }, id);
    }
}
