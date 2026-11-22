package ph.coop.core.procurement;

import java.sql.Timestamp;
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

/** The suppliers a cooperative buys from (guide phase 12). Purchasers add and edit them; a supplier is switched off, never deleted, because orders point at it. */
@RestController
@RequestMapping("/api/procurement/suppliers")
@PreAuthorize("hasAnyRole('PURCHASER','PURCHASE_APPROVER','ACCOUNTANT','ADMIN')")
class SupplierController {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9-]{2,20}");

    record Supplier(long id, String code, String name, String tin, String address, String phone, String email, String contact, int termsDays, boolean taxable, boolean active) {}

    record SupplierIn(String code, String name, String tin, String address, String phone, String email, String contact, Integer termsDays, Boolean taxable, Boolean active) {}

    private static final String SELECT = "SELECT id, code, name, tin, address, phone, email, contact, terms_days, taxable, active FROM ecis_supplier";

    private final JdbcTemplate jdbc;
    private final Db db;
    private final ProcurementSupport support;

    SupplierController(JdbcTemplate jdbc, Db db, ProcurementSupport support) {
        this.jdbc = jdbc;
        this.db = db;
        this.support = support;
    }

    private static Supplier map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Supplier(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
            rs.getInt(9), rs.getInt(10) == 1, rs.getInt(11) == 1);
    }

    @GetMapping
    List<Supplier> list(@RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean activeOnly) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return jdbc.query(SELECT + " WHERE branch_code = ? AND (? = 0 OR active = 1) AND (CAST(? AS CHAR) IS NULL OR LOWER(name) LIKE ? OR LOWER(code) LIKE ?) ORDER BY name LIMIT 500",
            SupplierController::map, ProcurementSupport.BRANCH, activeOnly ? 1 : 0, like, like, like);
    }

    @GetMapping("/{id}")
    Supplier get(@PathVariable long id) {
        List<Supplier> s = jdbc.query(SELECT + " WHERE branch_code = ? AND id = ?", SupplierController::map, ProcurementSupport.BRANCH, id);
        if (s.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That supplier was not found. It may have been switched off or the code mistyped: choose the supplier from the list.");
        return s.get(0);
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void check(SupplierIn in) {
        if (in.name() == null || in.name().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the supplier's name.");
        if (in.name().length() > 120) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The name is too long: it can have at most 120 characters. Shorten it and try again.");
        if (in.termsDays() != null && (in.termsDays() < 0 || in.termsDays() > 365)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment terms are 0 to 365 days.");
        if (in.email() != null && !in.email().isBlank() && !in.email().contains("@")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That email address does not look right.");
    }

    @PostMapping
    @Transactional
    Supplier create(@RequestBody SupplierIn in, Authentication auth) {
        support.requirePurchaser(auth);
        check(in);
        if (in.code() == null || !CODE.matcher(in.code().trim()).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The supplier code is 2 to 20 letters, digits or dashes.");
        String code = in.code().trim().toUpperCase();
        Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_supplier WHERE branch_code = ? AND code = ?", Integer.class, ProcurementSupport.BRANCH, code);
        if (dup != null && dup > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "There is already a supplier with the code " + code + ".");
        long id = db.insertReturningId("""
                INSERT INTO ecis_supplier (branch_code, code, name, tin, address, phone, email, contact, terms_days, taxable, active, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                """, "id", ProcurementSupport.BRANCH, code, in.name().trim(), clean(in.tin()), clean(in.address()), clean(in.phone()), clean(in.email()), clean(in.contact()),
            in.termsDays() == null ? 0 : in.termsDays(), in.taxable() == null || in.taxable() ? 1 : 0, auth.getName(), Timestamp.valueOf(support.now()));
        support.audit(auth, "procurement.supplier.create", "ecis_supplier", String.valueOf(id), code + " " + in.name().trim());
        return get(id);
    }

    /** Changes everything but the code; switching a supplier off keeps its orders. */
    @PutMapping("/{id}")
    @Transactional
    Supplier update(@PathVariable long id, @RequestBody SupplierIn in, Authentication auth) {
        support.requirePurchaser(auth);
        Supplier before = get(id);
        check(in);
        jdbc.update("""
                UPDATE ecis_supplier SET name = ?, tin = ?, address = ?, phone = ?, email = ?, contact = ?, terms_days = ?, taxable = ?, active = ?
                WHERE branch_code = ? AND id = ?
                """, in.name().trim(), clean(in.tin()), clean(in.address()), clean(in.phone()), clean(in.email()), clean(in.contact()),
            in.termsDays() == null ? before.termsDays() : in.termsDays(), (in.taxable() == null ? before.taxable() : in.taxable()) ? 1 : 0, (in.active() == null ? before.active() : in.active()) ? 1 : 0,
            ProcurementSupport.BRANCH, id);
        Supplier after = get(id);
        support.audit(auth, "procurement.supplier.update", "ecis_supplier", String.valueOf(id), before + " -> " + after);
        return after;
    }
}
