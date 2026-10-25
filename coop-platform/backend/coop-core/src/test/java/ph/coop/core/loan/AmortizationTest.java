package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.web.server.ResponseStatusException;

/** The amortization reference and the preview calculator: administrators only, and the preview gives the schedule the application would. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@WithMockUser(username = "tester", roles = "ADMIN")
class AmortizationTest {

    @Autowired AmortizationController amortization;

    private static AmortizationController.Try trial(int kind, String amount, int months, int mode, String rate, LocalDate release, Integer dayCount) {
        return new AmortizationController.Try(kind, new BigDecimal(amount), months, mode, new BigDecimal(rate), release, dayCount);
    }

    @Test void theKindsAreListedWithWhatIsMappedAndWhoUsesThem() {
        var kinds = amortization.kinds();
        assertEquals(6, kinds.size());
        var dim = kinds.stream().filter((k) -> k.id() == 1).findFirst().orElseThrow();
        var ann = kinds.stream().filter((k) -> k.id() == 3).findFirst().orElseThrow();
        var line = kinds.stream().filter((k) -> k.id() == 2).findFirst().orElseThrow();
        assertTrue(dim.supported() && ann.supported());
        assertTrue(!line.supported() && line.formula().startsWith("Not mapped"));
        assertTrue(dim.loans() > 1000 && ann.loans() > 1000, "real loan counts");
        assertTrue(!dim.types().isEmpty());
    }

    @Test void thePreviewGivesTheRealSalaryLoanSchedule() {
        var p = amortization.preview(trial(3, "80000.00", 12, LoanSchedule.MONTHLY, "10", LocalDate.of(2026, 3, 18), 1));
        assertEquals(12, p.payments());
        assertEquals(new BigDecimal("7028.18"), p.payment());
        assertEquals(new BigDecimal("6370.65"), p.rows().get(1).principalDue());
        assertEquals(new BigDecimal("657.53"), p.rows().get(1).interestDue());
        assertEquals(LocalDate.of(2027, 3, 18), p.maturity());
        assertEquals(0, p.totalPaid().compareTo(new BigDecimal("80000.00").add(p.totalInterest())));
    }

    @Test void thePreviewGivesADiminishingScheduleToo() {
        var p = amortization.preview(trial(1, "100000.00", 6, LoanSchedule.MONTHLY, "10", LocalDate.of(2026, 3, 31), null));
        assertEquals(6, p.payments());
        assertEquals(new BigDecimal("16666.67"), p.rows().get(1).principalDue());
        assertEquals(new BigDecimal("821.92"), p.rows().get(1).interestDue());
    }

    @Test void badInputAndUnmappedKindsAreRefused() {
        assertThrows(ResponseStatusException.class, () -> amortization.preview(trial(2, "1000", 12, LoanSchedule.MONTHLY, "10", null, null)));
        assertThrows(ResponseStatusException.class, () -> amortization.preview(trial(3, "0", 12, LoanSchedule.MONTHLY, "10", null, null)));
        assertThrows(ResponseStatusException.class, () -> amortization.preview(trial(3, "1000", 0, LoanSchedule.MONTHLY, "10", null, null)));
        assertThrows(ResponseStatusException.class, () -> amortization.preview(trial(3, "1000", 12, LoanSchedule.MONTHLY, "150", null, null)));
        // half-yearly is mapped for annuity only
        assertThrows(ResponseStatusException.class, () -> amortization.preview(trial(1, "1000", 12, LoanSchedule.SEMIANNUAL, "10", null, null)));
        // a term that is not a whole number of quarters
        assertThrows(ResponseStatusException.class, () -> amortization.preview(trial(3, "1000", 4, LoanSchedule.QUARTERLY, "10", null, null)));
    }

    @Test @WithMockUser(username = "tester", roles = "USER") void onlyAdministratorsCanUseIt() {
        assertThrows(AccessDeniedException.class, () -> amortization.kinds());
        assertThrows(AccessDeniedException.class, () -> amortization.preview(trial(3, "1000", 12, LoanSchedule.MONTHLY, "10", null, null)));
    }
}
