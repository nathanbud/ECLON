package ph.coop.core.member;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.db.Db;

/**
 * Administrator-controlled membership policy, kept in {@code ecis_setting}. Every change is audited with a reason. Two switches, both off by default: a returning member
 * must pay share capital again, and withdrawals need a good KYC record ({@link KycGate}).
 */
@RestController
public class MembershipPolicyController {

    static final String RETURNING_PAYS_SHARE = "membership.returning_pays_share_capital";

    public record Policy(boolean returningPaysShareCapital, boolean kycBlocksWithdrawals) {}

    /** A switch left null is not changed. */
    public record Change(Boolean returningPaysShareCapital, Boolean kycBlocksWithdrawals, String reason) {
        public Change(boolean returningPaysShareCapital, String reason) {
            this(returningPaysShareCapital, null, reason);
        }
    }

    private final JdbcTemplate jdbc;
    private final Db db;
    private final KycGate kyc;

    MembershipPolicyController(JdbcTemplate jdbc, Db db, KycGate kyc) {
        this.jdbc = jdbc;
        this.db = db;
        this.kyc = kyc;
    }

    /** Off unless an administrator switched it on (or the table does not exist yet). */
    public boolean returningPaysShareCapital() {
        return flag(RETURNING_PAYS_SHARE);
    }

    private boolean flag(String key) {
        if (!db.tableExists("ecis_setting")) return false;
        List<String> v = jdbc.queryForList("SELECT setting_value FROM ecis_setting WHERE setting_key = ?", String.class, key);
        return !v.isEmpty() && v.get(0).equals("true");
    }

    @GetMapping("/api/membership-policy")
    public Policy get() {
        return new Policy(returningPaysShareCapital(), kyc.blocksWithdrawals());
    }

    @PutMapping("/api/admin/membership-policy")
    @PreAuthorize("hasRole('ADMIN')")
    public Policy set(@RequestBody Change c, Authentication auth) {
        if (!db.allTablesExist("ecis_setting", "ecis_audit_log")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The settings table is not installed (V038).");
        if (c.reason() == null || c.reason().trim().length() < 3) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give a reason for the change.");
        if (Boolean.TRUE.equals(c.kycBlocksWithdrawals()) && !db.tableExists("ecis_member_kyc"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The KYC table is not installed on this database, so withdrawals cannot depend on it.");
        if (c.returningPaysShareCapital() != null) change(RETURNING_PAYS_SHARE, flag(RETURNING_PAYS_SHARE), c.returningPaysShareCapital(), c.reason(), auth);
        if (c.kycBlocksWithdrawals() != null) change(KycGate.BLOCK_WITHDRAWALS, flag(KycGate.BLOCK_WITHDRAWALS), c.kycBlocksWithdrawals(), c.reason(), auth);
        return get();
    }

    private void change(String key, boolean before, boolean after, String reason, Authentication auth) {
        db.upsert("ecis_setting", Map.of("setting_key", key), new LinkedHashMap<>(Map.of("setting_value", Boolean.toString(after), "updated_by", auth.getName())));
        jdbc.update("UPDATE ecis_setting SET updated_at = NOW() WHERE setting_key = ?", key);
        AuditLog.write(jdbc, null, auth.getName(), "membership.policy.changed", "setting", key, Boolean.toString(before), Boolean.toString(after), reason.trim());
    }
}
