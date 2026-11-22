package ph.coop.core.pos;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
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
import ph.coop.core.inventory.InventorySupport;

/**
 * What the point of sale sells and at what price (guide phase 14): the sellable products with their current price and promotion, the price list (insert-only history, a new
 * price never changes an old sale), discounts and promotions. A POS supervisor keeps prices, discounts and promotions; cashiers only read them.
 */
@RestController
@RequestMapping("/api/pos")
@PreAuthorize("hasAnyRole('POS_CASHIER','POS_SUPERVISOR','STOREKEEPER','ACCOUNTANT','ADMIN')")
class PosCatalogController {

    record Sellable(long id, String sku, String name, String unit, BigDecimal price, BigDecimal onHand, BigDecimal promoPercent, String promoName, boolean trackBatch) {}

    record PriceIn(long productId, BigDecimal price) {}

    record PriceRow(BigDecimal price, String setBy, java.time.LocalDateTime setAt) {}

    record Discount(long id, String code, String name, String kind, BigDecimal value, boolean requiresSupervisor, boolean active) {}

    record DiscountIn(String code, String name, String kind, BigDecimal value, Boolean requiresSupervisor, Boolean active) {}

    record Promotion(long id, String name, long productId, String product, BigDecimal percentOff, LocalDate startDate, LocalDate endDate, boolean active) {}

    record PromotionIn(String name, Long productId, BigDecimal percentOff, LocalDate startDate, LocalDate endDate, Boolean active) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final InventorySupport support;
    private final PosSupport pos;

    PosCatalogController(JdbcTemplate jdbc, Db db, InventorySupport support, PosSupport pos) {
        this.jdbc = jdbc;
        this.db = db;
        this.support = support;
        this.pos = pos;
    }

    /** The products that can be sold now, with the stock in a warehouse; one that has no price yet is listed with a null price and cannot be sold. */
    @GetMapping("/products")
    List<Sellable> products(@RequestParam long warehouseId, @RequestParam(required = false) String q) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        LocalDate today = support.today();
        return jdbc.query("""
                SELECT p.id, p.sku, p.name, p.unit, p.track_batch,
                  (SELECT x.price FROM ecis_product_price x WHERE x.product_id = p.id ORDER BY x.id DESC LIMIT 1),
                  (SELECT COALESCE(SUM(b.quantity), 0) FROM ecis_inventory_balance b WHERE b.product_id = p.id AND b.warehouse_id = ?),
                  (SELECT m.percent_off FROM ecis_promotion m WHERE m.product_id = p.id AND m.active = 1 AND m.start_date <= ? AND m.end_date >= ? ORDER BY m.percent_off DESC LIMIT 1),
                  (SELECT m.name FROM ecis_promotion m WHERE m.product_id = p.id AND m.active = 1 AND m.start_date <= ? AND m.end_date >= ? ORDER BY m.percent_off DESC LIMIT 1)
                FROM ecis_product p WHERE p.branch_code = ? AND p.active = 1 AND (CAST(? AS CHAR) IS NULL OR LOWER(p.name) LIKE ? OR LOWER(p.sku) LIKE ?) ORDER BY p.name LIMIT 300
                """, (rs, i) -> new Sellable(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getString(9), rs.getInt(5) == 1),
            warehouseId, today, today, today, today, InventorySupport.BRANCH, like, like, like);
    }

    // ---- prices ---------------------------------------------------------------------------------------------------

    @PostMapping("/prices")
    @Transactional
    PriceRow setPrice(@RequestBody PriceIn in, Authentication auth) {
        pos.requireSupervisor(auth);
        if (in.price() == null || in.price().signum() < 0 || in.price().compareTo(new BigDecimal("999999999.99")) > 0 || in.price().scale() > 2 && in.price().stripTrailingZeros().scale() > 2)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The price must be zero or more, with at most two decimals.");
        Integer p = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_product WHERE branch_code = ? AND id = ?", Integer.class, InventorySupport.BRANCH, in.productId());
        if (p == null || p == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That product was not found; it may have been switched off or the code mistyped. Choose the product from the list.");
        BigDecimal before = jdbc.queryForList("SELECT price FROM ecis_product_price WHERE product_id = ? ORDER BY id DESC LIMIT 1", BigDecimal.class, in.productId()).stream().findFirst().orElse(null);
        jdbc.update("INSERT INTO ecis_product_price (product_id, price, set_by, set_at) VALUES (?, ?, ?, ?)", in.productId(), in.price().setScale(2), auth.getName(), Timestamp.valueOf(support.now()));
        support.audit(auth, "pos.price.set", "ecis_product_price", String.valueOf(in.productId()), (before == null ? "none" : before.toPlainString()) + " -> " + in.price().setScale(2).toPlainString());
        return new PriceRow(in.price().setScale(2), auth.getName(), support.now());
    }

    @GetMapping("/prices/{productId}")
    List<PriceRow> prices(@PathVariable long productId) {
        return jdbc.query("SELECT price, set_by, set_at FROM ecis_product_price WHERE product_id = ? ORDER BY id DESC LIMIT 50",
            (rs, i) -> new PriceRow(rs.getBigDecimal(1), rs.getString(2), rs.getTimestamp(3).toLocalDateTime()), productId);
    }

    // ---- discounts ------------------------------------------------------------------------------------------------

    private static Discount discount(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Discount(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getInt(6) == 1, rs.getInt(7) == 1);
    }

    @GetMapping("/discounts")
    List<Discount> discounts() {
        return jdbc.query("SELECT id, code, name, kind, value, requires_supervisor, active FROM ecis_discount WHERE branch_code = ? ORDER BY name", PosCatalogController::discount, InventorySupport.BRANCH);
    }

    private static void checkDiscount(String kind, BigDecimal value) {
        if (kind == null || !(kind.equals("PERCENT") || kind.equals("AMOUNT"))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The discount is a PERCENT or an AMOUNT.");
        if (value == null || value.signum() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The discount value must be more than zero.");
        if (kind.equals("PERCENT") && value.compareTo(new BigDecimal("100")) > 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A percent discount cannot be more than 100.");
    }

    @PostMapping("/discounts")
    @Transactional
    Discount addDiscount(@RequestBody DiscountIn in, Authentication auth) {
        pos.requireSupervisor(auth);
        if (in.code() == null || !in.code().trim().matches("[A-Za-z0-9._-]{1,20}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The discount code is 1 to 20 letters, digits, dots or dashes.");
        if (in.name() == null || in.name().isBlank() || in.name().length() > 60) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the discount's name (60 characters at most).");
        checkDiscount(in.kind(), in.value());
        String code = in.code().trim().toUpperCase();
        Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_discount WHERE branch_code = ? AND code = ?", Integer.class, InventorySupport.BRANCH, code);
        if (dup != null && dup > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "There is already a discount " + code + ".");
        long id = db.insertReturningId("INSERT INTO ecis_discount (branch_code, code, name, kind, value, requires_supervisor, active) VALUES (?, ?, ?, ?, ?, ?, 1)", "id",
            InventorySupport.BRANCH, code, in.name().trim(), in.kind(), in.value(), Boolean.TRUE.equals(in.requiresSupervisor()) ? 1 : 0);
        support.audit(auth, "pos.discount.create", "ecis_discount", code, in.kind() + " " + in.value().toPlainString());
        return jdbc.query("SELECT id, code, name, kind, value, requires_supervisor, active FROM ecis_discount WHERE id = ?", PosCatalogController::discount, id).get(0);
    }

    /** Changes a discount's name, value, supervisor flag or on/off. Past sales keep what they were given. */
    @PutMapping("/discounts/{id}")
    @Transactional
    Discount updateDiscount(@PathVariable long id, @RequestBody DiscountIn in, Authentication auth) {
        pos.requireSupervisor(auth);
        List<Discount> found = jdbc.query("SELECT id, code, name, kind, value, requires_supervisor, active FROM ecis_discount WHERE branch_code = ? AND id = ?", PosCatalogController::discount, InventorySupport.BRANCH, id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such discount.");
        Discount d = found.get(0);
        BigDecimal value = in.value() == null ? d.value() : in.value();
        checkDiscount(d.kind(), value);
        String name = in.name() == null || in.name().isBlank() ? d.name() : in.name().trim();
        jdbc.update("UPDATE ecis_discount SET name = ?, value = ?, requires_supervisor = ?, active = ? WHERE id = ?", name, value,
            (in.requiresSupervisor() == null ? d.requiresSupervisor() : in.requiresSupervisor()) ? 1 : 0, (in.active() == null ? d.active() : in.active()) ? 1 : 0, id);
        support.audit(auth, "pos.discount.update", "ecis_discount", d.code(), d.value().toPlainString() + " -> " + value.toPlainString());
        return jdbc.query("SELECT id, code, name, kind, value, requires_supervisor, active FROM ecis_discount WHERE id = ?", PosCatalogController::discount, id).get(0);
    }

    // ---- promotions -----------------------------------------------------------------------------------------------

    private static final String PROMO = "SELECT m.id, m.name, m.product_id, p.name, m.percent_off, m.start_date, m.end_date, m.active FROM ecis_promotion m JOIN ecis_product p ON p.id = m.product_id";

    private static Promotion promo(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Promotion(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getBigDecimal(5), rs.getDate(6).toLocalDate(), rs.getDate(7).toLocalDate(), rs.getInt(8) == 1);
    }

    @GetMapping("/promotions")
    List<Promotion> promotions() {
        return jdbc.query(PROMO + " WHERE m.branch_code = ? ORDER BY m.end_date DESC, m.id DESC LIMIT 200", PosCatalogController::promo, InventorySupport.BRANCH);
    }

    @PostMapping("/promotions")
    @Transactional
    Promotion addPromotion(@RequestBody PromotionIn in, Authentication auth) {
        pos.requireSupervisor(auth);
        if (in.name() == null || in.name().isBlank() || in.name().length() > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the promotion's name (80 characters at most).");
        if (in.productId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the product.");
        if (in.percentOff() == null || in.percentOff().signum() <= 0 || in.percentOff().compareTo(new BigDecimal("100")) > 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The percent off is more than 0 and at most 100.");
        if (in.startDate() == null || in.endDate() == null || in.endDate().isBefore(in.startDate())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the start and end dates, the end not before the start.");
        Integer p = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_product WHERE branch_code = ? AND id = ?", Integer.class, InventorySupport.BRANCH, in.productId());
        if (p == null || p == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That product was not found; it may have been switched off or the code mistyped. Choose the product from the list.");
        long id = db.insertReturningId("INSERT INTO ecis_promotion (branch_code, name, product_id, percent_off, start_date, end_date, active, created_by) VALUES (?, ?, ?, ?, ?, ?, 1, ?)", "id",
            InventorySupport.BRANCH, in.name().trim(), in.productId(), in.percentOff(), in.startDate(), in.endDate(), auth.getName());
        support.audit(auth, "pos.promotion.create", "ecis_promotion", String.valueOf(id), in.name().trim() + " " + in.percentOff().toPlainString() + "% " + in.startDate() + ".." + in.endDate());
        return jdbc.query(PROMO + " WHERE m.id = ?", PosCatalogController::promo, id).get(0);
    }

    /** Switches a promotion on or off; its dates and percent are not edited, make a new one instead. */
    @PutMapping("/promotions/{id}")
    @Transactional
    Promotion setPromotion(@PathVariable long id, @RequestBody PromotionIn in, Authentication auth) {
        pos.requireSupervisor(auth);
        List<Promotion> found = jdbc.query(PROMO + " WHERE m.branch_code = ? AND m.id = ?", PosCatalogController::promo, InventorySupport.BRANCH, id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such promotion.");
        if (in.active() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say whether the promotion is on or off.");
        jdbc.update("UPDATE ecis_promotion SET active = ? WHERE id = ?", in.active() ? 1 : 0, id);
        support.audit(auth, "pos.promotion." + (in.active() ? "on" : "off"), "ecis_promotion", String.valueOf(id), found.get(0).name());
        return jdbc.query(PROMO + " WHERE m.id = ?", PosCatalogController::promo, id).get(0);
    }
}
