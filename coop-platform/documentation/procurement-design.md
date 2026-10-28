# Procurement (ECIS.pdf phase 12)

The guide lists: supplier, purchase_request, purchase_order(+line), goods_receipt, supplier_invoice, accounts_payable, supplier_payment, and the flow
Purchase Request - Approval - Purchase Order - Receiving - Invoice - Payable - Payment - Accounting.

The old database has no purchasing data: `inv_vendor` has one row, `inv_posum`, `inv_podtl`, `inv_pisum`, `inv_item`, `inv_salessum` and the other `inv_*` tables are empty.
So this module is new, in `ecis_*` tables (`V029_procurement.sql`, applied), designed from the guide, not mapped from old transactions.

## Stage 1 (built 2026-10-28): suppliers, requests, orders

* Roles: `PURCHASER` makes suppliers, requests and orders; `PURCHASE_APPROVER` approves; `ADMIN` can do both. Granted like `ACCOUNTANT` (`PUT /api/admin/users/{u}/roles/PURCHASER`); the Users screen has no switch for them yet.
* **Request** (`/api/procurement/requests`, `PR-2026-0001`): a purchaser submits it (purpose, lines with unit, quantity and estimated cost); an approver who is not the requester approves, or rejects with a reason; the requester may withdraw it with a reason until it is ordered.
* **Order** (`/api/procurement/orders`, `PO-2026-0001`): written to an active supplier, optionally answering an approved request (the request becomes ORDERED); the supplier's payment terms are copied; saved as DRAFT; an approver who is not its maker approves it; cancelling a DRAFT or APPROVED order that has received nothing needs a reason and frees its request again.
* **Supplier** (`/api/procurement/suppliers`): code, name, TIN, contact, terms in days, taxable; switched off, never deleted.
* Nobody decides their own work whatever their role (`ProcurementRules.approverProblem`); every step writes an audit row (`procurement.*`).
* Screen: the Purchasing tile (the old placeholder "Payables"), tabs Requests, Orders, Suppliers (`ProcurementPage`).
* Tests: `ProcurementRulesTest` (pure) and `ProcurementFlowTest` (real database, rolled back).

## Next stages

2. Goods receipt against an approved order (partial receipts, `qty_received`, order status RECEIVED / CLOSED), later feeding inventory (phase 13).
3. Supplier invoice with a three-way check (order, receipt, invoice), accounts payable account and due dates by the supplier's terms.
4. Supplier payment, and the accounting entries as journals through the existing journal flow (Dr expense or inventory, Cr accounts payable 2010501; Dr accounts payable, Cr bank), so approval and posting stay maker-checker.
Decisions for the owner before stage 3: which expense or inventory accounts purchases go to, whether input VAT (10403) is separated, and approval limits by amount.
