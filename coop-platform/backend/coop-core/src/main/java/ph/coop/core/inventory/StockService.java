package ph.coop.core.inventory;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;

/**
 * The one place stock changes. Every change writes a row to the insert-only ledger (ecis_inventory_txn) and moves the balance (ecis_inventory_balance) in the same
 * transaction, so the balance always equals the sum of the ledger. Stock never goes below zero, a batch-tracked product needs a batch, an expired batch cannot go out,
 * and receipts keep the product's moving average cost. Other modules (goods receipt, point of sale) post stock through here, never by touching the balance.
 */
@Component
public class StockService {

    /** What to move: a positive {@code quantity} comes in, a negative one goes out. {@code unitCost} matters only for RECEIVE. */
    public record Move(String type, long productId, long warehouseId, String batchNo, LocalDate expiry, BigDecimal quantity, BigDecimal unitCost, String refType, String refNo, String remarks) {}

    /** What was written: the ledger row number and the cost the movement carried. */
    public record Posted(long id, String txnNo, BigDecimal unitCost, long batchId) {}

    private record Product(String name, boolean trackBatch, boolean trackExpiry, boolean active, BigDecimal cost) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final InventorySupport support;

    private final ph.coop.core.businessday.BusinessDay businessDay;

    StockService(JdbcTemplate jdbc, Db db, InventorySupport support, ph.coop.core.businessday.BusinessDay businessDay) {
        this.businessDay = businessDay;
        this.jdbc = jdbc;
        this.db = db;
        this.support = support;
    }

    private static ResponseStatusException conflict(String m) {
        return new ResponseStatusException(HttpStatus.CONFLICT, m);
    }

    /** The batch's id and expiry, creating the batch when {@code create} and it is new; 0 for no batch. */
    long batchId(long productId, String batchNo, LocalDate expiry, boolean create) {
        if (batchNo == null || batchNo.isBlank()) return 0;
        List<Long> found = jdbc.queryForList("SELECT id FROM ecis_inventory_batch WHERE product_id = ? AND batch_no = ?", Long.class, productId, batchNo.trim());
        if (!found.isEmpty()) return found.get(0);
        if (!create) throw conflict("There is no batch " + batchNo.trim() + " of this product.");
        return db.insertReturningId("INSERT INTO ecis_inventory_batch (product_id, batch_no, expiry_date) VALUES (?, ?, ?)", "id", productId, batchNo.trim(), expiry);
    }

    /** On hand of a product in one warehouse and batch (0 when there is none). */
    public BigDecimal onHand(long productId, long warehouseId, long batchId) {
        List<BigDecimal> q = jdbc.queryForList("SELECT quantity FROM ecis_inventory_balance WHERE product_id = ? AND warehouse_id = ? AND batch_id = ?", BigDecimal.class, productId, warehouseId, batchId);
        return q.isEmpty() ? BigDecimal.ZERO : q.get(0);
    }

    /** A batch with stock in one warehouse. */
    public record BatchStock(long batchId, String batchNo, LocalDate expiry, BigDecimal quantity) {}

    /** The batches of a product that hold stock in a warehouse, earliest expiry first (no expiry date last), for first-expiry-first-out selling. */
    public List<BatchStock> batchesWithStock(long productId, long warehouseId) {
        return jdbc.query("""
                SELECT b.batch_id, t.batch_no, t.expiry_date, b.quantity FROM ecis_inventory_balance b JOIN ecis_inventory_batch t ON t.id = b.batch_id
                WHERE b.product_id = ? AND b.warehouse_id = ? AND b.batch_id <> 0 AND b.quantity > 0 ORDER BY CASE WHEN t.expiry_date IS NULL THEN 1 ELSE 0 END, t.expiry_date, t.id
                """, (rs, i) -> new BatchStock(rs.getLong(1), rs.getString(2), rs.getDate(3) == null ? null : rs.getDate(3).toLocalDate(), rs.getBigDecimal(4)), productId, warehouseId);
    }

    /** Posts one movement, or throws with the reason. Call inside a transaction. */
    public Posted post(Move m, String user) {
        // stock moves (receipts, sales, returns, adjustments, counts, transfers) only while the branch has a business day open
        businessDay.requireStarted(InventorySupport.BRANCH);
        if (!InventoryRules.TYPES.contains(m.type())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown movement type " + m.type() + ".");
        if (m.quantity() == null || m.quantity().signum() == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The quantity cannot be zero.");
        int sign = InventoryRules.sign(m.type());
        if (sign != 0 && m.quantity().signum() != sign) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, m.type() + " moves stock " + (sign > 0 ? "in" : "out") + ".");
        List<String> q = InventoryRules.quantityProblems(m.quantity().abs());
        if (!q.isEmpty()) throw InventorySupport.bad(q);
        if (m.type().equals("RECEIVE") && (m.unitCost() == null || m.unitCost().signum() < 0)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the cost of a unit received.");

        List<Product> products = jdbc.query("SELECT name, track_batch, track_expiry, active, cost FROM ecis_product WHERE branch_code = ? AND id = ? FOR UPDATE",
            (rs, i) -> new Product(rs.getString(1), rs.getInt(2) == 1, rs.getInt(3) == 1, rs.getInt(4) == 1, rs.getBigDecimal(5)), InventorySupport.BRANCH, m.productId());
        if (products.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That product was not found; it may have been switched off or the code mistyped. Choose the product from the list.");
        Product p = products.get(0);
        Integer wh = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_warehouse WHERE branch_code = ? AND id = ? AND active = 1", Integer.class, InventorySupport.BRANCH, m.warehouseId());
        if (wh == null || wh == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That warehouse was not found or is switched off. Choose an active warehouse from the list.");
        if (!p.active() && m.quantity().signum() > 0 && !m.type().equals("ADJUSTMENT")) throw conflict(p.name() + " is switched off.");

        boolean in = m.quantity().signum() > 0;
        boolean existing = m.batchNo() != null && !m.batchNo().isBlank()
            && !jdbc.queryForList("SELECT id FROM ecis_inventory_batch WHERE product_id = ? AND batch_no = ?", Long.class, m.productId(), m.batchNo().trim()).isEmpty();
        List<String> b = InventoryRules.batchProblems(p.trackBatch(), p.trackExpiry(), m.batchNo(), m.expiry(), !existing);
        if (!b.isEmpty()) throw InventorySupport.bad(b);
        long batch = batchId(m.productId(), m.batchNo(), m.expiry(), in);
        if (!in && batch != 0) {
            LocalDate exp = jdbc.queryForObject("SELECT expiry_date FROM ecis_inventory_batch WHERE id = ?", LocalDate.class, batch);
            if (!m.type().equals("EXPIRY") && !m.type().equals("ADJUSTMENT") && !m.type().equals("TRANSFER") && InventoryRules.expired(exp, support.today()))
                throw conflict("Batch " + m.batchNo().trim() + " of " + p.name() + " has expired; write it off as EXPIRY instead.");
        }

        // the balance row, locked; a missing one starts at zero
        List<BigDecimal> bal = jdbc.queryForList("SELECT quantity FROM ecis_inventory_balance WHERE product_id = ? AND warehouse_id = ? AND batch_id = ? FOR UPDATE", BigDecimal.class, m.productId(), m.warehouseId(), batch);
        BigDecimal before = bal.isEmpty() ? BigDecimal.ZERO : bal.get(0);
        BigDecimal after = before.add(m.quantity());
        if (after.signum() < 0) throw conflict(p.name() + ": " + InventoryRules.shortage(before, m.quantity().abs()));
        if (bal.isEmpty()) jdbc.update("INSERT INTO ecis_inventory_balance (product_id, warehouse_id, batch_id, quantity) VALUES (?, ?, ?, ?)", m.productId(), m.warehouseId(), batch, after);
        else jdbc.update("UPDATE ecis_inventory_balance SET quantity = ? WHERE product_id = ? AND warehouse_id = ? AND batch_id = ?", after, m.productId(), m.warehouseId(), batch);

        // receipts bring the average cost along; everything else moves at the current cost
        BigDecimal cost = p.cost();
        if (m.type().equals("RECEIVE")) {
            BigDecimal total = jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM ecis_inventory_balance WHERE product_id = ?", BigDecimal.class, m.productId()).subtract(m.quantity());
            cost = InventoryRules.movingAverage(total, p.cost(), m.quantity(), m.unitCost());
            jdbc.update("UPDATE ecis_product SET cost = ? WHERE id = ?", cost, m.productId());
        }

        int year = support.today().getYear();
        List<String> last = jdbc.queryForList("SELECT txn_no FROM ecis_inventory_txn WHERE branch_code = ? AND txn_no LIKE ? ORDER BY id DESC LIMIT 1 FOR UPDATE", String.class, InventorySupport.BRANCH, "INV-" + year + "-%");
        String no = InventoryRules.txnNumber(year, last.isEmpty() ? 1 : InventoryRules.txnSequence(last.get(0), year) + 1);
        long id = db.insertReturningId("""
                INSERT INTO ecis_inventory_txn (branch_code, txn_no, txn_date, txn_type, product_id, warehouse_id, batch_id, quantity, unit_cost, ref_type, ref_no, remarks, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "id", InventorySupport.BRANCH, no, support.today(), m.type(), m.productId(), m.warehouseId(), batch, m.quantity(), cost, m.refType(), m.refNo(),
            m.remarks() == null || m.remarks().isBlank() ? null : m.remarks().trim(), user, Timestamp.valueOf(support.now()));
        return new Posted(id, no, cost, batch);
    }

    /** The balance per product, warehouse and batch recomputed from the ledger, for checking. */
    public List<BigDecimal> ledgerSum(long productId) {
        return new ArrayList<>(jdbc.queryForList("SELECT COALESCE(SUM(quantity), 0) FROM ecis_inventory_txn WHERE product_id = ?", BigDecimal.class, productId));
    }
}
