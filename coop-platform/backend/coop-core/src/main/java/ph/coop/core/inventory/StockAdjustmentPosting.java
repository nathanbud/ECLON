package ph.coop.core.inventory;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.inventory.StockService.Move;

/**
 * What approving or turning down a stock adjustment does to stock and to the adjustment, in one place so the adjustment controller (its usual approver) and the workflow
 * engine (the last of the extra approvers) finish it the same way. Approving writes the ledger rows, so it fails whole if stock is no longer there.
 */
@Component
class StockAdjustmentPosting {

    private final JdbcTemplate jdbc;
    private final InventorySupport support;
    private final StockService stock;

    StockAdjustmentPosting(JdbcTemplate jdbc, InventorySupport support, StockService stock) {
        this.jdbc = jdbc;
        this.support = support;
        this.stock = stock;
    }

    private record Header(String adjustNo, long warehouseId, String type, String reason, String requestedBy) {}

    private Header header(long id) {
        return jdbc.query("SELECT adjust_no, warehouse_id, adj_type, reason, requested_by FROM ecis_stock_adjustment WHERE branch_code = ? AND id = ?",
            (rs, i) -> new Header(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)), InventorySupport.BRANCH, id).get(0);
    }

    /** What the adjustment is worth at average cost: the quantities, either way, times each product's cost. Workflow limits are set against this. */
    BigDecimal value(long id) {
        BigDecimal v = jdbc.queryForObject("SELECT COALESCE(SUM(ABS(l.quantity) * p.cost), 0) FROM ecis_stock_adjustment_line l JOIN ecis_product p ON p.id = l.product_id WHERE l.adjustment_id = ?", BigDecimal.class, id);
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** Writes the ledger rows and marks the adjustment approved by {@code approver}. */
    void complete(long id, String approver, String note) {
        Header a = header(id);
        List<Object[]> lines = jdbc.query("SELECT product_id, batch_id, quantity FROM ecis_stock_adjustment_line WHERE adjustment_id = ? ORDER BY line_no",
            (rs, i) -> new Object[] {rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3)}, id);
        for (Object[] l : lines) {
            String batchNo = (Long) l[1] == 0 ? null : jdbc.queryForObject("SELECT batch_no FROM ecis_inventory_batch WHERE id = ?", String.class, l[1]);
            stock.post(new Move(a.type(), (Long) l[0], a.warehouseId(), batchNo, null, (BigDecimal) l[2], null, "ADJUSTMENT", a.adjustNo(), a.reason()), a.requestedBy());
        }
        jdbc.update("UPDATE ecis_stock_adjustment SET status = 'APPROVED', decided_by = ?, decided_at = ?, decision_note = ? WHERE id = ? AND status = 'SUBMITTED'", approver, Timestamp.valueOf(support.now()), note, id);
    }

    /** Marks the adjustment rejected; stock is untouched. */
    void turnDown(long id, String by, String note) {
        jdbc.update("UPDATE ecis_stock_adjustment SET status = 'REJECTED', decided_by = ?, decided_at = ?, decision_note = ? WHERE id = ? AND status = 'SUBMITTED'", by, Timestamp.valueOf(support.now()), note, id);
    }

    String number(long id) {
        return header(id).adjustNo();
    }
}
