# Procurement (ECIS.pdf phase 12, stage 1 built)

Moved from CLAUDE.md unchanged (one sentence per line). Keep this note current when the feature changes.

**Procurement (ECIS.pdf phase 12, stage 1 built)**: the old database has no purchasing data (`inv_*` tables are empty), so it is new `ecis_*` tables (`V029_procurement.sql`, applied; `ecis_supplier`, `ecis_purchase_request(_line)`, `ecis_purchase_order(_line)`): suppliers, purchase requests (`PR-yyyy-nnnn`, submitted by a PURCHASER, approved or rejected by a different PURCHASE_APPROVER) and purchase orders (`PO-yyyy-nnnn`, to an active supplier, optionally from an approved request, approved by someone other than its maker) under `/api/procurement`; nobody decides their own work; screen = Purchasing tile (`ProcurementPage`); roles granted by API like ACCOUNTANT (no Users switch yet); tested by `ProcurementRulesTest` and `ProcurementFlowTest`. **Goods receipt is built** (`V030_goods_receipt.sql` applied, `GoodsReceiptController`, `GR-yyyy-nnnn`: a RECEIVER records goods against an approved order, partial or complete, order status APPROVED, PARTIAL, RECEIVED; the maker of an order cannot receive it; a different PURCHASE_APPROVER reverses a receipt with a reason; `GoodsReceiptTest`).
Receipts of stocked order lines now create stock (see Inventory).
Not built: supplier invoices and payables, payments and their journals (see `documentation/procurement-design.md`).
Then inventory (13), POS (14), fixed assets (15), reporting, BI, and CIC (11) last by the owner's choice.
