package ph.coop.core.frontoffice;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The lists the window needs to take a cheque: the banks a cheque can be drawn on and the kinds of cheque with their clearing days (bankcode, chktype). */
@RestController
@RequestMapping("/api/front-office/cheque-setup")
class ChequeSetupController {

    record Kind(int id, String name, int days) {}

    record Bank(int id, String name) {}

    record Setup(List<Kind> kinds, List<Bank> banks) {}

    private final JdbcTemplate jdbc;
    private final FrontOfficeAccess access;

    ChequeSetupController(JdbcTemplate jdbc, FrontOfficeAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @GetMapping
    Setup setup(Authentication auth) {
        access.of(auth);
        List<Kind> kinds = jdbc.query("SELECT ChkTypeID, ChkTypeDesc, ChkTypeDays FROM chktype ORDER BY ChkTypeID", (rs, i) -> new Kind(rs.getInt(1), rs.getString(2), rs.getInt(3)));
        List<Bank> banks = jdbc.query("SELECT BCID, COALESCE(NULLIF(BCDesc2, ''), BCDesc) FROM bankcode ORDER BY 2, BCID", (rs, i) -> new Bank(rs.getInt(1), rs.getString(2)));
        return new Setup(kinds, banks);
    }
}
