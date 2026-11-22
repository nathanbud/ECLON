package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;

/** What the Disbursement Transactions window learns before making a Cash or Check Voucher. Read only. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class BackOfficeVoucherSetupTest {

    @Autowired BackOfficeVoucherSetupController setup;
    @Autowired FrontOfficeAccess access;
    @Autowired JdbcTemplate jdbc;

    @Test
    @WithMockUser(username = "tester", roles = "ACCOUNTANT")
    void givesTheAccountACashVoucherCreditsAndWhatThePersonMayMake() {
        assumeTrue(!jdbc.queryForList("SELECT 1 FROM access WHERE AccessUserName = 'tester'").isEmpty(), "no tester account");
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var s = setup.setup(auth);
        String expected = jdbc.queryForList("SELECT GLControlACCT_CODE FROM glcontrol WHERE GLControlBR_CODE = (SELECT AccessBR_CODE FROM access WHERE AccessUserName = 'tester') "
            + "AND GLControlSLC_CODE = 29 AND GLControlSLT_CODE = 1 AND GLControlSLE_CODE = 11 AND GLControlCTS_NUM = 15", String.class).stream().findFirst().orElse(null);
        assertEquals(expected, s.payableAccount());
        if (expected != null) assertNotNull(s.payableAccountName());
        assertEquals(access.mayCreate(auth, 21), s.mayCashVoucher());
        assertEquals(access.mayCreate(auth, 22), s.mayCheckVoucher());
    }

    @Test
    @WithMockUser(username = "nobody-in-the-old-system", roles = "ACCOUNTANT")
    void saysWhyWhenTheSignInIsNotAnOldSystemUser() {
        var s = setup.setup(SecurityContextHolder.getContext().getAuthentication());
        assertFalse(s.mayCashVoucher() || s.mayCheckVoucher());
        assertNotNull(s.problem());
    }
}
