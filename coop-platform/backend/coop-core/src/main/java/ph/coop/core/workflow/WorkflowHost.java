package ph.coop.core.workflow;

/**
 * A kind of item that a workflow can add approvals to (a purchase order, a stock adjustment ...). The module keeps its own first approval and its own tables; the engine
 * calls the host when the whole chain has agreed or someone turns the item down, so the host can finish what its own approval would have finished.
 */
public interface WorkflowHost {

    /** The code the workflows are configured under, such as PURCHASE_ORDER. */
    String entityType();

    /** What it is called on screens, such as "Purchase order". */
    String label();

    /** The whole chain has approved: finish the item (set it approved, post its stock ...). {@code approver} is the last person to approve. */
    void approved(String entityId, String approver);

    /** A step turned the item down. Finish it as the module's own rejection would. */
    void rejected(String entityId, String by, String note);

    /** A short description of the item for the approvals list, such as "PO-2026-0004 · Trading Co." */
    String describe(String entityId);
}
