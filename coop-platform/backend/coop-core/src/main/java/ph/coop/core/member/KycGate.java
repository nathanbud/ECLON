package ph.coop.core.member;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.db.Db;

/**
 * The KYC check the money screens ask before paying a member: a member's KYC status, and whether the cooperative requires KYC for withdrawals (the policy
 * {@value #BLOCK_WITHDRAWALS} in {@code ecis_setting}, off unless an administrator switches it on). The rule itself is {@link KycRules#withdrawalProblem}.
 * Where the KYC table is not installed (the MySQL migration V055 not applied) nothing is refused, since no member could pass.
 */
@Component
public class KycGate {

    public static final String BLOCK_WITHDRAWALS = "kyc.block_withdrawals";

    private final JdbcTemplate jdbc;
    private final Db db;

    KycGate(JdbcTemplate jdbc, Db db) {
        this.jdbc = jdbc;
        this.db = db;
    }

    /** Whether withdrawals are refused for a member without a good KYC record. */
    public boolean blocksWithdrawals() {
        if (!db.allTablesExist("ecis_setting", "ecis_member_kyc")) return false;
        List<String> v = jdbc.queryForList("SELECT setting_value FROM ecis_setting WHERE setting_key = ?", String.class, BLOCK_WITHDRAWALS);
        return !v.isEmpty() && "true".equals(v.get(0));
    }

    public KycRules.Status status(int branchCode, int clientId, LocalDate today) {
        if (!db.tableExists("ecis_member_kyc")) return KycRules.Status.NONE;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id_type, id_number, id_expiry, source_of_funds, risk, next_review, pep, pep_note FROM ecis_member_kyc "
            + "WHERE branch_code = ? AND client_id = ?", branchCode, clientId);
        if (rows.isEmpty()) return KycRules.Status.NONE;
        Map<String, Object> r = rows.get(0);
        return KycRules.status(new KycRules.Record((String) r.get("id_type"), (String) r.get("id_number"), date(r.get("id_expiry")), (String) r.get("source_of_funds"),
            (String) r.get("risk"), date(r.get("next_review")), ((Number) r.get("pep")).intValue() != 0, (String) r.get("pep_note")), today);
    }

    /** The reason a withdrawal for this member must wait, or null when it may go ahead (always null while the policy is off). */
    public String withdrawalProblem(int branchCode, int clientId, LocalDate today) {
        if (!blocksWithdrawals()) return null;
        return KycRules.withdrawalProblem(status(branchCode, clientId, today));
    }

    private static LocalDate date(Object o) {
        if (o == null) return null;
        if (o instanceof Date d) return d.toLocalDate();
        if (o instanceof LocalDate d) return d;
        return LocalDate.parse(o.toString().substring(0, 10));
    }
}
