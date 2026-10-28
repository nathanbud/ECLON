package ph.coop.core.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.workflow.WorkflowHost;

/** Lets the workflow engine finish a stock adjustment once every extra approval has agreed (it then writes the stock ledger rows), or turn it down. */
@Component
class StockAdjustmentWorkflowHost implements WorkflowHost {

    private final JdbcTemplate jdbc;
    private final InventorySupport support;
    private final StockAdjustmentPosting posting;

    StockAdjustmentWorkflowHost(JdbcTemplate jdbc, InventorySupport support, StockAdjustmentPosting posting) {
        this.jdbc = jdbc;
        this.support = support;
        this.posting = posting;
    }

    private void audit(String user, String action, long id, String detail) {
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value) VALUES (?, ?, ?, ?, ?, ?)", java.sql.Timestamp.valueOf(support.now()), user, action, "ecis_stock_adjustment", posting.number(id), detail);
    }

    @Override
    public String entityType() {
        return StockAdjustmentController.WORKFLOW;
    }

    @Override
    public String label() {
        return "Stock adjustment";
    }

    @Override
    public void approved(String entityId, String approver) {
        long id = Long.parseLong(entityId);
        posting.complete(id, approver, "approved after the extra approvals");
        audit(approver, "inventory.adjustment.approve", id, "approved after the extra approvals");
    }

    @Override
    public void rejected(String entityId, String by, String note) {
        long id = Long.parseLong(entityId);
        posting.turnDown(id, by, note);
        audit(by, "inventory.adjustment.reject", id, note);
    }

    @Override
    public String describe(String entityId) {
        return jdbc.query("SELECT a.adjust_no, a.adj_type, a.reason FROM ecis_stock_adjustment a WHERE a.id = ?", (rs, i) -> rs.getString(1) + " · " + rs.getString(2).toLowerCase() + " · " + rs.getString(3), Long.parseLong(entityId)).stream().findFirst().orElse(entityId);
    }
}
