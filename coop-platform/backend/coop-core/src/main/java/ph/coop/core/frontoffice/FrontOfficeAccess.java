package ph.coop.core.frontoffice;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * What the front office window covers, and what the signed-in person may do in it. The legacy setup decides: the
 * "can access front office" flag on {@code access}, and the find/new flags per transaction type on {@code access_trans}.
 */
@Component
class FrontOfficeAccess {

    /** One tab of the window and the transaction types it handles (the Summary tab has none of its own). */
    record TabDef(String key, String label, List<Integer> types) {}

    /** The tabs of the old window, in order, and the legacy transaction type codes behind each. */
    static final List<TabDef> TABS = List.of(
        new TabDef("summary", "Summary", List.of()),
        new TabDef("receipt", "Receipt", List.of(10, 11, 12, 13, 14, 15, 87)),
        new TabDef("disburse", "Disburse", List.of(20, 21, 22, 24)),
        new TabDef("withdrawal", "Withdrawal", List.of(23)),
        new TabDef("encashment", "Encashment", List.of(25)),
        new TabDef("otc", "OTC Voucher", List.of(30)),
        new TabDef("handover", "Cash Handover", List.of(31, 32)),
        new TabDef("bank", "Bank Trans", List.of(33, 35)),
        new TabDef("passbook", "Passbook", List.of()),
        new TabDef("sales", "Sales Receipt", List.of(41)),
        new TabDef("salesReturn", "Sales Return", List.of(42)));

    /** Every type the window handles. */
    static final Set<Integer> ALL_TYPES = TABS.stream().flatMap((t) -> t.types().stream()).collect(Collectors.toUnmodifiableSet());

    /** What one person may do. `all` is true for the bootstrap administrator, who is not in the legacy user list. */
    record Perms(boolean access, boolean all, int branch, int userId, Map<Integer, boolean[]> byType) {
        boolean canFind(int type) { return access && (all || (byType.containsKey(type) && byType.get(type)[0])); }
        boolean canNew(int type) { return access && (all || (byType.containsKey(type) && byType.get(type)[1])); }
    }

    private final JdbcTemplate jdbc;

    FrontOfficeAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Perms of(Authentication auth) {
        List<int[]> rows = jdbc.query("SELECT AccessBR_CODE, AccessID, COALESCE(AccessCanAccessFrontOffice, 0) FROM access WHERE AccessUserName = ? LIMIT 1",
            (rs, i) -> new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3)}, auth.getName());
        if (rows.isEmpty()) return new Perms(true, true, 1, 0, Map.of());
        int[] r = rows.get(0);
        Map<Integer, boolean[]> byType = new HashMap<>();
        jdbc.query("SELECT TR_CODE, CAN_FIND, CAN_NEW FROM access_trans WHERE BR_CODE = ? AND USERID = ?",
            (rs) -> { byType.put(rs.getInt(1), new boolean[] {rs.getInt(2) == 1, rs.getInt(3) == 1}); }, r[0], r[1]);
        return new Perms(r[2] == 1, false, r[0], r[1], byType);
    }

    /** The types in this list that the person may look up. */
    List<Integer> findable(Perms p, List<Integer> wanted) {
        return (wanted == null || wanted.isEmpty() ? List.copyOf(ALL_TYPES) : wanted).stream().filter(ALL_TYPES::contains).filter(p::canFind).toList();
    }

    void requireFind(Perms p, int type) {
        if (!ALL_TYPES.contains(type) || !p.canFind(type)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot look up this type of transaction.");
    }

    void requireNew(Perms p, int type) {
        if (!ALL_TYPES.contains(type) || !p.canNew(type)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot create this type of transaction.");
    }

    Map<Integer, String> typeNames() {
        Map<Integer, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT TransTypeID, TransTypeDesc FROM transtype ORDER BY TransTypeID", (rs) -> { out.put(rs.getInt(1), rs.getString(2)); });
        return out;
    }
}
