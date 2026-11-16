package ph.coop.core.accounting;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

interface AccountRepository extends JpaRepository<Account, Account.Id> {
    List<Account> findByIdBranchCode(int branchCode, Sort sort);
}

@RestController
@RequestMapping("/api/accounts")
class AccountController {

    private final AccountRepository accounts;
    private final JdbcTemplate jdbc;

    AccountController(AccountRepository accounts, JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    record Postable(String code, String description) {}

    /**
     * Accounts that take postings (detail accounts that are switched on), found by the start of the number or any part of the name, for the
     * account pickers on journals and other forms. At most 30.
     */
    @GetMapping("/search")
    List<Postable> search(@RequestParam int branchCode, @RequestParam(defaultValue = "") String q) {
        String text = q.trim().replace("%", "").replace("_", "");
        return jdbc.query("""
                SELECT COAID, COADesc FROM coa
                WHERE COABR_CODE = ? AND COALESCE(COAisdisabled, 0) = 0 AND COAacct_tag = 'D' AND (COAID LIKE ? OR LOWER(COADesc) LIKE LOWER(?))
                ORDER BY COAID LIMIT 30
                """, (rs, i) -> new Postable(rs.getString(1), rs.getString(2)), branchCode, text + "%", "%" + text + "%");
    }

    @GetMapping
    List<Account> list(@RequestParam int branchCode) {
        return accounts.findByIdBranchCode(branchCode, Sort.by("id.code"));
    }
}
