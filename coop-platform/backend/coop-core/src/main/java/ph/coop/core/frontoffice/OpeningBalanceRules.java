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
import ph.coop.core.loan.LoanSchedule;
import ph.coop.core.member.SpreadsheetReader;

/**
 * What an opening balance batch must satisfy apart from the database. A batch is a new cooperative's balances on the day it starts on this system: lines on
 * general ledger accounts, on members' savings and share capital accounts, and members' loans and time deposits still running. A member line's amount goes to
 * the control account of that member account, so the control accounts are never typed as ledger lines: they come out as the sum of the members. A batch
 * balances, every savings or share capital account appears once and only as a credit (money the member has in it), and the batch is made, approved and posted
 * by three different people.
 * <p>
 * A loan or time deposit line opens its account when the batch is posted, so it carries its terms ({@link Terms}). A loan is opened for what the member still
 * owes: its schedule runs over the remaining term from one payment period before the next payment, so the payments made before the opening are not repeated
 * and none is shown as late. A certificate keeps its placement date, rate and term (the member is paid as agreed at maturity); the interest it earned before
 * the opening goes in as its accrued interest, and the accrual runs count only the days from the opening date (its ledger row's date).
 */
final class OpeningBalanceRules {

    static final String GL = "GL";
    static final String SAVINGS = "SAVINGS";
    static final String SHARE = "SHARE";
    static final String LOAN = "LOAN";
    static final String TD = "TD";
    /** The subsidiary ledger class of each kind of member account: savings 22, share capital 31, loans 12, time deposits 24. */
    static final Map<String, Integer> CLASS = Map.of(SAVINGS, 22, SHARE, 31, LOAN, 12, TD, 24);
    /** Loan statuses (`cts_type`) a loan can be opened with: current, past due, in litigation, restructured. */
    static final Map<Integer, String> LOAN_STATUS = Map.of(11, "current", 12, "past due", 13, "in litigation", 14, "restructured");
    static final int LOAN_CURRENT = 11;
    /** A time deposit is opened active (15); its accrued interest is ledger event 71 with status 99, as the accrual run writes it. */
    static final int TD_ACTIVE = 15, ACCRUED_EVENT = 71, ACCRUED_STATUS = 99;
    static final int MAX_LOAN_MONTHS = 600, MAX_TD_DAYS = 3650;
    static final BigDecimal MAX_RATE = new BigDecimal("100");
    static final int MAX_LINES = 5000;
    static final int DESCRIPTION_MIN = 5;
    static final int DESCRIPTION_MAX = 200;
    static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

    /**
     * The terms of a loan or time deposit that a line opens. Loan: {@code status} (default current), {@code rate} the yearly rate (default the loan type's),
     * {@code terms} the months still to run, {@code start} the next payment's due date, and for the record the amount and date it was granted. Time deposit:
     * {@code rate}, {@code terms} in days, {@code start} the placement date, {@code accrued} the interest earned to the opening date. A certificate's number is its
     * account number, given when the batch is posted (the database sets it so, as the old system did).
     */
    record Terms(Integer status, BigDecimal rate, Integer terms, LocalDate start, BigDecimal originalAmount, LocalDate grantedOn, BigDecimal accrued) {}

    /** One posting of the batch on an account, for its trial balance; member postings are counted per control account. */
    record Posting(String account, BigDecimal debit, BigDecimal credit, boolean member) {}

    /** One line as typed or imported. Exactly one of debit and credit is more than zero; a member line names the member and the type, the account number when there are several. */
    record Line(String kind, Integer clientId, Integer typeCode, Integer refNo, String accountCode, BigDecimal debit, BigDecimal credit, String note, Terms terms) {
        Line(String kind, Integer clientId, Integer typeCode, Integer refNo, String accountCode, BigDecimal debit, BigDecimal credit, String note) {
            this(kind, clientId, typeCode, refNo, accountCode, debit, credit, note, null);
        }

        /** A line on a member's account: savings, share capital, a loan or a time deposit. */
        boolean member() { return SAVINGS.equals(kind) || SHARE.equals(kind) || opens(); }

        /** A loan or time deposit line, which opens its account when the batch is posted. */
        boolean opens() { return LOAN.equals(kind) || TD.equals(kind); }

        /** The time deposit's accrued interest, a second credit on the same certificate; zero for every other line. */
        BigDecimal accrued() { return TD.equals(kind) && terms != null ? amount(terms.accrued()) : BigDecimal.ZERO; }

        /** Debit positive, credit negative, as the ledger stores it. */
        BigDecimal signed() { return amount(debit).signum() > 0 ? amount(debit) : amount(credit).negate(); }

        Line withRefNo(Integer ref) { return new Line(kind, clientId, typeCode, ref, accountCode, debit, credit, note, terms); }
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
                out.add(at + "choose a ledger account, or a member's savings, share capital, loan or time deposit.");
                continue;
            }
            BigDecimal dr = amount(l.debit()), cr = amount(l.credit());
            if (dr.signum() < 0 || cr.signum() < 0) out.add(at + "an amount cannot be negative; put it in the other column instead.");
            else if ((dr.signum() > 0) == (cr.signum() > 0)) out.add(at + "give either a debit or a credit, not both and not neither.");
            else if (tooPrecise(dr) || tooPrecise(cr)) out.add(at + "an amount can have at most two decimals.");
            else if (dr.compareTo(MAX_AMOUNT) > 0 || cr.compareTo(MAX_AMOUNT) > 0) out.add(at + "the amount is too large.");
            if (l.opens()) {
                String what = LOAN.equals(l.kind()) ? "loan" : "time deposit";
                if (l.clientId() == null || l.clientId() <= 0 || l.typeCode() == null) out.add(at + "give the member and the " + what + " type.");
                if (l.accountCode() != null && !l.accountCode().isBlank()) out.add(at + "a member line takes no ledger account; the system posts it to the account type's control account.");
                if (l.refNo() != null) out.add(at + "a " + what + " is opened when the batch is posted and gets its number then; leave the account number empty.");
                if (LOAN.equals(l.kind())) {
                    if (cr.signum() > 0) out.add(at + "a loan's opening balance is a debit: the principal the member still owes.");
                    out.addAll(loanTerms(at, l, dr, date));
                } else {
                    if (dr.signum() > 0) out.add(at + "a time deposit's opening balance is a credit: the principal the member placed.");
                    out.addAll(depositTerms(at, l, date));
                }
            } else if (l.member()) {
                if (l.terms() != null) out.add(at + "savings and share capital take no loan or time deposit terms.");
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
            credit = credit.add(cr.max(BigDecimal.ZERO)).add(l.accrued().max(BigDecimal.ZERO));
        }
        if (debit.compareTo(credit) != 0) {
            BigDecimal gap = debit.subtract(credit);
            out.add("The batch does not balance: debits " + money(debit) + ", credits " + money(credit) + ", a difference of " + money(gap.abs()) + " on the "
                + (gap.signum() > 0 ? "debit" : "credit") + " side. Check the trial balance it comes from; the difference is often a missing equity or members' line.");
        }
        return out;
    }

    /** What is wrong with a loan line's terms. */
    private static List<String> loanTerms(String at, Line l, BigDecimal owed, LocalDate opening) {
        List<String> out = new ArrayList<>();
        Terms t = l.terms();
        if (t == null) {
            out.add(at + "give the months the loan still runs and the date its next payment is due.");
            return out;
        }
        if (t.status() != null && !LOAN_STATUS.containsKey(t.status())) out.add(at + "the loan's status can be current, past due, in litigation or restructured.");
        if (t.terms() == null || t.terms() <= 0) out.add(at + "give the months the loan still runs, counted from one payment period before its next payment (1 or more).");
        else if (t.terms() > MAX_LOAN_MONTHS) out.add(at + "a loan can run at most " + MAX_LOAN_MONTHS + " months.");
        if (t.start() == null) out.add(at + "give the date the loan's next payment is due.");
        else if (opening != null && !t.start().isAfter(opening))
            out.add(at + "the next payment must be due after the opening date. A payment already due is part of what the member owes: count it in the balance and give the following due date.");
        out.addAll(rate(at, t.rate(), "the loan's yearly interest rate"));
        if (t.originalAmount() != null) {
            if (t.originalAmount().signum() <= 0 || tooPrecise(t.originalAmount())) out.add(at + "the amount granted must be more than zero, with at most two decimals.");
            else if (owed.signum() > 0 && t.originalAmount().compareTo(owed) < 0) out.add(at + "the member cannot owe more principal than the loan granted. Check the balance and the amount granted.");
        }
        if (t.grantedOn() != null && opening != null && t.grantedOn().isAfter(opening))
            out.add(at + "the loan was granted after the opening date; a loan granted since is released through a loan application instead.");
        if (t.accrued() != null) out.add(at + "a loan takes no accrued interest.");
        return out;
    }

    /** What is wrong with a time deposit line's terms. */
    private static List<String> depositTerms(String at, Line l, LocalDate opening) {
        List<String> out = new ArrayList<>();
        Terms t = l.terms();
        if (t == null) {
            out.add(at + "give the certificate's placement date, term in days and yearly interest rate.");
            return out;
        }
        if (t.start() == null) out.add(at + "give the date the certificate was placed.");
        else if (opening != null && t.start().isAfter(opening)) out.add(at + "the certificate was placed after the opening date; a certificate placed since is placed at the teller window instead.");
        if (t.terms() == null || t.terms() <= 0) out.add(at + "give the certificate's term in days (1 or more).");
        else if (t.terms() > MAX_TD_DAYS) out.add(at + "a certificate's term can be at most " + MAX_TD_DAYS + " days.");
        if (t.rate() == null) out.add(at + "give the certificate's yearly interest rate.");
        else out.addAll(rate(at, t.rate(), "the certificate's yearly interest rate"));
        if (t.accrued() != null && (t.accrued().signum() < 0 || tooPrecise(t.accrued()))) out.add(at + "the accrued interest cannot be negative and has at most two decimals.");
        if (t.status() != null || t.originalAmount() != null || t.grantedOn() != null) out.add(at + "a time deposit takes no loan status, amount granted or date granted.");
        return out;
    }

    private static List<String> rate(String at, BigDecimal rate, String what) {
        if (rate == null) return List.of();
        if (rate.signum() < 0 || rate.compareTo(MAX_RATE) > 0) return List.of(at + what + " must be from 0 to " + MAX_RATE.toPlainString() + " percent.");
        if (rate.stripTrailingZeros().scale() > 4) return List.of(at + what + " can have at most four decimals.");
        return List.of();
    }

    /** The date a certificate matures: its placement date plus its term in days. */
    static LocalDate maturity(Terms t) {
        return t.start().plusDays(t.terms());
    }

    /**
     * How many months apart a loan's payments are for its payment mode (`paymentmode`: monthly 4, quarterly 5, half-yearly 6, lump sum 9 = the whole remaining
     * term), or null for a mode the schedules do not cover.
     */
    static Integer paymentStep(Integer paymentMode, int remainingMonths) {
        if (paymentMode == null) return null;
        return switch (paymentMode) {
            case LoanSchedule.MONTHLY -> 1;
            case LoanSchedule.QUARTERLY -> 3;
            case LoanSchedule.SEMIANNUAL -> 6;
            case LoanSchedule.LUMP_SUM -> remainingMonths;
            default -> null;
        };
    }

    /**
     * The remaining schedule of an opening loan: what the member owes over the months still to run, from one payment period before the next payment (the
     * schedule's first row), so its first due date is the next payment. Throws IllegalArgumentException, in words for the person, when the loan type's method or
     * payment mode is not covered, the term is not whole payment periods, or the due day cannot be kept.
     */
    static List<LoanSchedule.Row> loanSchedule(BigDecimal owed, Terms t, BigDecimal rate, Integer amortType, Integer paymentMode, boolean thirtyDayMonths) {
        if (amortType == null || (amortType != LoanSchedule.ANNUITY && amortType != LoanSchedule.DIMINISHING))
            throw new IllegalArgumentException("the loan type's amortization is not diminishing balance or annuity, the two the schedules cover.");
        Integer step = paymentStep(paymentMode, t.terms());
        if (step == null) throw new IllegalArgumentException("the loan type's payment mode is not one the schedules cover (monthly, quarterly, half-yearly or lump sum).");
        if (paymentMode == LoanSchedule.SEMIANNUAL && amortType == LoanSchedule.DIMINISHING)
            throw new IllegalArgumentException("a half-yearly diminishing balance loan is not one the schedules cover.");
        if (t.terms() % step != 0) throw new IllegalArgumentException("the months still to run must be whole payment periods of " + step + " months.");
        LocalDate from = t.start().minusMonths(step);
        List<LoanSchedule.Row> rows = amortType == LoanSchedule.ANNUITY
            ? LoanSchedule.annuity(owed, t.terms(), paymentMode, rate, from, thirtyDayMonths)
            : LoanSchedule.diminishing(owed, t.terms(), paymentMode, rate, from);
        if (!rows.get(1).dueDate().equals(t.start())) {
            throw new IllegalArgumentException("payments due on day " + t.start().getDayOfMonth() + " cannot be kept after a shorter month. Give the next due date as it falls on the loan's"
                + " schedule (" + rows.get(1).dueDate() + " here), or a day of the month up to the 28th.");
        }
        return rows;
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
        List<Posting> postings = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            String account = l.member() ? controlOf.get(i) : l.accountCode() == null ? null : l.accountCode().trim();
            if (account != null) postings.add(new Posting(account, amount(l.debit()), amount(l.credit()), l.member()));
        }
        return trialBalance(postings);
    }

    /** The trial balance of a list of postings, by account, in order of account. */
    static List<TrialLine> trialBalance(List<Posting> postings) {
        Map<String, BigDecimal[]> sums = new LinkedHashMap<>();
        Map<String, Integer> members = new LinkedHashMap<>();
        for (Posting p : postings) {
            BigDecimal[] s = sums.computeIfAbsent(p.account(), (k) -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            s[0] = s[0].add(amount(p.debit()));
            s[1] = s[1].add(amount(p.credit()));
            if (p.member()) members.merge(p.account(), 1, Integer::sum);
        }
        List<TrialLine> out = new ArrayList<>();
        sums.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach((e) -> out.add(new TrialLine(e.getKey(), e.getValue()[0].setScale(2), e.getValue()[1].setScale(2), members.getOrDefault(e.getKey(), 0))));
        return out;
    }

    // ---- reading a file -----------------------------------------------------------------------------------------

    /**
     * Reads the lines of a CSV or Excel file whose first row names the columns: Kind (GL, Savings, Share capital, Loan or Time deposit), Member (the member number),
     * Type (the account type's number), Account no (the member account's number, when the member has more than one of that type), Ledger account, Debit, Credit and
     * Note; for a loan also Status, Rate, Term (months still to run), Next due, Amount granted and Granted on; for a time deposit Rate, Term (days), Placed on,
     * Accrued interest and Certificate no (the paper certificate's old number, kept in the note: the system numbers the certificate itself). Names are matched without regard to case, spaces or punctuation. A row that cannot be read is reported with its file
     * line and left out.
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
                problems.add(at + "say in the Kind column whether the line is GL, Savings, Share capital, Loan or Time deposit.");
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
                else if (LOAN.equals(kind)) lines.add(new Line(kind, client, type, ref, null, debit, credit, note, new Terms(status(first(v, "status", "loanstatus")),
                    decimal(first(v, "rate", "interestrate"), "rate"), integer(first(v, "term", "terms", "monthstorun", "remainingterm", "months"), "term"),
                    date(first(v, "nextdue", "nextduedate", "nextpayment"), "next due date"), decimal(first(v, "amountgranted", "originalamount", "loanamount"), "amount granted"),
                    date(first(v, "grantedon", "dategranted", "releasedon"), "date granted"), null)));
                else if (TD.equals(kind)) {
                    // the system numbers a certificate itself (its account number); the paper certificate's old number is kept in the note
                    String old = first(v, "certificateno", "certificatenumber", "certno", "oldcertificateno");
                    String tdNote = note == null && old != null ? truncate("Old certificate " + old, 100) : note;
                    lines.add(new Line(kind, client, type, ref, null, debit, credit, tdNote, new Terms(null,
                        decimal(first(v, "rate", "interestrate"), "rate"), integer(first(v, "term", "terms", "termdays", "days"), "term"),
                        date(first(v, "placedon", "placementdate", "dateplaced"), "placement date"), null, null, decimal(first(v, "accruedinterest", "accrued"), "accrued interest"))));
                }
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
            case "loan", "loans", "loanreceivable", "loansreceivable" -> LOAN;
            case "td", "timedeposit", "timedeposits" -> TD;
            default -> null;
        };
    }

    /** A loan status named in a file: current, past due, in litigation, restructured, or its number. Null when blank. */
    private static Integer status(String s) {
        if (s == null) return null;
        String k = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (Map.Entry<Integer, String> e : LOAN_STATUS.entrySet()) {
            if (k.equals(e.getValue().replace(" ", "")) || k.equals(String.valueOf(e.getKey()))) return e.getKey();
        }
        if (k.equals("litigation")) return 13;
        throw new IllegalArgumentException("the loan status \"" + s + "\" is not current, past due, in litigation or restructured.");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static LocalDate date(String s, String what) {
        if (s == null) return null;
        String t = s.trim();
        try {
            return LocalDate.parse(t.length() > 10 ? t.substring(0, 10) : t);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("the " + what + " \"" + s + "\" is not a date like 2027-01-15.");
        }
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
