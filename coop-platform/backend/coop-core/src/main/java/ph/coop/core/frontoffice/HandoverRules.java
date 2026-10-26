package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rules for handing cash from one drawer to another, as the old system did it: the head cashier takes cash from the vault,
 * hands it to tellers, takes it back at the end of the day, and puts it back in the vault. No database access here.
 */
final class HandoverRules {

    static final int VAULT = 50;
    static final int CASHIER = 52;
    static final int TELLER = 53;
    /** The vault has one drawer, numbered 99 in the old setup. */
    static final int VAULT_NUMBER = 99;

    private HandoverRules() {}

    /** A drawer: what kind (vault, cashier, teller) and its number. */
    record Drawer(int event, int number) {
        boolean isVault() { return event == VAULT; }
        String label() { return (event == VAULT ? "Vault" : event == CASHIER ? "Cashier" : "Teller") + " " + number; }
    }

    /** The ledger account each kind of drawer keeps its cash in. */
    static String account(int event) {
        return switch (event) {
            case VAULT -> "10001";   // Cash in vault
            case CASHIER -> "10002"; // Cash on hand - cashiers
            case TELLER -> "10003";  // Cash on hand - tellers
            default -> throw new IllegalArgumentException("Unknown drawer type " + event);
        };
    }

    /** The account each kind of drawer keeps its cheques in ("Coci"); the vault's cheques carry event 51 instead of 50. */
    static String chequeAccount(int event) {
        return switch (event) {
            case VAULT -> "100111";
            case CASHIER -> "100112";
            case TELLER -> "100113";
            default -> throw new IllegalArgumentException("Unknown drawer type " + event);
        };
    }

    static int chequeEvent(int event) { return event == VAULT ? 51 : event; }

    /** Legacy type: 31 "Cash Transfer From" when the cash comes out of the vault, otherwise 32 "Cash Transfer To". */
    static int trCode(Drawer from) { return from.isVault() ? 31 : 32; }

    /** Anything that touches the vault is posted at once by the head cashier; a hand-off between people waits to be counted. */
    static boolean postsAtOnce(Drawer from, Drawer to) { return from.isVault() || to.isVault(); }

    /** Problems with a hand-off, empty when it is fine. {@code denomValues} maps each denomination id to its face value. */
    static List<String> check(Drawer from, Drawer to, BigDecimal amount, Map<Integer, Integer> counts, Map<Integer, BigDecimal> denomValues, boolean headCashier) {
        return check(from, to, amount, counts, denomValues, headCashier, BigDecimal.ZERO);
    }

    /**
     * As above, with cheques handed over together with the cash (the old window put both on one transfer). The cash may be zero when
     * cheques are given. Cheques are only counted as an amount; they never leave the vault except in a bank deposit.
     */
    static List<String> check(Drawer from, Drawer to, BigDecimal amount, Map<Integer, Integer> counts, Map<Integer, BigDecimal> denomValues, boolean headCashier, BigDecimal cheques) {
        List<String> p = new ArrayList<>();
        boolean withCheques = cheques != null && cheques.signum() > 0;
        if (cheques != null && cheques.signum() < 0) p.add("The cheque amount cannot be negative.");
        if (cheques != null && cheques.scale() > 2 && cheques.stripTrailingZeros().scale() > 2) p.add("The cheque amount can have at most 2 decimal places.");
        if (withCheques && from.isVault()) p.add("Cheques leave the vault only in a bank deposit.");
        if (withCheques && amount != null && amount.signum() == 0) amount = null; // cheques only: no cash to check below
        boolean chequesOnly = withCheques && amount == null;
        if (from.equals(to)) p.add("Choose a different drawer to give the cash to.");
        if (from.event() != VAULT && from.event() != CASHIER && from.event() != TELLER) p.add("Cash can only be given from the vault, a cashier or a teller.");
        if (to.event() != VAULT && to.event() != CASHIER && to.event() != TELLER) p.add("Cash can only go to the vault, a cashier or a teller.");
        if (from.isVault() && to.isVault()) p.add("The vault cannot give cash to itself.");
        if ((from.isVault() || to.isVault()) && !headCashier) p.add("Only the head cashier can move cash to or from the vault.");
        if ((from.isVault() && to.event() != CASHIER) || (to.isVault() && from.event() != CASHIER))
            p.add("Cash moves between the vault and the head cashier, not tellers.");
        if (chequesOnly) {
            // nothing to say about the cash
        } else if (amount == null || amount.signum() <= 0) {
            p.add(withCheques ? "Enter the cash given, or leave it at zero." : "Enter an amount above zero.");
        } else if (amount.scale() > 2 && amount.stripTrailingZeros().scale() > 2) {
            p.add("The amount can have at most 2 decimal places.");
        }
        BigDecimal counted = BigDecimal.ZERO;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            BigDecimal face = denomValues.get(e.getKey());
            if (face == null) { p.add("Unknown note or coin " + e.getKey() + "."); continue; }
            if (e.getValue() < 0) { p.add("A count cannot be negative."); continue; }
            counted = counted.add(face.multiply(BigDecimal.valueOf(e.getValue())));
        }
        if (chequesOnly && counted.signum() != 0) p.add("Only cheques are given, so there should be no notes or coins.");
        if (amount != null && amount.signum() > 0 && counted.compareTo(amount) != 0)
            p.add("The notes and coins add up to " + counted.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + ", not " + amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + ".");
        return p;
    }

    /** The total of a count, to compare what the receiver counted with what was given. */
    static BigDecimal total(Map<Integer, Integer> counts, Map<Integer, BigDecimal> denomValues) {
        BigDecimal t = BigDecimal.ZERO;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            BigDecimal face = denomValues.get(e.getKey());
            if (face != null && e.getValue() > 0) t = t.add(face.multiply(BigDecimal.valueOf(e.getValue())));
        }
        return t;
    }
}
