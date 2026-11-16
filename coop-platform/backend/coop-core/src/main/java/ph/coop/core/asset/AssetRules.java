package ph.coop.core.asset;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import ph.coop.core.accounting.journal.JournalLine;

/** The fixed-asset rules that need no database: straight-line monthly depreciation, which months are due, gain or loss on disposal, and the journal lines. */
final class AssetRules {

    private AssetRules() {}

    static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");
    /** The old chart's account for a loss on the sale of property and equipment. */
    static final String LOSS_ACCOUNT = "6004108";

    /** Cost less the salvage value kept at the end of the life. */
    static BigDecimal depreciable(BigDecimal cost, BigDecimal salvage) {
        return cost.subtract(salvage);
    }

    /** Depreciation to the end of the {@code months}-th month, straight line: the depreciable amount x months / life, to the centavo, never more than the depreciable amount. */
    static BigDecimal cumulative(BigDecimal depreciable, int lifeMonths, int months) {
        if (months <= 0) return BigDecimal.ZERO.setScale(2);
        int m = Math.min(months, lifeMonths);
        return depreciable.multiply(BigDecimal.valueOf(m)).divide(BigDecimal.valueOf(lifeMonths), 2, RoundingMode.HALF_UP);
    }

    /** Months of depreciation up to and including the month that ends on {@code periodEnd}: the first is the month after the one the asset went into service. */
    static int monthsThrough(LocalDate inService, LocalDate periodEnd) {
        int n = (periodEnd.getYear() * 12 + periodEnd.getMonthValue()) - (inService.getYear() * 12 + inService.getMonthValue());
        return Math.max(0, n);
    }

    /** The depreciation of the single month ending {@code periodEnd} (the difference of two cumulative figures, so rounding never drifts and the last month lands on the exact total). */
    static BigDecimal monthAmount(BigDecimal depreciable, int lifeMonths, LocalDate inService, LocalDate periodEnd) {
        int n = monthsThrough(inService, periodEnd);
        return cumulative(depreciable, lifeMonths, n).subtract(cumulative(depreciable, lifeMonths, n - 1));
    }

    static boolean isMonthEnd(LocalDate d) {
        return d.equals(d.withDayOfMonth(d.lengthOfMonth()));
    }

    static LocalDate monthEnd(LocalDate d) {
        return d.withDayOfMonth(d.lengthOfMonth());
    }

    /** Every month end from the first month of depreciation up to {@code through}, in order. */
    static List<LocalDate> monthEnds(LocalDate inService, LocalDate through) {
        List<LocalDate> out = new ArrayList<>();
        LocalDate d = monthEnd(inService.plusMonths(1));
        while (!d.isAfter(through)) {
            out.add(d);
            d = monthEnd(d.plusMonths(1));
        }
        return out;
    }

    /** The problems with an asset's figures, empty when they can be used. */
    static List<String> assetProblems(BigDecimal cost, BigDecimal salvage, int lifeMonths, LocalDate acquired, LocalDate inService, LocalDate today) {
        List<String> out = new ArrayList<>();
        if (cost == null || cost.signum() <= 0) out.add("The cost must be more than zero.");
        else if (cost.compareTo(MAX_AMOUNT) > 0 || cost.stripTrailingZeros().scale() > 2) out.add("The cost is not valid (at most two decimals).");
        if (salvage == null || salvage.signum() < 0) out.add("The salvage value cannot be negative.");
        else if (cost != null && salvage.compareTo(cost) > 0) out.add("The salvage value cannot be more than the cost.");
        else if (salvage.stripTrailingZeros().scale() > 2) out.add("The salvage value can have at most two decimals.");
        if (lifeMonths < 1 || lifeMonths > 600) out.add("The useful life is 1 to 600 months.");
        if (acquired == null || inService == null) out.add("Give the acquisition date and the date it went into service.");
        else {
            if (acquired.isAfter(today)) out.add("The acquisition date cannot be in the future.");
            if (inService.isBefore(acquired)) out.add("The asset cannot go into service before it was acquired.");
            if (inService.isAfter(today)) out.add("The in-service date cannot be in the future.");
        }
        return out;
    }

    /** Proceeds less book value: positive is a gain, negative a loss. */
    static BigDecimal gainLoss(BigDecimal proceeds, BigDecimal bookValue) {
        return proceeds.subtract(bookValue);
    }

    /** The problems with a disposal before it is asked for. */
    static List<String> disposalProblems(String kind, BigDecimal proceeds, String proceedsAccount, String gainAccount, BigDecimal gainLoss) {
        List<String> out = new ArrayList<>();
        if (kind == null || !(kind.equals("SALE") || kind.equals("SCRAP") || kind.equals("DONATION") || kind.equals("LOSS"))) out.add("The kind is SALE, SCRAP, DONATION or LOSS.");
        if (proceeds == null || proceeds.signum() < 0 || proceeds.stripTrailingZeros().scale() > 2) { out.add("The proceeds must be zero or more, with at most two decimals."); return out; }
        if (proceeds.signum() > 0 && (proceedsAccount == null || proceedsAccount.isBlank())) out.add("Say which account takes the proceeds (cash, bank or a receivable).");
        if (gainLoss.signum() > 0 && (gainAccount == null || gainAccount.isBlank())) out.add("This disposal makes a gain of " + gainLoss.toPlainString() + ", so say which account takes it.");
        if (kind != null && !kind.equals("SALE") && proceeds.signum() > 0) out.add("Only a sale brings proceeds.");
        return out;
    }

    /**
     * The journal for a disposal: debit the accumulated depreciation, the proceeds and any loss, credit the cost of the asset and any gain. It balances because
     * loss = book value - proceeds = cost - accumulated - proceeds.
     */
    static List<JournalLine> disposalLines(String assetAccount, String accumAccount, BigDecimal cost, BigDecimal accumulated, BigDecimal proceeds, String proceedsAccount,
                                           BigDecimal gainLoss, String gainAccount, String lossAccount) {
        List<JournalLine> out = new ArrayList<>();
        if (accumulated.signum() > 0) out.add(new JournalLine(accumAccount, accumulated, BigDecimal.ZERO));
        if (proceeds.signum() > 0) out.add(new JournalLine(proceedsAccount, proceeds, BigDecimal.ZERO));
        if (gainLoss.signum() < 0) out.add(new JournalLine(lossAccount, gainLoss.negate(), BigDecimal.ZERO));
        out.add(new JournalLine(assetAccount, BigDecimal.ZERO, cost));
        if (gainLoss.signum() > 0) out.add(new JournalLine(gainAccount, BigDecimal.ZERO, gainLoss));
        return out;
    }

    /** Asset number FA-2026-0001 is built by the shared numbering; this names the journal reference of a month's depreciation: DEP-2026-10. */
    static String depreciationReference(LocalDate periodEnd) {
        return String.format("DEP-%d-%02d", periodEnd.getYear(), periodEnd.getMonthValue());
    }
}
