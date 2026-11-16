package ph.coop.core.asset;

import java.math.BigDecimal;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;
import ph.coop.core.inventory.InventorySupport;

/**
 * Asset categories (guide phase 15): a kind of asset with the three ledger accounts it uses (cost, accumulated depreciation, depreciation expense) and its default useful
 * life and salvage percent. Accountants keep them; the accounts must be enabled detail accounts of the chart. A category is switched off, never deleted.
 */
@RestController
@RequestMapping("/api/assets/categories")
@PreAuthorize("hasAnyRole('ASSET_CUSTODIAN','ACCOUNTANT','ADMIN')")
class AssetCategoryController {

    record Category(long id, String name, String assetAccount, String accumAccount, String expenseAccount, int lifeMonths, BigDecimal salvagePercent, boolean active) {}

    record CategoryIn(String name, String assetAccount, String accumAccount, String expenseAccount, Integer lifeMonths, BigDecimal salvagePercent, Boolean active) {}

    private static final String SELECT = "SELECT id, name, asset_account, accum_account, expense_account, life_months, salvage_percent, active FROM ecis_asset_category";

    private final JdbcTemplate jdbc;
    private final Db db;
    private final InventorySupport support;
    private final AssetSupport assets;

    AssetCategoryController(JdbcTemplate jdbc, Db db, InventorySupport support, AssetSupport assets) {
        this.jdbc = jdbc;
        this.db = db;
        this.support = support;
        this.assets = assets;
    }

    private static Category map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Category(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getBigDecimal(7), rs.getInt(8) == 1);
    }

    @GetMapping
    List<Category> list() {
        return jdbc.query(SELECT + " WHERE branch_code = ? ORDER BY name", AssetCategoryController::map, InventorySupport.BRANCH);
    }

    @GetMapping("/{id}")
    Category get(@PathVariable long id) {
        List<Category> c = jdbc.query(SELECT + " WHERE branch_code = ? AND id = ?", AssetCategoryController::map, InventorySupport.BRANCH, id);
        if (c.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such asset category.");
        return c.get(0);
    }

    private void check(CategoryIn in, String asset, String accum, String expense, int life, BigDecimal salvage) {
        if (in.name() == null || in.name().isBlank() || in.name().length() > 60) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the category's name (60 characters at most).");
        assets.requireUsableAccount(InventorySupport.BRANCH, asset, "Asset account");
        assets.requireUsableAccount(InventorySupport.BRANCH, accum, "Accumulated depreciation account");
        assets.requireUsableAccount(InventorySupport.BRANCH, expense, "Depreciation expense account");
        if (asset.equals(accum) || asset.equals(expense) || accum.equals(expense)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The asset, accumulated depreciation and expense accounts must be three different accounts.");
        if (life < 1 || life > 600) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The useful life is 1 to 600 months.");
        if (salvage.signum() < 0 || salvage.compareTo(new BigDecimal("100")) > 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The salvage percent is 0 to 100.");
    }

    @PostMapping
    @Transactional
    Category create(@RequestBody CategoryIn in, Authentication auth) {
        assets.requireAccountant(auth);
        String asset = in.assetAccount() == null ? "" : in.assetAccount().trim(), accum = in.accumAccount() == null ? "" : in.accumAccount().trim(), expense = in.expenseAccount() == null ? "" : in.expenseAccount().trim();
        int life = in.lifeMonths() == null ? 0 : in.lifeMonths();
        BigDecimal salvage = in.salvagePercent() == null ? BigDecimal.ZERO : in.salvagePercent();
        check(in, asset, accum, expense, life, salvage);
        Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_asset_category WHERE branch_code = ? AND name = ?", Integer.class, InventorySupport.BRANCH, in.name().trim());
        if (dup != null && dup > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "There is already a category " + in.name().trim() + ".");
        long id = db.insertReturningId("INSERT INTO ecis_asset_category (branch_code, name, asset_account, accum_account, expense_account, life_months, salvage_percent, active) VALUES (?, ?, ?, ?, ?, ?, ?, 1)", "id",
            InventorySupport.BRANCH, in.name().trim(), asset, accum, expense, life, salvage);
        support.audit(auth, "asset.category.create", "ecis_asset_category", in.name().trim(), asset + " / " + accum + " / " + expense + ", " + life + " months");
        return get(id);
    }

    /** Changes a category. Assets already registered keep the figures they were given; the accounts apply from the next depreciation run. */
    @PutMapping("/{id}")
    @Transactional
    Category update(@PathVariable long id, @RequestBody CategoryIn in, Authentication auth) {
        assets.requireAccountant(auth);
        Category before = get(id);
        CategoryIn m = new CategoryIn(in.name() == null || in.name().isBlank() ? before.name() : in.name(), in.assetAccount() == null ? before.assetAccount() : in.assetAccount().trim(),
            in.accumAccount() == null ? before.accumAccount() : in.accumAccount().trim(), in.expenseAccount() == null ? before.expenseAccount() : in.expenseAccount().trim(),
            in.lifeMonths() == null ? before.lifeMonths() : in.lifeMonths(), in.salvagePercent() == null ? before.salvagePercent() : in.salvagePercent(), in.active() == null ? before.active() : in.active());
        check(m, m.assetAccount(), m.accumAccount(), m.expenseAccount(), m.lifeMonths(), m.salvagePercent());
        jdbc.update("UPDATE ecis_asset_category SET name = ?, asset_account = ?, accum_account = ?, expense_account = ?, life_months = ?, salvage_percent = ?, active = ? WHERE id = ?",
            m.name().trim(), m.assetAccount(), m.accumAccount(), m.expenseAccount(), m.lifeMonths(), m.salvagePercent(), m.active() ? 1 : 0, id);
        support.audit(auth, "asset.category.update", "ecis_asset_category", before.name(), before + " -> " + get(id));
        return get(id);
    }
}
