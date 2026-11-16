# Workflow engine (ECIS.pdf phase 17)

The guide asks for configurable workflows for Membership, Loan, Purchasing, Journal Approval, Cash Adjustments, Inventory Adjustments and CIC Submission, with a loan example (Officer, Supervisor, Credit Committee, Authorized Approver, Release) and: "Approval limits must be configurable." It lists Camunda in the stack; none is running here, so this is a small engine inside the application.

## How it works

Every module already has its own approval (the purchase approver, the stock approver ...). The engine does not replace it: that approval is always **step 0**, done by the person the module already allows. A workflow adds **extra steps after it**, each with a **name**, a **role** and a **limit** (the step applies when the item's amount is at least the limit). With no workflow configured, or an amount under every limit, nothing changes.

* A module's approve button asks the engine (`WorkflowEngine.gate`). If no extra step applies, the module finishes as before. If some do, the first approval is recorded, the **steps that apply are copied into the item** (so changing the configuration later never changes an item under way) and the item waits.
* The extra approvers act from **Approvals > Other approvals waiting for you** (`GET /api/workflow/waiting`, `POST /api/workflow/{type}/{id}/approve` or `/reject`). A step is taken by someone holding the step's role (an administrator can take any step); **never by the maker or by anyone who already approved an earlier step**. A rejection needs a reason and ends the item the way the module's own rejection would.
* When the last step agrees, the engine calls the item's `WorkflowHost` (`approved`), which finishes it exactly as the module's own approval would (sets it approved, posts the stock ledger ...). A host failing (for example, stock no longer there) undoes the step.
* If the module cancels or rejects the item while it waits, the chain is closed (`CANCELLED`). Every action is kept (`ecis_workflow_action`), and every configuration change is audited with before and after (`workflow.configure`).
* Configuration (`V036_workflow.sql`, applied): **Settings > Approval workflows** (administrators change, accountants read). At most 5 extra steps per kind, roles from ACCOUNTANT, MANAGER, CREDIT_COMMITTEE, PURCHASE_APPROVER, STOCK_APPROVER, ADMIN. New roles `MANAGER` and `CREDIT_COMMITTEE` are granted through the API like the others.

## Wired so far

| Kind | Module | Amount used for the limit |
|---|---|---|
| PURCHASE_ORDER | purchase order approval | the order total |
| PURCHASE_REQUEST | purchase request approval | the estimate |
| STOCK_ADJUSTMENT | stock adjustment approval | quantity x average cost of every line |

Lists show "waiting for ..." under the status of an item held by a chain, and the usual approver's Approve button is hidden once they have approved.

* Tests: `WorkflowRulesTest` (pure) and `WorkflowPurchasingTest`, `WorkflowStockTest` (real database, rolled back): amounts under the limits need nothing extra; chains in order; the maker, an earlier approver and the wrong role are refused; an administrator can stand in; rejection; configuration changes do not affect items under way; cancelling closes the chain; the saved configuration is validated.

## Not wired yet (each needs its module's approval to ask the engine, and its finishing step offered to a host)

* **Loan application** (the guide's own example). The loan screens authorise by legacy permission flags (verify, approve) and the release voucher has its own three-person rule; a Credit Committee step before the final approval is the natural first addition.
* **Journal approval** (the journal states have no reject), **membership changes**, **cash adjustments** (no module yet) and **CIC submission** (last phase).
* Parallel approvals (two members of a committee), delegation when an approver is away, deadlines and reminders, and notifications (phase notification is separate).
