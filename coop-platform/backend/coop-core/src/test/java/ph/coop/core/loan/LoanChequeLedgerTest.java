package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** The loan ledger shows the cheque behind a payment and whether it cleared, against the database and rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.audit.seal.enabled=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class LoanChequeLedgerTest {

    @Autowired LoansController loans;
    @Autowired JdbcTemplate jdbc;

    @Test
    void theLoanLedgerNamesTheChequeAndWhetherItCleared() {
        var row = jdbc.queryForList("SELECT SL_BRCODE, SL_CLIENTID, SLT_CODE, REF_NO FROM sldtl WHERE SLC_CODE = 12 AND COCI_TYPE = 2 AND AMT < 0 AND CHECKNO IS NOT NULL AND CHECKNO <> '' ORDER BY TR_DATE DESC LIMIT 1");
        assumeTrue(!row.isEmpty(), "no loan payment by cheque in this database");
        var r = row.get(0);
        var d = loans.detail(((Number) r.get("SL_BRCODE")).intValue(), ((Number) r.get("SL_CLIENTID")).intValue(), ((Number) r.get("SLT_CODE")).intValue(), ((Number) r.get("REF_NO")).intValue(), null);
        var cheques = d.entries().stream().filter((e) -> e.chequeNo() != null).toList();
        assertTrue(!cheques.isEmpty(), "the cheque payment is in the ledger");
        assertTrue(cheques.stream().allMatch((e) -> e.credit().signum() > 0), "only a payment carries a cheque");
        assertTrue(d.entries().stream().filter((e) -> e.chequeNo() == null).allMatch((e) -> e.chequeCleared() == null && e.clearedOn() == null));
    }
}
