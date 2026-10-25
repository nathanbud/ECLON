package ph.coop.core.loan;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The amortization kinds of the old system, explained, and a calculator to try one before using it. Administrators only.
 * <p>
 * The kinds are the rows of {@code amortype}; the way each one is worked out is in {@link LoanSchedule}, not in the database, and is checked against
 * real schedules. So this is a reference and a preview, not an editor: a formula typed into a screen could not be checked the same way.
 */
@RestController
@RequestMapping("/api/admin/loan-setup/amortization")
@PreAuthorize("hasRole('ADMIN')")
public class AmortizationController {

    private static final Map<Integer, String> FORMULA = Map.of(
        LoanSchedule.DIMINISHING,
        "Every payment repays the same share of the principal (the amount divided by the number of payments; the last payment clears what is left). "
            + "Interest on each payment is the balance x rate x the real days since the last due date / 36,500, so it shrinks as the balance falls and the total payment shrinks with it.",
        LoanSchedule.ANNUITY,
        "Every payment is the same total of principal and interest: P x r / (1 - (1 + r)^-n), where r is the rate for one payment period counted as 30 days a month "
            + "(rate x 30 x months per payment / 36,500). Interest on each payment is the balance x r (or the real days, if the loan type counts real days); "
            + "the rest of the payment repays principal, so the principal part grows. The last payment clears what is left.");

    private static final Set<Integer> DIMINISHING_MODES = Set.of(LoanSchedule.MONTHLY, LoanSchedule.QUARTERLY, LoanSchedule.LUMP_SUM);
    private static final Set<Integer> ANNUITY_MODES = Set.of(LoanSchedule.MONTHLY, LoanSchedule.QUARTERLY, LoanSchedule.SEMIANNUAL, LoanSchedule.LUMP_SUM);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    AmortizationController(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Kind(int id, String name, boolean supported, String formula, List<String> types, long loans) {}

    /** The kinds, what each one does, and which loan types and how many loans use it. */
    @GetMapping
    public List<Kind> kinds() {
        Map<Integer, List<String>> types = new HashMap<>();
        jdbc.query("SELECT SLTypeAMORTYPE, SLTypeM_DESC FROM sltype WHERE SLTypeSLC_CODE = ? AND COALESCE(SLTypeIsDisabled, 0) = 0 AND SLTypeAMORTYPE IS NOT NULL ORDER BY SLTypeM_DESC",
            (rs) -> { types.computeIfAbsent(rs.getInt(1), (k) -> new ArrayList<>()).add(rs.getString(2)); }, LoanSetupController.LOAN_CLASS);
        Map<Integer, Long> loans = new HashMap<>();
        jdbc.query("SELECT LoanAMORTYPE, COUNT(*) FROM loan WHERE LoanAMORTYPE IS NOT NULL GROUP BY LoanAMORTYPE", (rs) -> { loans.put(rs.getInt(1), rs.getLong(2)); });
        return jdbc.query("SELECT AmorTypeID, AmorTypeDesc FROM amortype ORDER BY AmorTypeID", (rs, i) -> {
            int id = rs.getInt(1);
            boolean ok = FORMULA.containsKey(id);
            return new Kind(id, rs.getString(2), ok,
                ok ? FORMULA.get(id) : "Not mapped yet. The old system works it out in its program code, and no real schedule has been matched to a formula, so the application screen refuses it.",
                types.getOrDefault(id, List.of()), loans.getOrDefault(id, 0L));
        });
    }

    /** What to try: an amortization kind, a loan, and how interest days are counted. */
    public record Try(Integer kind, BigDecimal amount, Integer termMonths, Integer paymentMode, BigDecimal ratePercent, LocalDate release, Integer dayCount) {}

    public record Preview(List<LoanSchedule.Row> rows, int payments, BigDecimal payment, BigDecimal totalInterest, BigDecimal totalPaid, LocalDate maturity) {}

    /** Works out a schedule exactly as the loan application would, without saving anything. */
    @PostMapping("/preview")
    public Preview preview(@RequestBody Try in) {
        if (in == null || in.kind() == null) throw bad("Choose an amortization kind.");
        if (!FORMULA.containsKey(in.kind())) throw bad("That kind is not mapped yet.");
        if (in.amount() == null || in.amount().signum() <= 0 || in.amount().compareTo(new BigDecimal("1000000000")) > 0) throw bad("The amount must be more than zero and under a billion.");
        if (in.termMonths() == null || in.termMonths() < 1 || in.termMonths() > 600) throw bad("The term must be 1 to 600 months.");
        if (in.ratePercent() == null || in.ratePercent().signum() < 0 || in.ratePercent().compareTo(BigDecimal.valueOf(100)) > 0) throw bad("The rate is 0 to 100.");
        if (in.paymentMode() == null) throw bad("Choose how often it is paid.");
        boolean annuity = in.kind() == LoanSchedule.ANNUITY;
        if (!(annuity ? ANNUITY_MODES : DIMINISHING_MODES).contains(in.paymentMode())) throw bad("That payment mode is not mapped for this kind yet.");
        LocalDate release = in.release() == null ? LocalDate.now(clock) : in.release();
        List<LoanSchedule.Row> rows;
        try {
            rows = annuity
                ? LoanSchedule.annuity(in.amount(), in.termMonths(), in.paymentMode(), in.ratePercent(), release, in.dayCount() != null && in.dayCount() == 1)
                : LoanSchedule.diminishing(in.amount(), in.termMonths(), in.paymentMode(), in.ratePercent(), release);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        BigDecimal interest = LoanSchedule.totalInterest(rows);
        LoanSchedule.Row first = rows.size() > 1 ? rows.get(1) : null;
        BigDecimal payment = first == null ? BigDecimal.ZERO : first.principalDue().add(first.interestDue());
        return new Preview(rows, rows.size() - 1, payment, interest, in.amount().add(interest), rows.get(rows.size() - 1).dueDate());
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
