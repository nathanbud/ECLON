package ph.coop.core.frontoffice;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * What the Back Office's Disbursement Transactions window needs before a Cash Voucher or Check Voucher is made: the account a Cash Voucher credits
 * (the payee's vouchers payable, class 29 type 1, from glcontrol), the person's department, and whether the person has New on each type. The vouchers
 * themselves are made through the front office draft and its posting ({@link DraftPostController}), so every check there applies; see
 * documentation/general-voucher-map.md. Read only.
 */
@RestController
@RequestMapping("/api/backoffice/voucher-setup")
class BackOfficeVoucherSetupController {

    static final int CASH_VOUCHER = 21, CHECK_VOUCHER = 22;
    private static final int PAYABLE = 29, PRINCIPAL = 11, ACTIVE = 15;

    record Setup(String payableAccount, String payableAccountName, Integer dept, boolean mayCashVoucher, boolean mayCheckVoucher, String problem) {}

    private final JdbcTemplate jdbc;
    private final FrontOfficeAccess access;
    private final MemberLedgerPoster poster;

    private final ph.coop.core.accounting.ProductRoles roles;

    BackOfficeVoucherSetupController(JdbcTemplate jdbc, FrontOfficeAccess access, MemberLedgerPoster poster, ph.coop.core.accounting.ProductRoles roles) {
        this.roles = roles;
        this.jdbc = jdbc;
        this.access = access;
        this.poster = poster;
    }

    @GetMapping
    Setup setup(Authentication auth) {
        boolean cash = access.mayCreate(auth, CASH_VOUCHER), check = access.mayCreate(auth, CHECK_VOUCHER);
        MemberLedgerPoster.Person p;
        try {
            p = poster.person(auth.getName());
        } catch (ResponseStatusException e) {
            return new Setup(null, null, null, false, false, "Your sign-in is not a user of the old system, so you cannot make vouchers.");
        }
        String payable = null, name = null;
        try {
            payable = poster.glAccount(p.branch(), PAYABLE, roles.vouchersPayable(p.branch(), "the voucher's credit to vouchers payable"), PRINCIPAL, ACTIVE);
            List<String> n = jdbc.queryForList("SELECT COADesc FROM coa WHERE COABR_CODE = ? AND COAID = ?", String.class, p.branch(), payable);
            name = n.isEmpty() || n.get(0) == null ? null : n.get(0).trim();
        } catch (ResponseStatusException e) {
            // no vouchers-payable account set up: the Cash Voucher says why below
        }
        String problem = p.dept() <= 0 ? "Your account has no department set, so vouchers cannot be posted. An administrator sets it in Settings > Users."
            : payable == null ? "The ledger has no vouchers-payable account set up (class 29, type 1), so a Cash Voucher cannot be made." : null;
        return new Setup(payable, name, p.dept() > 0 ? p.dept() : null, cash, check, problem);
    }
}
