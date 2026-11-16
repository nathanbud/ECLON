-- Goods receipts feed stock (procurement stage 2 into inventory phase 13): an order line may name a product, a receipt names the warehouse that takes the goods,
-- and each received line remembers its batch and whether it was stocked, so a reversal can take the same stock back out.
-- Plain ALTERs: MySQL 5.6 has no ADD COLUMN IF NOT EXISTS, so run this ONCE, by an administrator. No new grants are needed.
ALTER TABLE ecis_purchase_order_line ADD COLUMN product_id BIGINT UNSIGNED NULL AFTER line_no;
ALTER TABLE ecis_goods_receipt ADD COLUMN warehouse_id BIGINT UNSIGNED NULL AFTER order_id;
ALTER TABLE ecis_goods_receipt_line ADD COLUMN batch_id BIGINT UNSIGNED NOT NULL DEFAULT 0, ADD COLUMN stocked TINYINT(1) NOT NULL DEFAULT 0;
