package ph.coop.core.frontoffice;

import ph.coop.core.connection.DatabaseChanges;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.businessday.BusinessDay;
import ph.coop.core.businessday.DrawerOpening;
import ph.coop.core.db.Db;
import ph.coop.core.frontoffice.HandoverRules.Drawer;
import ph.coop.core.frontoffice.MemberLedgerPoster.Person;

/**
 * Opening a drawer at the start of the business day ({@link DrawerOpening}): a blind count, like the end-of-day count (the ledger's figure is shown only
 * after the count is submitted). A difference needs a reason and is recorded; the drawer opens either way, since the count is what is really there.
 * One opening per drawer per business day; it cannot be changed afterwards (a later count is the end-of-day one).
 */
@RestController
@RequestMapping("/api/front-office/drawer-open")
class DrawerOpenController {

    record DrawerState(int event, int number, String label, boolean opened, String openedAt, BigDecimal counted, BigDecimal difference, String reason) {}

    record Mine(boolean required, boolean policyOn, boolean available, String businessDate, List<DrawerState> drawers) {}

    record OpenIn(int event, Map<Integer, Integer> counts, String reason) {}

    record Opened(String label, BigDecimal expected, BigDecimal counted, BigDecimal difference, String openedAt) {}

    record Row(String label, String person, String openedAt, BigDecimal expected, BigDecimal counted, BigDecimal difference, String reason) {}

    record PolicyIn(Boolean on, String reason) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final Clock clock;
    private final MemberLedgerPoster poster;
    private final DrawerBalances balances;
    private final BusinessDay businessDay;
    private final DrawerOpening opening;

    DrawerOpenController(JdbcTemplate jdbc, Db db, Clock clock, MemberLedgerPoster poster, DrawerBalances balances, BusinessDay businessDay, DrawerOpening opening) {
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
        this.poster = poster;
        this.balances = balances;
        this.businessDay = businessDay;
        this.opening = opening;
    }

    private static List<Drawer> drawersOf(Person p) {
        if (p.number() <= 0) return List.of();
        return HandoverController.eventsOf(p.head(), p.assignment()).stream().map((e) -> new Drawer(e, p.number())).toList();
    }

    @GetMapping
    Mine mine(Authentication auth) {
        Person p = poster.person(auth.getName());
        LocalDate day = businessDay.today(p.branch());
        List<DrawerState> out = new ArrayList<>();
        for (Drawer d : drawersOf(p)) out.add(state(p.branch(), day, d));
        return new Mine(opening.required(), opening.policyOn(), opening.available(), day.toString(), out);
    }

    private DrawerState state(int branch, LocalDate day, Drawer d) {
        if (!opening.available()) return new DrawerState(d.event(), d.number(), d.label(), false, null, null, null, null);
        List<DrawerState> r = jdbc.query("""
                SELECT opened_at, counted, difference, reason FROM ecis_drawer_open WHERE branch_code = ? AND business_date = ? AND event = ? AND drawer_number = ?
                """, (rs, i) -> new DrawerState(d.event(), d.number(), d.label(), true, rs.getTimestamp(1).toLocalDateTime().format(DateTimeFormatter.ofPattern("HH:mm")),
                rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getString(4)), branch, java.sql.Date.valueOf(day), d.event(), d.number());
        return r.isEmpty() ? new DrawerState(d.event(), d.number(), d.label(), false, null, null, null, null) : r.get(0);
    }

    /** Opens one of the person's drawers for the business day with their count. */
    @PostMapping
    Opened open(@RequestBody OpenIn in, Authentication auth) {
        Person p = poster.person(auth.getName());
        if (!opening.available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, DatabaseChanges.notSetUp("opening drawers"));
        Drawer d = new Drawer(in.event(), p.number());
        if (!drawersOf(p).contains(d)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That cash drawer is not yours, so you cannot use it. Choose your own drawer, or ask the head cashier which drawer you are assigned.");
        LocalDate day = businessDay.postingDate(p.branch());
        if (opening.isOpen(p.branch(), day, d.event(), d.number())) throw new ResponseStatusException(HttpStatus.CONFLICT, d.label() + " is already open for " + day + ".");
        Map<Integer, BigDecimal> faces = new LinkedHashMap<>();
        jdbc.query("SELECT ID, AMOUNT FROM denomtype ORDER BY ID", (rs) -> { faces.put(rs.getInt(1), rs.getBigDecimal(2)); });
        Map<Integer, Integer> counts = in.counts() == null ? Map.of() : in.counts();
        for (Map.Entry<Integer, Integer> e : counts.entrySet())
            if (!faces.containsKey(e.getKey()) || e.getValue() == null || e.getValue() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The count includes a note or coin value that is not used. Check the denominations and enter only the notes and coins listed.");
        BigDecimal counted = HandoverRules.total(counts, faces).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal expected = balances.of(p.branch(), d).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal difference = DrawerRules.variance(expected, counted);
        String reason = in.reason() == null || in.reason().isBlank() ? null : in.reason().trim().substring(0, Math.min(in.reason().trim().length(), 250));
        if (difference.signum() != 0 && (reason == null || reason.length() < 5))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The count does not match the ledger. Count again, or give a reason for the difference.");
        StringBuilder json = new StringBuilder("{");
        counts.entrySet().stream().filter((e) -> e.getValue() > 0).forEach((e) -> json.append(json.length() > 1 ? "," : "").append('"').append(e.getKey()).append("\":").append(e.getValue()));
        json.append('}');
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        try {
            jdbc.update("""
                    INSERT INTO ecis_drawer_open (branch_code, business_date, event, drawer_number, access_id, username, expected, counted, difference, counts, reason, opened_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, p.branch(), java.sql.Date.valueOf(day), d.event(), d.number(), p.id(), auth.getName(), expected, counted, difference, json.toString(), reason, Timestamp.valueOf(now));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, d.label() + " is already open for " + day + ".");
        }
        AuditLog.write(jdbc, auth.getName(), "drawer.open", "drawer", d.label() + " " + day, null,
            "expected " + expected.toPlainString() + ", counted " + counted.toPlainString() + ", difference " + difference.toPlainString(), reason);
        return new Opened(d.label(), expected, counted, difference, now.format(DateTimeFormatter.ofPattern("HH:mm")));
    }

    /** Every drawer opened on the business day, for the head cashier and administrators. */
    @GetMapping("/day")
    List<Row> openedToday(Authentication auth) {
        Person p = poster.person(auth.getName());
        if (!p.head() && !admin(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the head cashier or an administrator sees every drawer.");
        if (!opening.available()) return List.of();
        LocalDate day = businessDay.today(p.branch());
        return jdbc.query("""
                SELECT o.event, o.drawer_number, COALESCE(NULLIF(TRIM(CONCAT_WS(' ', a.AccessFName, a.AccessLName)), ''), o.username), o.opened_at, o.expected, o.counted, o.difference, o.reason
                FROM ecis_drawer_open o LEFT JOIN access a ON a.AccessID = o.access_id AND a.AccessBR_CODE = o.branch_code
                WHERE o.branch_code = ? AND o.business_date = ? ORDER BY o.opened_at
                """, (rs, i) -> new Row(new Drawer(rs.getInt(1), rs.getInt(2)).label(), rs.getString(3), rs.getTimestamp(4).toLocalDateTime().format(DateTimeFormatter.ofPattern("HH:mm")),
                rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getString(8)), p.branch(), java.sql.Date.valueOf(day));
    }

    /** Switches the rule on or off (administrators, with a reason; audited). */
    @PutMapping("/policy")
    Mine policy(@RequestBody PolicyIn in, Authentication auth) {
        if (!admin(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an administrator changes this rule.");
        if (in == null || in.on() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say whether drawers must be opened.");
        if (in.reason() == null || in.reason().trim().length() < 3) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give a reason for the change.");
        if (!db.tableExists("ecis_setting")) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, DatabaseChanges.notSetUp("drawer settings"));
        boolean before = opening.policyOn();
        db.upsert("ecis_setting", Map.of("setting_key", DrawerOpening.POLICY), new LinkedHashMap<>(Map.of("setting_value", Boolean.toString(in.on()), "updated_by", auth.getName())));
        jdbc.update("UPDATE ecis_setting SET updated_at = NOW() WHERE setting_key = ?", DrawerOpening.POLICY);
        AuditLog.write(jdbc, auth.getName(), "drawer.open.policy", "setting", DrawerOpening.POLICY, Boolean.toString(before), Boolean.toString(in.on()), in.reason().trim());
        try {
            return mine(auth);
        } catch (ResponseStatusException e) {
            // an administrator who is not a cashier: the rule is changed, there is no drawer to show
            return new Mine(opening.required(), opening.policyOn(), opening.available(), businessDay.today(1).toString(), List.of());
        }
    }

    private static boolean admin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch((a) -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
