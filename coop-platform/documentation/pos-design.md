# Point of sale (ECIS.pdf phase 14)

The guide lists: product catalog, pricing, discount, promotion, sales, sales lines, returns, payments, receipts, and the flow
Sale - Payment - Inventory Reduction - Revenue - Cost of Goods Sold - Accounting.

The old database's `inv_*` sales tables are empty (no items, no sales), and the Front Office Sales Receipt / Sales Return tabs have no data behind them, so this module is new,
in `ecis_*` tables (`V033_pos.sql`, applied).

## Built (2026-10-28)

* **Prices** (`ecis_product_price`, insert-only history, the newest row is the price): set by a POS supervisor; a new price never changes an old sale (each sale line keeps the price it sold at). A product with no price cannot be sold.
* **Promotions** (`ecis_promotion`): a percent off one product between two dates, picked up automatically when the sale is rung up (the highest running percent wins); switched on or off, not edited.
* **Discounts** (`ecis_discount`): a PERCENT or AMOUNT off the whole sale, optionally "supervisor only" (for example a senior citizen discount); an amount discount can never exceed the sale.
* **Sale** (`POST /api/pos/sales`, receipt number `SL-yyyy-nnnn`): lines at the current price less the promotion, the sale discount, payments by CASH, CARD or OTHER (only cash can exceed the total and give change; the payments must cover it), optional member or customer name. The sale takes its stock out of the chosen warehouse through `StockService` (type SALE, `ref_type` SALE), **batch products first-expiry-first-out, expired batches never sold** (a line can split across batches); each sale line keeps the **unit cost from the stock ledger**, and the sale keeps `cost_total` (cost of goods sold). Selling more than is on hand or at a payment that does not cover the total is refused with nothing written.
* **Returns** (`/api/pos/returns`, `RT-yyyy-nnnn`): a cashier records goods brought back against a sale with a reason (not more than was sold less what is already back or waiting); the refund is what the customer paid for those units with the promotion and the sale discount shared out in proportion; **a POS supervisor who did not record it approves it**, which puts the goods back (RETURN into the warehouse and batch they came from), counts the refund against the sale and the line; a rejection needs a reason; nothing changes before approval.
* **Roles** (granted by API like ACCOUNTANT, no Users switch yet): `POS_CASHIER` sells and records returns, `POS_SUPERVISOR` sets prices, discounts, promotions and decides returns; ADMIN may do both but never approves their own return. Audit rows `pos.*`.
* **Screen**: the POS tile (`PosPage`): Sell (store, product search with price, promotion and stock, cart, discount, payment and change, printable receipt), Sales (a day's sales, receipts, ask for a return), Returns (approve or reject), Prices (price, discounts, promotions).
* Tests: `PosRulesTest` (pure), `PosFlowTest` (real database, rolled back).

## Not built

* **Revenue, cost of sales and cash entries in the general ledger.** The flow ends with Accounting, but which accounts take the cash, sales, discounts, cost of goods sold and inventory is the owner's decision, so every sale carries `journaled = 0` and its `cost_total`; a day-end journal (debit cash and cost of goods sold, credit sales and inventory) through the existing journal approval is the intended way once the accounts are chosen.
* VAT and the BIR receipt requirements (official receipt numbering, senior citizen VAT exemption); the prices are taken as the selling price.
* Charging a sale to a member's savings or share account, sales shifts and cash counts per cashier, held sales, barcode scanning, and returns of a refund by method other than as recorded.
* A return puts goods back at the product's current cost rather than the cost they sold at.
