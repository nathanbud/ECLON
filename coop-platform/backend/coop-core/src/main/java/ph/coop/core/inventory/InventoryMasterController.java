package ph.coop.core.inventory;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;

/**
 * Units, product categories, warehouses and products (guide phase 13). Storekeepers keep them; nothing is deleted, a product or warehouse is switched off.
 * Stock itself is never typed here: it only changes through {@link StockService}, so every quantity has a ledger row behind it.
 */
@RestController
@RequestMapping("/api/inventory")
@PreAuthorize("hasAnyRole('STOREKEEPER','STOCK_APPROVER','RECEIVER','PURCHASER','PURCHASE_APPROVER','ACCOUNTANT','ADMIN')")
class InventoryMasterController {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9._-]{1,30}");

    record Unit(String code, String name) {}

    record Category(long id, String name) {}

    record Warehouse(long id, String code, String name, boolean active) {}

    record WarehouseIn(String code, String name, Boolean active) {}

    record Product(long id, String sku, String name, Long categoryId, String category, String unit, BigDecimal cost, BigDecimal reorderLevel, boolean trackBatch, boolean trackExpiry, boolean active,
                   BigDecimal onHand, boolean low) {}

    record ProductIn(String sku, String name, Long categoryId, String unit, BigDecimal reorderLevel, Boolean trackBatch, Boolean trackExpiry, Boolean active) {}

    record StockRow(long warehouseId, String warehouse, long batchId, String batchNo, LocalDate expiry, BigDecimal quantity) {}

    record LedgerRow(long id, String txnNo, LocalDate date, String type, long productId, String sku, String product, long warehouseId, String warehouse, String batchNo, BigDecimal quantity,
                     BigDecimal unitCost, String refType, String refNo, String remarks, String by) {}

    private static final String PRODUCT = """
            SELECT p.id, p.sku, p.name, p.category_id, c.name, p.unit, p.cost, p.reorder_level, p.track_batch, p.track_expiry, p.active,
                   (SELECT COALESCE(SUM(b.quantity), 0) FROM ecis_inventory_balance b WHERE b.product_id = p.id)
            FROM ecis_product p LEFT JOIN ecis_product_category c ON c.id = p.category_id
            """;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final InventorySupport support;

    InventoryMasterController(JdbcTemplate jdbc, Db db, InventorySupport support) {
        this.jdbc = jdbc;
        this.db = db;
        this.support = support;
    }

    // ---- units and categories --------------------------------------------------------------------------------------

    @GetMapping("/units")
    List<Unit> units() {
        return jdbc.query("SELECT code, name FROM ecis_unit ORDER BY code", (rs, i) -> new Unit(rs.getString(1), rs.getString(2)));
    }

    @PostMapping("/units")
    @Transactional
    Unit addUnit(@RequestBody Unit in, Authentication auth) {
        support.requireKeeper(auth);
        if (in.code() == null || !in.code().trim().matches("[A-Za-z0-9]{1,10}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The unit code is 1 to 10 letters or digits.");
        if (in.name() == null || in.name().isBlank() || in.name().length() > 40) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the unit's name (40 characters at most).");
        String code = in.code().trim().toLowerCase();
        Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_unit WHERE code = ?", Integer.class, code);
        if (dup != null && dup > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "There is already a unit " + code + ".");
        jdbc.update("INSERT INTO ecis_unit (code, name) VALUES (?, ?)", code, in.name().trim());
        support.audit(auth, "inventory.unit.create", "ecis_unit", code, in.name().trim());
        return new Unit(code, in.name().trim());
    }

    @GetMapping("/categories")
    List<Category> categories() {
        return jdbc.query("SELECT id, name FROM ecis_product_category WHERE branch_code = ? ORDER BY name", (rs, i) -> new Category(rs.getLong(1), rs.getString(2)), InventorySupport.BRANCH);
    }

    @PostMapping("/categories")
    @Transactional
    Category addCategory(@RequestBody Category in, Authentication auth) {
        support.requireKeeper(auth);
        if (in.name() == null || in.name().isBlank() || in.name().length() > 60) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the category's name (60 characters at most).");
        Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_product_category WHERE branch_code = ? AND name = ?", Integer.class, InventorySupport.BRANCH, in.name().trim());
        if (dup != null && dup > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "There is already a category " + in.name().trim() + ".");
        long id = db.insertReturningId("INSERT INTO ecis_product_category (branch_code, name) VALUES (?, ?)", "id", InventorySupport.BRANCH, in.name().trim());
        support.audit(auth, "inventory.category.create", "ecis_product_category", String.valueOf(id), in.name().trim());
        return new Category(id, in.name().trim());
    }

    // ---- warehouses -----------------------------------------------------------------------------------------------

    private static Warehouse warehouse(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Warehouse(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4) == 1);
    }

    @GetMapping("/warehouses")
    List<Warehouse> warehouses(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return jdbc.query("SELECT id, code, name, active FROM ecis_warehouse WHERE branch_code = ? AND (? = 0 OR active = 1) ORDER BY name", InventoryMasterController::warehouse, InventorySupport.BRANCH, activeOnly ? 1 : 0);
    }

    @PostMapping("/warehouses")
    @Transactional
    Warehouse addWarehouse(@RequestBody WarehouseIn in, Authentication auth) {
        support.requireKeeper(auth);
        if (in.code() == null || !CODE.matcher(in.code().trim()).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The warehouse code is 1 to 20 letters, digits, dots or dashes.");
        if (in.name() == null || in.name().isBlank() || in.name().length() > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the warehouse's name (80 characters at most).");
        String code = in.code().trim().toUpperCase();
        if (code.length() > 20) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The warehouse code is 20 characters at most.");
        Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_warehouse WHERE branch_code = ? AND code = ?", Integer.class, InventorySupport.BRANCH, code);
        if (dup != null && dup > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "There is already a warehouse " + code + ".");
        long id = db.insertReturningId("INSERT INTO ecis_warehouse (branch_code, code, name, active) VALUES (?, ?, ?, 1)", "id", InventorySupport.BRANCH, code, in.name().trim());
        support.audit(auth, "inventory.warehouse.create", "ecis_warehouse", code, in.name().trim());
        return new Warehouse(id, code, in.name().trim(), true);
    }

    /** Renames a warehouse or switches it off; one that still holds stock cannot be switched off. */
    @PutMapping("/warehouses/{id}")
    @Transactional
    Warehouse updateWarehouse(@PathVariable long id, @RequestBody WarehouseIn in, Authentication auth) {
        support.requireKeeper(auth);
        List<Warehouse> found = jdbc.query("SELECT id, code, name, active FROM ecis_warehouse WHERE branch_code = ? AND id = ? FOR UPDATE", InventoryMasterController::warehouse, InventorySupport.BRANCH, id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such warehouse.");
        Warehouse w = found.get(0);
        String name = in.name() == null || in.name().isBlank() ? w.name() : in.name().trim();
        boolean active = in.active() == null ? w.active() : in.active();
        if (name.length() > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The name is too long (80 characters at most).");
        if (w.active() && !active) {
            BigDecimal held = jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM ecis_inventory_balance WHERE warehouse_id = ?", BigDecimal.class, id);
            if (held != null && held.signum() != 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "This warehouse still holds stock; move it out first.");
        }
        jdbc.update("UPDATE ecis_warehouse SET name = ?, active = ? WHERE id = ?", name, active ? 1 : 0, id);
        support.audit(auth, "inventory.warehouse.update", "ecis_warehouse", w.code(), w.name() + "/" + w.active() + " -> " + name + "/" + active);
        return new Warehouse(id, w.code(), name, active);
    }

    // ---- products -------------------------------------------------------------------------------------------------

    private static Product product(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        long cat = rs.getLong(4);
        Long categoryId = rs.wasNull() ? null : cat;
        BigDecimal reorder = rs.getBigDecimal(8), onHand = rs.getBigDecimal(12);
        return new Product(rs.getLong(1), rs.getString(2), rs.getString(3), categoryId, rs.getString(5), rs.getString(6), rs.getBigDecimal(7), reorder, rs.getInt(9) == 1, rs.getInt(10) == 1, rs.getInt(11) == 1,
            onHand, InventoryRules.low(onHand, reorder));
    }

    @GetMapping("/products")
    List<Product> products(@RequestParam(required = false) String q, @RequestParam(required = false) Long categoryId, @RequestParam(defaultValue = "false") boolean activeOnly) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return jdbc.query(PRODUCT + " WHERE p.branch_code = ? AND (? = 0 OR p.active = 1) AND (? IS NULL OR p.category_id = ?) AND (? IS NULL OR LOWER(p.name) LIKE ? OR LOWER(p.sku) LIKE ?) ORDER BY p.name LIMIT 500",
            InventoryMasterController::product, InventorySupport.BRANCH, activeOnly ? 1 : 0, categoryId, categoryId, like, like, like);
    }

    @GetMapping("/products/{id}")
    Product product(@PathVariable long id) {
        List<Product> p = jdbc.query(PRODUCT + " WHERE p.branch_code = ? AND p.id = ?", InventoryMasterController::product, InventorySupport.BRANCH, id);
        if (p.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such product.");
        return p.get(0);
    }

    private void checkProduct(ProductIn in, boolean creating) {
        if (in.name() == null || in.name().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the product's name.");
        if (in.name().length() > 120) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The name is too long (120 characters at most).");
        if (creating && (in.sku() == null || !CODE.matcher(in.sku().trim()).matches())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The SKU is 1 to 30 letters, digits, dots, dashes or underscores.");
        Integer unit = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_unit WHERE code = ?", Integer.class, in.unit() == null ? "" : in.unit().trim().toLowerCase());
        if (unit == null || unit == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a unit from the list.");
        if (in.reorderLevel() != null && in.reorderLevel().signum() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The reorder level cannot be negative.");
        if (in.categoryId() != null) {
            Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_product_category WHERE branch_code = ? AND id = ?", Integer.class, InventorySupport.BRANCH, in.categoryId());
            if (c == null || c == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such category.");
        }
        if (Boolean.TRUE.equals(in.trackExpiry()) && !Boolean.TRUE.equals(in.trackBatch())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expiry is tracked per batch, so the product must be tracked by batch.");
    }

    @PostMapping("/products")
    @Transactional
    Product addProduct(@RequestBody ProductIn in, Authentication auth) {
        support.requireKeeper(auth);
        checkProduct(in, true);
        String sku = in.sku().trim().toUpperCase();
        Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_product WHERE branch_code = ? AND sku = ?", Integer.class, InventorySupport.BRANCH, sku);
        if (dup != null && dup > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "There is already a product with the SKU " + sku + ".");
        long id = db.insertReturningId("""
                INSERT INTO ecis_product (branch_code, sku, name, category_id, unit, cost, reorder_level, track_batch, track_expiry, active, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, 1, ?, ?)
                """, "id", InventorySupport.BRANCH, sku, in.name().trim(), in.categoryId(), in.unit().trim().toLowerCase(), in.reorderLevel() == null ? BigDecimal.ZERO : in.reorderLevel(),
            Boolean.TRUE.equals(in.trackBatch()) ? 1 : 0, Boolean.TRUE.equals(in.trackExpiry()) ? 1 : 0, auth.getName(), Timestamp.valueOf(support.now()));
        support.audit(auth, "inventory.product.create", "ecis_product", sku, in.name().trim());
        return product(id);
    }

    /** Changes the name, category, reorder level or on/off; the SKU, unit and tracking cannot change once there is stock history. */
    @PutMapping("/products/{id}")
    @Transactional
    Product updateProduct(@PathVariable long id, @RequestBody ProductIn in, Authentication auth) {
        support.requireKeeper(auth);
        Product before = product(id);
        ProductIn merged = new ProductIn(before.sku(), in.name() == null || in.name().isBlank() ? before.name() : in.name(), in.categoryId() == null ? before.categoryId() : in.categoryId(),
            in.unit() == null ? before.unit() : in.unit(), in.reorderLevel() == null ? before.reorderLevel() : in.reorderLevel(), in.trackBatch() == null ? before.trackBatch() : in.trackBatch(),
            in.trackExpiry() == null ? before.trackExpiry() : in.trackExpiry(), in.active() == null ? before.active() : in.active());
        checkProduct(merged, false);
        Integer history = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_inventory_txn WHERE product_id = ?", Integer.class, id);
        if (history != null && history > 0 && (!merged.unit().trim().equalsIgnoreCase(before.unit()) || merged.trackBatch() != before.trackBatch() || merged.trackExpiry() != before.trackExpiry()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This product already has stock history, so its unit and batch tracking cannot change.");
        if (before.active() && !merged.active() && before.onHand().signum() != 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "This product still has stock on hand; adjust or issue it first.");
        jdbc.update("UPDATE ecis_product SET name = ?, category_id = ?, unit = ?, reorder_level = ?, track_batch = ?, track_expiry = ?, active = ? WHERE id = ?",
            merged.name().trim(), merged.categoryId(), merged.unit().trim().toLowerCase(), merged.reorderLevel(), merged.trackBatch() ? 1 : 0, merged.trackExpiry() ? 1 : 0, merged.active() ? 1 : 0, id);
        support.audit(auth, "inventory.product.update", "ecis_product", before.sku(), before.name() + " -> " + merged.name());
        return product(id);
    }

    /** Where a product is held: one row per warehouse and batch with something on hand. */
    @GetMapping("/products/{id}/stock")
    List<StockRow> stock(@PathVariable long id) {
        product(id);
        return jdbc.query("""
                SELECT w.id, w.name, b.batch_id, COALESCE(t.batch_no, ''), t.expiry_date, b.quantity
                FROM ecis_inventory_balance b JOIN ecis_warehouse w ON w.id = b.warehouse_id LEFT JOIN ecis_inventory_batch t ON t.id = b.batch_id
                WHERE b.product_id = ? AND b.quantity <> 0 ORDER BY w.name, t.expiry_date, t.batch_no
                """, (rs, i) -> new StockRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getDate(5) == null ? null : rs.getDate(5).toLocalDate(), rs.getBigDecimal(6)), id);
    }

    /** The ledger, newest first, for a product and/or warehouse. */
    @GetMapping("/ledger")
    List<LedgerRow> ledger(@RequestParam(required = false) Long productId, @RequestParam(required = false) Long warehouseId, @RequestParam(defaultValue = "100") int limit) {
        int n = Math.max(1, Math.min(limit, 500));
        return jdbc.query("""
                SELECT t.id, t.txn_no, t.txn_date, t.txn_type, t.product_id, p.sku, p.name, t.warehouse_id, w.name, COALESCE(b.batch_no, ''), t.quantity, t.unit_cost, t.ref_type, t.ref_no, t.remarks, t.created_by
                FROM ecis_inventory_txn t JOIN ecis_product p ON p.id = t.product_id JOIN ecis_warehouse w ON w.id = t.warehouse_id LEFT JOIN ecis_inventory_batch b ON b.id = t.batch_id
                WHERE t.branch_code = ? AND (? IS NULL OR t.product_id = ?) AND (? IS NULL OR t.warehouse_id = ?) ORDER BY t.id DESC LIMIT %d
                """.formatted(n), (rs, i) -> new LedgerRow(rs.getLong(1), rs.getString(2), rs.getDate(3).toLocalDate(), rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getString(9),
            rs.getString(10), rs.getBigDecimal(11), rs.getBigDecimal(12), rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16)), InventorySupport.BRANCH, productId, productId, warehouseId, warehouseId);
    }
}
