package ph.coop.core.report;

import static ph.coop.core.report.Reports.date;
import static ph.coop.core.report.Reports.dateParam;
import static ph.coop.core.report.Reports.money;
import static ph.coop.core.report.Reports.number;
import static ph.coop.core.report.Reports.numberParam;
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

/** Inventory reports (guide phase 20): stock balance, movement, low stock, valuation and stock count variance, all read from the stock ledger and balances. */
@Component
class InventoryReports implements Provider {

    private static final int BRANCH = 1, LIMIT = 3000;
    private static final Set<String> ROLES = Set.of("STOREKEEPER", "STOCK_APPROVER", "PURCHASER", "PURCHASE_APPROVER");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    InventoryReports(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public List<Report> reports() {
        LocalDate today = LocalDate.now(clock);
        return List.of(
            of("stock-balance", "Inventory", "Stock balance", "What is on hand now, by product, warehouse and batch, at the average cost.",
                List.of(numberParam("warehouseId", "Warehouse number (blank = all)", "")), ROLES, (a) -> {
                    Long wh = a.number("warehouseId");
                    List<List<Object>> rows = new ArrayList<>();
                    BigDecimal[] value = {BigDecimal.ZERO};
                    jdbc.query("""
                            SELECT p.sku, p.name, w.name, COALESCE(t.batch_no, ''), t.expiry_date, b.quantity, p.unit, p.cost
                            FROM ecis_inventory_balance b JOIN ecis_product p ON p.id = b.product_id JOIN ecis_warehouse w ON w.id = b.warehouse_id LEFT JOIN ecis_inventory_batch t ON t.id = b.batch_id
                            WHERE p.branch_code = ? AND b.quantity <> 0 AND (CAST(? AS CHAR) IS NULL OR b.warehouse_id = ?) ORDER BY p.name, w.name, t.expiry_date
                            """, (rs) -> {
                            BigDecimal v = rs.getBigDecimal(6).multiply(rs.getBigDecimal(8)).setScale(2, java.math.RoundingMode.HALF_UP);
                            value[0] = value[0].add(v);
                            rows.add(row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), Reports.isoDate(rs.getDate(5)), rs.getBigDecimal(6), rs.getString(7), rs.getBigDecimal(8), v));
                        }, BRANCH, wh, wh);
                    return new Table(List.of(text("sku", "SKU"), text("product", "Product"), text("warehouse", "Warehouse"), text("batch", "Batch"), date("expiry", "Expires"), number("qty", "On hand"), text("unit", "Unit"), money("cost", "Avg cost"), money("value", "Value")),
                        rows, row("Total value", null, null, null, null, null, null, null, value[0]), null);
                }),
            of("stock-movement", "Inventory", "Stock movement", "Every stock ledger entry in a period: receipts, issues, sales, returns, transfers and adjustments.",
                List.of(dateParam("from", "From", today.withDayOfMonth(1)), dateParam("to", "To", today), numberParam("warehouseId", "Warehouse number (blank = all)", "")), ROLES, (a) -> {
                    LocalDate[] p = a.period(today.withDayOfMonth(1), today, 800);
                    Long wh = a.number("warehouseId");
                    List<List<Object>> rows = new ArrayList<>();
                    jdbc.query("""
                            SELECT t.txn_no, t.txn_date, t.txn_type, p.sku, p.name, w.name, COALESCE(b.batch_no, ''), t.quantity, t.unit_cost, t.ref_no, t.created_by
                            FROM ecis_inventory_txn t JOIN ecis_product p ON p.id = t.product_id JOIN ecis_warehouse w ON w.id = t.warehouse_id LEFT JOIN ecis_inventory_batch b ON b.id = t.batch_id
                            WHERE t.branch_code = ? AND t.txn_date >= ? AND t.txn_date <= ? AND (CAST(? AS CHAR) IS NULL OR t.warehouse_id = ?) ORDER BY t.id LIMIT %d
                            """.formatted(LIMIT + 1), (rs) -> {
                            if (rows.size() > LIMIT) return;
                            rows.add(row(rs.getString(1), Reports.isoDate(rs.getDate(2)), rs.getString(3).toLowerCase(), rs.getString(4) + " " + rs.getString(5), rs.getString(6), rs.getString(7), rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getString(10), rs.getString(11)));
                        }, BRANCH, p[0], p[1], wh, wh);
                    boolean cut = rows.size() > LIMIT;
                    if (cut) rows.remove(rows.size() - 1);
                    return new Table(List.of(text("no", "No."), date("date", "Date"), text("type", "Type"), text("product", "Product"), text("warehouse", "Warehouse"), text("batch", "Batch"), number("qty", "Quantity"), money("cost", "Unit cost"), text("ref", "Reference"), text("by", "By")),
                        rows, null, cut ? "Only the first " + LIMIT + " entries are shown; choose a shorter period." : "Quantities in are positive, out are negative.");
                }),
            of("low-stock", "Inventory", "Low stock", "Products at or under their reorder level (all warehouses together).", List.of(), ROLES, (a) -> {
                List<List<Object>> rows = new ArrayList<>();
                jdbc.query("""
                        SELECT p.sku, p.name, p.unit, p.reorder_level, (SELECT COALESCE(SUM(b.quantity), 0) FROM ecis_inventory_balance b WHERE b.product_id = p.id)
                        FROM ecis_product p WHERE p.branch_code = ? AND p.active = 1 AND p.reorder_level > 0 ORDER BY p.name
                        """, (rs) -> {
                        BigDecimal level = rs.getBigDecimal(4), onHand = rs.getBigDecimal(5);
                        if (onHand.compareTo(level) <= 0) rows.add(row(rs.getString(1), rs.getString(2), rs.getString(3), level, onHand, level.subtract(onHand)));
                    }, BRANCH);
                return new Table(List.of(text("sku", "SKU"), text("product", "Product"), text("unit", "Unit"), number("level", "Reorder at"), number("onHand", "On hand"), number("short", "Short by")), rows, null,
                    "Only products with a reorder level set are checked.");
            }),
            of("stock-valuation", "Inventory", "Stock valuation", "The value of stock on hand at average cost, by category and warehouse.", List.of(), ROLES, (a) -> {
                List<List<Object>> rows = new ArrayList<>();
                BigDecimal[] total = {BigDecimal.ZERO};
                jdbc.query("""
                        SELECT COALESCE(c.name, '(no category)'), w.name, COUNT(DISTINCT p.id), SUM(b.quantity * p.cost)
                        FROM ecis_inventory_balance b JOIN ecis_product p ON p.id = b.product_id JOIN ecis_warehouse w ON w.id = b.warehouse_id LEFT JOIN ecis_product_category c ON c.id = p.category_id
                        WHERE p.branch_code = ? AND b.quantity <> 0 GROUP BY c.name, w.name ORDER BY c.name, w.name
                        """, (rs) -> {
                        BigDecimal v = rs.getBigDecimal(4).setScale(2, java.math.RoundingMode.HALF_UP);
                        total[0] = total[0].add(v);
                        rows.add(row(rs.getString(1), rs.getString(2), rs.getLong(3), v));
                    }, BRANCH);
                return new Table(List.of(text("category", "Category"), text("warehouse", "Warehouse"), number("products", "Products"), money("value", "Value")), rows, row("Total", null, null, total[0]),
                    "Valued at each product's moving average cost. No general ledger entries are written for stock yet, so this is not compared with a ledger balance.");
            }),
            of("count-variance", "Inventory", "Stock count variance", "What each stock count found against the books, line by line, valued at average cost.",
                List.of(dateParam("from", "From", today.minusMonths(3)), dateParam("to", "To", today)), ROLES, (a) -> {
                    LocalDate[] p = a.period(today.minusMonths(3), today, 1500);
                    List<List<Object>> rows = new ArrayList<>();
                    BigDecimal[] total = {BigDecimal.ZERO};
                    jdbc.query("""
                            SELECT c.count_no, c.count_date, w.name, p.sku, p.name, l.system_qty, l.counted_qty, l.counted_qty - l.system_qty, (l.counted_qty - l.system_qty) * p.cost, c.status
                            FROM ecis_stock_count_line l JOIN ecis_stock_count c ON c.id = l.count_id JOIN ecis_warehouse w ON w.id = c.warehouse_id JOIN ecis_product p ON p.id = l.product_id
                            WHERE c.branch_code = ? AND c.count_date >= ? AND c.count_date <= ? AND l.counted_qty <> l.system_qty ORDER BY c.count_date, c.id, l.line_no
                            """, (rs) -> {
                            BigDecimal v = rs.getBigDecimal(9).setScale(2, java.math.RoundingMode.HALF_UP);
                            if (rs.getString(10).equals("APPROVED")) total[0] = total[0].add(v);
                            rows.add(row(rs.getString(1), Reports.isoDate(rs.getDate(2)), rs.getString(3), rs.getString(4) + " " + rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8), v, rs.getString(10).toLowerCase()));
                        }, BRANCH, p[0], p[1]);
                    return new Table(List.of(text("no", "Count"), date("date", "Date"), text("warehouse", "Warehouse"), text("product", "Product"), number("system", "Books"), number("counted", "Counted"), number("variance", "Difference"), money("value", "Value"), text("status", "Status")),
                        rows, row("Approved counts, net", null, null, null, null, null, null, total[0], null), "Only lines that differ are listed. The total counts approved counts only.");
                }));
    }
}
