package ph.coop.core.accounting;

import static ph.coop.core.web.ApiErrors.bad;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.connection.DatabaseChanges;
import ph.coop.core.db.Db;

/** Settings > Products > Product roles: which of the cooperative's own account types the system posts to for each role ({@link ProductRoles}). Administrators. */
@RestController
@RequestMapping("/api/admin/product-roles")
@PreAuthorize("hasRole('ADMIN')")
class ProductRolesController {

    /** A type that can take the role: its code and name, and whether it is switched off. */
    record Choice(int code, String name, boolean disabled) {}

    /** {@code vouchersPayable}: the type chosen, null when none; {@code choices}: the fund and payable types of this database. */
    record Roles(Integer vouchersPayable, List<Choice> choices) {}

    record RolesIn(Integer vouchersPayable, String reason) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final ProductRoles roles;

    ProductRolesController(JdbcTemplate jdbc, Db db, ProductRoles roles) {
        this.jdbc = jdbc;
        this.db = db;
        this.roles = roles;
    }

    private int branch(Authentication auth) {
        List<Integer> b = jdbc.queryForList("SELECT AccessBR_CODE FROM access WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0 ORDER BY AccessID LIMIT 1", Integer.class, auth.getName());
        return b.isEmpty() || b.get(0) == null ? 1 : b.get(0);
    }

    @GetMapping
    Roles get(Authentication auth) {
        int branch = branch(auth);
        List<Choice> choices = jdbc.query("""
                SELECT SLTypeSLT_CODE, SLTypeM_DESC, COALESCE(SLTypeIsDisabled, 0) FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? ORDER BY SLTypeSLT_CODE
                """, (rs, i) -> new Choice(rs.getInt(1), rs.getString(2) == null ? "Type " + rs.getInt(1) : rs.getString(2).trim(), rs.getInt(3) != 0), branch, ProductRoles.PAYABLE_CLASS);
        return new Roles(roles.vouchersPayableOrNull(branch), choices);
    }

    @PutMapping
    @Transactional
    Roles set(@RequestBody RolesIn in, Authentication auth) {
        if (!db.tableExists("ecis_setting")) throw new ResponseStatusException(HttpStatus.CONFLICT, DatabaseChanges.notSetUp("Product roles"));
        if (in == null || in.reason() == null || in.reason().trim().length() < 3) throw bad("Give the reason for the change (at least 3 characters); it is kept in the audit log. Nothing was saved.");
        if (in.vouchersPayable() == null) throw bad("Choose the account type that holds members' vouchers payable. Nothing was saved.");
        Roles now = get(auth);
        Choice c = now.choices().stream().filter((x) -> x.code() == in.vouchersPayable()).findFirst().orElseThrow(() ->
            bad("Type " + ProductRoles.PAYABLE_CLASS + "/" + in.vouchersPayable() + " is not a fund and payable type of this database. Choose one from the list, or add it in "
                + "Settings > Products > Fund and payable products. Nothing was saved."));
        if (c.disabled()) throw bad(c.name() + " is switched off in Account Types. Switch it on there first, or choose another. Nothing was saved.");
        if (in.vouchersPayable().equals(now.vouchersPayable())) return now;
        db.upsert("ecis_setting", Map.of("setting_key", ProductRoles.VOUCHERS_PAYABLE),
            Map.of("setting_value", String.valueOf(in.vouchersPayable()), "updated_by", auth.getName(), "updated_at", Timestamp.valueOf(LocalDateTime.now())));
        AuditLog.write(jdbc, auth.getName(), "product.role.change", "setting", ProductRoles.VOUCHERS_PAYABLE,
            now.vouchersPayable() == null ? null : String.valueOf(now.vouchersPayable()), in.vouchersPayable() + " " + c.name(), in.reason().trim());
        return get(auth);
    }
}
