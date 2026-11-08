package ph.coop.core.procurement;


import ph.coop.core.audit.AuditLog;import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.workflow.WorkflowHost;

/**
 * Lets the workflow engine finish a purchase order once every extra approval has agreed (or turn it down), doing what the order controller's own approval and cancellation would
 * have done. The controller keeps the first approval and the segregation of duties; the engine keeps the extra ones.
 */
@Component
class PurchaseOrderWorkflowHost implements WorkflowHost {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    PurchaseOrderWorkflowHost(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now(clock).withNano(0));
    }

    private void audit(String user, String action, String no, String detail) {
        AuditLog.write(jdbc, now(), user, action, "ecis_purchase_order", no, null, detail, null);
    }

    private String number(String entityId) {
        return jdbc.queryForList("SELECT order_no FROM ecis_purchase_order WHERE id = ?", String.class, Long.parseLong(entityId)).stream().findFirst().orElse(entityId);
    }

    @Override
    public String entityType() {
        return PurchaseOrderController.WORKFLOW;
    }

    @Override
    public String label() {
        return "Purchase order";
    }

    @Override
    public void approved(String entityId, String approver) {
        jdbc.update("UPDATE ecis_purchase_order SET status = 'APPROVED', approved_by = ?, approved_at = ? WHERE id = ? AND status = 'DRAFT'", approver, now(), Long.parseLong(entityId));
        audit(approver, "procurement.order.approve", number(entityId), "approved after the extra approvals");
    }

    @Override
    public void rejected(String entityId, String by, String note) {
        long id = Long.parseLong(entityId);
        // joined here rather than with CONCAT_WS(.., ?): PostgreSQL cannot tell what type a bare parameter inside CONCAT_WS is
        List<String> before = jdbc.queryForList("SELECT remarks FROM ecis_purchase_order WHERE id = ?", String.class, id);
        String old = before.isEmpty() || before.get(0) == null || before.get(0).isEmpty() ? null : before.get(0);
        jdbc.update("UPDATE ecis_purchase_order SET status = 'CANCELLED', remarks = ? WHERE id = ? AND status = 'DRAFT'", (old == null ? "" : old + " | ") + "Turned down: " + note, id);
        List<Long> requests = jdbc.query("SELECT request_id FROM ecis_purchase_order WHERE id = ?", (rs, i) -> { long v = rs.getLong(1); return rs.wasNull() ? null : v; }, id);
        Long request = requests.isEmpty() ? null : requests.get(0);
        if (request != null) {
            Integer others = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_purchase_order WHERE request_id = ? AND status <> 'CANCELLED' AND id <> ?", Integer.class, request, id);
            if (others != null && others == 0) jdbc.update("UPDATE ecis_purchase_request SET status = 'APPROVED' WHERE id = ? AND status = 'ORDERED'", request);
        }
        audit(by, "procurement.order.reject", number(entityId), note);
    }

    @Override
    public String describe(String entityId) {
        return jdbc.query("SELECT o.order_no, s.name FROM ecis_purchase_order o JOIN ecis_supplier s ON s.id = o.supplier_id WHERE o.id = ?", (rs, i) -> rs.getString(1) + " · " + rs.getString(2), Long.parseLong(entityId)).stream().findFirst().orElse(entityId);
    }
}
