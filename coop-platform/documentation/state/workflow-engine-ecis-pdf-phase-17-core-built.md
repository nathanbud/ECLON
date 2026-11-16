# Workflow engine (ECIS.pdf phase 17, core built)

Moved from CLAUDE.md unchanged (one sentence per line). Keep this note current when the feature changes.

**Workflow engine (ECIS.pdf phase 17, core built)**: `ph.coop.core.workflow` (`WorkflowEngine`, `WorkflowHost`, `WorkflowController`, `/api/workflow`; `V036_workflow.sql` applied).
A module keeps its own approval as step 0 and asks `engine.gate(type, id, amount, maker, approver)`; a configured workflow (Settings > Approval workflows, administrators change it) adds **extra steps with a role and an amount limit** (e.g. purchase orders of 100,000 or more also need the accountant, 1,000,000 or more the manager); the applicable steps are copied into the item when its first approval happens, extra approvers act from Approvals > Other approvals waiting for you (never the maker or an earlier approver, role or ADMIN), the last approval calls the item's host to finish it, a rejection or a cancel closes the chain, every action and configuration change is recorded.
Wired to PURCHASE_ORDER, PURCHASE_REQUEST and STOCK_ADJUSTMENT (`PurchaseOrderWorkflowHost`, `PurchaseRequestWorkflowHost`, `StockAdjustmentWorkflowHost`; the stock posting moved to `StockAdjustmentPosting`); roles MANAGER and CREDIT_COMMITTEE (granted by API); `WorkflowRulesTest`, `WorkflowPurchasingTest`, `WorkflowStockTest`; see `documentation/workflow-design.md`.
Not wired: loan applications (the guide's Credit Committee example; they authorise by legacy permission flags), journals, membership changes, cash adjustments, CIC.
