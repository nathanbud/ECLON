package ph.coop.core.accounting.close;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The year-end closing, worked out the way the old system's computer-generated Closing Entry (type 99) does it: for each department, every
 * income (4xxxx) and expense (5xxxx) account with a balance for the year gets the opposite amount, and the department's difference goes to
 * the undivided net surplus account (30198). Accounts are chosen by their number, not by the chart's account type, as the old system did
 * (three loan accounts in the chart carry an income or expense type by mistake). Pure: no database.
 */
public final class YearEndRules {

    private YearEndRules() {}

    /** The year's net for one account in one department: debits plus credits, credits negative (as in gltrans). */
    public record Balance(int dept, String account, BigDecimal net) {}

    /** One closing line: positive is a debit, negative a credit (as in transdt). */
    public record Line(int dept, String account, BigDecimal amount) {}

    /** Income, expenses and surplus (income less expenses) of one department, all as positive figures when normal. */
    public record Department(int dept, BigDecimal income, BigDecimal expenses, BigDecimal surplus, int lines) {}

    /** What the checks need to know about the books. {@code lastClosedYear} is null when no year was ever closed. */
    public record Facts(int year, LocalDate today, boolean alreadyClosed, Integer lastClosedYear, int pendingJournals,
                        boolean surplusAccountUsable, boolean nothingToClose) {}

    public static boolean closes(String account) {
        return account != null && (account.startsWith("4") || account.startsWith("5"));
    }

    public static LocalDate closingDate(int year) {
        return LocalDate.of(year, 12, 31);
    }

    /** The closing lines, department by department: each account's reversing line, then the department's line on the surplus account. */
    public static List<Line> closingLines(List<Balance> balances, String surplusAccount) {
        Map<Integer, List<Balance>> byDept = byDept(balances);
        List<Line> out = new ArrayList<>();
        for (var e : byDept.entrySet()) {
            BigDecimal net = BigDecimal.ZERO;
            for (Balance b : e.getValue()) {
                out.add(new Line(e.getKey(), b.account(), b.net().negate()));
                net = net.add(b.net());
            }
            if (net.signum() != 0) out.add(new Line(e.getKey(), surplusAccount, net));
        }
        return out;
    }

    public static List<Department> departments(List<Balance> balances) {
        List<Department> out = new ArrayList<>();
        for (var e : byDept(balances).entrySet()) {
            BigDecimal income = BigDecimal.ZERO, expenses = BigDecimal.ZERO;
            for (Balance b : e.getValue()) {
                if (b.account().startsWith("4")) income = income.subtract(b.net());
                else expenses = expenses.add(b.net());
            }
            out.add(new Department(e.getKey(), income, expenses, income.subtract(expenses), e.getValue().size()));
        }
        return out;
    }

    /** Net surplus of the year (a loss is negative). */
    public static BigDecimal surplus(List<Balance> balances) {
        return balances.stream().map(Balance::net).reduce(BigDecimal.ZERO, BigDecimal::add).negate();
    }

    /** Why the year cannot be closed yet; empty when it can. */
    public static List<String> problems(Facts f) {
        List<String> p = new ArrayList<>();
        if (!f.today().isAfter(closingDate(f.year()))) p.add(f.year() + " has not ended yet; it can be closed from 1 January " + (f.year() + 1) + ".");
        if (f.alreadyClosed()) p.add("The books for " + f.year() + " are already closed.");
        else if (f.lastClosedYear() != null && f.year() < f.lastClosedYear()) p.add("A later year (" + f.lastClosedYear() + ") is already closed.");
        else if (f.lastClosedYear() != null && f.year() > f.lastClosedYear() + 1) p.add("Close " + (f.lastClosedYear() + 1) + " first; years are closed in order.");
        if (f.pendingJournals() > 0) {
            p.add(f.pendingJournals() + (f.pendingJournals() == 1 ? " journal dated " : " journals dated ") + f.year() + " are not posted yet. Post or move them first.");
        }
        if (!f.surplusAccountUsable()) p.add("The undivided net surplus account is missing from the chart or switched off.");
        if (f.nothingToClose()) p.add("No income or expense was booked in " + f.year() + ", so there is nothing to close.");
        return p;
    }

    /** A fingerprint of the closing lines, to see at posting time whether the ledger changed after the closing was prepared. */
    public static String fingerprint(List<Line> lines) {
        StringBuilder s = new StringBuilder();
        lines.stream().sorted(Comparator.comparingInt(Line::dept).thenComparing(Line::account))
                .forEach(l -> s.append(l.dept()).append('|').append(l.account()).append('|').append(l.amount().stripTrailingZeros().toPlainString()).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<Integer, List<Balance>> byDept(List<Balance> balances) {
        Map<Integer, List<Balance>> m = new LinkedHashMap<>();
        balances.stream().filter(b -> closes(b.account()) && b.net().signum() != 0)
                .sorted(Comparator.comparingInt(Balance::dept).thenComparing(Balance::account))
                .forEach(b -> m.computeIfAbsent(b.dept(), k -> new ArrayList<>()).add(b));
        return m;
    }
}
