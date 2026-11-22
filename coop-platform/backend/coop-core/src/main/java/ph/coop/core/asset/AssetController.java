package ph.coop.core.asset;

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
 * The asset register (guide phase 15): what the cooperative owns, what it cost, where it is, its depreciation and its upkeep. A custodian or accountant registers an asset;
 * its location changes only through a transfer that a different accountant approves; maintenance is recorded as it happens. Depreciation and disposal have their own screens.
 */
@RestController
@RequestMapping("/api/assets")
@PreAuthorize("hasAnyRole('ASSET_CUSTODIAN','ACCOUNTANT','ADMIN')")
class AssetController {

    record NewAsset(String tag, String name, Long categoryId, String serialNo, String location, LocalDate acquisitionDate, LocalDate inServiceDate, BigDecimal cost, BigDecimal salvageValue,
                    Integer lifeMonths, Long supplierId, String reference) {}

    record EditAsset(String name, String serialNo, String reference) {}

    record Summary(long id, String assetNo, String tag, String name, long categoryId, String category, String location, LocalDate acquisitionDate, LocalDate inServiceDate, BigDecimal cost,
                   BigDecimal salvageValue, int lifeMonths, String status, LocalDate disposedOn, BigDecimal booked, BigDecimal posted, BigDecimal netBookValue) {}

    record DepRow(LocalDate periodEnd, BigDecimal amount, String journal, String journalStatus) {}

    record TransferRow(long id, String transferNo, String from, String to, String reason, String status, String requestedBy, String decidedBy, String decisionNote) {}

    record MaintRow(long id, LocalDate date, String description, BigDecimal cost, String vendor, LocalDate nextDue, String recordedBy) {}

    record Detail(Summary summary, String serialNo, String reference, Long supplierId, List<DepRow> depreciation, List<TransferRow> transfers, List<MaintRow> maintenance) {}

    record TransferIn(String toLocation, String reason) {}

    record Decision(String note) {}

    record MaintIn(LocalDate date, String description, BigDecimal cost, String vendor, LocalDate nextDue) {}

    record DueRow(long assetId, String assetNo, String name, String location, LocalDate nextDue, String lastWork) {}

    private static final String SUMMARY = """
            SELECT a.id, a.asset_no, a.tag, a.name, a.category_id, c.name, a.location, a.acquisition_date, a.in_service_date, a.cost, a.salvage_value, a.life_months, a.status, a.disposed_on,
              (SELECT COALESCE(SUM(d.amount), 0) FROM ecis_asset_depreciation d WHERE d.asset_id = a.id),
              (SELECT COALESCE(SUM(d.amount), 0) FROM ecis_asset_depreciation d JOIN ecis_journal j ON j.id = d.journal_id WHERE d.asset_id = a.id AND j.status = 'POSTED')
            FROM ecis_asset a JOIN ecis_asset_category c ON c.id = a.category_id
            """;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final InventorySupport support;
    private final AssetSupport assets;

    AssetController(JdbcTemplate jdbc, Db db, InventorySupport support, AssetSupport assets) {
        this.jdbc = jdbc;
        this.db = db;
        this.support = support;
        this.assets = assets;
    }

    private static Summary map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        java.sql.Date disposed = rs.getDate(14);
        BigDecimal cost = rs.getBigDecimal(10), booked = rs.getBigDecimal(15);
        return new Summary(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7), rs.getDate(8).toLocalDate(), rs.getDate(9).toLocalDate(), cost,
            rs.getBigDecimal(11), rs.getInt(12), rs.getString(13), disposed == null ? null : disposed.toLocalDate(), booked, rs.getBigDecimal(16), cost.subtract(booked));
    }

    @GetMapping
    List<Summary> list(@RequestParam(required = false) String q, @RequestParam(required = false) String status, @RequestParam(required = false) Long categoryId) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return jdbc.query(SUMMARY + " WHERE a.branch_code = ? AND (CAST(? AS CHAR) IS NULL OR a.status = ?) AND (CAST(? AS CHAR) IS NULL OR a.category_id = ?) AND (CAST(? AS CHAR) IS NULL OR LOWER(a.name) LIKE ? OR LOWER(a.tag) LIKE ? OR LOWER(a.asset_no) LIKE ?) ORDER BY a.asset_no DESC LIMIT 500",
            AssetController::map, InventorySupport.BRANCH, status, status, categoryId, categoryId, like, like, like, like);
    }

    @GetMapping("/{id}")
    Detail get(@PathVariable long id) {
        List<Summary> s = jdbc.query(SUMMARY + " WHERE a.branch_code = ? AND a.id = ?", AssetController::map, InventorySupport.BRANCH, id);
        if (s.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That asset was not found in the register. It may have been disposed of or the number mistyped: reload the list and open it from there.");
        List<Object[]> extra = jdbc.query("SELECT serial_no, reference, supplier_id FROM ecis_asset WHERE id = ?", (rs, i) -> new Object[] {rs.getString(1), rs.getString(2), rs.getObject(3) == null ? null : rs.getLong(3)}, id);
        List<DepRow> dep = jdbc.query("""
                SELECT d.period_end, d.amount, j.reference, j.status FROM ecis_asset_depreciation d LEFT JOIN ecis_journal j ON j.id = d.journal_id WHERE d.asset_id = ? ORDER BY d.period_end
                """, (rs, i) -> new DepRow(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2), rs.getString(3), rs.getString(4)), id);
        List<TransferRow> tr = jdbc.query("SELECT id, transfer_no, from_location, to_location, reason, status, requested_by, decided_by, decision_note FROM ecis_asset_transfer WHERE asset_id = ? ORDER BY id DESC",
            (rs, i) -> new TransferRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)), id);
        List<MaintRow> mt = jdbc.query("SELECT id, maint_date, description, cost, vendor, next_due, recorded_by FROM ecis_asset_maintenance WHERE asset_id = ? ORDER BY maint_date DESC, id DESC",
            (rs, i) -> new MaintRow(rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getString(3), rs.getBigDecimal(4), rs.getString(5), rs.getDate(6) == null ? null : rs.getDate(6).toLocalDate(), rs.getString(7)), id);
        return new Detail(s.get(0), (String) extra.get(0)[0], (String) extra.get(0)[1], (Long) extra.get(0)[2], dep, tr, mt);
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @PostMapping
    @Transactional
    Summary register(@RequestBody NewAsset in, Authentication auth) {
        assets.requireCustodian(auth);
        if (in.tag() == null || !in.tag().trim().matches("[A-Za-z0-9._/-]{1,30}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The tag is 1 to 30 letters, digits, dots, slashes or dashes.");
        if (in.name() == null || in.name().isBlank() || in.name().length() > 120) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the asset's name (120 characters at most).");
        if (in.location() == null || in.location().isBlank() || in.location().length() > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say where the asset is (80 characters at most).");
        if (in.categoryId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the category.");
        List<Object[]> cat = jdbc.query("SELECT life_months, salvage_percent, active FROM ecis_asset_category WHERE branch_code = ? AND id = ?", (rs, i) -> new Object[] {rs.getInt(1), rs.getBigDecimal(2), rs.getInt(3) == 1}, InventorySupport.BRANCH, in.categoryId());
        if (cat.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such category.");
        if (!(Boolean) cat.get(0)[2]) throw new ResponseStatusException(HttpStatus.CONFLICT, "That category is switched off.");
        if (in.supplierId() != null && jdbc.queryForObject("SELECT COUNT(*) FROM ecis_supplier WHERE branch_code = ? AND id = ?", Integer.class, InventorySupport.BRANCH, in.supplierId()) == 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That supplier was not found. It may have been switched off or the code mistyped: choose the supplier from the list.");
        int life = in.lifeMonths() == null ? (Integer) cat.get(0)[0] : in.lifeMonths();
        BigDecimal salvage = in.salvageValue() != null ? in.salvageValue()
            : in.cost() == null ? BigDecimal.ZERO : in.cost().multiply((BigDecimal) cat.get(0)[1]).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
        LocalDate inService = in.inServiceDate() == null ? in.acquisitionDate() : in.inServiceDate();
        List<String> problems = AssetRules.assetProblems(in.cost(), salvage, life, in.acquisitionDate(), inService, support.today());
        if (!problems.isEmpty()) throw InventorySupport.bad(problems);
        String tag = in.tag().trim().toUpperCase();
        Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_asset WHERE branch_code = ? AND tag = ?", Integer.class, InventorySupport.BRANCH, tag);
        if (dup != null && dup > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "There is already an asset with the tag " + tag + ".");
        String no = support.nextNumber("ecis_asset", "asset_no", "FA", support.today().getYear());
        long id = db.insertReturningId("""
                INSERT INTO ecis_asset (branch_code, asset_no, tag, name, category_id, serial_no, location, acquisition_date, in_service_date, cost, salvage_value, life_months, supplier_id, reference, status, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, "id", InventorySupport.BRANCH, no, tag, in.name().trim(), in.categoryId(), clean(in.serialNo()), in.location().trim(), in.acquisitionDate(), inService, in.cost(), salvage, life,
            in.supplierId(), clean(in.reference()), auth.getName(), Timestamp.valueOf(support.now()));
        support.audit(auth, "asset.register", "ecis_asset", no, tag + " " + in.name().trim() + ", cost " + in.cost().toPlainString() + ", " + life + " months");
        return get(id).summary();
    }

    /** Changes the name, serial number or reference; cost, dates and life never change after registration (a wrong one is disposed of and registered again). */
    @PutMapping("/{id}")
    @Transactional
    Summary edit(@PathVariable long id, @RequestBody EditAsset in, Authentication auth) {
        assets.requireCustodian(auth);
        Summary before = get(id).summary();
        String name = in.name() == null || in.name().isBlank() ? before.name() : in.name().trim();
        if (name.length() > 120) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The name is too long: it can have at most 120 characters. Shorten it and try again.");
        jdbc.update("UPDATE ecis_asset SET name = ?, serial_no = COALESCE(?, serial_no), reference = COALESCE(?, reference) WHERE id = ?", name, clean(in.serialNo()), clean(in.reference()), id);
        support.audit(auth, "asset.update", "ecis_asset", before.assetNo(), before.name() + " -> " + name);
        return get(id).summary();
    }

    // ---- transfers ------------------------------------------------------------------------------------------------

    @PostMapping("/{id}/transfers")
    @Transactional
    TransferRow askTransfer(@PathVariable long id, @RequestBody TransferIn in, Authentication auth) {
        assets.requireCustodian(auth);
        List<Object[]> a = jdbc.query("SELECT status, location FROM ecis_asset WHERE branch_code = ? AND id = ? FOR UPDATE", (rs, i) -> new Object[] {rs.getString(1), rs.getString(2)}, InventorySupport.BRANCH, id);
        if (a.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That asset was not found in the register. It may have been disposed of or the number mistyped: reload the list and open it from there.");
        if (!a.get(0)[0].equals("ACTIVE")) throw new ResponseStatusException(HttpStatus.CONFLICT, "This asset has been disposed of.");
        if (in.toLocation() == null || in.toLocation().isBlank() || in.toLocation().length() > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say where the asset is going (80 characters at most).");
        if (in.toLocation().trim().equalsIgnoreCase((String) a.get(0)[1])) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The asset is already there.");
        if (in.reason() == null || in.reason().isBlank() || in.reason().length() > 255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the reason (255 characters at most).");
        Integer waiting = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_asset_transfer WHERE asset_id = ? AND status = 'PENDING'", Integer.class, id);
        if (waiting != null && waiting > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "A transfer of this asset is already waiting to be decided.");
        String no = support.nextNumber("ecis_asset_transfer", "transfer_no", "AT", support.today().getYear());
        long tid = db.insertReturningId("INSERT INTO ecis_asset_transfer (branch_code, transfer_no, asset_id, from_location, to_location, reason, status, requested_by, requested_at) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)", "id",
            InventorySupport.BRANCH, no, id, a.get(0)[1], in.toLocation().trim(), in.reason().trim(), auth.getName(), Timestamp.valueOf(support.now()));
        support.audit(auth, "asset.transfer.create", "ecis_asset_transfer", no, a.get(0)[1] + " -> " + in.toLocation().trim());
        return transfer(tid);
    }

    private TransferRow transfer(long tid) {
        return jdbc.query("SELECT id, transfer_no, from_location, to_location, reason, status, requested_by, decided_by, decision_note FROM ecis_asset_transfer WHERE id = ?",
            (rs, i) -> new TransferRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)), tid).get(0);
    }

    private TransferRow decideTransfer(long tid, boolean approve, Decision in, Authentication auth) {
        assets.requireAccountant(auth);
        List<Object[]> t = jdbc.query("SELECT asset_id, status, requested_by, to_location, transfer_no FROM ecis_asset_transfer WHERE branch_code = ? AND id = ? FOR UPDATE",
            (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)}, InventorySupport.BRANCH, tid);
        if (t.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such transfer.");
        if (!t.get(0)[1].equals("PENDING")) throw new ResponseStatusException(HttpStatus.CONFLICT, "This transfer is already " + ((String) t.get(0)[1]).toLowerCase() + ".");
        String own = InventorySupport.own((String) t.get(0)[2], auth.getName(), "transfer");
        if (own != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, own);
        String note = in == null || in.note() == null || in.note().isBlank() ? null : in.note().trim();
        if (!approve && note == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say why the transfer is rejected.");
        if (note != null && note.length() > 255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The note is too long: it can have at most 255 characters. Shorten it and try again.");
        if (approve) {
            String status = jdbc.queryForObject("SELECT status FROM ecis_asset WHERE id = ? FOR UPDATE", String.class, t.get(0)[0]);
            if (!"ACTIVE".equals(status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "This asset has been disposed of.");
            jdbc.update("UPDATE ecis_asset SET location = ? WHERE id = ?", t.get(0)[3], t.get(0)[0]);
        }
        jdbc.update("UPDATE ecis_asset_transfer SET status = ?, decided_by = ?, decided_at = ?, decision_note = ? WHERE id = ? AND status = 'PENDING'", approve ? "APPROVED" : "REJECTED", auth.getName(), Timestamp.valueOf(support.now()), note, tid);
        support.audit(auth, "asset.transfer." + (approve ? "approve" : "reject"), "ecis_asset_transfer", (String) t.get(0)[4], note == null ? (String) t.get(0)[3] : note);
        return transfer(tid);
    }

    @PostMapping("/transfers/{tid}/approve")
    @Transactional
    TransferRow approveTransfer(@PathVariable long tid, @RequestBody(required = false) Decision in, Authentication auth) {
        return decideTransfer(tid, true, in, auth);
    }

    @PostMapping("/transfers/{tid}/reject")
    @Transactional
    TransferRow rejectTransfer(@PathVariable long tid, @RequestBody Decision in, Authentication auth) {
        return decideTransfer(tid, false, in, auth);
    }

    @GetMapping("/transfers")
    List<TransferRow> transfers(@RequestParam(required = false) String status) {
        return jdbc.query("SELECT id, transfer_no, from_location, to_location, reason, status, requested_by, decided_by, decision_note FROM ecis_asset_transfer WHERE branch_code = ? AND (CAST(? AS CHAR) IS NULL OR status = ?) ORDER BY id DESC LIMIT 300",
            (rs, i) -> new TransferRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)), InventorySupport.BRANCH, status, status);
    }

    // ---- maintenance ----------------------------------------------------------------------------------------------

    @PostMapping("/{id}/maintenance")
    @Transactional
    MaintRow maintain(@PathVariable long id, @RequestBody MaintIn in, Authentication auth) {
        assets.requireCustodian(auth);
        Summary a = get(id).summary();
        if (in.description() == null || in.description().isBlank() || in.description().length() > 255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say what was done (255 characters at most).");
        LocalDate date = in.date() == null ? support.today() : in.date();
        if (date.isAfter(support.today())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The date of the work cannot be in the future.");
        if (date.isBefore(a.acquisitionDate())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The work cannot be dated before the asset was acquired.");
        BigDecimal cost = in.cost() == null ? BigDecimal.ZERO : in.cost();
        if (cost.signum() < 0 || cost.stripTrailingZeros().scale() > 2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The cost must be zero or more, with at most two decimals.");
        if (in.nextDue() != null && !in.nextDue().isAfter(date)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The next due date must be after the work.");
        long mid = db.insertReturningId("INSERT INTO ecis_asset_maintenance (asset_id, maint_date, description, cost, vendor, next_due, recorded_by, recorded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", "id",
            id, date, in.description().trim(), cost, clean(in.vendor()), in.nextDue(), auth.getName(), Timestamp.valueOf(support.now()));
        support.audit(auth, "asset.maintenance", "ecis_asset", a.assetNo(), in.description().trim() + (cost.signum() > 0 ? ", " + cost.toPlainString() : ""));
        return new MaintRow(mid, date, in.description().trim(), cost, clean(in.vendor()), in.nextDue(), auth.getName());
    }

    /** Assets whose latest maintenance record says more work is due within {@code days} days (or is overdue). */
    @GetMapping("/maintenance/due")
    List<DueRow> due(@RequestParam(defaultValue = "30") int days) {
        LocalDate until = support.today().plusDays(Math.max(0, Math.min(days, 365)));
        return jdbc.query("""
                SELECT a.id, a.asset_no, a.name, a.location, m.next_due, m.description FROM ecis_asset_maintenance m JOIN ecis_asset a ON a.id = m.asset_id
                WHERE a.branch_code = ? AND a.status = 'ACTIVE' AND m.next_due IS NOT NULL AND m.next_due <= ?
                  AND NOT EXISTS (SELECT 1 FROM ecis_asset_maintenance m2 WHERE m2.asset_id = m.asset_id AND m2.id > m.id)
                ORDER BY m.next_due
                """, (rs, i) -> new DueRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getDate(5).toLocalDate(), rs.getString(6)), InventorySupport.BRANCH, until);
    }
}
