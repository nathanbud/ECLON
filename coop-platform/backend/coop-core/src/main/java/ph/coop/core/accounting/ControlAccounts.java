package ph.coop.core.accounting;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.connection.OnConnectionSwitch;

/**
 * The branch's control accounts (balance-sheet accounts that members' accounts post their principal to, glcontrol event 11), grouped as the SL
 * reconciliation groups them, and the journal rule over them ({@link ControlAccountRules}). Read once per branch and kept five minutes, so a link
 * changed in Settings > Products applies soon after; forgotten when the database is switched.
 */
@Component
public class ControlAccounts implements OnConnectionSwitch {

    private static final long KEEP_MS = 5 * 60_000L;

    private record Kept(SlReconciliation.Groups groups, Map<String, String> names, long at) {}

    private final JdbcTemplate jdbc;
    private final Map<Integer, Kept> kept = new ConcurrentHashMap<>();

    public ControlAccounts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void connectionSwitched() {
        kept.clear();
    }

    private Kept load(int branch) {
        Kept k = kept.get(branch);
        if (k != null && System.currentTimeMillis() - k.at() < KEEP_MS) return k;
        List<SlReconciliation.Link> links = jdbc.query("""
                SELECT DISTINCT g.GLControlSLC_CODE, g.GLControlSLT_CODE, g.GLControlACCT_CODE FROM glcontrol g
                JOIN coa c ON c.COABR_CODE = g.GLControlBR_CODE AND c.COAID = g.GLControlACCT_CODE
                WHERE g.GLControlBR_CODE = ? AND g.GLControlSLE_CODE = 11 AND c.COAacct_type IN (1, 2, 3)
                ORDER BY g.GLControlSLC_CODE, g.GLControlSLT_CODE, g.GLControlACCT_CODE
                """, (rs, i) -> new SlReconciliation.Link(rs.getInt(1), rs.getInt(2), rs.getString(3).trim()), branch);
        Map<String, String> typeNames = new HashMap<>();
        jdbc.query("SELECT SLTypeSLC_CODE, SLTypeSLT_CODE, SLTypeM_DESC FROM sltype WHERE SLTypeBR_CODE = ?",
            (rs) -> { typeNames.put(rs.getInt(1) + "/" + rs.getInt(2), rs.getString(3) == null ? "" : rs.getString(3).trim()); }, branch);
        Map<Integer, String> classNames = new HashMap<>();
        jdbc.query("SELECT SLClassID, SLClassDesc FROM slclass", (rs) -> { classNames.put(rs.getInt(1), rs.getString(2) == null ? "" : rs.getString(2).trim()); });
        Map<String, String> names = new HashMap<>();
        jdbc.query("SELECT COAID, COADesc FROM coa WHERE COABR_CODE = ? AND COAID IN (SELECT GLControlACCT_CODE FROM glcontrol WHERE GLControlBR_CODE = ? AND GLControlSLE_CODE = 11)",
            (rs) -> { names.put(rs.getString(1).trim(), rs.getString(2) == null ? "" : rs.getString(2).trim()); }, branch, branch);
        k = new Kept(SlReconciliation.groups(links, typeNames, classNames), Map.copyOf(names), System.currentTimeMillis());
        kept.put(branch, k);
        return k;
    }

    /** Whether an account is a control account of the branch. */
    public boolean isControl(int branch, String account) {
        return account != null && load(branch).groups().groupOf().containsKey(account.trim());
    }

    /** Why a journal with these lines may not be posted (it changes a control account's balance), or null when it may. */
    public String journalProblem(int branch, List<ControlAccountRules.Effect> lines) {
        Kept k = load(branch);
        List<ControlAccountRules.Effect> trimmed = lines.stream().map((e) -> new ControlAccountRules.Effect(e.account().trim(), e.amount())).toList();
        return ControlAccountRules.problem(ControlAccountRules.unbalanced(trimmed, k.groups().groupOf(), k.groups().titles(), k.names()));
    }
}
