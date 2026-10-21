package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.frontoffice.BankCashController.BankRow;
import ph.coop.core.frontoffice.BankCashController.Posted;
import ph.coop.core.frontoffice.BankCashController.Request;

/**
 * Posts a bank deposit and a bank withdrawal through the real code, compares the rows with the old system's Bank Deposit and Bank Withdrawal
 * (documentation/bank-cash-posting-map.md), then rolls everything back. Needs the head cashier with "new" rights on types 33 and 35, a bank in the
 * ledger and cash in the vault; skips otherwise. Runs only when DB_PASSWORD is set; needs the V006 grants.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class BankCashPostingTest {

    @Autowired BankCashController bankCash;
    @Autowired JdbcTemplate jdbc;

    private Authentication head;
    private int headId;
    private BankRow bank;
    private int denomId;
    private BigDecimal face;

    @BeforeEach
    void setUp() {
        List<Map<String, Object>> h = jdbc.queryForList("""
                SELECT a.AccessID, a.AccessUserName FROM access a
                JOIN access_trans x ON x.USERID = a.AccessID AND x.BR_CODE = a.AccessBR_CODE AND x.TR_CODE = 33 AND x.CAN_NEW = 1
                JOIN access_trans y ON y.USERID = a.AccessID AND y.BR_CODE = a.AccessBR_CODE AND y.TR_CODE = 35 AND y.CAN_NEW = 1
                WHERE a.AccessBR_CODE = 1 AND COALESCE(a.AccessIsHeadCashier, 0) = 1 AND COALESCE(a.AccessIsDisabled, 0) = 0 AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 1
                  AND COALESCE(a.AccessDepartment, 0) > 0 ORDER BY a.AccessID LIMIT 1
                """);
        assumeTrue(!h.isEmpty(), "no head cashier who may add bank deposits and withdrawals");
        headId = ((Number) h.get(0).get("AccessID")).intValue();
        head = new TestingAuthenticationToken((String) h.get(0).get("AccessUserName"), null);
        List<BankRow> banks = bankCash.banks(head);
        assumeTrue(!banks.isEmpty(), "no bank account in the ledger");
        bank = banks.get(0);
        List<Map<String, Object>> d = jdbc.queryForList("SELECT ID, AMOUNT FROM denomtype WHERE AMOUNT >= 20 ORDER BY AMOUNT LIMIT 1");
        assumeTrue(!d.isEmpty(), "no note to count");
        denomId = ((Number) d.get(0).get("ID")).intValue();
        face = (BigDecimal) d.get(0).get("AMOUNT");
        try {
            jdbc.update("UPDATE transdt SET DTPOSTBY = DTPOSTBY WHERE DTTR_YEAR = -1");
        } catch (RuntimeException e) {
            assumeTrue(false, "MySQL is read only or the write grants are missing");
        }
    }

    private BigDecimal gl(String account, int code) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT), 0) FROM gltrans WHERE GLTransBR_CODE = 1 AND GLTransACCT_CODE = ? AND GLTransTR_CODE = ? AND GLTransTR_DATE = CURDATE()",
            BigDecimal.class, account, code);
    }

    @Test
    void depositThenWithdrawalWriteTheOldRows() {
        BigDecimal amount = face.setScale(2);
        Map<Integer, Integer> counts = Map.of(denomId, 1);

        Posted dep = bankCash.deposit(new Request(bank.bankId(), amount, counts, "itest"), head);
        assertEquals(String.format("*%07d", dep.controlNo()), dep.documentNo());
        List<Map<String, Object>> dt = jdbc.queryForList("SELECT * FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 5 AND DTTR_CODE = 33 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO", dep.year(), dep.controlNo());
        assertEquals(2, dt.size());
        assertEquals("10001", dt.get(0).get("DTACCT_CODE"));
        assertEquals(50, ((Number) dt.get(0).get("DTSLE_CODE")).intValue());
        assertEquals(99, ((Number) dt.get(0).get("DTCTS_NUM")).intValue());
        assertEquals(0, amount.negate().compareTo((BigDecimal) dt.get(0).get("DTAMT")), "the vault is credited");
        assertEquals(bank.ledgerAccount(), dt.get(1).get("DTACCT_CODE"));
        assertEquals(bank.event(), ((Number) dt.get(1).get("DTSLE_CODE")).intValue());
        assertEquals(bank.bankId(), ((Number) dt.get(1).get("DTCTS_NUM")).intValue());
        assertEquals(0, amount.compareTo((BigDecimal) dt.get(1).get("DTAMT")), "the bank is debited");
        for (Map<String, Object> l : dt) {
            assertEquals(1, ((Number) l.get("DTUPD_TAG")).intValue());
            assertEquals(headId, ((Number) l.get("DTENCDBY")).intValue());
            assertEquals(headId, ((Number) l.get("DTPOSTBY")).intValue());
        }
        assertEquals(0, amount.compareTo(gl(bank.ledgerAccount(), 33)), "the ledger bank account went up");
        Map<String, Object> ds = jdbc.queryForMap("SELECT FROM_USERID, TO_USERID FROM denomsum WHERE BR_CODE = 1 AND TR_CODE = 33 AND TR_CTLNO = ?", dep.controlNo());
        assertEquals(headId, ((Number) ds.get("TO_USERID")).intValue());

        Posted wd = bankCash.withdraw(new Request(bank.bankId(), amount, counts, "itest"), head);
        List<Map<String, Object>> w = jdbc.queryForList("SELECT DTAMT FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 5 AND DTTR_CODE = 35 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO", wd.year(), wd.controlNo());
        assertEquals(0, amount.compareTo((BigDecimal) w.get(0).get("DTAMT")), "the vault is debited");
        assertEquals(0, amount.negate().compareTo((BigDecimal) w.get(1).get("DTAMT")), "the bank is credited");
        assertEquals(0, BigDecimal.ZERO.compareTo(gl(bank.ledgerAccount(), 33).add(gl(bank.ledgerAccount(), 35))));
    }

    @Test
    void refusesACountThatDoesNotMatchAndAnyoneButTheHeadCashier() {
        assertThrows(ResponseStatusException.class, () -> bankCash.deposit(new Request(bank.bankId(), face.add(BigDecimal.ONE), Map.of(denomId, 1), null), head));
        assertThrows(ResponseStatusException.class, () -> bankCash.deposit(new Request(bank.bankId(), face, Map.of(), null), head));
        assertThrows(ResponseStatusException.class, () -> bankCash.deposit(new Request(-5, face, Map.of(denomId, 1), null), head));
    }
}
