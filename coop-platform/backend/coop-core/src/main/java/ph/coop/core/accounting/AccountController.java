package ph.coop.core.accounting;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
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

    AccountController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    List<Account> list(@RequestParam int branchCode) {
        return accounts.findByIdBranchCode(branchCode, Sort.by("id.code"));
    }
}
