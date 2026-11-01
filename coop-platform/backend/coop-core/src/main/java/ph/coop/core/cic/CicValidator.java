package ph.coop.core.cic;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks one record of a batch the way the CIC will read it, field by field, and says what is wrong in words a person can act on (guide step 11.3: VALID, WARNING, ERROR). It reads
 * the stored line, so what is checked is what would be sent. An ERROR must be corrected, or the record left out of the file by the reviewer; a WARNING is shown and can be approved.
 * <p>
 * Two rules come from how the cooperative's previous submission was made, not from the CIC's own text: a member with no identification number, and a loan with no CIC contract type,
 * were left out of that file, so they are errors here. A member with no mobile number was left out far more often than not, so that is a warning.
 */
final class CicValidator {

    static final String ERROR = "ERROR";
    static final String WARNING = "WARNING";
    static final String VALID = "VALID";

    /** What is wrong, and how badly. {@code field} says what it is about in words ("Date of birth"). */
    record Finding(String severity, String field, String message) {}

    private CicValidator() {}

    /** The worst severity among the findings: ERROR, then WARNING, else VALID. */
    static String worst(List<Finding> findings) {
        String w = VALID;
        for (Finding f : findings) {
            if (ERROR.equals(f.severity())) return ERROR;
            if (WARNING.equals(f.severity())) w = WARNING;
        }
        return w;
    }

    private static LocalDate parseDate(String v) {
        if (v == null || v.length() != 8 || !v.chars().allMatch(Character::isDigit)) return null;
        try {
            return LocalDate.of(Integer.parseInt(v.substring(4)), Integer.parseInt(v.substring(2, 4)), Integer.parseInt(v.substring(0, 2)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static String get(String[] f, String letters) {
        int i = Csdf.col(letters) - 1;
        return i < f.length ? f[i] : "";
    }

    private static void err(List<Finding> out, String field, String message) {
        out.add(new Finding(ERROR, field, message));
    }

    private static void warn(List<Finding> out, String field, String message) {
        out.add(new Finding(WARNING, field, message));
    }

    /** Checks the fields of a stored line (see {@link Csdf#parse}). {@code cutOff} is the batch's cut-off date, which no date in a record may pass. */
    static List<Finding> validate(String[] f, String provider, LocalDate cutOff) {
        List<Finding> out = new ArrayList<>();
        String type = f.length > 0 ? f[0] : "";
        if (!"ID".equals(type) && !"CI".equals(type) && !"CN".equals(type)) {
            err(out, "Record type", "Unknown record type '" + type + "'.");
            return out;
        }
        int width = "ID".equals(type) ? Csdf1.ID_WIDTH : "CI".equals(type) ? Csdf1.CI_WIDTH : Csdf1.CN_WIDTH;
        if (f.length != width) err(out, "Record length", "The record has " + f.length + " columns; a " + type + " record has " + width + ".");
        if (!provider.equals(get(f, "B"))) err(out, "Provider code", "The provider code is '" + get(f, "B") + "', not " + provider + ".");
        if (!get(f, "E").matches("\\d{11}")) err(out, "Subject number", "The subject number must be 11 digits (branch and client number).");
        if (parseDate(get(f, "D")) == null) err(out, "File date", "The file date is not a date (ddmmyyyy).");
        if ("ID".equals(type)) individual(f, cutOff, out);
        else contract(f, type, cutOff, out);
        return out;
    }

    private static void individual(String[] f, LocalDate cutOff, List<Finding> out) {
        CicCodes codes = CicCodes.get();
        if (get(f, "G").isEmpty()) err(out, "First name", "The first name is missing.");
        if (get(f, "H").isEmpty()) err(out, "Last name", "The last name is missing.");
        String gender = get(f, "M");
        if (!gender.equals("M") && !gender.equals("F")) err(out, "Gender", "The gender must be M or F.");
        String born = get(f, "N");
        LocalDate birth = parseDate(born);
        if (born.isEmpty()) err(out, "Date of birth", "The date of birth is missing.");
        else if (birth == null) err(out, "Date of birth", "The date of birth is not a date (ddmmyyyy).");
        else if (birth.isAfter(cutOff)) err(out, "Date of birth", "The date of birth is after the cut-off date.");
        else {
            int age = Period.between(birth, cutOff).getYears();
            if (age < 18) warn(out, "Date of birth", "The borrower is " + age + " years old at the cut-off date.");
            else if (age > 100) warn(out, "Date of birth", "The borrower would be " + age + " years old at the cut-off date.");
        }
        String civil = get(f, "S");
        if (civil.isEmpty()) warn(out, "Civil status", "The civil status is not known.");
        else if (!codes.has("ID/CivilStatusDomain", civil)) err(out, "Civil status", "'" + civil + "' is not a CIC civil status.");
        String idType = get(f, "BB"), idNo = get(f, "BC");
        if (idNo.isEmpty()) err(out, "Identification number", "There is no identification number (TIN, SSS, PhilHealth or GSIS). The previous submission left such members out.");
        else {
            if (!codes.has("ID/IdentificationTypeDomain", idType)) err(out, "Identification type", "'" + idType + "' is not a CIC identification type.");
            if (idNo.length() < 9) warn(out, "Identification number", "The identification number has only " + idNo.length() + " digits.");
        }
        String second = get(f, "BD");
        if (!second.isEmpty() && !codes.has("ID/IdentificationTypeDomain", second)) err(out, "Identification type", "'" + second + "' is not a CIC identification type.");
        String hdmf = get(f, "BH");
        if (!hdmf.isEmpty() && !codes.has("ID/IDTypeDomain", hdmf)) err(out, "Identity document type", "'" + hdmf + "' is not a CIC identity document type.");
        String mobile = get(f, "CA");
        if (mobile.isEmpty()) warn(out, "Mobile number", "There is no mobile number.");
        else if (mobile.length() < 10 || mobile.length() > 12) warn(out, "Mobile number", "The mobile number has " + mobile.length() + " digits.");
        if (get(f, "AG").isEmpty()) warn(out, "Address", "There is no residence address.");
    }

    private static void contract(String[] f, String type, LocalDate cutOff, List<Finding> out) {
        CicCodes codes = CicCodes.get();
        String no = get(f, "G");
        if (!no.matches("\\d{16}")) err(out, "Contract number", "The contract number must be 16 digits.");
        String contractType = get(f, "H");
        String loanType = no.length() >= 6 ? no.substring(4, 6) : "?";
        String list = "CI".equals(type) ? "CI/InstallmentContractTypeDomain" : "CN/ContractTypeDomain";
        if (contractType.isEmpty()) err(out, "Contract type", "Loan type " + loanType + " has no CIC contract type.");
        else if (!codes.has(list, contractType)) err(out, "Contract type", "'" + contractType + "' is not a CIC " + ("CI".equals(type) ? "installment" : "non-installment") + " contract type.");
        if (!"B".equals(get(f, "F"))) err(out, "Role", "The role must be B (borrower).");
        LocalDate start = parseDate(get(f, "N")), end = parseDate(get(f, "O")), last = parseDate(get(f, "Q"));
        if (parseDate(get(f, "M")) == null) err(out, "Request date", "The request date is not a date (ddmmyyyy).");
        if (start == null) err(out, "Start date", "The start date is not a date (ddmmyyyy).");
        else if (start.isAfter(cutOff)) err(out, "Start date", "The contract starts after the cut-off date.");
        if (get(f, "O").isEmpty()) warn(out, "End date", "The contract has no end date.");
        else if (end == null) err(out, "End date", "The end date is not a date (ddmmyyyy).");
        else if (start != null && end.isBefore(start)) err(out, "End date", "The contract ends before it starts.");
        if (!get(f, "Q").isEmpty()) {
            if (last == null) err(out, "Last payment date", "The last payment date is not a date (ddmmyyyy).");
            else if (last.isAfter(cutOff)) err(out, "Last payment date", "The last payment is after the cut-off date.");
            else if (start != null && last.isBefore(start)) warn(out, "Last payment date", "The last payment is before the contract started.");
        }
        double amount = number(get(f, "T"));
        if (Double.isNaN(amount) || amount <= 0) err(out, "Contract amount", "The contract amount must be more than zero.");
        if ("CI".equals(type)) {
            if (!codes.has("CI/PaymentPeriodicityDomain", get(f, "X"))) err(out, "Payment period", "'" + get(f, "X") + "' is not a CIC payment period.");
            double balance = number(get(f, "AH"));
            if (Double.isNaN(balance) || balance < 0) err(out, "Outstanding balance", "The outstanding balance is missing or negative.");
            else if (!Double.isNaN(amount) && balance > amount + 1) warn(out, "Outstanding balance", "The outstanding balance is more than the contract amount.");
        }
    }

    private static double number(String v) {
        try {
            return v == null || v.isBlank() ? Double.NaN : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
