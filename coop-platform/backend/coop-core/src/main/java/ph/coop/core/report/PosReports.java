package ph.coop.core.report;

import static ph.coop.core.report.Reports.date;
import static ph.coop.core.report.Reports.dateParam;
import static ph.coop.core.report.Reports.money;
import static ph.coop.core.report.Reports.number;
import static ph.coop.core.report.Reports.of;
import static ph.coop.core.report.Reports.row;
import static ph.coop.core.report.Reports.text;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.report.Reports.Provider;
import ph.coop.core.report.Reports.Report;
import ph.coop.core.report.Reports.Table;

/** Point-of-sale reports (guide phase 20): daily sales, sales by product, sales by store, returns and discounts. Sales are shown before returns, which have their own column. */
@Component
class PosReports implements Provider {

    private static final int BRANCH = 1;
    private static final Set<String> ROLES = Set.of("POS_SUPERVISOR", "POS_CASHIER");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    PosReports(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public List<Report> reports() {
        LocalDate today = LocalDate.now(clock);
        var period = List.of(dateParam("from", "From", today.withDayOfMonth(1)), dateParam("to", "To", today));
        return List.of(
            of("daily-sales", "POS", "Daily sales", "Sales per day: how many, the value, discounts given, returns and the cost of what was sold.", period, ROLES, (a) -> {
                LocalDate[] p = a.period(today.withDayOfMonth(1), today, 800);
                List<List<Object>> rows = new ArrayList<>();
                BigDecimal[] t = new BigDecimal[6];
                java.util.Arrays.fill(t, BigDecimal.ZERO);
                long[] count = {0};
                jdbc.query("""
                        SELECT sale_date, COUNT(*), SUM(subtotal), SUM(discount_total), SUM(total), SUM(returned_total), SUM(cost_total)
                        FROM ecis_sale WHERE branch_code = ? AND sale_date >= ? AND sale_date <= ? GROUP BY sale_date ORDER BY sale_date
                        """, (rs) -> {
                        BigDecimal total = rs.getBigDecimal(5), returned = rs.getBigDecimal(6), cost = rs.getBigDecimal(7);
                        count[0] += rs.getLong(2);
                        t[0] = t[0].add(rs.getBigDecimal(3)); t[1] = t[1].add(rs.getBigDecimal(4)); t[2] = t[2].add(total); t[3] = t[3].add(returned); t[4] = t[4].add(cost);
                        rows.add(row(Reports.isoDate(rs.getDate(1)), rs.getLong(2), rs.getBigDecimal(3), rs.getBigDecimal(4), total, returned, total.subtract(returned), cost, total.subtract(cost)));
                    }, BRANCH, p[0], p[1]);
                return new Table(List.of(date("day", "Day"), number("sales", "Sales"), money("subtotal", "Before discount"), money("discount", "Discounts"), money("total", "Sales"), money("returned", "Returned"), money("net", "Net of returns"), money("cost", "Cost of goods"), money("margin", "Margin")), rows, row("Total", count[0], t[0], t[1], t[2], t[3], t[2].subtract(t[3]), t[4], t[2].subtract(t[4])),
                    "Margin is sales less the cost of goods at the time, before returns. Promotions are already out of the first money column.");
            }),
            of("sales-by-product", "POS", "Sales by product", "Quantity and value sold per product, with the cost and margin.", period, ROLES, (a) -> {
                LocalDate[] p = a.period(today.withDayOfMonth(1), today, 800);
                List<List<Object>> rows = new ArrayList<>();
                BigDecimal[] t = {BigDecimal.ZERO, BigDecimal.ZERO};
                jdbc.query("""
                        SELECT p.sku, p.name, SUM(l.quantity), SUM(l.qty_returned), SUM(l.line_total), SUM(l.quantity * l.unit_cost)
                        FROM ecis_sale_line l JOIN ecis_sale s ON s.id = l.sale_id JOIN ecis_product p ON p.id = l.product_id
                        WHERE s.branch_code = ? AND s.sale_date >= ? AND s.sale_date <= ? GROUP BY p.id, p.sku, p.name ORDER BY SUM(l.line_total) DESC LIMIT 1000
                        """, (rs) -> {
                        BigDecimal amount = rs.getBigDecimal(5), cost = rs.getBigDecimal(6).setScale(2, java.math.RoundingMode.HALF_UP);
                        t[0] = t[0].add(amount); t[1] = t[1].add(cost);
                        rows.add(row(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4), amount, cost, amount.subtract(cost)));
                    }, BRANCH, p[0], p[1]);
                return new Table(List.of(text("sku", "SKU"), text("product", "Product"), number("qty", "Sold"), number("returned", "Returned"), money("amount", "Sales"), money("cost", "Cost"), money("margin", "Margin")), rows,
                    row("Total", null, null, null, t[0], t[1], t[0].subtract(t[1])), "Best sellers first (by value), at most 1,000 products.");
            }),
            of("sales-by-store", "POS", "Sales by store", "Sales per store (warehouse): the 'by branch' of the guide, since stores are where the point of sale sells from.", period, ROLES, (a) -> {
                LocalDate[] p = a.period(today.withDayOfMonth(1), today, 800);
                List<List<Object>> rows = new ArrayList<>();
                BigDecimal[] t = {BigDecimal.ZERO, BigDecimal.ZERO};
                long[] count = {0};
                jdbc.query("""
                        SELECT w.name, COUNT(*), SUM(s.total), SUM(s.returned_total) FROM ecis_sale s JOIN ecis_warehouse w ON w.id = s.warehouse_id
                        WHERE s.branch_code = ? AND s.sale_date >= ? AND s.sale_date <= ? GROUP BY w.id, w.name ORDER BY SUM(s.total) DESC
                        """, (rs) -> {
                        count[0] += rs.getLong(2); t[0] = t[0].add(rs.getBigDecimal(3)); t[1] = t[1].add(rs.getBigDecimal(4));
                        rows.add(row(rs.getString(1), rs.getLong(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(3).subtract(rs.getBigDecimal(4))));
                    }, BRANCH, p[0], p[1]);
                return new Table(List.of(text("store", "Store"), number("sales", "Sales"), money("total", "Total"), money("returned", "Returned"), money("net", "Net of returns")), rows, row("Total", count[0], t[0], t[1], t[0].subtract(t[1])), null);
            }),
            of("pos-returns", "POS", "Returns", "Returns asked for in the period, their refunds and who decided them.", period, ROLES, (a) -> {
                LocalDate[] p = a.period(today.withDayOfMonth(1), today, 800);
                List<List<Object>> rows = new ArrayList<>();
                BigDecimal[] t = {BigDecimal.ZERO};
                jdbc.query("""
                        SELECT r.return_no, r.requested_at, s.sale_no, r.reason, r.refund, r.status, r.requested_by, r.decided_by FROM ecis_sale_return r JOIN ecis_sale s ON s.id = r.sale_id
                        WHERE r.branch_code = ? AND r.requested_at >= ? AND r.requested_at < ? ORDER BY r.id
                        """, (rs) -> {
                        if (rs.getString(6).equals("APPROVED")) t[0] = t[0].add(rs.getBigDecimal(5));
                        rows.add(row(rs.getString(1), rs.getTimestamp(2).toLocalDateTime().toLocalDate().toString(), rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getString(6).toLowerCase(), rs.getString(7), rs.getString(8)));
                    }, BRANCH, java.sql.Timestamp.valueOf(p[0].atStartOfDay()), java.sql.Timestamp.valueOf(p[1].plusDays(1).atStartOfDay()));
                return new Table(List.of(text("no", "No."), date("date", "Date"), text("sale", "Sale"), text("reason", "Reason"), money("refund", "Refund"), text("status", "Status"), text("by", "Asked by"), text("decided", "Decided by")), rows,
                    row("Approved refunds", null, null, null, t[0], null, null, null), null);
            }),
            of("pos-discounts", "POS", "Discounts given", "Sale discounts by kind, and the promotion discounts taken off lines.", period, ROLES, (a) -> {
                LocalDate[] p = a.period(today.withDayOfMonth(1), today, 800);
                List<List<Object>> rows = new ArrayList<>();
                BigDecimal[] t = {BigDecimal.ZERO};
                jdbc.query("""
                        SELECT COALESCE(d.name, '(no discount name)'), COUNT(*), SUM(s.discount_total) FROM ecis_sale s LEFT JOIN ecis_discount d ON d.id = s.discount_id
                        WHERE s.branch_code = ? AND s.sale_date >= ? AND s.sale_date <= ? AND s.discount_total > 0 GROUP BY d.id, d.name ORDER BY SUM(s.discount_total) DESC
                        """, (rs) -> { t[0] = t[0].add(rs.getBigDecimal(3)); rows.add(row(rs.getString(1), rs.getLong(2), rs.getBigDecimal(3))); }, BRANCH, p[0], p[1]);
                jdbc.query("""
                        SELECT COUNT(DISTINCT l.sale_id), COALESCE(SUM(l.promo_off), 0) FROM ecis_sale_line l JOIN ecis_sale s ON s.id = l.sale_id
                        WHERE s.branch_code = ? AND s.sale_date >= ? AND s.sale_date <= ? AND l.promo_off > 0
                        """, (rs) -> { if (rs.getBigDecimal(2).signum() > 0) { t[0] = t[0].add(rs.getBigDecimal(2)); rows.add(row("Promotions (taken off lines)", rs.getLong(1), rs.getBigDecimal(2))); } }, BRANCH, p[0], p[1]);
                return new Table(List.of(text("kind", "Discount"), number("sales", "Sales"), money("amount", "Amount")), rows, row("Total", null, t[0]), null);
            }));
    }
}
