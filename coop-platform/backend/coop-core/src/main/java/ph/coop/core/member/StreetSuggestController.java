package ph.coop.core.member;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Street names already used in a barangay, offered while an address is typed (see {@link StreetRules}). Read only; any signed-in person. */
@RestController
@RequestMapping("/api/members/address-streets")
public class StreetSuggestController {

    private final JdbcTemplate jdbc;

    StreetSuggestController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<String> streets(@RequestParam(defaultValue = "") String barangay, @RequestParam(defaultValue = "") String city, @RequestParam(defaultValue = "") String province) {
        String b = barangay.trim().toUpperCase(java.util.Locale.ROOT);
        if (b.isEmpty()) return List.of();
        // the present and the provincial addresses of every member whose barangay is this one; the city and province are compared in Java because records write them in many ways
        List<StreetRules.Seen> seen = jdbc.query(
            "SELECT ResAddStreet, ResAddCity, ResAddProvince FROM client WHERE UPPER(TRIM(ResAddBarangay)) = ? AND ResAddStreet IS NOT NULL "
                + "UNION ALL SELECT ProvAddStreet, ProvAddCity, ProvAddProvince FROM client WHERE UPPER(TRIM(ProvAddBarangay)) = ? AND ProvAddStreet IS NOT NULL",
            (rs, i) -> new StreetRules.Seen(rs.getString(1), rs.getString(2), rs.getString(3)), b, b);
        return StreetRules.suggestions(seen, city, province);
    }
}
