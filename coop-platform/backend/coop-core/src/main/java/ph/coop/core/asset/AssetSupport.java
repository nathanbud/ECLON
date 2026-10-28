package ph.coop.core.asset;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Who may do what with fixed assets: custodians and accountants keep the register, only accountants (and administrators) set categories, run depreciation and decide transfers and disposals. */
@Component
class AssetSupport {

    private final JdbcTemplate jdbc;

    AssetSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static boolean has(Authentication auth, String role) {
        return auth.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals("ROLE_" + role));
    }

    void requireCustodian(Authentication auth) {
        if (!has(auth, "ADMIN") && !has(auth, "ACCOUNTANT") && !has(auth, "ASSET_CUSTODIAN")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You need the Asset Custodian or Accountant role for this.");
    }

    void requireAccountant(Authentication auth) {
        if (!has(auth, "ADMIN") && !has(auth, "ACCOUNTANT")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You need the Accountant role for this.");
    }

    /** True when {@code code} is an enabled detail account of the chart. */
    boolean usableAccount(int branch, String code) {
        List<Object[]> a = jdbc.query("SELECT COAacct_tag, COALESCE(COAisdisabled, 0) FROM coa WHERE COABR_CODE = ? AND COAID = ?", (rs, i) -> new Object[] {rs.getString(1), rs.getInt(2)}, branch, code);
        return !a.isEmpty() && !"S".equals(a.get(0)[0]) && (Integer) a.get(0)[1] == 0;
    }

    void requireUsableAccount(int branch, String code, String what) {
        if (code == null || code.isBlank() || !usableAccount(branch, code.trim())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, what + ": " + (code == null ? "" : code) + " is not an enabled detail account of the chart.");
    }
}
