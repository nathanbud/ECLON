package ph.coop.core.deposit;

import java.time.LocalDate;
import java.util.Set;

/**
 * When a savings account is due to be marked dormant: its type has a dormancy period, and the member has neither deposited nor withdrawn for that long. What the system itself
 * posts (savings interest, dividends) is not the member's activity, so it does not keep an account awake. An account that was reactivated starts counting again from that day.
 * Marking is never automatic: the accounts are listed for an administrator, who marks them with a reason. Pure logic, no database access.
 */
final class DormancyRules {

    private DormancyRules() {}

    static final int ACTIVE = 15;
    static final int DORMANT = 16;

    /** Transaction types the system posts by itself: 53 savings interest, 54 dividends. */
    static final Set<Integer> SYSTEM_POSTINGS = Set.of(53, 54);

    /** Activity on or before this day is too old to keep an account of this period awake. */
    static LocalDate cutoff(LocalDate today, int months) {
        return today.minusMonths(months);
    }

    /**
     * @param opened       the day the account was opened (may be unknown)
     * @param lastActivity the member's last deposit or withdrawal (null when there was never one)
     * @param reactivated  the day the account was last reactivated (null when never)
     * @param months       the type's dormancy period (null when it has none)
     */
    static boolean due(int status, LocalDate opened, LocalDate lastActivity, LocalDate reactivated, LocalDate today, Integer months) {
        if (status != ACTIVE || months == null || months <= 0) return false;
        LocalDate last = lastActivity;
        if (last == null || (opened != null && opened.isAfter(last))) last = opened == null ? last : opened;
        if (reactivated != null && (last == null || reactivated.isAfter(last))) last = reactivated;
        if (last == null) return false; // nothing says how old the account is
        return !last.isAfter(cutoff(today, months));
    }
}
