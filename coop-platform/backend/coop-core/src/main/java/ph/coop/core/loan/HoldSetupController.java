package ph.coop.core.loan;



import static ph.coop.core.web.ApiErrors.bad;import ph.coop.core.audit.AuditLog;import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;

/**
 * Which deposit types a co-maker's agreed amount may be held on, and which one the loan application suggests first (share capital to begin with).
 * Administrators only; each change needs a reason and is audited. A type that is switched off stops being offered for new holds, but holds already
 * placed on it stay.
 */
@RestController
@RequestMapping("/api/admin/hold-setup")
@PreAuthorize("hasRole('ADMIN')")
class HoldSetupController {

    private static final int SAVINGS = 22, SHARE_CAPITAL = 31;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final Clock clock;

    HoldSetupController(JdbcTemplate jdbc, Db db, Clock clock) {
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
    }

    record Row(int slc, int slt, String group, String name, boolean holdable, boolean isDefault, long holds) {}

    record Change(boolean holdable, String reason) {}

    record Default(int slc, int slt, String reason) {}


    private int branch(Authentication auth) {
        List<Integer> r = jdbc.queryForList("SELECT AccessBR_CODE FROM access WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0 LIMIT 1", Integer.class, auth.getName());
        if (r.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is not a staff account.");
        return r.get(0);
    }

    /** Every enabled savings and share capital type, with whether it can take a hold and how many holds sit on it now. */
    @GetMapping
    List<Row> list(Authentication auth) {
        return rows(branch(auth));
    }

    private List<Row> rows(int branch) {
        return jdbc.query("""
                SELECT t.SLTypeSLC_CODE, t.SLTypeSLT_CODE, t.SLTypeM_DESC, COALESCE(hs.holdable, 0), COALESCE(hs.is_default, 0),
                       (SELECT COUNT(*) FROM hold h WHERE h.HBR_CODE = t.SLTypeBR_CODE AND h.HSLC_CODE = t.SLTypeSLC_CODE AND h.HSLT_CODE = t.SLTypeSLT_CODE)
                FROM sltype t
                LEFT JOIN ecis_hold_setting hs ON hs.branch_code = t.SLTypeBR_CODE AND hs.slc_code = t.SLTypeSLC_CODE AND hs.slt_code = t.SLTypeSLT_CODE
                WHERE t.SLTypeBR_CODE = ? AND t.SLTypeSLC_CODE IN (?, ?) AND COALESCE(t.SLTypeIsDisabled, 0) = 0
                ORDER BY t.SLTypeSLC_CODE DESC, t.SLTypeSLT_CODE
                """, (rs, i) -> new Row(rs.getInt(1), rs.getInt(2), rs.getInt(1) == SHARE_CAPITAL ? "Share capital" : "Savings", rs.getString(3), rs.getInt(4) == 1, rs.getInt(5) == 1, rs.getLong(6)),
            branch, SAVINGS, SHARE_CAPITAL);
    }

    private Row find(int branch, int slc, int slt) {
        return rows(branch).stream().filter((r) -> r.slc() == slc && r.slt() == slt).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such savings or share capital type."));
    }

    private static String reason(String r) {
        if (r == null || r.isBlank()) throw bad("Give the reason for this change.");
        String t = r.trim();
        return t.length() > 255 ? t.substring(0, 255) : t;
    }

    private void audit(Authentication auth, String action, int branch, int slc, int slt, String prev, String next, String why) {
        AuditLog.write(jdbc, auth.getName(), action, "hold_setting", branch + "/" + slc + "/" + slt, prev, next, why);
    }

    @PutMapping("/{slc}/{slt}")
    @Transactional
    Row setHoldable(@PathVariable int slc, @PathVariable int slt, @RequestBody Change in, Authentication auth) {
        int branch = branch(auth);
        Row now = find(branch, slc, slt);
        String why = reason(in == null ? null : in.reason());
        if (now.holdable() == in.holdable()) return now;
        if (!in.holdable() && now.isDefault()) throw new ResponseStatusException(HttpStatus.CONFLICT, now.name() + " is the type suggested first. Choose another type to suggest first, then switch this one off.");
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("branch_code", branch); key.put("slc_code", slc); key.put("slt_code", slt);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("holdable", in.holdable() ? 1 : 0);
        values.put("updated_at", Timestamp.valueOf(LocalDateTime.now(clock).withNano(0)));
        values.put("updated_by", auth.getName());
        db.upsert("ecis_hold_setting", key, values);
        audit(auth, in.holdable() ? "hold.type.allow" : "hold.type.forbid", branch, slc, slt, now.holdable() ? "holdable" : "not holdable", in.holdable() ? "holdable" : "not holdable", why);
        return find(branch, slc, slt);
    }

    /** Makes one type the one suggested first. It must be allowed to take holds. */
    @PutMapping("/default")
    @Transactional
    Row setDefault(@RequestBody Default in, Authentication auth) {
        int branch = branch(auth);
        Row target = find(branch, in.slc(), in.slt());
        String why = reason(in.reason());
        if (!target.holdable()) throw bad(target.name() + " cannot take holds yet. Allow it first.");
        if (target.isDefault()) return target;
        List<Row> before = rows(branch).stream().filter(Row::isDefault).toList();
        jdbc.update("UPDATE ecis_hold_setting SET is_default = 0 WHERE branch_code = ? AND is_default = 1", branch);
        jdbc.update("UPDATE ecis_hold_setting SET is_default = 1, updated_at = ?, updated_by = ? WHERE branch_code = ? AND slc_code = ? AND slt_code = ?",
            Timestamp.valueOf(LocalDateTime.now(clock).withNano(0)), auth.getName(), branch, in.slc(), in.slt());
        audit(auth, "hold.type.default", branch, in.slc(), in.slt(), before.isEmpty() ? null : before.get(0).name(), target.name(), why);
        return find(branch, in.slc(), in.slt());
    }
}
