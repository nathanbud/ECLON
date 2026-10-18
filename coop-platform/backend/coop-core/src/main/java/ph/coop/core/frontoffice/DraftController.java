package ph.coop.core.frontoffice;

import ph.coop.core.db.Db;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.frontoffice.FrontOfficeAccess.Perms;

/**
 * Front office drafts: a transaction being prepared. A draft is checked and kept in {@code ecis_fo_draft}, and belongs to
 * the person who made it. It is never posted: nothing here writes to the transaction tables or the ledger.
 */
@RestController
@RequestMapping("/api/front-office/drafts")
class DraftController {

    record Draft(long id, DraftRules.Payload payload, DraftRules.Totals totals, String updatedAt) {}

    record DraftRow(long id, int trCode, String type, String date, String clientName, BigDecimal debit, boolean balanced, String updatedAt) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final FrontOfficeAccess access;
    private final ObjectMapper json;

    DraftController(JdbcTemplate jdbc, Db db, FrontOfficeAccess access, ObjectMapper json) {
        this.jdbc = jdbc;
        this.db = db;
        this.access = access;
        this.json = json;
    }

    @PostMapping
    Draft create(@RequestBody DraftRules.Payload body, Authentication auth) {
        Perms p = access.of(auth);
        access.requireNew(p, body == null ? -1 : body.trCode());
        DraftRules.Payload clean = check(body, p);
        long id = db.insertReturningId("INSERT INTO ecis_fo_draft (branch_code, tr_code, created_by, created_at, updated_at, payload) VALUES (?, ?, ?, NOW(), NOW(), ?)", "id",
            p.branch(), clean.trCode(), auth.getName(), write(clean));
        return load(id, auth.getName());
    }

    @PutMapping("/{id}")
    Draft update(@PathVariable long id, @RequestBody DraftRules.Payload body, Authentication auth) {
        Perms p = access.of(auth);
        load(id, auth.getName()); // only the owner may change it
        access.requireNew(p, body == null ? -1 : body.trCode());
        DraftRules.Payload clean = check(body, p);
        jdbc.update("UPDATE ecis_fo_draft SET tr_code = ?, updated_at = NOW(), payload = ? WHERE id = ? AND created_by = ?", clean.trCode(), write(clean), id, auth.getName());
        return load(id, auth.getName());
    }

    @GetMapping
    List<DraftRow> mine(Authentication auth) {
        Perms p = access.of(auth);
        java.util.Map<Integer, String> names = access.typeNames();
        return jdbc.query("SELECT id, tr_code, payload, updated_at FROM ecis_fo_draft WHERE created_by = ? AND branch_code = ? ORDER BY updated_at DESC LIMIT 200",
            (rs, i) -> {
                DraftRules.Payload pl = read(rs.getString(3));
                DraftRules.Totals t = DraftRules.totals(pl.lines());
                return new DraftRow(rs.getLong(1), rs.getInt(2), names.getOrDefault(rs.getInt(2), "Type " + rs.getInt(2)), pl.date(), pl.clientName(), t.debit(), t.balanced(), rs.getString(4));
            }, auth.getName(), p.branch());
    }

    @GetMapping("/{id}")
    Draft get(@PathVariable long id, Authentication auth) {
        return load(id, auth.getName());
    }

    @DeleteMapping("/{id}")
    void delete(@PathVariable long id, Authentication auth) {
        load(id, auth.getName());
        jdbc.update("DELETE FROM ecis_fo_draft WHERE id = ? AND created_by = ?", id, auth.getName());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private DraftRules.Payload check(DraftRules.Payload body, Perms p) {
        Set<String> accounts = new HashSet<>();
        if (body != null && body.lines() != null) {
            List<String> codes = body.lines().stream().map(DraftRules.Line::accountCode).filter((c) -> c != null && !c.isBlank()).map(String::trim).distinct().toList();
            if (!codes.isEmpty()) {
                jdbc.query("SELECT COAID FROM coa WHERE COABR_CODE = ? AND COALESCE(COAisdisabled, 0) = 0 AND COAID IN (" + String.join(",", codes.stream().map((c) -> "?").toList()) + ")",
                    (rs) -> { accounts.add(rs.getString(1)); },
                    java.util.stream.Stream.concat(java.util.stream.Stream.of((Object) p.branch()), codes.stream()).toArray());
            }
        }
        Set<Integer> denomTypes = new HashSet<>();
        jdbc.query("SELECT ID FROM denomtype", (rs) -> { denomTypes.add(rs.getInt(1)); });
        try {
            return DraftRules.validate(body, FrontOfficeAccess.ALL_TYPES, accounts, denomTypes);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private Draft load(long id, String user) {
        List<Draft> found = jdbc.query("SELECT id, payload, updated_at FROM ecis_fo_draft WHERE id = ? AND created_by = ?",
            (rs, i) -> {
                DraftRules.Payload pl = read(rs.getString(2));
                return new Draft(rs.getLong(1), pl, DraftRules.totals(pl.lines()), rs.getString(3));
            }, id, user);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found.");
        return found.get(0);
    }

    private String write(DraftRules.Payload p) {
        try {
            return json.writeValueAsString(p);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private DraftRules.Payload read(String text) {
        try {
            return json.readValue(text, DraftRules.Payload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
