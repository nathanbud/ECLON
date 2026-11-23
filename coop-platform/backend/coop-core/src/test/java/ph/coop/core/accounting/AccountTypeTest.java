package ph.coop.core.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.accounting.AccountTypeController.Form;
import ph.coop.core.accounting.AccountTypeController.LinkIn;
import ph.coop.core.accounting.AccountTypeController.TypeRow;

/** Account types outside loans: listed by class, changed with a reason, added as a copy with its ledger links, links pointed at another account of the same kind, all audited. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
@WithMockUser(username = "tester", roles = "ADMIN")
class AccountTypeTest {

    @Autowired AccountTypeController types;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void ready() {
        assumeTrue(!jdbc.queryForList("SELECT 1 FROM access WHERE AccessUserName = 'tester'").isEmpty(), "no tester account");
        assumeTrue(new ph.coop.core.db.Db(jdbc).canInsertInto("sltype", "glcontrol"), "the loan setup grants have not been applied");
        assumeTrue(!jdbc.queryForList("SELECT 1 FROM sltype WHERE SLTypeBR_CODE = 1 AND SLTypeSLC_CODE = 22 AND SLTypeSLT_CODE = 1").isEmpty(), "no savings type 1 in this database");
        assumeTrue(!jdbc.queryForList("SELECT 1 FROM glcontrol WHERE GLControlBR_CODE = 1 AND GLControlSLC_CODE = 22 AND GLControlSLT_CODE = 1 AND GLControlSLE_CODE = 11 AND GLControlCTS_NUM = 15 AND GLControlACCT_CODE = '2010101'").isEmpty(),
            "savings type 1 is not linked to 2010101 here");
    }

    private Authentication me() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private TypeRow savings(int code) {
        return types.list(me()).stream().filter((c) -> c.id() == 22).findFirst().orElseThrow().types().stream().filter((t) -> t.code() == code).findFirst().orElseThrow();
    }

    private static Form form(TypeRow t, String name, String rate, String min, boolean disabled, String reason) {
        return new Form(name, t.fullName(), t.priority(), rate == null ? null : new BigDecimal(rate), min == null ? null : new BigDecimal(min), t.maxBalance(), t.dormantAfterMonths(), disabled, reason);
    }

    @Test void theClassesAreListedWithTheirTypesAndWhereEachIsKept() {
        var all = types.list(me());
        var loans = all.stream().filter((c) -> c.id() == 12).findFirst().orElseThrow();
        assertEquals("LOAN_SETUP", loans.managed());
        assertFalse(loans.types().isEmpty());
        assertEquals("CASH", all.stream().filter((c) -> c.id() == 11).findFirst().orElseThrow().managed());
        var sd = all.stream().filter((c) -> c.id() == 22).findFirst().orElseThrow();
        assertEquals("EDIT", sd.managed());
        assertEquals(List.of("interestRate", "minBalance", "maxBalance", "dormantAfterMonths"), sd.fields());
        TypeRow regular = savings(1);
        assertNotNull(regular.accounts());
        assertTrue(regular.accounts() > 0);
        assertTrue(regular.links() > 0);
    }

    @Test void aRateChangeIsSavedAndAuditedWithTheValuesBeforeAndAfter() {
        TypeRow was = savings(1);
        TypeRow now = types.update(22, 1, form(was, was.name(), "1.75", "500", false, "board resolution 12"), me());
        assertEquals(0, new BigDecimal("1.75").compareTo(now.interestRate()));
        var a = jdbc.queryForMap("SELECT prev_value, new_value, reason FROM ecis_audit_log WHERE action = 'account.type.update' AND entity = 'account_type' AND entity_id = '1/22/1' ORDER BY id DESC LIMIT 1");
        assertTrue(String.valueOf(a.get("new_value")).contains("1.75"));
        assertTrue(String.valueOf(a.get("new_value")).contains("SLTypeINT_RATE"));
        assertFalse(String.valueOf(a.get("new_value")).contains("SLTypeM_DESC"), "only what changed is recorded");
        assertEquals("board resolution 12", a.get("reason"));
    }

    @Test void savingTheSameValuesWritesNothing() {
        TypeRow was = savings(1);
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE entity = 'account_type'", Long.class);
        types.update(22, 1, form(was, was.name(), was.interestRate() == null ? null : was.interestRate().toPlainString(), was.minBalance() == null ? null : was.minBalance().toPlainString(), was.disabled(), "no change"), me());
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE entity = 'account_type'", Long.class));
    }

    @Test void aReasonIsRequiredAndNamesAreNotRepeatedInAClass() {
        TypeRow one = savings(1);
        assertThrows(ResponseStatusException.class, () -> types.update(22, 1, form(one, one.name(), "1", "500", false, " "), me()));
        TypeRow two = savings(2);
        assertThrows(ResponseStatusException.class, () -> types.update(22, 1, form(one, two.name().toUpperCase(), "1", "500", false, "rename"), me()));
        assertThrows(ResponseStatusException.class, () -> types.update(22, 999, form(one, "Nothing", "1", "500", false, "x"), me()));
    }

    @Test void aTypeIsSwitchedOffAndOnAgain() {
        TypeRow was = savings(2);
        assertTrue(types.update(22, 2, form(was, was.name(), "0.25", "500", true, "no longer offered"), me()).disabled());
        assertFalse(types.update(22, 2, form(was, was.name(), "0.25", "500", false, "offered again"), me()).disabled());
        List<String> actions = jdbc.queryForList("SELECT action FROM ecis_audit_log WHERE entity = 'account_type' AND entity_id = '1/22/2' AND action IN ('account.type.disable', 'account.type.enable') ORDER BY id", String.class);
        assertEquals(List.of("account.type.disable", "account.type.enable"), actions.subList(actions.size() - 2, actions.size()));
    }

    @Test void aNewTypeIsACopyWithItsLedgerLinks() {
        TypeRow from = savings(1);
        TypeRow made = types.create(22, 1, new Form("SD - Test Youth", "SD - Test Youth savings", 20, new BigDecimal("2.50"), new BigDecimal("100"), false, "new product"), me());
        assertTrue(made.code() > 8);
        assertEquals(from.links(), made.links());
        assertEquals(0L, made.accounts());
        assertEquals(0, new BigDecimal("2.50").compareTo(made.interestRate()));
        assertEquals(types.links(22, 1, me()).stream().map((l) -> l.event() + "/" + l.number() + "/" + l.account()).toList(),
            types.links(22, made.code(), me()).stream().map((l) -> l.event() + "/" + l.number() + "/" + l.account()).toList());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'account.type.create' AND entity_id = ?", Integer.class, "1/22/" + made.code()));
        // the other settings of the copied type came along
        assertEquals(jdbc.queryForObject("SELECT SLTypeNDAYS_1YEAR_INTDEPO FROM sltype WHERE SLTypeSLC_CODE = 22 AND SLTypeSLT_CODE = 1", Integer.class),
            jdbc.queryForObject("SELECT SLTypeNDAYS_1YEAR_INTDEPO FROM sltype WHERE SLTypeSLC_CODE = 22 AND SLTypeSLT_CODE = ?", Integer.class, made.code()));
    }

    @Test void aLedgerLinkMovesOnlyToAPostingAccountOfTheSameKind() {
        var links = types.links(22, 1, me());
        var active = links.stream().filter((l) -> l.event() == 11 && l.number() == 15).findFirst().orElseThrow();
        assertEquals("2010101", active.account());
        assertEquals("Deposit balance", active.what());
        assertEquals("Active", active.statusName());
        // a heading, an account of another kind (income), an unknown account, the same account: all refused
        assertThrows(ResponseStatusException.class, () -> types.changeLink(22, 1, new LinkIn(11, 15, "201", "try"), me()));
        assertThrows(ResponseStatusException.class, () -> types.changeLink(22, 1, new LinkIn(11, 15, "412", "try"), me()));
        assertThrows(ResponseStatusException.class, () -> types.changeLink(22, 1, new LinkIn(11, 15, "2999999", "try"), me()));
        assertThrows(ResponseStatusException.class, () -> types.changeLink(22, 1, new LinkIn(11, 15, "2010101", "try"), me()));
        assertThrows(ResponseStatusException.class, () -> types.changeLink(22, 1, new LinkIn(11, 15, "2010106", " "), me()));
        assertThrows(ResponseStatusException.class, () -> types.changeLink(22, 1, new LinkIn(55, 15, "2010106", "no such link"), me()));
        var after = types.changeLink(22, 1, new LinkIn(11, 15, "2010106", "separate ledger account"), me());
        assertEquals("2010106", after.stream().filter((l) -> l.event() == 11 && l.number() == 15).findFirst().orElseThrow().account());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'account.type.link.change' AND entity_id = '1/22/1' AND reason = 'separate ledger account'", Integer.class));
    }

    @Test void loanAndCashTypesAreNotChangedHere() {
        TypeRow one = savings(1);
        assertThrows(ResponseStatusException.class, () -> types.update(12, 3, new Form("Renamed loan", null, 99, null, null, false, "try"), me()));
        assertThrows(ResponseStatusException.class, () -> types.update(11, 1, new Form("Renamed cash", null, 99, null, null, false, "try"), me()));
        assertThrows(ResponseStatusException.class, () -> types.create(11, 1, new Form("New cash", null, 99, null, null, false, "try"), me()));
        assertThrows(ResponseStatusException.class, () -> types.changeLink(12, 3, new LinkIn(11, 11, "2010106", "try"), me()));
        assertNull(types.list(me()).stream().filter((c) -> c.id() == 12).findFirst().orElseThrow().types().get(0).accounts());
    }

    @Test void aSavingsTypeKeepsItsMaximumBalanceAndDormancyPeriod() {
        TypeRow was = savings(1);
        assertNull(was.maxBalance(), "999,999,999.00 is shown as no limit");
        assertEquals(24, was.dormantAfterMonths());
        TypeRow now = types.update(22, 1, new Form(was.name(), was.fullName(), was.priority(), was.interestRate(), was.minBalance(), new BigDecimal("250000"), 36, false, "policy"), me());
        assertEquals(0, new BigDecimal("250000").compareTo(now.maxBalance()));
        assertEquals(36, now.dormantAfterMonths());
        TypeRow back = types.update(22, 1, new Form(was.name(), was.fullName(), was.priority(), was.interestRate(), was.minBalance(), null, null, false, "no limit again"), me());
        assertNull(back.maxBalance());
        assertNull(back.dormantAfterMonths());
    }

    @Test void aLedgerLinkIsAddedAndRemovedOnlyWhereTheClassAllowsIt() {
        var o = types.options(22);
        assertTrue(o.events().stream().anyMatch((e) -> e.id() == 75), "savings posts accrued interest");
        assertFalse(o.events().stream().anyMatch((e) -> e.id() == 71), "a time deposit posting is not offered for savings");
        assertEquals(List.of(15, 16, 17), o.statuses().stream().map((s) -> s.id()).toList());
        // savings type 8 has no accrued interest link
        assertTrue(types.links(22, 8, me()).stream().noneMatch((l) -> l.event() == 75));
        assertThrows(ResponseStatusException.class, () -> types.addLink(22, 8, new LinkIn(71, 99, "2011603", "try"), me()), "not a savings posting");
        assertThrows(ResponseStatusException.class, () -> types.addLink(22, 8, new LinkIn(11, 15, "2010106", "try"), me()), "already there");
        assertThrows(ResponseStatusException.class, () -> types.addLink(22, 8, new LinkIn(75, 99, "201", "try"), me()), "a heading");
        var added = types.addLink(22, 8, new LinkIn(75, 0, "2011603", "accrue interest on Kaya"), me());
        var link = added.stream().filter((l) -> l.event() == 75).findFirst().orElseThrow();
        assertEquals(99, link.number());
        assertEquals(2, jdbc.queryForObject("SELECT BAL_TYPE FROM glcontrol WHERE GLControlSLC_CODE = 22 AND GLControlSLT_CODE = 8 AND GLControlSLE_CODE = 75", Integer.class), "the side the other savings types use");
        // nothing was posted through the new link, so it can go again; one that carries entries cannot
        assertTrue(types.removeLink(22, 8, 75, 99, "not needed", me()).stream().noneMatch((l) -> l.event() == 75));
        assertThrows(ResponseStatusException.class, () -> types.removeLink(22, 1, 11, 15, "try", me()));
        assertThrows(ResponseStatusException.class, () -> types.removeLink(12, 3, 22, 99, "try", me()));
        List<String> actions = jdbc.queryForList("SELECT action FROM ecis_audit_log WHERE entity = 'account_type' AND entity_id = '1/22/8' ORDER BY id", String.class);
        assertEquals(List.of("account.type.link.add", "account.type.link.remove"), actions.subList(actions.size() - 2, actions.size()));
    }

    @Test void aClassWithNoTypesGetsItsFirstOne() {
        assumeTrue(jdbc.queryForObject("SELECT COUNT(*) FROM sltype WHERE SLTypeBR_CODE = 1 AND SLTypeSLC_CODE = 14", Integer.class) == 0, "class 14 already has types here");
        assertThrows(ResponseStatusException.class, () -> types.createFirst(22, new AccountTypeController.FirstType("Another", null, "2010106", "try"), me()), "savings has types: copy one");
        assertThrows(ResponseStatusException.class, () -> types.createFirst(14, new AccountTypeController.FirstType("Deposits paid", null, "201", "try"), me()), "a heading");
        String asset = jdbc.queryForObject("SELECT MIN(COAID) FROM coa WHERE COABR_CODE = 1 AND COAacct_tag = 'D' AND COAacct_type = 1 AND COALESCE(COAisdisabled, 0) = 0", String.class);
        TypeRow made = types.createFirst(14, new AccountTypeController.FirstType("Deposits paid", "Deposits paid to suppliers", asset, "new ledger"), me());
        assertEquals(1, made.code());
        assertEquals(1, made.links());
        var link = types.links(14, 1, me()).get(0);
        assertEquals(11, link.event());
        assertEquals(15, link.number());
        assertEquals(asset, link.account());
        assertEquals(1, jdbc.queryForObject("SELECT BAL_TYPE FROM glcontrol WHERE GLControlSLC_CODE = 14 AND GLControlSLT_CODE = 1", Integer.class), "an asset account keeps a debit balance");
    }

    @Test void aCashLinkIsRepointedAndADrawerIsAdded() {
        var drawers = types.drawers(me());
        assertTrue(drawers.stream().anyMatch((d) -> d.event() == 53 && d.kind().equals("Teller")));
        int free = java.util.stream.IntStream.rangeClosed(31, 94).filter((n) -> drawers.stream().noneMatch((d) -> d.event() == 53 && d.number() == n)).findFirst().orElse(-1);
        assumeTrue(free > 0, "every teller drawer number is taken");
        int taken = drawers.stream().filter((d) -> d.event() == 53).findFirst().orElseThrow().number();
        assertThrows(ResponseStatusException.class, () -> types.addDrawer(new AccountTypeController.DrawerIn(53, taken, "try", null), me()));
        assertThrows(ResponseStatusException.class, () -> types.addDrawer(new AccountTypeController.DrawerIn(53, 15, "try", null), me()), "15 is an account status, not a drawer");
        assertThrows(ResponseStatusException.class, () -> types.addDrawer(new AccountTypeController.DrawerIn(50, free, "try", null), me()), "the vault is not a drawer");
        assertThrows(ResponseStatusException.class, () -> types.addDrawer(new AccountTypeController.DrawerIn(53, free, " ", null), me()));
        var after = types.addDrawer(new AccountTypeController.DrawerIn(53, free, "new teller window", null), me());
        assertTrue(after.stream().anyMatch((d) -> d.event() == 53 && d.number() == free && d.account() != null));
        // the cheque and ATM types that keep teller drawers got the same number
        assertEquals(jdbc.queryForObject("SELECT COUNT(DISTINCT GLControlSLT_CODE) FROM glcontrol WHERE GLControlSLC_CODE = 11 AND GLControlSLE_CODE = 53", Integer.class),
            jdbc.queryForObject("SELECT COUNT(*) FROM glcontrol WHERE GLControlSLC_CODE = 11 AND GLControlSLE_CODE = 53 AND GLControlCTS_NUM = ?", Integer.class, free));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'cash.drawer.add' AND entity_id = ?", Integer.class, "1/11/53/" + free));
        // a cash link moves to another asset account, never to a liability, and cash types take no added or removed links
        String was = types.links(11, 1, me()).stream().filter((l) -> l.event() == 53 && l.number() == free).findFirst().orElseThrow().account();
        String other = jdbc.queryForObject("SELECT MIN(COAID) FROM coa WHERE COABR_CODE = 1 AND COAacct_tag = 'D' AND COAacct_type = 1 AND COALESCE(COAisdisabled, 0) = 0 AND COAID <> ?", String.class, was);
        assertThrows(ResponseStatusException.class, () -> types.changeLink(11, 1, new LinkIn(53, free, "2010106", "try"), me()));
        assertEquals(other, types.changeLink(11, 1, new LinkIn(53, free, other, "own ledger account"), me()).stream().filter((l) -> l.event() == 53 && l.number() == free).findFirst().orElseThrow().account());
        assertThrows(ResponseStatusException.class, () -> types.addLink(11, 1, new LinkIn(53, free + 1, other, "try"), me()));
        assertThrows(ResponseStatusException.class, () -> types.removeLink(11, 1, 53, free, "try", me()));
    }

    @Test void aTypeNothingUsedIsDeletedWithItsLinksAndAUsedOneIsRefused() {
        TypeRow made = types.create(22, 1, new Form("SD - Mistake", "SD - Added by mistake", 21, new BigDecimal("1.00"), new BigDecimal("100"), false, "added by mistake"), me());
        var check = types.deleteCheck(22, made.code(), me());
        assertTrue(check.deletable(), String.valueOf(check.problems()));
        assertEquals(made.links(), check.links());
        assertThrows(ResponseStatusException.class, () -> types.delete(22, made.code(), " ", me()), "a reason is required");
        types.delete(22, made.code(), "added by mistake", me());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sltype WHERE SLTypeBR_CODE = 1 AND SLTypeSLC_CODE = 22 AND SLTypeSLT_CODE = ?", Integer.class, made.code()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM glcontrol WHERE GLControlBR_CODE = 1 AND GLControlSLC_CODE = 22 AND GLControlSLT_CODE = ?", Integer.class, made.code()), "its links went with it");
        String audit = jdbc.queryForObject("SELECT prev_value FROM ecis_audit_log WHERE action = 'account.type.delete' AND entity_id = ?", String.class, "1/22/" + made.code());
        assertTrue(audit.contains("SD - Mistake") && audit.contains("2010101"), "the row and its links are kept in the audit log");

        var used = types.deleteCheck(22, 1, me());
        assertFalse(used.deletable());
        assertTrue(used.problems().get(0).contains("accounts") && used.problems().get(0).contains("Switch it off instead"), used.problems().get(0));
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> types.delete(22, 1, "try", me()));
        assertEquals(409, e.getStatusCode().value());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sltype WHERE SLTypeBR_CODE = 1 AND SLTypeSLC_CODE = 22 AND SLTypeSLT_CODE = 1", Integer.class));
        assertFalse(types.deleteCheck(11, 1, me()).deletable(), "cash types stay");
    }

    @Test void aNewSavingsTypeIsSuggestedTheAccountTheOtherSavingsTypesUse() {
        var s = types.suggestions(22, 11, 15, null, "Holiday Savings", me());
        assertFalse(s.isEmpty());
        var regular = s.stream().filter((x) -> x.account().equals("2010101")).findFirst().orElseThrow();
        assertTrue(regular.why().startsWith("Used for this posting by"), regular.why());
        // every suggestion takes postings and is in use
        for (var x : s) {
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM coa WHERE COABR_CODE = 1 AND COAID = ? AND COAacct_tag = 'D' AND COALESCE(COAisdisabled, 0) = 0", Integer.class, x.account()));
        }
        // for type 1 itself, its own link is not counted as another type's
        assertTrue(types.suggestions(22, 11, 15, 1, null, me()).stream().noneMatch((x) -> x.why().contains(savings(1).name().trim() + " ")));
    }

    @Test void coopsCashClassIsSetUpForTheTellerWindowAndNothingIsAddedTwice() {
        var setup = types.cashSetup(me());
        assertTrue(setup.ready(), setup.missing().toString());
        assertEquals("10001", setup.fixed().get(0).account(), "the vault");
        var e = assertThrows(ResponseStatusException.class, () -> types.setUpCash(new AccountTypeController.CashSetupIn(java.util.Map.of("1/50", "10001"), "again"), me()));
        assertTrue(e.getReason().contains("already set up"), e.getReason());
    }

    @Test @WithMockUser(username = "tester", roles = "ACCOUNTANT") void onlyAdministratorsMayOpenIt() {
        assertThrows(AccessDeniedException.class, () -> types.list(me()));
        assertThrows(AccessDeniedException.class, () -> types.changeLink(22, 1, new LinkIn(11, 15, "2010106", "try"), me()));
    }
}
