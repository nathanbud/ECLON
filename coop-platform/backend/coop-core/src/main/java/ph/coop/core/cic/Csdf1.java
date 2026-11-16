package ph.coop.core.cic;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import ph.coop.core.cic.Csdf.Rec;

/**
 * Mapping CSDF-1: how this cooperative's members and loans become the CIC's records. It was worked out from the cooperative's own November 2025 submission file and checked
 * against the ledger (documentation/cic-csdf-mapping.md): 6,740 individuals and 7,777 loans, every loan matched to a loan here by its number. What was checked is written here
 * as it was found; what could not be checked is left empty and listed in {@link #UNVERIFIED}, so it is never guessed. A new CIC layout becomes a new mapping class and version.
 * <p>
 * The record types: ID an individual (a borrower), CI an installment contract (a loan paid in instalments), CN a non-installment contract (a lump-sum loan).
 */
final class Csdf1 {

    static final String VERSION = "CSDF-1";
    static final int ID_WIDTH = 123;
    static final int CI_WIDTH = 143;
    static final int CN_WIDTH = 127;
    static final int HD_WIDTH = 6;
    static final int FT_WIDTH = 4;

    /**
     * Columns the CIC file has but this mapping leaves empty because they could not be checked against the cooperative's file. A batch cannot be approved while the mapping is
     * not confirmed (see CicController); these are what stands in the way of confirming it.
     */
    static final List<String> UNVERIFIED = List.of(
        "Individual: the text of the addresses (AG, AR) is built as street, barangay, city, province, which is close to but not exactly what the old utility wrote; monthly income, its period and currency (CH, CI, CJ), employer phone (CF), employment status and date (CK, CL) are empty",
        "Installment contract: number of installments (U), the installment amounts (Z, AD, AF), the dates AA and AC, the counts AE and AG, payment method (Y), contract status (J) and the overdue days bucket (AI) are empty, because the rule the old utility used for them could not be reproduced",
        "Non-installment contract: only the columns that could be checked are filled (amount, dates, last payment); the rest of the record is empty",
        "Loan type 49 (Co-maker loan) is mapped to CIC type 23 here but was reported as 29 in the November 2025 file; confirm which is right");

    /** The loans reported as non-installment contracts are the lump-sum ones: payment mode 9. The old file gave every one of them CIC contract type 46. */
    static final int LUMP_SUM_MODE = 9;
    static final String LUMP_SUM_CONTRACT_TYPE = "46";

    private Csdf1() {}

    /** A borrower: a member with a loan in the batch. Codes are this system's own (gender 1 male, 2 female; civil status 1 married, 2 single, 3 widow, 4 separated, 5 divorced). */
    record Subject(int branch, int clientId, String last, String first, String middle, String suffix, String gender, String civil, LocalDate birth, String tin, String sss, String gsis,
                   String philHealth, String pagibig, String cell, String street, String barangay, String city, String province, String provStreet, String provBarangay, String provCity,
                   String provProvince, String employer, String spouseFirst, String spouseLast, String spouseMiddle, int beneficiaries) {}

    /** A loan outstanding at the cut-off date. {@code cicType} is what the loan type is mapped to in the old database (null when it is not mapped). */
    record Loan(int branch, int clientId, int typeCode, int refNo, Integer cicType, BigDecimal amount, LocalDate released, LocalDate matures, LocalDate lastPayment, int paymentMode,
                BigDecimal balance) {
        boolean lumpSum() { return paymentMode == LUMP_SUM_MODE; }
    }

    // ---- header and footer ------------------------------------------------------------------------------------------

    static Rec header(String provider, LocalDate fileDate, String version, int submissionType, String comments) {
        return new Rec("HD", HD_WIDTH).set("B", provider).set("C", Csdf.date(fileDate)).set("D", version).set("E", Integer.toString(submissionType)).set("F", comments);
    }

    /** The footer carries the number of records between the header and itself. */
    static Rec footer(String provider, LocalDate fileDate, int records) {
        return new Rec("FT", FT_WIDTH).set("B", provider).set("C", Csdf.date(fileDate)).set("D", Integer.toString(records));
    }

    // ---- keys -----------------------------------------------------------------------------------------------------

    /** How a record names its subject: the branch in two digits and the client number in nine ("01000100523"). */
    static String subjectKey(int branch, int clientId) {
        return String.format("%02d%09d", branch, clientId);
    }

    /** How a contract is numbered: the branch in four digits, the loan type in two and the reference number in ten. */
    static String contractNo(int branch, int typeCode, int refNo) {
        return String.format("%04d%02d%010d", branch, typeCode, refNo);
    }

    // ---- an individual ------------------------------------------------------------------------------------------

    /** The CIC's civil status code for ours: 1-Single, 2 married, 3 divorced or separated, 4 widow. Empty when it is not known. */
    static String civilStatus(String ours) {
        return switch (ours == null ? "" : ours) {
            case "2" -> "1";
            case "1" -> "2";
            case "4", "5" -> "3";
            case "3" -> "4";
            default -> "";
        };
    }

    static String gender(String ours) {
        return "1".equals(ours) ? "M" : "2".equals(ours) ? "F" : "";
    }

    /** The spouse's middle initial as the member's record holds it: its first letter, with the full stop when the record has one. */
    static String initial(String middle) {
        String m = Csdf.clean(middle);
        if (m.isEmpty()) return "";
        return m.length() > 1 && m.charAt(1) == '.' ? m.substring(0, 2) : m.substring(0, 1);
    }

    private static String address(String... parts) {
        StringBuilder s = new StringBuilder();
        for (String p : parts) {
            String c = Csdf.clean(p);
            if (c.isEmpty()) continue;
            if (s.length() > 0) s.append(", ");
            s.append(c);
        }
        return s.toString();
    }

    static Rec individual(Subject s, String provider, LocalDate fileDate) {
        String key = subjectKey(s.branch(), s.clientId());
        Rec r = new Rec("ID", ID_WIDTH)
            .set("B", provider).set("C", String.format("%02d", s.branch())).set("D", Csdf.date(fileDate)).set("E", key)
            .set("G", s.first()).set("H", s.last()).set("I", s.middle()).set("J", s.suffix())
            .set("M", gender(s.gender())).set("N", Csdf.date(s.birth())).set("P", "PH").set("Q", "PH").set("R", "1")
            .set("S", civilStatus(s.civil())).set("T", Integer.toString(s.beneficiaries()))
            .set("V", s.spouseFirst()).set("W", s.spouseLast()).set("X", initial(s.spouseMiddle()))
            .set("AF", "MI").set("AG", address(s.street(), s.barangay(), s.city(), s.province()))
            .set("AQ", "AI").set("AR", address(s.provStreet(), s.provBarangay(), s.provCity(), s.provProvince()))
            .set("AZ", "4");
        // the old file wrote the mother's maiden name, which is the member's middle name, for married members
        if ("1".equals(s.civil())) r.set("L", s.middle());
        // identification numbers, digits only. The first of TIN, SSS, PhilHealth, GSIS goes in the first pair of columns and the next in the second, as the old file did;
        // the Pag-IBIG number (HDMF) has a pair of its own in the list of identity documents.
        String[][] ids = {{"10", s.tin()}, {"11", s.sss()}, {"13", s.philHealth()}, {"12", s.gsis()}};
        int slot = 0;
        String[] pairs = {"BB", "BC", "BD", "BE"};
        for (String[] id : ids) {
            String number = Csdf.digits(id[1]);
            if (number.isEmpty() || slot >= 2) continue;
            r.set(pairs[slot * 2], id[0]).set(pairs[slot * 2 + 1], number);
            slot++;
        }
        String hdmf = Csdf.digits(s.pagibig());
        if (!hdmf.isEmpty()) r.set("BH", "23").set("BI", hdmf);
        String cell = Csdf.digits(s.cell());
        if (!cell.isEmpty()) r.set("BZ", "3").set("CA", cell);
        r.set("CD", s.employer());
        return r;
    }

    // ---- a contract ---------------------------------------------------------------------------------------------

    /** The CIC's payment period for this system's payment mode (1 daily ... 9 lump sum). The monthly, quarterly and semestral ones were checked against the old file. */
    static String periodicity(int mode) {
        return switch (mode) {
            case 1 -> "D";
            case 2 -> "W";
            case 3 -> "F";
            case 4 -> "M";
            case 5 -> "Q";
            case 6 -> "S";
            case 7 -> "Y";
            case 9 -> "P";
            default -> "I";
        };
    }

    private static String whole(BigDecimal v) {
        return v == null ? "" : v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    /** An installment contract (CI) or, for a lump-sum loan, a non-installment contract (CN). */
    static Rec contract(Loan l, String provider, LocalDate fileDate) {
        boolean cn = l.lumpSum();
        Rec r = new Rec(cn ? "CN" : "CI", cn ? CN_WIDTH : CI_WIDTH)
            .set("B", provider).set("C", String.format("%02d", l.branch())).set("D", Csdf.date(fileDate)).set("E", subjectKey(l.branch(), l.clientId())).set("F", "B")
            .set("G", contractNo(l.branch(), l.typeCode(), l.refNo()))
            .set("H", cn ? LUMP_SUM_CONTRACT_TYPE : l.cicType() == null ? "" : String.format("%02d", l.cicType()))
            .set("I", "AC").set("K", "PHP").set("L", "PHP")
            .set("M", Csdf.date(l.released())).set("N", Csdf.date(l.released())).set("O", Csdf.date(l.matures())).set("Q", Csdf.date(l.lastPayment()))
            .set("T", whole(l.amount()));
        if (cn) {
            r.set("U", "NA").set("V", "32").set("Y", whole(l.amount()));
        } else {
            r.set("V", "NA").set("W", "32").set("X", periodicity(l.paymentMode())).set("AB", "0").set("AH", whole(l.balance()));
        }
        return r;
    }
}
