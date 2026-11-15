package ph.coop.core.loan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The Amortization Schedule a member signs (CONFORME) when applying for a loan, laid out like the old system's printout (template
 * {@code reports/amortization-schedule.jrxml}): the cooperative and office at the top of every page, the loan's terms on the first page (later pages repeat only
 * the member and the loan), one line per due date with the total due, others, interest, principal and the balance it should come down to, the totals, then a
 * page for the member's signature. Built from the schedule the application saves ({@code LoanApplicationController.plan}), so the two always agree.
 */
@Component
public class AmortizationSchedulePrint {

    private static final int LOAN_CLASS = 12, YEARS = 7;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MMMM d, yyyy    hh:mm a", Locale.ENGLISH);

    /** One printed line: the due date and what falls due on it; line 0 is the release with nothing due. */
    public record Line(int no, LocalDate date, BigDecimal total, BigDecimal others, BigDecimal interest, BigDecimal principal, BigDecimal balance) {}

    /** Everything printed. {@code refNo} is the loan number, or "To be assigned" before the application is saved. */
    public record Sheet(String coopName, String officeName, String address, String tin, String accountNo, String borrower, String refNo, String loanType,
                        String officer, String status, String geoGroup, BigDecimal principal, String mode, String term, BigDecimal rate, BigDecimal effectiveRate,
                        String basedOn, String interestMode, BigDecimal penaltyRate, String penaltyMode, LocalDate released, LocalDate firstPrincipalDue,
                        LocalDate firstInterestDue, LocalDate maturity, String collectionType, List<Line> lines) {
        public BigDecimal totalDue() { return sum(Line::total); }
        public BigDecimal totalOthers() { return sum(Line::others); }
        public BigDecimal totalInterest() { return sum(Line::interest); }
        public BigDecimal totalPrincipal() { return sum(Line::principal); }
        private BigDecimal sum(java.util.function.Function<Line, BigDecimal> f) { return lines.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add); }
    }

    private final JdbcTemplate jdbc;
    private final String configuredName;
    private final String appName;
    private volatile JasperReport compiled;

    AmortizationSchedulePrint(JdbcTemplate jdbc, @Value("${coop.name:}") String configuredName, @Value("${coop.report.app-name:Coop Platform}") String appName) {
        this.jdbc = jdbc;
        this.configuredName = configuredName == null ? "" : configuredName.trim();
        this.appName = appName;
    }

    /** The lines of a schedule: each due date with its interest and principal, nothing else charged by an application (others 0.00). */
    static List<Line> lines(List<LoanSchedule.Row> rows) {
        List<Line> out = new ArrayList<>();
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        for (int i = 0; i < rows.size(); i++) {
            LoanSchedule.Row r = rows.get(i);
            BigDecimal interest = r.interestDue().setScale(2, RoundingMode.HALF_UP), principal = r.principalDue().setScale(2, RoundingMode.HALF_UP);
            out.add(new Line(i, r.dueDate(), interest.add(principal), zero, interest, principal, r.principalBalance().setScale(2, RoundingMode.HALF_UP)));
        }
        return out;
    }

    /**
     * The yearly rate the member pays on the amount borrowed, as the old printout shows it ("Effective"): the total interest over the principal and the
     * years of the term (12 % a year on a diminishing balance over 10 years comes to about 6.06 %).
     */
    static BigDecimal effectiveRate(BigDecimal totalInterest, BigDecimal principal, int termMonths) {
        if (principal.signum() <= 0 || termMonths <= 0) return BigDecimal.ZERO.setScale(2);
        return totalInterest.multiply(BigDecimal.valueOf(1200)).divide(principal.multiply(BigDecimal.valueOf(termMonths)), 2, RoundingMode.HALF_UP);
    }

    Sheet sheet(int branch, int clientId, int typeCode, Map<String, Object> type, Integer purpose, BigDecimal amount, LoanApplicationController.Planned planned,
                Integer refNo, int officerId) {
        Map<String, Object> o = first("SELECT BranchesDesc, Address, TIN, ACRONYM FROM branches WHERE BranchesID = ?", branch);
        Map<String, Object> c = first("SELECT c.LName, c.FName, c.MName, c.ClientIDChkDgt, g.GeoDesc FROM client c LEFT JOIN geo g ON g.GeoID = c.Geographical WHERE c.ClientIDBrCode = ? AND c.ClientID = ?",
            branch, clientId);
        String borrower = "";
        if (!c.isEmpty()) {
            String mn = str(c.get("MName"));
            borrower = str(c.get("LName")) + ", " + str(c.get("FName")) + (mn.isBlank() ? "" : " " + mn.charAt(0) + ".");
        }
        String chk = c.get("ClientIDChkDgt") == null ? "" : "-" + c.get("ClientIDChkDgt");
        Map<String, Object> who = first("SELECT AccessLName, AccessFName FROM access WHERE AccessID = ? AND AccessBR_CODE = ?", officerId, branch);
        String officer = who.isEmpty() ? "" : str(who.get("AccessLName")) + ", " + str(who.get("AccessFName"));
        String purposeText = purpose == null ? "" : desc("SELECT LoanPurposeDesc FROM loanpurpose WHERE LoanPurposeID = ?", purpose);
        String typeName = str(type.get("SLTypeM_DESC"));
        String unit = desc("SELECT TermDesc FROM term WHERE TermID = ?", planned.unit());
        List<Line> lines = lines(planned.rows());
        BigDecimal interest = lines.stream().map(Line::interest).reduce(BigDecimal.ZERO, BigDecimal::add);
        Object penalty = type.get("SLTypePEN_RATE");
        LocalDate first = planned.rows().size() > 1 ? planned.rows().get(1).dueDate() : null;
        return new Sheet(coopName(str(o.get("ACRONYM"))), str(o.get("BranchesDesc")), str(o.get("Address")), str(o.get("TIN")),
            String.format("%04d-%07d%s", branch, clientId, chk), borrower,
            refNo == null ? "To be assigned" : String.format("%02d%02d-%08d", LOAN_CLASS, typeCode, refNo), typeName + (purposeText.isBlank() ? "" : " - " + purposeText),
            officer.isBlank() ? "n/a" : officer, refNo == null ? "New" : "Applied", str(c.get("GeoDesc")), amount,
            desc("SELECT PaymentModeDesc FROM paymentmode WHERE PaymentModeID = ?", planned.paymentMode()),
            BigDecimal.valueOf(planned.terms()).setScale(2) + " " + (unit.isBlank() ? (planned.unit() == YEARS ? "Years" : "Months") : unit),
            planned.rate(), effectiveRate(interest, amount, planned.termMonths()),
            desc("SELECT AmorTypeDesc FROM amortype WHERE AmorTypeID = ?", type.get("SLTypeAMORTYPE")),
            desc("SELECT PaymentModeDesc FROM paymentmode WHERE PaymentModeID = ?", type.get("SLTypeIPMT_MODE")),
            penalty == null ? null : (BigDecimal) penalty, desc("SELECT PaymentModeDesc FROM paymentmode WHERE PaymentModeID = ?", type.get("SLTypePEN_MODE")),
            planned.release(), first, first, planned.rows().get(planned.rows().size() - 1).dueDate(),
            desc("SELECT CollectTypeDesc FROM collecttype WHERE CollectTypeID = ?", type.get("SLTypeCOLLECTTYPE")), lines);
    }

    public byte[] pdf(Sheet s, String generatedBy, LocalDateTime printedAt) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JasperExportManager.exportReportToPdfStream(filled(s, generatedBy, printedAt), out);
            return out.toByteArray();
        } catch (JRException | IOException e) {
            throw new IllegalStateException("The amortization schedule could not be made: " + e.getMessage(), e);
        }
    }

    JasperPrint filled(Sheet s, String generatedBy, LocalDateTime printedAt) throws JRException, IOException {
        Map<String, Object> p = new HashMap<>();
        p.put("coopName", s.coopName());
        p.put("officeName", s.officeName());
        p.put("address", s.address());
        p.put("tin", s.tin().isBlank() ? "" : "TIN: " + s.tin());
        p.put("createdText", "Date Created: " + printedAt.format(STAMP).replace("AM", "am").replace("PM", "pm"));
        p.put("accountNo", s.accountNo());
        p.put("borrower", s.borrower());
        p.put("refNo", s.refNo());
        p.put("loanType", s.loanType());
        p.put("officer", s.officer());
        p.put("status", s.status());
        p.put("geoGroup", s.geoGroup());
        p.put("principalText", money(s.principal()));
        p.put("mode", s.mode());
        p.put("term", s.term());
        p.put("interestText", percent(s.rate()));
        p.put("effectiveText", s.effectiveRate().stripTrailingZeros().scale() <= 0 ? s.effectiveRate().setScale(2) + "% p.a." : s.effectiveRate().toPlainString() + "% p.a.");
        p.put("basedOn", s.basedOn());
        p.put("interestMode", s.interestMode());
        p.put("penaltyText", s.penaltyRate() == null ? "" : percent(s.penaltyRate()));
        p.put("penaltyMode", s.penaltyMode());
        p.put("releasedText", day(s.released()));
        p.put("firstPrinText", day(s.firstPrincipalDue()));
        p.put("firstIntText", day(s.firstInterestDue()));
        p.put("maturityText", day(s.maturity()));
        p.put("cutoffText", day(s.released()));
        p.put("collectionType", s.collectionType());
        p.put("others", s.totalOthers().signum() == 0 ? "" : "Others: " + money(s.totalOthers()));
        p.put("tTotal", s.totalDue());
        p.put("tOthers", s.totalOthers());
        p.put("tInterest", s.totalInterest());
        p.put("tPrincipal", s.totalPrincipal());
        p.put("note", "Prepared before release: the dates count from " + day(s.released()) + ", the day the schedule was made.");
        p.put("generatedBy", "Software: " + appName + " | Generated by: " + generatedBy);
        List<Map<String, ?>> rows = new ArrayList<>();
        for (Line l : s.lines()) {
            Map<String, Object> m = new HashMap<>();
            m.put("no", l.no() == 0 ? "" : String.valueOf(l.no()));
            m.put("date", day(l.date()));
            m.put("total", l.total());
            m.put("others", l.others());
            m.put("interest", l.interest());
            m.put("principal", l.principal());
            m.put("balance", l.balance());
            rows.add(m);
        }
        return JasperFillManager.fillReport(template(), p, new JRMapCollectionDataSource(rows));
    }

    private JasperReport template() throws JRException, IOException {
        JasperReport r = compiled;
        if (r == null) {
            try (InputStream in = AmortizationSchedulePrint.class.getResourceAsStream("/reports/amortization-schedule.jrxml")) {
                r = JasperCompileManager.compileReport(in);
            }
            compiled = r;
        }
        return r;
    }

    private String coopName(String acronym) {
        for (String sql : new String[] {"SELECT coop_name FROM coopsystem_report_header LIMIT 1", "SELECT coop_name FROM ecis_branding LIMIT 1"}) {
            try {
                String n = desc(sql);
                if (!n.isBlank()) return n;
            } catch (org.springframework.dao.DataAccessException e) {
                // the table is not there on this database; try the next source
            }
        }
        return configuredName.isBlank() ? acronym : configuredName;
    }

    private Map<String, Object> first(String sql, Object... args) {
        List<Map<String, Object>> r = jdbc.queryForList(sql, args);
        return r.isEmpty() ? Map.of() : r.get(0);
    }

    private String desc(String sql, Object... args) {
        if (args.length > 0 && args[0] == null) return "";
        List<String> r = jdbc.query(sql, (rs, i) -> rs.getString(1), args);
        return r.isEmpty() || r.get(0) == null ? "" : r.get(0).trim();
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    private static String day(LocalDate d) { return d == null ? "" : d.format(DAY); }

    private static String money(BigDecimal v) {
        return v == null ? "" : new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH)).format(v);
    }

    private static String percent(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString() + " % p. a.";
    }
}
