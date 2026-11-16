package ph.coop.core.member;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A member's beneficiaries, read from the legacy {@code benef} table (one row per person, with the relationship from {@code relation} and the kind, Principal or
 * Secondary, from {@code beneftype}). Read only for now: adding and changing beneficiaries will follow the maker-checker rule of the other profile changes.
 */
@RestController
@RequestMapping("/api/members/{branchCode}/{clientId}/beneficiaries")
class BeneficiaryController {

    record Beneficiary(long id, String name, Integer relationCode, String relation, LocalDate dateOfBirth, String occupation, int typeCode, String type, LocalDate added) {}

    private final JdbcTemplate jdbc;

    BeneficiaryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Principal beneficiaries first, then secondary, each in the order they were recorded. */
    @GetMapping
    List<Beneficiary> list(@PathVariable int branchCode, @PathVariable int clientId) {
        return jdbc.query("""
                SELECT b.BenefID, b.BenefName, b.BenefRelation, r.RelationDesc, b.BenefDateofBirth, b.BenefOccu, b.BenefTypeID, t.DESC1, b.BenefDateTimeAdded
                FROM benef b
                LEFT JOIN relation r ON r.RelationID = b.BenefRelation
                LEFT JOIN beneftype t ON t.ID = b.BenefTypeID
                WHERE b.BenefBR_CODE = ? AND b.ClientIDBenef = ?
                ORDER BY b.BenefTypeID, b.BenefID
                """, (rs, i) -> {
                java.sql.Date born = rs.getDate(5);
                java.sql.Timestamp added = rs.getTimestamp(9);
                int rel = rs.getInt(3);
                return new Beneficiary(rs.getLong(1), clean(rs.getString(2)), rs.wasNull() ? null : rel, rs.getString(4), born == null ? null : born.toLocalDate(), clean(rs.getString(6)),
                    rs.getInt(7), rs.getString(8), added == null ? null : added.toLocalDateTime().toLocalDate());
            }, branchCode, clientId);
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
