package ph.coop.core.asset;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.accounting.journal.JournalDrafts;
import ph.coop.core.accounting.journal.JournalLine;
import ph.coop.core.asset.DepreciationPlanner.Due;
import ph.coop.core.inventory.InventorySupport;

/**
 * Monthly straight-line depreciation (guide phase 15, "depreciation schedules and accounting integration"). The accountant picks a month end; every active asset that is due
 * for that month (and any earlier month it missed) gets a depreciation row, and one DRAFT journal per month is left for the accountants: debit each category's depreciation
 * expense account, credit its accumulated depreciation account. The journal then follows the usual review, approval and posting by other people, as the old monthly
 * "depreciation for the month of ..." journals did. A month is never depreciated twice for an asset.
 */
@RestController
@RequestMapping("/api/assets/depreciation")
@PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
class DepreciationController {

    record Request(LocalDate periodEnd) {}

    record PeriodPreview(LocalDate periodEnd, String reference, int assets, BigDecimal total, boolean referenceUsed) {}

    record Preview(LocalDate through, List<PeriodPreview> periods, int assets, BigDecimal total) {}

    record RunResult(LocalDate periodEnd, String reference, long journalId, int assets, BigDecimal total) {}

    record RunRow(LocalDate periodEnd, int assets, BigDecimal total, String journal, String journalStatus) {}

    private final JdbcTemplate jdbc;
    private final InventorySupport support;
    private final AssetSupport assets;
    private final JournalDrafts journals;
    private final DepreciationPlanner planner;

    DepreciationController(JdbcTemplate jdbc, InventorySupport support, AssetSupport assets, JournalDrafts journals, DepreciationPlanner planner) {
        this.planner = planner;
        this.jdbc = jdbc;
        this.support = support;
        this.assets = assets;
        this.journals = journals;
    }

    private LocalDate checked(LocalDate periodEnd) {
        if (periodEnd == null || !AssetRules.isMonthEnd(periodEnd)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the last day of a month, such as 2026-10-31.");
        if (periodEnd.isAfter(support.today())) throw new ResponseStatusException(HttpStatus.CONFLICT, "That month has not ended yet.");
        return periodEnd;
    }

    private Map<LocalDate, List<Due>> byPeriod(List<Due> due) {
        Map<LocalDate, List<Due>> out = new TreeMap<>();
        for (Due d : due) out.computeIfAbsent(d.periodEnd(), k -> new ArrayList<>()).add(d);
        return out;
    }

    @PostMapping("/preview")
    Preview preview(@RequestBody Request in, Authentication auth) {
        assets.requireAccountant(auth);
        LocalDate through = checked(in.periodEnd());
        List<PeriodPreview> periods = new ArrayList<>();
        int count = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<LocalDate, List<Due>> e : byPeriod(planner.due(through)).entrySet()) {
            BigDecimal sum = e.getValue().stream().map(Due::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            String ref = AssetRules.depreciationReference(e.getKey());
            Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_journal WHERE branch_code = ? AND reference = ?", Integer.class, InventorySupport.BRANCH, ref);
            periods.add(new PeriodPreview(e.getKey(), ref, e.getValue().size(), sum, used != null && used > 0));
            count += e.getValue().size();
            total = total.add(sum);
        }
        return new Preview(through, periods, count, total);
    }

    /** Writes the depreciation rows and one draft journal for each month that is due, up to {@code periodEnd}. */
    @PostMapping("/run")
    @Transactional
    List<RunResult> run(@RequestBody Request in, Authentication auth) {
        assets.requireAccountant(auth);
        LocalDate through = checked(in.periodEnd());
        Map<LocalDate, List<Due>> periods = byPeriod(planner.due(through));
        if (periods.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Nothing is due for depreciation up to " + through + ".");
        List<RunResult> out = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Due>> e : periods.entrySet()) {
            // one debit and one credit per pair of accounts
            Map<String, BigDecimal> perPair = new LinkedHashMap<>();
            for (Due d : e.getValue()) perPair.merge(d.expense() + "|" + d.accum(), d.amount(), BigDecimal::add);
            List<JournalLine> lines = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> p : perPair.entrySet()) {
                String[] a = p.getKey().split("\\|");
                lines.add(new JournalLine(a[0], p.getValue(), BigDecimal.ZERO));
                lines.add(new JournalLine(a[1], BigDecimal.ZERO, p.getValue()));
            }
            BigDecimal sum = e.getValue().stream().map(Due::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            String ref = AssetRules.depreciationReference(e.getKey());
            long journal = journals.create(InventorySupport.BRANCH, ref, e.getKey(), "Depreciation for the month ended " + e.getKey() + " (" + e.getValue().size() + " assets)", auth.getName(), lines);
            for (Due d : e.getValue())
                jdbc.update("INSERT INTO ecis_asset_depreciation (asset_id, period_end, amount, journal_id, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?)", d.assetId(), d.periodEnd(), d.amount(), journal, auth.getName(), Timestamp.valueOf(support.now()));
            support.audit(auth, "asset.depreciation.run", "ecis_journal", ref, e.getValue().size() + " assets, " + sum.toPlainString());
            out.add(new RunResult(e.getKey(), ref, journal, e.getValue().size(), sum));
        }
        return out;
    }

    /** The months depreciated so far, with the journal that books each and where it stands. */
    @GetMapping
    List<RunRow> runs() {
        return jdbc.query("""
                SELECT d.period_end, COUNT(*), SUM(d.amount), MAX(j.reference), MAX(j.status) FROM ecis_asset_depreciation d JOIN ecis_asset a ON a.id = d.asset_id LEFT JOIN ecis_journal j ON j.id = d.journal_id
                WHERE a.branch_code = ? GROUP BY d.period_end ORDER BY d.period_end DESC LIMIT 60
                """, (rs, i) -> new RunRow(rs.getDate(1).toLocalDate(), rs.getInt(2), rs.getBigDecimal(3), rs.getString(4), rs.getString(5)), InventorySupport.BRANCH);
    }
}
