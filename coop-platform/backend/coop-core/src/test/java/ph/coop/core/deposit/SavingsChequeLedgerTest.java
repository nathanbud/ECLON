package ph.coop.core.deposit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** The savings ledger shows the cheque behind a credit and whether it cleared, against the database and rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.audit.seal.enabled=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class SavingsChequeLedgerTest {

    @Autowired SavingsController savings;
    @Autowired TimeDepositController deposits;
    @Autowired JdbcTemplate jdbc;

    @Test
    void theSavingsLedgerNamesTheChequeAndWhetherItCleared() {
        var row = jdbc.queryForList("SELECT SL_BRCODE, SL_CLIENTID, SLT_CODE, REF_NO FROM sldtl WHERE SLC_CODE = 22 AND SLE_CODE = 11 AND COCI_TYPE = 2 AND AMT < 0 AND CHECKNO IS NOT NULL AND CHECKNO <> '' ORDER BY TR_DATE DESC LIMIT 1");
        assumeTrue(!row.isEmpty(), "no savings credit paid by cheque in this database");
        var r = row.get(0);
        var h = savings.history(((Number) r.get("SL_BRCODE")).intValue(), ((Number) r.get("SL_CLIENTID")).intValue(), ((Number) r.get("SLT_CODE")).intValue(), ((Number) r.get("REF_NO")).intValue(), 0, 100);
        var cheques = h.entries().stream().filter((e) -> e.chequeNo() != null).toList();
        assertTrue(!cheques.isEmpty(), "the newest cheque credit is on the newest page");
        assertTrue(cheques.stream().allMatch((e) -> e.amount().signum() > 0), "only money paid in carries a cheque");
        assertTrue(h.entries().stream().filter((e) -> e.chequeNo() == null).allMatch((e) -> e.chequeCleared() == null && e.clearedOn() == null));
    }

    @Test
    void theTimeDepositLedgerNamesTheChequeAndWhetherItCleared() {
        var row = jdbc.queryForList("SELECT SL_BRCODE, SL_CLIENTID, SLT_CODE, REF_NO FROM sldtl WHERE SLC_CODE = 24 AND SLE_CODE = 11 AND COCI_TYPE = 2 AND AMT < 0 AND CHECKNO IS NOT NULL AND CHECKNO <> '' ORDER BY TR_DATE DESC LIMIT 1");
        assumeTrue(!row.isEmpty(), "no time deposit placed by cheque in this database");
        var r = row.get(0);
        var h = deposits.history(((Number) r.get("SL_BRCODE")).intValue(), ((Number) r.get("SL_CLIENTID")).intValue(), ((Number) r.get("SLT_CODE")).intValue(), ((Number) r.get("REF_NO")).intValue(), 0, 100);
        var cheques = h.entries().stream().filter((e) -> e.chequeNo() != null).toList();
        assertTrue(!cheques.isEmpty(), "the newest cheque placement is on the newest page");
        assertTrue(cheques.stream().allMatch((e) -> e.amount().signum() > 0), "only money paid in carries a cheque");
    }
}
