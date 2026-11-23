package ph.coop.core.member;

import ph.coop.core.connection.DatabaseChanges;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.db.Db;
import ph.coop.core.member.LifecycleRules.Acct;
import ph.coop.core.member.SlControlRules.Switch;
import ph.coop.core.member.SlControlRules.Type;

/**
 * SL Controls (Settings > Products > Account opening (SL Controls), administrators): which accounts open by themselves, empty, when a Membership Officer approves a new member or reactivates
 * one, per member type. Kept in {@code ecis_sl_control} (PostgreSQL V031, MySQL V068), which starts with what the application always opened. Every change needs a reason and
 * is audited as {@code sl.control.changed}. {@link MemberLifecycleController} asks {@link #accountsFor}; only switches the cooperative turned on count, with no built-in list behind them.
 */
@RestController
public class SlControlController {

    static final String TABLE = "ecis_sl_control";

    /** {@code missing}: a switch is on for this type but the type is not set up on this database (left from another cooperative's list); it can only be switched off. */
    public record TypeRow(int slc, int slt, String name, BigDecimal minBalance, BigDecimal interestRate, boolean disabled, Map<Integer, Boolean> on, boolean missing) {}

    public record ClassGroup(int slc, String name, String note, List<TypeRow> types) {}

    public record MemberType(int code, String name) {}

    public record Controls(int branch, boolean installed, List<MemberType> memberTypes, List<ClassGroup> classes, String lastChangedBy, LocalDateTime lastChangedAt) {}

    public record Change(int clientType, int slc, int slt, boolean on) {}

    public record Save(List<Change> changes, String reason) {}

    private static final Map<Integer, String> CLASS_NOTES = Map.of(
        SlControlRules.SAVINGS, "Savings deposit accounts. The minimum balance is recorded on the account; the first deposit is taken at the teller.",
        SlControlRules.FUNDS, "Member funds (mutual benefit, hospitalization and the like) held for the member.",
        SlControlRules.SHARE_CAPITAL, "Share capital. Every member type keeps one, since membership is subscribing to the cooperative's capital.");

    private final JdbcTemplate jdbc;
    private final Db db;
    private final Clock clock;
    private final MemberDefaults defaults;

    SlControlController(JdbcTemplate jdbc, Db db, Clock clock, MemberDefaults defaults) {
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
        this.defaults = defaults;
    }

    /** The accounts that open for a member of this type in this branch: the switches that are on, of types still in use; the built-in list where the table is not installed. */
    public List<Acct> accountsFor(int branch, int clientType) {
        // only what this cooperative switched on in Account opening (SL Controls); there is no built-in list to fall back on
        if (!db.tableExists(TABLE)) throw new ResponseStatusException(HttpStatus.CONFLICT, DatabaseChanges.notSetUp("Account opening (SL Controls), which says which accounts a new member gets,"));
        // exactly what this cooperative chose: a member type with nothing chosen opens nothing (registration then says to choose)
        List<Switch> switches = switches(branch);
        Set<String> disabled = new HashSet<>();
        for (Type t : types(branch)) if (t.disabled()) disabled.add(t.slc() + "/" + t.slt());
        return SlControlRules.accountsFor(clientType, switches, disabled).stream().filter((a) -> features == null || features.classOn(a.slc())).toList();
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ph.coop.core.feature.Features features;

    /** The ledger classes of the accounts that open for a member of this type in this branch (22 savings, 29 funds, 31 share capital). */
    public List<Integer> classesFor(int branch, int clientType) {
        return accountsFor(branch, clientType).stream().map(Acct::slc).distinct().toList();
    }

    private List<Switch> switches(int branch) {
        return jdbc.query("SELECT client_type, slc_code, slt_code, auto_open FROM " + TABLE + " WHERE branch_code = ? ORDER BY client_type, slc_code, slt_code",
            (rs, i) -> new Switch(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getBoolean(4)), branch);
    }

    /** The account types of the classes that can open with a membership, as set up in the branch. */
    private List<Type> types(int branch) {
        return jdbc.query("SELECT SLTypeSLC_CODE, SLTypeSLT_CODE, SLTypeM_DESC, COALESCE(SLTypeIsDisabled, 0) FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE IN (?, ?, ?)"
                + " ORDER BY SLTypeSLC_CODE, SLTypeSLT_CODE",
            (rs, i) -> new Type(rs.getInt(1), rs.getInt(2), rs.getString(3) == null ? "Type " + rs.getInt(2) : rs.getString(3).trim(), rs.getInt(4) != 0),
            branch, SlControlRules.SAVINGS, SlControlRules.FUNDS, SlControlRules.SHARE_CAPITAL);
    }

    /** The administrator's own branch (the bootstrap administrator works in branch 1). */
    private int branchOf(Authentication auth) {
        List<Integer> b = jdbc.queryForList("SELECT AccessBR_CODE FROM access WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0 ORDER BY AccessID LIMIT 1", Integer.class, auth.getName());
        return b.isEmpty() || b.get(0) == null ? 1 : b.get(0);
    }

    private Map<Integer, String> memberTypes() {
        Map<Integer, String> out = new LinkedHashMap<>();
        out.putAll(defaults.types());
        return out;
    }

    @GetMapping("/api/admin/sl-controls")
    @PreAuthorize("hasRole('ADMIN')")
    public Controls get(Authentication auth) {
        int branch = branchOf(auth);
        boolean installed = db.tableExists(TABLE);
        Map<Integer, String> members = memberTypes();
        // what opens now for each member type (the built-in list where nothing is stored), shown as the switches
        Map<Integer, Set<String>> opens = new LinkedHashMap<>();
        for (int m : members.keySet()) {
            Set<String> s = new HashSet<>();
            List<Acct> accts = installed ? onOnly(branch, m) : List.of();
            for (Acct a : accts) s.add(a.slc() + "/" + a.slt());
            opens.put(m, s);
        }
        Map<Integer, String> classNames = new LinkedHashMap<>();
        jdbc.query("SELECT SLClassID, SLClassDesc FROM slclass", (rs) -> { classNames.put(rs.getInt(1), rs.getString(2)); });
        List<ClassGroup> groups = new ArrayList<>();
        List<Type> types = types(branch);
        Map<String, BigDecimal[]> amounts = new LinkedHashMap<>();
        jdbc.query("SELECT SLTypeSLC_CODE, SLTypeSLT_CODE, SLTypeMIN_BAL, SLTypeINT_RATE FROM sltype WHERE SLTypeBR_CODE = ?",
            (rs) -> { amounts.put(rs.getInt(1) + "/" + rs.getInt(2), new BigDecimal[] {rs.getBigDecimal(3), rs.getBigDecimal(4)}); }, branch);
        for (int slc : List.of(SlControlRules.SAVINGS, SlControlRules.FUNDS, SlControlRules.SHARE_CAPITAL)) {
            if (features != null && !features.classOn(slc)) continue; // an area this cooperative does not use
            List<TypeRow> rows = new ArrayList<>();
            for (Type t : types) {
                if (t.slc() != slc) continue;
                Map<Integer, Boolean> on = new LinkedHashMap<>();
                for (int m : members.keySet()) on.put(m, opens.get(m).contains(t.slc() + "/" + t.slt()));
                BigDecimal[] a = amounts.getOrDefault(t.slc() + "/" + t.slt(), new BigDecimal[2]);
                rows.add(new TypeRow(t.slc(), t.slt(), t.name(), a[0], a[1], t.disabled(), on, false));
            }
            // switches on for a type of this class that is not set up here: shown so they can be switched off, else registration is refused
            java.util.TreeSet<Integer> stale = new java.util.TreeSet<>();
            for (Map.Entry<Integer, Set<String>> e : opens.entrySet()) {
                for (String k : e.getValue()) {
                    String[] p = k.split("/");
                    int c = Integer.parseInt(p[0]), t = Integer.parseInt(p[1]);
                    if (c == slc && types.stream().noneMatch((x) -> x.slc() == c && x.slt() == t)) stale.add(t);
                }
            }
            for (int t : stale) {
                Map<Integer, Boolean> on = new LinkedHashMap<>();
                for (int m : members.keySet()) on.put(m, opens.get(m).contains(slc + "/" + t));
                rows.add(new TypeRow(slc, t, "Type " + slc + "/" + t + " (not set up)", null, null, true, on, true));
            }
            groups.add(new ClassGroup(slc, classNames.getOrDefault(slc, "Class " + slc), CLASS_NOTES.get(slc), rows));
        }
        String by = null;
        LocalDateTime at = null;
        if (installed) {
            List<Map<String, Object>> last = jdbc.queryForList("SELECT updated_by, updated_at FROM " + TABLE + " WHERE branch_code = ? ORDER BY updated_at DESC LIMIT 1", branch);
            if (!last.isEmpty()) {
                by = (String) last.get(0).get("updated_by");
                Object t = last.get(0).get("updated_at");
                at = t instanceof Timestamp ts ? ts.toLocalDateTime() : t instanceof LocalDateTime l ? l : null;
            }
        }
        List<MemberType> mt = members.entrySet().stream().map((e) -> new MemberType(e.getKey(), e.getValue())).toList();
        return new Controls(branch, installed, mt, groups, by, at);
    }

    private List<Acct> onOnly(int branch, int clientType) {
        return SlControlRules.accountsFor(clientType, switches(branch), Set.of());
    }

    @PutMapping("/api/admin/sl-controls")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Controls save(@RequestBody Save s, Authentication auth) {
        if (!db.allTablesExist(TABLE, "ecis_audit_log")) throw new ResponseStatusException(HttpStatus.CONFLICT, DatabaseChanges.notSetUp("SL Controls"));
        int branch = branchOf(auth);
        List<Switch> current = switches(branch);
        List<Switch> changes = s.changes() == null ? List.of() : s.changes().stream().map((c) -> new Switch(c.clientType(), c.slc(), c.slt(), c.on())).toList();
        List<Type> types = types(branch);
        List<String> problems = SlControlRules.problems(changes, current, types, memberTypes(), s.reason());
        if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", problems));

        Timestamp now = Timestamp.valueOf(LocalDateTime.now(clock));
        for (Switch sw : SlControlRules.merged(current, changes)) {
            boolean stored = jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE + " WHERE branch_code = ? AND client_type = ? AND slc_code = ? AND slt_code = ?", Integer.class,
                branch, sw.clientType(), sw.slc(), sw.slt()) > 0;
            Switch was = current.stream().filter((x) -> x.clientType() == sw.clientType() && x.slc() == sw.slc() && x.slt() == sw.slt()).findFirst().orElse(null);
            boolean before = was != null && was.on();
            boolean changed = changes.contains(sw) && before != sw.on();
            if (!stored) {
                jdbc.update("INSERT INTO " + TABLE + " (branch_code, client_type, slc_code, slt_code, auto_open, updated_by, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    branch, sw.clientType(), sw.slc(), sw.slt(), sw.on(), changed ? auth.getName() : "system", now);
            } else if (changed) {
                jdbc.update("UPDATE " + TABLE + " SET auto_open = ?, updated_by = ?, updated_at = ? WHERE branch_code = ? AND client_type = ? AND slc_code = ? AND slt_code = ?",
                    sw.on(), auth.getName(), now, branch, sw.clientType(), sw.slc(), sw.slt());
            }
            if (changed) {
                String name = types.stream().filter((t) -> t.slc() == sw.slc() && t.slt() == sw.slt()).map(Type::name).findFirst().orElse(sw.slc() + "/" + sw.slt());
                AuditLog.write(jdbc, auth.getName(), "sl.control.changed", "sl-control", branch + "/" + sw.clientType() + "/" + sw.slc() + "/" + sw.slt(),
                    (before ? "opens" : "does not open") + " for " + defaults.typeName(sw.clientType()),
                    name + " " + (sw.on() ? "opens" : "does not open") + " for " + defaults.typeName(sw.clientType()) + " members", s.reason().trim());
            }
        }
        return get(auth);
    }
}
