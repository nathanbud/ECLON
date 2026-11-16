package ph.coop.core.cic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ph.coop.core.cic.Csdf.Rec;
import ph.coop.core.cic.Csdf1.Loan;
import ph.coop.core.cic.Csdf1.Subject;

/** The CSDF line format and mapping CSDF-1, with values and shapes taken from the cooperative's own November 2025 submission file. */
class CsdfTest {

    private static final LocalDate CUT = LocalDate.of(2024, 12, 31);
    private static final String P = "CO008840";

    @Test
    void columnLettersCountLikeASpreadsheet() {
        assertEquals(1, Csdf.col("A"));
        assertEquals(26, Csdf.col("Z"));
        assertEquals(27, Csdf.col("AA"));
        assertEquals(55, Csdf.col("BC"));
        assertEquals(143, Csdf.col("EM"), "the CI record is 143 columns wide");
        for (int n : new int[] {1, 26, 27, 52, 53, 123, 143}) assertEquals(n, Csdf.col(Csdf.letters(n)));
    }

    @Test
    void aRecordIsAFixedNumberOfPipeSeparatedFieldsAndRefusesAColumnOutsideIt() {
        Rec r = new Rec("HD", 6).set("B", "CO008840").set("F", "a|b\r\nc ");
        assertEquals("HD|CO008840||||a b  c", r.line(), "a pipe or line break inside a value would break the line");
        assertEquals(6, Csdf.parse(r.line()).length);
        assertThrows(IllegalArgumentException.class, () -> r.set("G", "x"));
    }

    @Test
    void theHeaderAndFooterAreWrittenAsInTheOldFile() {
        LocalDate fileDate = LocalDate.of(2025, 11, 30);
        assertEquals("HD|CO008840|30112025|1.0|0|November 2024", Csdf1.header(P, fileDate, "1.0", 0, "November 2024 ").line());
        assertEquals("FT|CO008840|30112025|14517", Csdf1.footer(P, fileDate, 14517).line());
    }

    @Test
    void subjectsAndContractsAreNumberedAsInTheOldFile() {
        assertEquals("01000100523", Csdf1.subjectKey(1, 100523));
        assertEquals(11, Csdf1.subjectKey(1, 100523).length());
        assertEquals("0001210000004321", Csdf1.contractNo(1, 21, 4321));
        assertEquals(16, Csdf1.contractNo(1, 21, 4321).length());
    }

    @Test
    void ourCodesBecomeTheCicCodes() {
        assertEquals("1", Csdf1.civilStatus("2"), "single");
        assertEquals("2", Csdf1.civilStatus("1"), "married");
        assertEquals("4", Csdf1.civilStatus("3"), "widow");
        assertEquals("3", Csdf1.civilStatus("4"), "separated");
        assertEquals("3", Csdf1.civilStatus("5"), "divorced");
        assertEquals("", Csdf1.civilStatus("6"), "not applicable");
        assertEquals("M", Csdf1.gender("1"));
        assertEquals("F", Csdf1.gender("2"));
        assertEquals("", Csdf1.gender("3"));
        assertEquals("M", Csdf1.periodicity(4).equals("M") ? "M" : "?");
        assertEquals("Q", Csdf1.periodicity(5));
        assertEquals("S", Csdf1.periodicity(6));
        assertEquals("A.", Csdf1.initial("A. Reyes"), "keeps the full stop the record has");
        assertEquals("A", Csdf1.initial("Alma"));
        assertEquals("", Csdf1.initial(null));
    }

    private static Subject subject(String civil, String tin, String sss, String cell) {
        return new Subject(1, 100523, "Reyes", "Ana", "Santos", "", "2", civil, LocalDate.of(1985, 3, 9), tin, sss, "", "", "1234-5678", cell, "12 Main St", "", "Baguio", "Benguet",
            "", "", "", "", "ACME Co", "Juan", "Reyes", "Perez", 2);
    }

    @Test
    void anIndividualFillsTheColumnsTheOldFileFilled() {
        Rec r = Csdf1.individual(subject("1", "123-456-789", "", "0917 123 4567"), P, LocalDate.of(2025, 11, 30));
        assertEquals(Csdf1.ID_WIDTH, r.fields.length);
        assertEquals("ID", r.get("A"));
        assertEquals(P, r.get("B"));
        assertEquals("01", r.get("C"));
        assertEquals("30112025", r.get("D"));
        assertEquals("01000100523", r.get("E"));
        assertEquals("Ana", r.get("G"), "the first name comes before the last, as in the old file");
        assertEquals("Reyes", r.get("H"));
        assertEquals("Santos", r.get("I"));
        assertEquals("Santos", r.get("L"), "mother's maiden name is the middle name, written for married members");
        assertEquals("F", r.get("M"));
        assertEquals("09031985", r.get("N"));
        assertEquals("PH", r.get("P"));
        assertEquals("2", r.get("S"), "married");
        assertEquals("2", r.get("T"), "number of beneficiaries");
        assertEquals("Juan", r.get("V"));
        assertEquals("Reyes", r.get("W"));
        assertEquals("P", r.get("X"));
        assertEquals("MI", r.get("AF"));
        assertEquals("12 Main St, Baguio, Benguet", r.get("AG"));
        assertEquals("AI", r.get("AQ"));
        assertEquals("10", r.get("BB"));
        assertEquals("123456789", r.get("BC"), "identification numbers are digits only");
        assertEquals("23", r.get("BH"));
        assertEquals("12345678", r.get("BI"));
        assertEquals("3", r.get("BZ"));
        assertEquals("09171234567", r.get("CA"));
        assertEquals("ACME Co", r.get("CD"));
    }

    @Test
    void withoutATinTheNextIdentificationNumberTakesItsPlaceAsInTheOldFile() {
        Rec r = Csdf1.individual(subject("2", "", "33-1234567-8", ""), P, CUT);
        assertEquals("11", r.get("BB"), "SSS");
        assertEquals("3312345678", r.get("BC"));
        assertEquals("", r.get("BD"));
        assertEquals("", r.get("L"), "only a married member has it");
        assertEquals("1", r.get("S"), "single");
        assertEquals("", r.get("CA"));
        Rec both = Csdf1.individual(subject("2", "111111111", "2222222222", ""), P, CUT);
        assertEquals("10", both.get("BB"));
        assertEquals("11", both.get("BD"));
        assertEquals("2222222222", both.get("BE"));
    }

    private static Loan loan(int mode, Integer cic) {
        return new Loan(1, 100523, 21, 4321, cic, new BigDecimal("50000.40"), LocalDate.of(2024, 3, 1), LocalDate.of(2026, 3, 1), LocalDate.of(2024, 12, 15), mode, new BigDecimal("30123.50"));
    }

    @Test
    void anInstallmentLoanBecomesACiRecordWithTheColumnsThatWereChecked() {
        Rec r = Csdf1.contract(loan(4, 15), P, LocalDate.of(2025, 11, 30));
        assertEquals("CI", r.type);
        assertEquals(Csdf1.CI_WIDTH, r.fields.length);
        assertEquals("01000100523", r.get("E"));
        assertEquals("B", r.get("F"));
        assertEquals("0001210000004321", r.get("G"));
        assertEquals("15", r.get("H"), "the CIC contract type of the loan type");
        assertEquals("AC", r.get("I"));
        assertEquals("PHP", r.get("K"));
        assertEquals("01032024", r.get("M"));
        assertEquals("01032024", r.get("N"));
        assertEquals("01032026", r.get("O"), "maturity");
        assertEquals("15122024", r.get("Q"), "last payment");
        assertEquals("50000", r.get("T"), "whole pesos");
        assertEquals("M", r.get("X"));
        assertEquals("30124", r.get("AH"), "outstanding balance");
        assertEquals("", r.get("J"), "left empty: not verified");
        assertEquals("", r.get("AI"), "left empty: not verified");
    }

    @Test
    void aLumpSumLoanBecomesAnCnRecordOfTypeFortySix() {
        Rec r = Csdf1.contract(loan(9, 22), P, CUT);
        assertEquals("CN", r.type);
        assertEquals(Csdf1.CN_WIDTH, r.fields.length);
        assertEquals("46", r.get("H"), "the old file gave every lump-sum loan this type, whatever the loan type was mapped to");
        assertEquals("50000", r.get("T"));
        assertEquals("50000", r.get("Y"), "the amount is repeated, as in the old file");
        assertEquals("NA", r.get("U"));
        assertEquals("", r.get("X"));
    }

    @Test
    void aLoanTypeWithNoCicTypeLeavesTheTypeEmptyForValidationToCatch() {
        assertEquals("", Csdf1.contract(loan(4, null), P, CUT).get("H"));
        assertEquals("09", Csdf1.contract(loan(4, 9), P, CUT).get("H"), "two digits");
    }

    @Test
    void whatIsNotVerifiedIsListedAndTheMappingIsNamed() {
        assertEquals("CSDF-1", Csdf1.VERSION);
        assertFalse(Csdf1.UNVERIFIED.isEmpty());
        assertTrue(Csdf1.UNVERIFIED.stream().anyMatch((s) -> s.contains("overdue days bucket")));
        assertEquals(List.of("ID", "CI", "CN"), List.of(Csdf1.individual(subject("1", "1", "", ""), P, CUT).type, Csdf1.contract(loan(4, 15), P, CUT).type, Csdf1.contract(loan(9, 15), P, CUT).type));
    }
}
