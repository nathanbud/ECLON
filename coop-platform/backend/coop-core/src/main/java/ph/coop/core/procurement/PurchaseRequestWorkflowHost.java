package ph.coop.core.procurement;


import ph.coop.core.audit.AuditLog;import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.workflow.WorkflowHost;

/** Lets the workflow engine finish a purchase request once every extra approval has agreed (or turn it down), as the request controller's own decision would have. */
@Component
class PurchaseRequestWorkflowHost implements WorkflowHost {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    PurchaseRequestWorkflowHost(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now(clock).withNano(0));
    }

    private void audit(String user, String action, String no, String detail) {
        AuditLog.write(jdbc, now(), user, action, "ecis_purchase_request", no, null, detail, null);
    }

    private String number(String entityId) {
        return jdbc.queryForList("SELECT request_no FROM ecis_purchase_request WHERE id = ?", String.class, Long.parseLong(entityId)).stream().findFirst().orElse(entityId);
    }

    @Override
    public String entityType() {
        return PurchaseRequestController.WORKFLOW;
    }

    @Override
    public String label() {
        return "Purchase request";
    }

    @Override
    public void approved(String entityId, String approver) {
        jdbc.update("UPDATE ecis_purchase_request SET status = 'APPROVED', decided_by = ?, decided_at = ? WHERE id = ? AND status = 'SUBMITTED'", approver, now(), Long.parseLong(entityId));
        audit(approver, "procurement.request.approve", number(entityId), "approved after the extra approvals");
    }

    @Override
    public void rejected(String entityId, String by, String note) {
        jdbc.update("UPDATE ecis_purchase_request SET status = 'REJECTED', decided_by = ?, decided_at = ?, decision_note = ? WHERE id = ? AND status = 'SUBMITTED'", by, now(), note, Long.parseLong(entityId));
        audit(by, "procurement.request.reject", number(entityId), note);
    }

    @Override
    public String describe(String entityId) {
        return jdbc.query("SELECT request_no, purpose FROM ecis_purchase_request WHERE id = ?", (rs, i) -> rs.getString(1) + " · " + rs.getString(2), Long.parseLong(entityId)).stream().findFirst().orElse(entityId);
    }
}
