package ph.coop.core.asset;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.inventory.InventorySupport;

/** Works out which months of depreciation are still due for the active assets (read only); used by the depreciation run and to check an asset is up to date before it is disposed of. */
@Component
class DepreciationPlanner {

    /** One asset's depreciation for one month, with the two accounts it is booked to. */
    record Due(long assetId, LocalDate periodEnd, BigDecimal amount, String expense, String accum) {}

    private final JdbcTemplate jdbc;

    DepreciationPlanner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What is due up to {@code through} for every active asset: the months it has not been depreciated for yet, from the month after it went into service. */
    List<Due> due(LocalDate through) {
        Map<Long, Set<LocalDate>> done = new HashMap<>();
        jdbc.query("SELECT d.asset_id, d.period_end FROM ecis_asset_depreciation d JOIN ecis_asset a ON a.id = d.asset_id WHERE a.branch_code = ? AND a.status = 'ACTIVE'",
            (rs) -> { done.computeIfAbsent(rs.getLong(1), k -> new HashSet<>()).add(rs.getDate(2).toLocalDate()); }, InventorySupport.BRANCH);
        List<Due> out = new ArrayList<>();
        jdbc.query("""
                SELECT a.id, a.in_service_date, a.cost, a.salvage_value, a.life_months, c.expense_account, c.accum_account FROM ecis_asset a JOIN ecis_asset_category c ON c.id = a.category_id
                WHERE a.branch_code = ? AND a.status = 'ACTIVE' ORDER BY a.id
                """, (rs) -> {
                long id = rs.getLong(1);
                LocalDate inService = rs.getDate(2).toLocalDate();
                BigDecimal depreciable = AssetRules.depreciable(rs.getBigDecimal(3), rs.getBigDecimal(4));
                int life = rs.getInt(5);
                for (LocalDate m : AssetRules.monthEnds(inService, through)) {
                    if (done.getOrDefault(id, Set.of()).contains(m)) continue;
                    BigDecimal amount = AssetRules.monthAmount(depreciable, life, inService, m);
                    if (amount.signum() > 0) out.add(new Due(id, m, amount, rs.getString(6), rs.getString(7)));
                }
            }, InventorySupport.BRANCH);
        return out;
    }
}
