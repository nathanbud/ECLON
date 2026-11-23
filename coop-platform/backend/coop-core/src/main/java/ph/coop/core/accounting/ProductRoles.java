package ph.coop.core.accounting;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;

/**
 * The account types a cooperative chose for the roles the system posts to by itself, set in Settings > Products > Product roles; nothing is assumed from
 * another cooperative's numbering. Today one role: <b>vouchers payable</b>, the fund and payable account type (class 29) a loan's net proceeds and a cash or
 * check voucher are credited to, and paid out from. Without a choice, what needs it is refused with directions to set it.
 */
@Component
public class ProductRoles {

    public static final String VOUCHERS_PAYABLE = "product.vouchers_payable_type";
    /** Vouchers payable are member accounts of the fund and payable class (the old osl table). */
    public static final int PAYABLE_CLASS = 29;

    private final JdbcTemplate jdbc;
    private final Db db;

    ProductRoles(JdbcTemplate jdbc, Db db) {
        this.jdbc = jdbc;
        this.db = db;
    }

    /** The vouchers-payable type chosen, or null when none is (or the type chosen is no longer set up). */
    public Integer vouchersPayableOrNull(int branch) {
        if (!db.tableExists("ecis_setting")) return null;
        List<String> v = jdbc.queryForList("SELECT setting_value FROM ecis_setting WHERE setting_key = ?", String.class, VOUCHERS_PAYABLE);
        if (v.isEmpty() || v.get(0) == null || v.get(0).isBlank()) return null;
        int slt;
        try {
            slt = Integer.parseInt(v.get(0).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ?", Integer.class, branch, PAYABLE_CLASS, slt);
        return n != null && n > 0 ? slt : null;
    }

    /**
     * The vouchers-payable type, or a refusal naming what could not be done and where the type is chosen. {@code what} completes "... so <what> cannot be done",
     * for example "the loan's net proceeds".
     */
    public int vouchersPayable(int branch, String what) {
        Integer t = vouchersPayableOrNull(branch);
        if (t == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This cooperative has not chosen which account type holds members' vouchers payable, so " + what
                + " cannot be done and nothing was saved. An administrator chooses it in Settings > Products > Product roles (a fund and payable type; add one in "
                + "Settings > Products > Fund and payable products if there is none).");
        }
        return t;
    }
}
