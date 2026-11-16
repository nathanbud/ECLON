package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The address columns added for the house, unit, block and lot number and the subdivision, village or building name (V054 on MySQL, V017 on PostgreSQL) must exist in the database
 * the tests run on, and be on the list of columns a profile edit may change: an edit of any column not on that list is refused when it is approved.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class AddressFieldsTest {

    private static final List<String> NEW_COLUMNS = List.of("ResAddHouseNo", "ResAddSubdivision", "ProvAddHouseNo", "ProvAddSubdivision");

    @Autowired JdbcTemplate jdbc;

    @Test
    void theNewColumnsAreOnTheClientTable() {
        // Selecting them is enough: an unknown column fails the statement, on either database.
        jdbc.queryForList("SELECT ResAddHouseNo, ResAddSubdivision, ProvAddHouseNo, ProvAddSubdivision, ProvAddZipCode FROM client WHERE 1 = 0");
    }

    @Test
    void everyAddressColumnCanBeEdited() {
        for (String column : List.of("ResAddHouseNo", "ResAddStreet", "ResAddSubdivision", "ResAddBarangay", "ResAddCity", "ResAddZipCode", "ResAddProvince",
                "ProvAddHouseNo", "ProvAddStreet", "ProvAddSubdivision", "ProvAddBarangay", "ProvAddCity", "ProvAddZipCode", "ProvAddProvince")) {
            assertTrue(ClientDetailController.EDITABLE.containsKey(column), column + " must be editable");
        }
        assertTrue(NEW_COLUMNS.stream().allMatch(ClientDetailController.EDITABLE::containsKey));
    }
}
