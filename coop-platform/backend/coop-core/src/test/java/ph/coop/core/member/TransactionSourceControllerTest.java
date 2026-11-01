package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** The transaction behind a ledger row: header, ledger lines that balance, and only for a transaction in the member's own ledger. Read only, against the database. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.audit.seal.enabled=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class TransactionSourceControllerTest {

    @Autowired TransactionSourceController source;
    @Autowired JdbcTemplate jdbc;

    private java.util.Map<String, Object> loanVoucher() {
        var rows = jdbc.queryForList("SELECT SL_BRCODE, SL_CLIENTID, TR_CODE, TR_CTLNO, EXTRACT(YEAR FROM TR_DATE) AS Y FROM sldtl s "
            + "WHERE s.SLC_CODE = 12 AND s.SLE_CODE = 11 AND s.TR_CODE = 20 AND s.TR_DATE >= '2026-01-01' "
            + "AND EXISTS (SELECT 1 FROM transdt d WHERE d.DTTR_BRCODE = s.SL_BRCODE AND d.DTTR_YEAR = EXTRACT(YEAR FROM s.TR_DATE) AND d.DTTR_CODE = s.TR_CODE "
            + "AND d.DTTR_CTLNO = s.TR_CTLNO AND d.DTSLC_CODE IS NULL) ORDER BY s.TR_DATE DESC LIMIT 1");
        assumeTrue(!rows.isEmpty(), "no loan voucher in 2026");
        return rows.get(0);
    }

    @Test
    void aLoanVoucherShowsItsHeaderAndBalancedLines() {
        var r = loanVoucher();
        int branch = ((Number) r.get("SL_BRCODE")).intValue(), client = ((Number) r.get("SL_CLIENTID")).intValue();
        var d = source.detail(branch, client, ((Number) r.get("Y")).intValue(), ((Number) r.get("TR_CODE")).intValue(), ((Number) r.get("TR_CTLNO")).longValue());
        assertEquals("Loan Voucher", d.header().type());
        assertEquals("LV", d.header().typeShort());
        assertTrue(d.header().controlNo().matches("\\d{4} - \\d{8}"), d.header().controlNo());
        assertEquals("APPROVED", d.header().status());
        assertTrue(!d.lines().isEmpty());
        assertEquals(0, d.difference().signum(), "debits equal credits");
        assertEquals(0, d.debit().compareTo(d.credit()));
        assertTrue(d.lines().stream().anyMatch((l) -> l.sc() != null && l.sc() == 12 && l.title().contains(" : ")), "a loan line is titled by its type and event");
        assertTrue(d.lines().stream().anyMatch((l) -> l.sc() == null && l.title() != null), "a general ledger line is titled by its account");
        assertTrue(d.lines().stream().allMatch((l) -> l.debit().signum() == 0 || l.credit().signum() == 0), "a line is a debit or a credit");
        assertTrue(d.header().clientNo().matches("\\d{4}-\\d{7}-\\d"), d.header().clientNo());
    }

    @Test
    void aTransactionThatIsNotInThisMembersLedgerIsNotShown() {
        var r = loanVoucher();
        int branch = ((Number) r.get("SL_BRCODE")).intValue(), client = ((Number) r.get("SL_CLIENTID")).intValue();
        int year = ((Number) r.get("Y")).intValue(), code = ((Number) r.get("TR_CODE")).intValue();
        long ctl = ((Number) r.get("TR_CTLNO")).longValue();
        assertThrows(ResponseStatusException.class, () -> source.detail(branch, client + 1000000, year, code, ctl), "another member");
        assertThrows(ResponseStatusException.class, () -> source.detail(branch, client, year, code, 999999999L), "no such control number");
    }

    @Test
    void theNumbersAreFormattedAsTheOldSystemPrintsThem() {
        assertEquals("0001-0028711-9", TransactionSourceController.clientNo(1, 28711));
        assertEquals("00001999", TransactionSourceController.pad(1999, 8));
        assertEquals("Osting, Samson M.", TransactionSourceController.name("Osting", "Samson", "Mallare"));
        assertEquals("Osting, Samson", TransactionSourceController.name(" Osting ", "Samson", ""));
        assertEquals(null, TransactionSourceController.name(null, "", null));
    }
}
