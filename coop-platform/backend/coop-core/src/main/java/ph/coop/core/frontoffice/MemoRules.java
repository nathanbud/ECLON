package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a debit/credit memo must satisfy apart from the database. A memo is one balanced entry whose lines are on a member's savings account, a member's share capital account, or a
 * general ledger account. A debit takes money out of a member's account and a credit puts it in, as in the ledger. It must change at least one member's account (a plain ledger
 * entry is a journal), carry the reason for it, and be posted by three different people.
 */
final class MemoRules {

    static final String SAVINGS = "SAVINGS";
    static final String SHARE = "SHARE";
    static final String LEDGER = "LEDGER";
    /** The subsidiary ledger class of each kind of member account: savings 22, share capital 31. */
    static final Map<String, Integer> CLASS = Map.of(SAVINGS, 22, SHARE, 31);
    static final int MAX_LINES = 200;
    static final int DESCRIPTION_MIN = 5;
    static final int DESCRIPTION_MAX = 200;
    static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

    /** One line as typed. Exactly one of debit and credit is more than zero. */
    record Line(String kind, Integer clientId, Integer typeCode, Integer refNo, String accountCode, BigDecimal debit, BigDecimal credit, String note) {
        boolean member() { return SAVINGS.equals(kind) || SHARE.equals(kind); }

        /** Debit positive, credit negative, as the ledger stores it. */
        BigDecimal signed() { return amount(debit).signum() > 0 ? amount(debit) : amount(credit).negate(); }
    }

    private MemoRules() {}

    private static boolean tooPrecise(BigDecimal v) {
        return v.signum() != 0 && v.stripTrailingZeros().scale() > 2;
    }

    static BigDecimal amount(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Everything wrong with a memo as typed, in the words a person can act on. Empty when it is fine. */
    static List<String> problems(List<Line> lines, String description, LocalDate date, LocalDate today) {
        List<String> out = new ArrayList<>();
        String d = description == null ? "" : description.trim();
        if (d.length() < DESCRIPTION_MIN) out.add("Say why the memo is made and who authorised it (at least " + DESCRIPTION_MIN + " characters).");
        else if (d.length() > DESCRIPTION_MAX) out.add("The description can be at most " + DESCRIPTION_MAX + " characters.");
        if (date == null) out.add("Give the date of the memo.");
        else if (date.isAfter(today)) out.add("The date of the memo cannot be in the future.");
        if (lines == null || lines.size() < 2) {
            out.add("A memo needs at least two lines.");
            return out;
        }
        if (lines.size() > MAX_LINES) {
            out.add("A memo can have at most " + MAX_LINES + " lines.");
            return out;
        }
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        boolean anyMember = false;
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            String at = "Line " + (i + 1) + ": ";
            if (l == null || l.kind() == null || !(l.member() || LEDGER.equals(l.kind()))) {
                out.add(at + "choose savings, share capital or a ledger account.");
                continue;
            }
            BigDecimal dr = amount(l.debit()), cr = amount(l.credit());
            if (dr.signum() < 0 || cr.signum() < 0) out.add(at + "an amount cannot be negative.");
            else if ((dr.signum() > 0) == (cr.signum() > 0)) out.add(at + "give either a debit or a credit, not both and not neither.");
            else if (tooPrecise(dr) || tooPrecise(cr)) out.add(at + "an amount can have at most two decimals.");
            else if (dr.compareTo(MAX_AMOUNT) > 0 || cr.compareTo(MAX_AMOUNT) > 0) out.add(at + "the amount is too large.");
            if (l.member()) {
                anyMember = true;
                if (l.clientId() == null || l.clientId() <= 0 || l.typeCode() == null || l.refNo() == null) out.add(at + "choose the member and the account.");
                if (l.accountCode() != null && !l.accountCode().isBlank()) out.add(at + "a member line takes no ledger account.");
            } else if (l.accountCode() == null || l.accountCode().isBlank()) {
                out.add(at + "give the ledger account.");
            } else if (l.clientId() != null || l.typeCode() != null || l.refNo() != null) {
                out.add(at + "a ledger line takes no member.");
            }
            if (l.note() != null && l.note().length() > 100) out.add(at + "the note can be at most 100 characters.");
            debit = debit.add(dr.max(BigDecimal.ZERO));
            credit = credit.add(cr.max(BigDecimal.ZERO));
        }
        if (!anyMember) out.add("A memo must change at least one member's account. A ledger-only entry is a journal.");
        if (debit.compareTo(credit) != 0) out.add("Debits " + debit.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " and credits " + credit.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " must be equal.");
        return out;
    }

    /** The maker, the approver and the poster must be three different people. Null when they are. */
    static String separation(String creator, String approver, String poster) {
        if (approver != null && approver.equalsIgnoreCase(creator)) return "The person who approves a memo cannot be the person who made it.";
        if (poster != null && poster.equalsIgnoreCase(creator)) return "The person who posts a memo cannot be the person who made it.";
        if (poster != null && approver != null && poster.equalsIgnoreCase(approver)) return "The person who posts a memo cannot be the person who approved it.";
        return null;
    }

    /** A member's account may not be taken below zero by a memo. Null when the balance after is fine. */
    static String balanceProblem(String who, BigDecimal balanceAfter) {
        return balanceAfter.signum() < 0 ? who + " would be overdrawn by " + balanceAfter.negate().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "." : null;
    }
}
