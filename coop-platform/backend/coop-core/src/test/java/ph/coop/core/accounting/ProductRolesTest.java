package ph.coop.core.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Product roles on coop: vouchers payable is the type the cooperative chose, changed with a reason and audited, refused when none is chosen. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
@WithMockUser(username = "tester", roles = "ADMIN")
class ProductRolesTest {

    @Autowired ProductRolesController controller;
    @Autowired ProductRoles roles;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void ready() {
        assumeTrue(jdbc.queryForObject("SELECT COUNT(*) FROM sltype WHERE SLTypeBR_CODE = 1 AND SLTypeSLC_CODE = 29 AND SLTypeSLT_CODE IN (1, 5)", Integer.class) == 2,
            "needs coop's fund types 29/1 and 29/5");
    }

    @Test void vouchersPayableIsTheTypeTheCooperativeChose() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertEquals(1, controller.get(auth).vouchersPayable(), "coop's 29/1 is named Vouchers Payable");
        assertTrue(controller.get(auth).choices().size() >= 2);
        var after = controller.set(new ProductRolesController.RolesIn(5, "Testing another type"), auth);
        assertEquals(5, after.vouchersPayable());
        assertEquals(5, roles.vouchersPayable(1, "a test"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'product.role.change' AND reason = 'Testing another type'", Integer.class));
        assertThrows(ResponseStatusException.class, () -> controller.set(new ProductRolesController.RolesIn(98, "not set up"), auth));
        assertThrows(ResponseStatusException.class, () -> controller.set(new ProductRolesController.RolesIn(1, " "), auth), "a reason is needed");
    }

    @Test void withoutAChoiceWhatNeedsItIsRefusedWithDirections() {
        jdbc.update("UPDATE ecis_setting SET setting_value = '' WHERE setting_key = ?", ProductRoles.VOUCHERS_PAYABLE);
        assertNull(roles.vouchersPayableOrNull(1));
        var e = assertThrows(ResponseStatusException.class, () -> roles.vouchersPayable(1, "the loan application"));
        assertTrue(e.getReason().contains("Settings > Products > Product roles"), e.getReason());
        assertTrue(e.getReason().contains("the loan application cannot be done"), e.getReason());
    }
}
