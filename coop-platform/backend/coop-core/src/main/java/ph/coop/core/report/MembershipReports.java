package ph.coop.core.report;

import static ph.coop.core.report.Reports.dateParam;
import static ph.coop.core.report.Reports.money;
import static ph.coop.core.report.Reports.number;
import static ph.coop.core.report.Reports.of;
import static ph.coop.core.report.Reports.percent;
import static ph.coop.core.report.Reports.pct;
import static ph.coop.core.report.Reports.row;
import static ph.coop.core.report.Reports.text;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.report.Reports.Provider;
import ph.coop.core.report.Reports.Report;
import ph.coop.core.report.Reports.Table;

/** Membership reports (guide phase 20): the members by status (active, dormant, closed ...) and the growth of the membership month by month. */
@Component
class MembershipReports implements Provider {

    private static final int BRANCH = 1;
    private static final Set<String> ROLES = Set.of("MEMBERSHIP_OFFICER");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    MembershipReports(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Month key such as 2026-04 for a date. */
    static String monthKey(LocalDate d) {
        return YearMonth.from(d).toString();
    }

    @Override
    public List<Report> reports() {
        LocalDate today = LocalDate.now(clock);
        return List.of(
            of("members-status", "Membership", "Members by status", "How many members are active, dormant, closed and so on, from the member master.", List.of(), ROLES, (a) -> {
                List<Object[]> data = jdbc.query("""
                        SELECT COALESCE(s.AcctStatDesc, CONCAT('Status ', c.AccountStatus)), COUNT(*) FROM client c LEFT JOIN acctstat s ON s.AcctStatID = c.AccountStatus
                        WHERE c.ClientIDBrCode = ? GROUP BY c.AccountStatus, s.AcctStatDesc ORDER BY COUNT(*) DESC
                        """, (rs, i) -> new Object[] {rs.getString(1), rs.getLong(2)}, BRANCH);
                long total = data.stream().mapToLong((d) -> (Long) d[1]).sum();
                List<List<Object>> rows = new ArrayList<>();
                for (Object[] d : data) rows.add(row(d[0], d[1], pct(BigDecimal.valueOf((Long) d[1]), BigDecimal.valueOf(total))));
                long dead = jdbc.queryForObject("SELECT COUNT(*) FROM client WHERE ClientIDBrCode = ? AND DateDeceased IS NOT NULL", Long.class, BRANCH);
                return new Table(List.of(text("status", "Status"), number("members", "Members"), percent("share", "Share")), rows, row("Total", total, new BigDecimal("100.00")),
                    dead + " of these members are recorded as deceased. Applicants (New) and disapproved ones are listed apart from active members.");
            }),
            of("members-growth", "Membership", "Membership growth", "Members who joined and members who left each month, with the running net.", List.of(dateParam("from", "From", today.minusMonths(11).withDayOfMonth(1)), dateParam("to", "To", today)), ROLES, (a) -> {
                LocalDate[] p = a.period(today.minusMonths(11).withDayOfMonth(1), today, 3700);
                Map<String, long[]> months = new TreeMap<>();
                for (YearMonth m = YearMonth.from(p[0]); !m.isAfter(YearMonth.from(p[1])); m = m.plusMonths(1)) months.put(m.toString(), new long[2]);
                jdbc.query("SELECT DateOpened FROM client WHERE ClientIDBrCode = ? AND AccountStatus IN (1, 2, 3) AND DateOpened >= ? AND DateOpened <= ?",
                    (rs) -> { long[] x = months.get(monthKey(rs.getDate(1).toLocalDate())); if (x != null) x[0]++; }, BRANCH, p[0], p[1]);
                jdbc.query("SELECT ClosedDate FROM client WHERE ClientIDBrCode = ? AND AccountStatus IN (1, 2, 3) AND ClosedDate >= ? AND ClosedDate <= ?",
                    (rs) -> { long[] x = months.get(monthKey(rs.getDate(1).toLocalDate())); if (x != null) x[1]++; }, BRANCH, p[0], p[1]);
                List<List<Object>> rows = new ArrayList<>();
                long joined = 0, left = 0, running = 0;
                for (Map.Entry<String, long[]> e : months.entrySet()) {
                    joined += e.getValue()[0];
                    left += e.getValue()[1];
                    running += e.getValue()[0] - e.getValue()[1];
                    rows.add(row(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[0] - e.getValue()[1], running));
                }
                return new Table(List.of(text("month", "Month"), number("joined", "Joined"), number("left", "Left (closed)"), number("net", "Net"), number("running", "Running net")), rows, row("Total", joined, left, joined - left, null),
                    "Joined counts members opened in the month whatever their status now; left counts members with a closing date in the month.");
            }));
    }
}
