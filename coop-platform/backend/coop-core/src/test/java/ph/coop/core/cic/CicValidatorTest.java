package ph.coop.core.cic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ph.coop.core.cic.CicValidator.Finding;
import ph.coop.core.cic.Csdf.Rec;
import ph.coop.core.cic.Csdf1.Loan;
import ph.coop.core.cic.Csdf1.Subject;

class CicValidatorTest {

    private static final LocalDate CUT = LocalDate.of(2024, 12, 31);
    private static final LocalDate FILE = LocalDate.of(2024, 12, 31);
    private static final String P = "CO008840";

    private static Subject subject(String tin, String cell, LocalDate birth, String gender, String civil) {
        return new Subject(1, 100523, "Reyes", "Ana", "Santos", "", gender, civil, birth, tin, "", "", "", "", cell, "12 Main St", "", "Baguio", "Benguet", "", "", "", "", "", "", "", "", 0);
    }

    private static List<Finding> check(Rec r) {
        return CicValidator.validate(Csdf.parse(r.line()), P, CUT);
    }

    private static boolean has(List<Finding> f, String severity, String field) {
        return f.stream().anyMatch((x) -> x.severity().equals(severity) && x.field().equals(field));
    }

    private static Rec goodPerson() {
        return Csdf1.individual(subject("123456789", "09171234567", LocalDate.of(1985, 3, 9), "2", "1"), P, FILE);
    }

    private static Loan loan(Integer cic, int mode) {
        return new Loan(1, 100523, 21, 4321, cic, new BigDecimal("50000"), LocalDate.of(2024, 3, 1), LocalDate.of(2026, 3, 1), LocalDate.of(2024, 12, 15), mode, new BigDecimal("30000"));
    }

    @Test
    void aCompleteIndividualIsValid() {
        var f = check(goodPerson());
        assertEquals("VALID", CicValidator.worst(f), f.toString());
        assertTrue(f.isEmpty());
    }

    @Test
    void aMissingNameDateOrGenderIsAnError() {
        Rec r = goodPerson();
        r.set("H", "").set("G", "").set("N", "").set("M", "X");
        var f = check(r);
        assertTrue(has(f, "ERROR", "Last name"));
        assertTrue(has(f, "ERROR", "First name"));
        assertTrue(has(f, "ERROR", "Date of birth"));
        assertTrue(has(f, "ERROR", "Gender"));
        assertEquals("ERROR", CicValidator.worst(f));
    }

    @Test
    void aBirthDateAfterTheCutOffOrNotADateIsAnError() {
        assertTrue(has(check(goodPerson().set("N", "01012025")), "ERROR", "Date of birth"));
        assertTrue(has(check(goodPerson().set("N", "31022000")), "ERROR", "Date of birth"), "31 February");
        assertTrue(has(check(goodPerson().set("N", "1985-03-09")), "ERROR", "Date of birth"));
    }

    @Test
    void anUnusualAgeIsOnlyAWarning() {
        assertTrue(has(check(Csdf1.individual(subject("123456789", "09171234567", LocalDate.of(2010, 1, 1), "2", "2"), P, FILE)), "WARNING", "Date of birth"));
        assertTrue(has(check(Csdf1.individual(subject("123456789", "09171234567", LocalDate.of(1900, 1, 1), "2", "2"), P, FILE)), "WARNING", "Date of birth"));
    }

    @Test
    void noIdentificationNumberIsAnErrorAndNoMobileOrAddressIsAWarning() {
        var noId = check(Csdf1.individual(subject("", "09171234567", LocalDate.of(1985, 3, 9), "2", "1"), P, FILE));
        assertTrue(has(noId, "ERROR", "Identification number"));
        var noPhone = check(Csdf1.individual(subject("123456789", "", LocalDate.of(1985, 3, 9), "2", "1"), P, FILE));
        assertEquals("WARNING", CicValidator.worst(noPhone));
        assertTrue(has(noPhone, "WARNING", "Mobile number"));
        assertTrue(has(check(goodPerson().set("AG", "")), "WARNING", "Address"));
        assertTrue(has(check(goodPerson().set("S", "")), "WARNING", "Civil status"));
        assertTrue(has(check(goodPerson().set("S", "9")), "ERROR", "Civil status"));
    }

    @Test
    void anIdentificationTypeTheCicDoesNotKnowIsAnError() {
        assertTrue(has(check(goodPerson().set("BB", "99")), "ERROR", "Identification type"));
        assertTrue(has(check(goodPerson().set("BH", "99").set("BI", "123")), "ERROR", "Identity document type"));
    }

    @Test
    void theProviderTheSubjectNumberAndTheLengthAreChecked() {
        assertTrue(has(check(goodPerson().set("B", "XX000000")), "ERROR", "Provider code"));
        assertTrue(has(check(goodPerson().set("E", "123")), "ERROR", "Subject number"));
        assertTrue(has(CicValidator.validate(Csdf.parse("ID|CO008840|1"), P, CUT), "ERROR", "Record length"));
        assertTrue(has(CicValidator.validate(Csdf.parse("XX|a"), P, CUT), "ERROR", "Record type"));
    }

    @Test
    void aCompleteInstallmentContractIsValid() {
        var f = check(Csdf1.contract(loan(15, 4), P, FILE));
        assertEquals("VALID", CicValidator.worst(f), f.toString());
    }

    @Test
    void aLoanTypeWithNoCicContractTypeIsAnErrorThatNamesTheLoanType() {
        var f = check(Csdf1.contract(loan(null, 4), P, FILE));
        assertTrue(has(f, "ERROR", "Contract type"));
        assertTrue(f.stream().anyMatch((x) -> x.message().contains("Loan type 21")), f.toString());
        assertTrue(has(check(Csdf1.contract(loan(99, 4), P, FILE)), "ERROR", "Contract type"), "a type the CIC does not have");
    }

    @Test
    void aLumpSumContractIsCheckedAgainstTheNonInstallmentTypes() {
        assertEquals("VALID", CicValidator.worst(check(Csdf1.contract(loan(22, 9), P, FILE))));
        assertTrue(has(check(Csdf1.contract(loan(22, 9), P, FILE).set("H", "15")), "ERROR", "Contract type"), "15 is an installment type");
    }

    @Test
    void datesAndAmountsOfAContractAreChecked() {
        assertTrue(has(check(Csdf1.contract(loan(15, 4), P, FILE).set("O", "01012020")), "ERROR", "End date"), "ends before it starts");
        assertTrue(has(check(Csdf1.contract(loan(15, 4), P, FILE).set("O", "")), "WARNING", "End date"));
        assertTrue(has(check(Csdf1.contract(loan(15, 4), P, FILE).set("N", "02022025")), "ERROR", "Start date"), "after the cut-off");
        assertTrue(has(check(Csdf1.contract(loan(15, 4), P, FILE).set("Q", "05052025")), "ERROR", "Last payment date"));
        assertTrue(has(check(Csdf1.contract(loan(15, 4), P, FILE).set("T", "0")), "ERROR", "Contract amount"));
        assertTrue(has(check(Csdf1.contract(loan(15, 4), P, FILE).set("AH", "-5")), "ERROR", "Outstanding balance"));
        assertTrue(has(check(Csdf1.contract(loan(15, 4), P, FILE).set("AH", "90000")), "WARNING", "Outstanding balance"));
        assertTrue(has(check(Csdf1.contract(loan(15, 4), P, FILE).set("G", "123")), "ERROR", "Contract number"));
        assertTrue(has(check(Csdf1.contract(loan(15, 4), P, FILE).set("X", "Z")), "ERROR", "Payment period"));
    }

    @Test
    void theWorstFindingDecidesTheStatus() {
        assertEquals("VALID", CicValidator.worst(List.of()));
        assertEquals("WARNING", CicValidator.worst(List.of(new Finding("WARNING", "a", "b"))));
        assertEquals("ERROR", CicValidator.worst(List.of(new Finding("WARNING", "a", "b"), new Finding("ERROR", "c", "d"))));
    }

    @Test
    void theCodeListsAreLoadedFromTheCicWorkbook() {
        CicCodes c = CicCodes.get();
        assertTrue(c.has("ID/CivilStatusDomain", "1"), "single is written 1, the workbook says 1-Single");
        assertTrue(c.has("ID/CivilStatusDomain", "1-Single"));
        assertTrue(c.has("ID/IdentificationTypeDomain", "10"), "TIN");
        assertTrue(c.has("ID/IDTypeDomain", "23"), "HDMF");
        assertTrue(c.has("CI/InstallmentContractTypeDomain", "20"), "salary loan");
        assertTrue(c.has("CN/ContractTypeDomain", "46"));
        assertTrue(c.has("CI/PaymentPeriodicityDomain", "M"));
        assertTrue(c.size("ID/PSICDomain") > 1000);
        assertTrue(c.size("ID/PSOCDomain") > 500);
        assertEquals("TIN", c.describe("ID/IdentificationTypeDomain", "10").trim());
    }
}
