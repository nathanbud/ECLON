package ph.coop.core.accounting.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;

/** The posting engine on the real database, rolled back: a request key is written once, and a voucher that breaks a rule writes nothing. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class LedgerTest {

    static final LocalDate DAY = LocalDate.of(2099, 2, 3);

    @Autowired Ledger ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired Db db;

    private int person;

    @BeforeEach
    void setUp() {
        assumeTrue(db.tableExists("ecis_posting_request"), "V026 (request keys) has not been applied");
        Integer accounts = jdbc.queryForObject("SELECT COUNT(*) FROM coa WHERE COABR_CODE = 1 AND COAID IN ('50105', '2010201')", Integer.class);
        assumeTrue(accounts != null && accounts == 2, "test accounts 50105 and 2010201 not in the chart");
        person = jdbc.queryForObject("SELECT MIN(AccessID) FROM access WHERE COALESCE(AccessIsDisabled, 0) = 0", Integer.class);
    }

    private Voucher voucher(String key, String debit, String credit, String debitAccount) {
        return new Voucher(key, "itest", 1, 1, 1, DAY, "ledger test", Voucher.Style.GENERAL_JOURNAL, new Voucher.People(person, person, person),
            List.of(new Voucher.Line(debitAccount, 1, Money.of(debit)), new Voucher.Line("2010201", 1, Money.of(credit))), List.of());
    }

    private int vouchersOn(LocalDate day) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM transsum WHERE TR_BRCODE = 1 AND TR_MODULE = 1 AND TR_CODE = 1 AND TR_DATE = ?", Integer.class, day);
    }

    @Test void theSameRequestKeyTwiceWritesOneVoucherAndGivesBackTheFirst() {
        Ledger.Posted first = ledger.write(voucher("itest/once", "7.00", "-7.00", "50105"));
        Ledger.Posted again = ledger.write(voucher("itest/once", "7.00", "-7.00", "50105"));
        assertFalse(first.repeated());
        assertTrue(again.repeated());
        assertEquals(first.controlNo(), again.controlNo());
        assertEquals(first.documentNo(), again.documentNo());
        assertEquals(1, vouchersOn(DAY));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM gltrans WHERE GLTransBR_CODE = 1 AND GLTransTR_DATE = ? AND GLTransTR_CODE = 1", Integer.class, DAY));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'ledger.write' AND entity_id = ?", Integer.class,
            "1/2099/1/1/" + first.controlNo()));
    }

    @Test void aVoucherThatBreaksARuleWritesNothing() {
        ResponseStatusException unbalanced = assertThrows(ResponseStatusException.class, () -> ledger.write(voucher("itest/unbalanced", "7.00", "-6.00", "50105")));
        assertTrue(unbalanced.getReason().contains("does not balance"), unbalanced.getReason());
        String summary = jdbc.queryForObject("SELECT MIN(COAID) FROM coa WHERE COABR_CODE = 1 AND COAacct_tag = 'S'", String.class);
        assumeTrue(summary != null, "no summary account in the chart");
        ResponseStatusException toSummary = assertThrows(ResponseStatusException.class, () -> ledger.write(voucher("itest/summary", "7.00", "-7.00", summary)));
        assertTrue(toSummary.getReason().contains("summary account"), toSummary.getReason());
        assertEquals(0, vouchersOn(DAY));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_posting_request WHERE request_key LIKE 'itest/%'", Integer.class));
    }

    @Test void aDateInAPeriodThatIsNotOpenIsRefused() {
        jdbc.update("INSERT INTO ecis_accounting_period (branch_code, name, start_date, end_date, status, updated_by, updated_at) VALUES (1, 'ITEST 2099-02', ?, ?, 'CLOSED', 'itest', CURRENT_TIMESTAMP)",
            LocalDate.of(2099, 2, 1), LocalDate.of(2099, 2, 28));
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> ledger.write(voucher("itest/closed", "7.00", "-7.00", "50105")));
        assertTrue(e.getReason().contains("is closed"), e.getReason());
        assertEquals(0, vouchersOn(DAY));
    }
}
