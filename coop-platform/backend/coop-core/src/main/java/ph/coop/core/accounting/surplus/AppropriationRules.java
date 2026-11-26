package ph.coop.core.accounting.surplus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The appropriation of a year's net surplus (RA 9520, the Philippine Cooperative Code of 2008, Article 86): the statutory funds are set aside first, as a percent
 * of the net surplus, then what the general assembly allocated to interest on share capital and patronage refund. What is left stays in the undivided net surplus.
 * The limits checked are Article 86's as this application reads them ({@link #RESERVE_MIN} and the rest); the cooperative's bylaws may ask for more, and the
 * accountant confirms them. Pure: no database.
 */
public final class AppropriationRules {

    private AppropriationRules() {}

    public static final String RESERVE = "RESERVE", EDUCATION = "EDUCATION", COMMUNITY = "COMMUNITY", OPTIONAL = "OPTIONAL", OTHER = "OTHER", ISC = "ISC", PR = "PR";
    public static final List<String> FUNDS = List.of(RESERVE, EDUCATION, COMMUNITY, OPTIONAL, OTHER);
    public static final List<String> KINDS = List.of(RESERVE, EDUCATION, COMMUNITY, OPTIONAL, OTHER, ISC, PR);

    /** Article 86 (1): the reserve fund is at least 10% of the net surplus, and at least 50% in the first five years after registration. */
    public static final BigDecimal RESERVE_MIN = new BigDecimal("10"), RESERVE_MIN_EARLY = new BigDecimal("50");
    /** Article 86 (2): the education and training fund is at most 10% (half spent by the cooperative, half may go to its union or federation). */
    public static final BigDecimal EDUCATION_MAX = new BigDecimal("10");
    /** Article 86 (3): the community development fund is at least 3%. */
    public static final BigDecimal COMMUNITY_MIN = new BigDecimal("3");
    /** Article 86 (4): the optional fund (land and building, and the like) is at most 7%. */
    public static final BigDecimal OPTIONAL_MAX = new BigDecimal("7");

    public static final int MAX_LINES = 20, RESOLUTION_MIN = 5, RESOLUTION_MAX = 200;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** One line: a fund (kind RESERVE ... OTHER) with its percent of the net surplus, or the interest on share capital (ISC) or patronage refund (PR) with an amount. */
    public record Line(String kind, String name, BigDecimal percent, BigDecimal amount, String account) {
        public boolean fund() { return FUNDS.contains(kind); }
    }

    /** The figures of an appropriation: funds, interest on share capital, patronage refund, all allocated, and what stays undivided. */
    public record Totals(BigDecimal netSurplus, BigDecimal funds, BigDecimal isc, BigDecimal pr, BigDecimal allocated, BigDecimal undivided) {}

    /** One line of the General Journal it posts: positive a debit, negative a credit. */
    public record Entry(String account, BigDecimal amount) {}

    /** A fund's amount: the percent of the net surplus, to the centavo (half up). ISC and PR keep the amount typed. */
    public static Line priced(Line l, BigDecimal netSurplus) {
        if (!l.fund() || l.percent() == null || netSurplus == null) return l;
        return new Line(l.kind(), l.name(), l.percent(), netSurplus.multiply(l.percent()).divide(HUNDRED, 2, RoundingMode.HALF_UP), l.account());
    }

    public static List<Line> priced(List<Line> lines, BigDecimal netSurplus) {
        return lines.stream().map((l) -> priced(l, netSurplus)).toList();
    }

    public static Totals totals(BigDecimal netSurplus, List<Line> lines) {
        BigDecimal funds = BigDecimal.ZERO, isc = BigDecimal.ZERO, pr = BigDecimal.ZERO;
        for (Line l : priced(lines, netSurplus)) {
            BigDecimal a = l.amount() == null ? BigDecimal.ZERO : l.amount();
            if (l.fund()) funds = funds.add(a);
            else if (ISC.equals(l.kind())) isc = isc.add(a);
            else if (PR.equals(l.kind())) pr = pr.add(a);
        }
        BigDecimal all = funds.add(isc).add(pr);
        return new Totals(netSurplus, funds, isc, pr, all, netSurplus == null ? null : netSurplus.subtract(all));
    }

    /** The smallest reserve fund percent for the year: 50% while the year ends within five years of registration, else 10%. */
    public static BigDecimal reserveMinimum(int year, LocalDate registeredOn) {
        return registeredOn != null && LocalDate.of(year, 12, 31).isBefore(registeredOn.plusYears(5)) ? RESERVE_MIN_EARLY : RESERVE_MIN;
    }

    /** The share of the net surplus each kind of fund takes, in percent. */
    public static Map<String, BigDecimal> percents(List<Line> lines) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (String k : FUNDS) m.put(k, BigDecimal.ZERO);
        for (Line l : lines) if (l.fund() && l.percent() != null) m.merge(l.kind(), l.percent(), BigDecimal::add);
        return m;
    }

    /**
     * Why the appropriation cannot go on, in words; empty when it can. {@code netSurplus} is what the year's closing put in the undivided net surplus (null when
     * the year is not closed); {@code payoutAccount} is the account the dividend vouchers are paid from, which interest and refund must be credited to.
     */
    public static List<String> problems(int year, BigDecimal netSurplus, List<Line> lines, String resolution, LocalDate entryDate, LocalDate today, LocalDate registeredOn,
                                        String sourceAccount, String payoutAccount) {
        List<String> p = new ArrayList<>();
        if (netSurplus == null) {
            p.add("The books for " + year + " are not closed yet. Close the year in Accounting > Year-end close first; the net surplus to appropriate is what the closing puts in the undivided net surplus.");
            return p;
        }
        if (netSurplus.signum() <= 0) {
            p.add("The closing of " + year + " shows " + (netSurplus.signum() == 0 ? "no net surplus" : "a net loss of " + netSurplus.negate().toPlainString())
                + ", so there is nothing to appropriate. A loss is not distributed; the general assembly decides how it is covered.");
            return p;
        }
        String r = resolution == null ? "" : resolution.trim();
        if (r.length() < RESOLUTION_MIN || r.length() > RESOLUTION_MAX) {
            p.add("Name the general assembly resolution that approved the appropriation, in " + RESOLUTION_MIN + " to " + RESOLUTION_MAX + " characters, such as \"GA Resolution No. 3, s. " + (year + 1) + "\".");
        }
        if (entryDate == null) p.add("Give the date of the entry: the day the general assembly approved it, or later.");
        else {
            if (!entryDate.isAfter(LocalDate.of(year, 12, 31))) p.add("The entry is dated " + entryDate + ", but " + year + "'s surplus is appropriated after the year ends: date it in " + (year + 1) + " or later.");
            if (today != null && entryDate.isAfter(today)) p.add("The entry is dated " + entryDate + ", which has not come yet. Date it today or earlier.");
        }
        if (lines.isEmpty()) p.add("Add the statutory funds (reserve fund, education and training fund, community development fund and optional fund) and the amounts allocated to members.");
        if (lines.size() > MAX_LINES) p.add("An appropriation has at most " + MAX_LINES + " lines. Join funds that go to the same account.");

        Set<String> accounts = new HashSet<>();
        int isc = 0, pr = 0;
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            String at = "Line " + (i + 1) + (l.name() == null || l.name().isBlank() ? "" : " (" + l.name().trim() + ")") + ": ";
            if (l.kind() == null || !KINDS.contains(l.kind())) { p.add(at + "say what it is: a statutory fund (which one), interest on share capital or patronage refund."); continue; }
            String name = l.name() == null ? "" : l.name().trim();
            if (name.length() < 2 || name.length() > 80) p.add(at + "give it a name of 2 to 80 characters.");
            if (l.fund()) {
                if (l.percent() == null || l.percent().signum() <= 0 || l.percent().compareTo(HUNDRED) > 0) p.add(at + "give the percent of the net surplus it takes, more than 0 and at most 100.");
                else if (l.percent().stripTrailingZeros().scale() > 4) p.add(at + "give the percent with at most four decimals.");
            } else {
                if (ISC.equals(l.kind())) isc++; else pr++;
                if (l.amount() == null || l.amount().signum() < 0) p.add(at + "give the amount the general assembly allocated, zero or more.");
                else if (l.amount().stripTrailingZeros().scale() > 2) p.add(at + "give the amount to the centavo (two decimals).");
            }
            String a = l.account() == null ? "" : l.account().trim();
            if (a.isEmpty()) { p.add(at + "choose the ledger account it is credited to."); continue; }
            if (a.equals(sourceAccount)) p.add(at + "account " + a + " is the undivided net surplus the appropriation is taken from. Credit the fund's own account.");
            if (l.fund()) {
                if (a.equals(payoutAccount)) p.add(at + "account " + a + " holds the interest and refund payable to members. Credit the fund's own account.");
                else if (!accounts.add(a)) p.add(at + "account " + a + " is used by another fund too. Give each fund its own account, or join them into one line.");
            } else if (payoutAccount != null && !a.equals(payoutAccount)) {
                p.add(at + "interest on share capital and patronage refund are credited to " + payoutAccount + ", the account the dividend vouchers pay members from.");
            }
        }
        if (isc > 1 || pr > 1) p.add("Put the interest on share capital and the patronage refund on one line each.");

        Map<String, BigDecimal> pct = percents(lines);
        BigDecimal reserveMin = reserveMinimum(year, registeredOn);
        if (pct.get(RESERVE).compareTo(reserveMin) < 0) {
            p.add("The reserve fund takes " + plain(pct.get(RESERVE)) + "% of the net surplus; Article 86 of the Cooperative Code asks for at least " + plain(reserveMin) + "%"
                + (reserveMin.equals(RESERVE_MIN_EARLY) ? ", since " + year + " is within the cooperative's first five years after registration." : "."));
        }
        if (pct.get(EDUCATION).compareTo(EDUCATION_MAX) > 0) p.add("The education and training fund takes " + plain(pct.get(EDUCATION)) + "% of the net surplus; Article 86 allows at most " + plain(EDUCATION_MAX) + "% (both halves together).");
        if (pct.get(COMMUNITY).compareTo(COMMUNITY_MIN) < 0) p.add("The community development fund takes " + plain(pct.get(COMMUNITY)) + "% of the net surplus; Article 86 asks for at least " + plain(COMMUNITY_MIN) + "%.");
        if (pct.get(OPTIONAL).compareTo(OPTIONAL_MAX) > 0) p.add("The optional fund takes " + plain(pct.get(OPTIONAL)) + "% of the net surplus; Article 86 allows at most " + plain(OPTIONAL_MAX) + "%.");

        Totals t = totals(netSurplus, lines);
        if (t.undivided().signum() < 0) {
            p.add("The funds and allocations come to " + t.allocated().toPlainString() + ", more than the net surplus of " + netSurplus.toPlainString() + " by " + t.undivided().negate().toPlainString()
                + ". Lower the allocations to members or the optional funds.");
        }
        return p;
    }

    /**
     * The General Journal the appropriation posts: one debit of the undivided net surplus for everything allocated, then a credit per account (lines on the same
     * account joined), in the order of the lines. Lines with no amount are left out.
     */
    public static List<Entry> entries(BigDecimal netSurplus, List<Line> lines, String sourceAccount) {
        Map<String, BigDecimal> credits = new LinkedHashMap<>();
        for (Line l : priced(lines, netSurplus)) {
            if (l.amount() == null || l.amount().signum() == 0) continue;
            credits.merge(l.account().trim(), l.amount(), BigDecimal::add);
        }
        BigDecimal total = credits.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Entry> out = new ArrayList<>();
        if (total.signum() == 0) return out;
        out.add(new Entry(sourceAccount, total));
        credits.forEach((a, v) -> out.add(new Entry(a, v.negate())));
        return out;
    }

    /** The maker, approver and poster are three different people; the reason in words when they are not, else null. */
    public static String separation(String maker, String approver, String poster) {
        if (approver != null && approver.equalsIgnoreCase(maker)) return "The person who approves the appropriation cannot be the person who prepared it. Another accountant or an administrator approves it.";
        if (poster != null && poster.equalsIgnoreCase(maker)) return "The person who posts the appropriation cannot be the person who prepared it. A third person posts it.";
        if (poster != null && approver != null && poster.equalsIgnoreCase(approver)) return "The person who posts the appropriation cannot be the person who approved it. A third person posts it.";
        return null;
    }

    /** The kind of statutory fund a fund's name suggests (for funds set up in the dividend settings before kinds were kept), or OTHER. */
    public static String kindFor(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.contains("reserve")) return RESERVE;
        if (n.contains("education") || n.contains("training") || n.contains("cetf")) return EDUCATION;
        if (n.contains("community")) return COMMUNITY;
        if (n.contains("optional") || n.contains("land") || n.contains("building")) return OPTIONAL;
        return OTHER;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
