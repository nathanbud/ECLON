package ph.coop.core.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only trial balance over the legacy general ledger ({@code gltrans}) for a date range.
 * The legacy ledger stores credits as negative amounts; they are negated here so both columns
 * are presented as positive figures.
 */
@RestController
@RequestMapping("/api/trial-balance")
class TrialBalanceController {

    /** gltrans holds ~15M rows, so unbounded ranges are refused. */
    private static final int MAX_RANGE_DAYS = 400;

    /** Legacy transaction type 98 ("Profit Loss Summ"): one-sided summary rows that break the balance. */
    private static final int PROFIT_LOSS_SUMMARY = 98;

    record Line(String accountCode, String description, BigDecimal debit, BigDecimal credit) {}

    record TrialBalance(int branchCode, LocalDate from, LocalDate to, List<Line> lines,
                        BigDecimal totalDebit, BigDecimal totalCredit, boolean balanced) {}

    private final JdbcTemplate jdbc;

    TrialBalanceController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    TrialBalance get(@RequestParam int branchCode,
                     @RequestParam LocalDate from,
                     @RequestParam LocalDate to,
                     @RequestParam(defaultValue = "false") boolean includeProfitLossSummary) {
        if (to.isBefore(from)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'to' is before 'from'");
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "range is limited to " + MAX_RANGE_DAYS + " days");
        }
        List<Line> lines = jdbc.query("""
                SELECT g.GLTransACCT_CODE AS code, c.COADesc AS descr,
                       SUM(g.GLTransDR_AMT) AS dr, -SUM(g.GLTransCR_AMT) AS cr
                FROM gltrans g
                LEFT JOIN coa c ON c.COABR_CODE = g.GLTransBR_CODE AND c.COAID = g.GLTransACCT_CODE
                WHERE g.GLTransBR_CODE = ? AND g.GLTransTR_DATE BETWEEN ? AND ?
                  AND (? OR g.GLTransTR_CODE <> ?)
                GROUP BY g.GLTransACCT_CODE, c.COADesc
                ORDER BY g.GLTransACCT_CODE
                """,
                (rs, i) -> new Line(rs.getString("code"), rs.getString("descr"),
                        rs.getBigDecimal("dr"), rs.getBigDecimal("cr")),
                branchCode, from, to, includeProfitLossSummary, PROFIT_LOSS_SUMMARY);
        BigDecimal dr = lines.stream().map(Line::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cr = lines.stream().map(Line::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TrialBalance(branchCode, from, to, lines, dr, cr, dr.compareTo(cr) == 0);
    }
}
