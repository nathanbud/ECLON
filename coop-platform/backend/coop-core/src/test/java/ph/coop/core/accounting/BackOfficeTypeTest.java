package ph.coop.core.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** The Back Office window's Trans. type list: every back-office type of the old system, its use in the old data, and which ones this application makes. Read only. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class BackOfficeTypeTest {

    @Autowired BackOfficeTypeController types;
    @Autowired JdbcTemplate jdbc;

    @Test
    @WithMockUser(username = "tester", roles = "ACCOUNTANT")
    void listsEveryBackOfficeTypeOfTheOldSystemAndSaysWhichOnesAreMadeHere() {
        List<BackOfficeTypeController.Type> list = types.types(1);
        Integer inTable = jdbc.queryForList("SELECT TransTypeModule FROM transtype", String.class).stream()
            .filter((m) -> m != null && m.trim().equals("1")).map((m) -> 1).reduce(0, Integer::sum);
        assertEquals(inTable, list.size(), "every module 1 type of transtype, and nothing else");
        Map<Integer, BackOfficeTypeController.Type> byId = list.stream().collect(Collectors.toMap(BackOfficeTypeController.Type::id, Function.identity()));
        assertEquals("GJ", byId.get(1).entry());
        assertEquals("General Journal", byId.get(1).name());
        assertEquals("DCM", byId.get(2).entry());
        if (byId.containsKey(3)) assertNull(byId.get(3).entry(), "Transfer Client is not made here");
        if (byId.containsKey(81)) assertNotNull(byId.get(81).madeElsewhere(), "a reversing entry is made with Reverse…");
        Long journals = jdbc.queryForObject("SELECT COUNT(*) FROM transsum WHERE TR_MODULE = 1 AND TR_CODE = 1", Long.class);
        assertEquals(journals, byId.get(1).entries());
        assertTrue(list.stream().noneMatch((t) -> t.id() == 10 && t.name().contains("Cash Receipt")), "front office types are left out");
    }

    @Test
    @WithMockUser(username = "teller", roles = "USER")
    void isOnlyForAccountantsAndAdministrators() {
        assertThrows(AccessDeniedException.class, () -> types.types(1));
    }

    @Test
    @WithMockUser(username = "tester", roles = "ACCOUNTANT")
    void listsTheDisbursementTypesTooWithTheCashAndCheckVoucherMadeHere() {
        Map<Integer, BackOfficeTypeController.Type> byId = types.types(2).stream().collect(Collectors.toMap(BackOfficeTypeController.Type::id, Function.identity()));
        assertEquals("CV", byId.get(21).entry());
        assertEquals("CKV", byId.get(22).entry());
        if (byId.containsKey(23)) assertNotNull(byId.get(23).madeElsewhere(), "withdrawals are made on the Teller tab");
        if (byId.containsKey(20)) assertNull(byId.get(20).entry(), "loan vouchers come from the Loan Module");
        assertTrue(byId.values().stream().noneMatch((t) -> t.id() == 1), "the general journal is module 1");
        assertEquals(400, assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> types.types(3)).getStatusCode().value());
    }
}
