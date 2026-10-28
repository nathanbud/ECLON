# Inventory (ECIS.pdf phase 13)

The guide lists: product, product_category, unit_of_measure, warehouse, warehouse_location, inventory_balance, inventory_transaction, inventory_batch, stock_count, stock_adjustment.
It must support RECEIVE, ISSUE, SALE, RETURN, TRANSFER, ADJUSTMENT, DAMAGE and EXPIRY, and "do not simply modify quantity fields without recording the transaction".

The old database's `inv_*` tables are all empty (no items, no stock, no sales), so this module is new, in `ecis_*` tables (`V031_inventory.sql`, applied).

## What is built (2026-10-28)

* **Ledger and balance**: `ecis_inventory_txn` is insert-only (the app has no UPDATE or DELETE grant on it); quantity is signed. `ecis_inventory_balance` per product, warehouse and batch is changed only together with a ledger row, inside one transaction, by `StockService.post`. The balance always equals the sum of the ledger (`InventoryFlowTest` checks it after each scenario). Other modules (goods receipt next, point of sale later) must post stock through `StockService`, never by touching the balance.
* **Rules** (`InventoryRules`, pure): RECEIVE and RETURN go in; ISSUE, SALE, DAMAGE and EXPIRY go out; TRANSFER and ADJUSTMENT may go either way. Stock never goes below zero. A product tracked by batch needs a batch on every movement, and a new batch of an expiring product needs its expiry date; an expired batch cannot be issued or sold (it is written off as EXPIRY). Quantities have at most three decimals.
* **Cost**: the product keeps a **moving average cost**; a RECEIVE (which must state the cost of a unit) re-averages it over the quantity on hand in all warehouses; every other movement goes at the current cost. This is stock valuation only: **nothing is posted to the general ledger yet** (the inventory, cost of sales and payable accounts are the owner's accounting decisions, see procurement-design.md).
* **Movements by hand** (`/api/inventory/movements`, `/transfers`): RECEIVE, ISSUE, RETURN to stock, TRANSFER between warehouses (an out row and an in row). SALE is refused (point of sale, phase 14).
* **Adjustments** (`/api/inventory/adjustments`, `SA-yyyy-nnnn`): ADJUSTMENT (either way), DAMAGE and EXPIRY (out only), with a reason; **nothing changes until a different STOCK_APPROVER approves it**, which writes the ledger rows; a rejection needs a reason.
* **Counts** (`/api/inventory/counts`, `SC-yyyy-nnnn`): the storekeeper enters what was counted, the screen records what the books showed at that moment; a different STOCK_APPROVER approves, which posts an ADJUSTMENT row for each difference only.
* **Master data** (`/api/inventory/products`, `warehouses`, `categories`, `units`): a product or warehouse is switched off, never deleted; a warehouse or product that still holds stock cannot be switched off; once a product has stock history its unit and batch tracking are locked. 14 units are seeded.
* Roles (granted by API like ACCOUNTANT; the Users screen has no switch yet): `STOREKEEPER`, `STOCK_APPROVER`; ADMIN may do both but never decides their own work. Audit rows `inventory.*`.
* Screen: the Inventory tile (`InventoryPage`): Products (with a stock and movement history dialog and low-stock flag), Movements, Adjustments, Counts, Setup.
* Tests: `InventoryRulesTest` (pure) and `InventoryFlowTest` (real database, rolled back).

## Not built

* **Receipts feeding stock**: a goods receipt does not create a RECEIVE row yet. It needs a product on each order line (order lines are free text today) and a warehouse on the receipt; the next step.
* Warehouse locations (bins) inside a warehouse; the balance is per warehouse and batch.
* Accounting entries for stock (inventory, cost of goods sold, adjustments) and the sales side, which come with the owner's account decisions and phase 14.
* A reservation or "available" quantity, serial numbers, and automatic reorder requests (the reorder level only flags a product as low).
