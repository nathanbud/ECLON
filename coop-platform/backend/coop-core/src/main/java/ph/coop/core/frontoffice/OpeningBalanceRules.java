package ph.coop.core.frontoffice;

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
import ph.coop.core.member.SpreadsheetReader;

/**
 * What an opening balance batch must satisfy apart from the database. A batch is a new cooperative's balances on the day it starts on this system: lines on
 * general ledger accounts and on members' savings and share capital accounts. A member line's amount goes to the control account of that member account, so the
 * control accounts are never typed as ledger lines: they come out as the sum of the members. A batch balances, every member account appears once and only as a
 * credit (money the member has in it), and the batch is made, approved and posted by three different people.
 */
final class OpeningBalanceRules {

    static final String GL = "GL";
    static final String SAVINGS = "SAVINGS";
    static final String SHARE = "SHARE";
    /** The subsidiary ledger class of each kind of member account: savings 22, share capital 31. */
    static final Map<String, Integer> CLASS = Map.of(SAVINGS, 22, SHARE, 31);
    static final int MAX_LINES = 5000;
    static final int DESCRIPTION_MIN = 5;
    static final int DESCRIPTION_MAX = 200;
    static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

    /** One line as typed or imported. Exactly one of debit and credit is more than zero; a member line names the member and the type, the account number when there are several. */
    record Line(String kind, Integer clientId, Integer typeCode, Integer refNo, String accountCode, BigDecimal debit, BigDecimal credit, String note) {
        boolean member() { return SAVINGS.equals(kind) || SHARE.equals(kind); }

        /** Debit positive, credit negative, as the ledger stores it. */
        BigDecimal signed() { return amount(debit).signum() > 0 ? amount(debit) : amount(credit).negate(); }

        Line withRefNo(Integer ref) { return new Line(kind, clientId, typeCode, ref, accountCode, debit, credit, note); }
    }

    /** One account of the trial balance the batch makes: a ledger account, or a control account with the number of member accounts it adds up. */
    record TrialLine(String account, BigDecimal debit, BigDecimal credit, int memberAccounts) {}

    /** What reading a file gave: the lines it could read and, per file line, what was wrong with the others. */
    record Imported(List<Line> lines, List<String> problems) {}

    private OpeningBalanceRules() {}

    static BigDecimal amount(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Everything wrong with a batch as typed, in words a person can act on. Empty when it is fine. */
    static List<String> problems(List<Line> lines, String description, LocalDate date, LocalDate today) {
        List<String> out = new ArrayList<>();
        String d = description == null ? "" : description.trim();
        if (d.length() < DESCRIPTION_MIN) out.add("Describe the opening balances, for example \"Opening balances as of 1 January 2027 per the audited statements\" (at least " + DESCRIPTION_MIN + " characters).");
        else if (d.length() > DESCRIPTION_MAX) out.add("The description can be at most " + DESCRIPTION_MAX + " characters.");
        if (date == null) out.add("Give the opening date: the day the balances are as of, which is the first day the cooperative works on this system.");
        else if (date.isAfter(today)) out.add("The opening date cannot be in the future. Prepare the batch on or after the opening date.");
        if (lines == null || lines.size() < 2) {
            out.add("Opening balances need at least two lines that balance, such as cash on hand and the members' share capital.");
            return out;
        }
        if (lines.size() > MAX_LINES) {
            out.add("A batch can have at most " + MAX_LINES + " lines. Split the members over two batches, each with ledger lines that make it balance.");
            return out;
        }
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        Set<String> seenMember = new HashSet<>();
        Set<String> seenAccount = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            String at = "Line " + (i + 1) + ": ";
            if (l == null || l.kind() == null || !(l.member() || GL.equals(l.kind()))) {
                out.add(at + "choose a ledger account, a member's savings or a member's share capital.");
                continue;
            }
            BigDecimal dr = amount(l.debit()), cr = amount(l.credit());
            if (dr.signum() < 0 || cr.signum() < 0) out.add(at + "an amount cannot be negative; put it in the other column instead.");
            else if ((dr.signum() > 0) == (cr.signum() > 0)) out.add(at + "give either a debit or a credit, not both and not neither.");
            else if (tooPrecise(dr) || tooPrecise(cr)) out.add(at + "an amount can have at most two decimals.");
            else if (dr.compareTo(MAX_AMOUNT) > 0 || cr.compareTo(MAX_AMOUNT) > 0) out.add(at + "the amount is too large.");
            if (l.member()) {
                if (l.clientId() == null || l.clientId() <= 0 || l.typeCode() == null) out.add(at + "give the member and the account type.");
                else {
                    String key = l.kind() + "/" + l.clientId() + "/" + l.typeCode() + "/" + (l.refNo() == null ? "" : l.refNo());
                    if (!seenMember.add(key)) out.add(at + "member " + l.clientId() + "'s " + (SHARE.equals(l.kind()) ? "share capital" : "savings") + " account is already on an earlier line. Add the amounts into one line.");
                }
                if (l.accountCode() != null && !l.accountCode().isBlank()) out.add(at + "a member line takes no ledger account; the system posts it to the account type's control account.");
                if (dr.signum() > 0) out.add(at + "a member's opening " + (SHARE.equals(l.kind()) ? "share capital" : "savings") + " is a credit (the money the member has in it). A member who owes the cooperative is a loan or a receivable, not a debit here.");
            } else if (l.accountCode() == null || l.accountCode().isBlank()) {
                out.add(at + "give the ledger account.");
            } else {
                if (l.clientId() != null || l.typeCode() != null || l.refNo() != null) out.add(at + "a ledger line takes no member.");
                if (!seenAccount.add(l.accountCode().trim())) out.add(at + "account " + l.accountCode().trim() + " is already on an earlier line. Add the amounts into one line.");
            }
            if (l.note() != null && l.note().length() > 100) out.add(at + "the note can be at most 100 characters.");
            debit = debit.add(dr.max(BigDecimal.ZERO));
            credit = credit.add(cr.max(BigDecimal.ZERO));
        }
        if (debit.compareTo(credit) != 0) {
            BigDecimal gap = debit.subtract(credit);
            out.add("The batch does not balance: debits " + money(debit) + ", credits " + money(credit) + ", a difference of " + money(gap.abs()) + " on the "
                + (gap.signum() > 0 ? "debit" : "credit") + " side. Check the trial balance it comes from; the difference is often a missing equity or members' line.");
        }
        return out;
    }

    private static boolean tooPrecise(BigDecimal v) {
        return v.signum() != 0 && v.stripTrailingZeros().scale() > 2;
    }

    /** The maker, the approver and the poster must be three different people. Null when they are. */
    static String separation(String creator, String approver, String poster) {
        if (approver != null && approver.equalsIgnoreCase(creator)) return "The person who approves the opening balances cannot be the person who prepared them. Another accountant or an administrator approves them.";
        if (poster != null && poster.equalsIgnoreCase(creator)) return "The person who posts the opening balances cannot be the person who prepared them. A third person posts them.";
        if (poster != null && approver != null && poster.equalsIgnoreCase(approver)) return "The person who posts the opening balances cannot be the person who approved them. A third person posts them.";
        return null;
    }

    /**
     * The trial balance the batch makes, by account, in order of account: each ledger line as it is, and each member line added into the control account it posts to
     * ({@code controlOf} gives it per line index; a line without one is left out, its problem is reported elsewhere).
     */
    static List<TrialLine> trialBalance(List<Line> lines, Map<Integer, String> controlOf) {
        Map<String, BigDecimal[]> sums = new LinkedHashMap<>();
        Map<String, Integer> members = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            String account = l.member() ? controlOf.get(i) : l.accountCode() == null ? null : l.accountCode().trim();
            if (account == null) continue;
            BigDecimal[] s = sums.computeIfAbsent(account, (k) -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            s[0] = s[0].add(amount(l.debit()));
            s[1] = s[1].add(amount(l.credit()));
            if (l.member()) members.merge(account, 1, Integer::sum);
        }
        List<TrialLine> out = new ArrayList<>();
        sums.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach((e) -> out.add(new TrialLine(e.getKey(), e.getValue()[0].setScale(2), e.getValue()[1].setScale(2), members.getOrDefault(e.getKey(), 0))));
        return out;
    }

    // ---- reading a file -----------------------------------------------------------------------------------------

    /**
     * Reads the lines of a CSV or Excel file whose first row names the columns: Kind (GL, Savings or Share capital), Member (the member number), Type (the account type's
     * number), Account no (the member account's number, when the member has more than one of that type), Ledger account, Debit, Credit and Note. Names are matched
     * without regard to case, spaces or punctuation. A row that cannot be read is reported with its file line and left out.
     */
    static Imported read(List<SpreadsheetReader.Row> rows) {
        List<Line> lines = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (SpreadsheetReader.Row r : rows) {
            Map<String, String> v = new LinkedHashMap<>();
            r.values().forEach((k, val) -> v.put(norm(k), val));
            String at = "File line " + r.line() + ": ";
            String kind = kind(first(v, "kind", "linekind", "for"));
            if (kind == null) {
                problems.add(at + "say in the Kind column whether the line is GL, Savings or Share capital.");
                continue;
            }
            try {
                Integer client = integer(first(v, "member", "memberno", "membernumber", "clientid"), "member number");
                Integer type = integer(first(v, "type", "typecode", "accounttype"), "account type");
                Integer ref = integer(first(v, "accountno", "accountnumber", "refno", "reference"), "account number");
                String account = first(v, "ledgeraccount", "glaccount", "account", "accountcode");
                BigDecimal debit = decimal(first(v, "debit", "dr"), "debit");
                BigDecimal credit = decimal(first(v, "credit", "cr"), "credit");
                String note = first(v, "note", "notes", "remarks");
                if (GL.equals(kind)) lines.add(new Line(kind, null, null, null, account, debit, credit, note));
                else lines.add(new Line(kind, client, type, ref, null, debit, credit, note));
            } catch (IllegalArgumentException e) {
                problems.add(at + e.getMessage());
            }
        }
        return new Imported(lines, problems);
    }

    private static String norm(String header) {
        return header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String first(Map<String, String> v, String... names) {
        for (String n : names) {
            String s = v.get(n);
            if (s != null && !s.isBlank()) return s.trim();
        }
        return null;
    }

    static String kind(String s) {
        if (s == null) return null;
        String k = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return switch (k) {
            case "gl", "ledger", "generalledger", "account" -> GL;
            case "savings", "sd", "savingsdeposit", "deposit" -> SAVINGS;
            case "share", "shares", "sharecapital", "sc", "capital" -> SHARE;
            default -> null;
        };
    }

    private static Integer integer(String s, String what) {
        if (s == null) return null;
        String t = s.endsWith(".0") ? s.substring(0, s.length() - 2) : s; // Excel keeps whole numbers as 123.0
        try {
            return Integer.valueOf(t.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("the " + what + " \"" + s + "\" is not a whole number.");
        }
    }

    private static BigDecimal decimal(String s, String what) {
        if (s == null) return null;
        String t = s.replace(",", "").replace("₱", "").trim();
        if (t.isEmpty()) return null;
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("the " + what + " \"" + s + "\" is not an amount.");
        }
    }
}
