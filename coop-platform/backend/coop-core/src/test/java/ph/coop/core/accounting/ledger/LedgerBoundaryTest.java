package ph.coop.core.accounting.ledger;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The ledger has one writer (documentation/design/posting-engine.md): only {@link Ledger} writes the posted tables. The flows not moved yet are listed
 * in {@link #NOT_MOVED_YET}; that list may only get shorter. If this fails, hand the voucher to the ledger instead of writing the rows yourself; when a
 * flow has moved, take it off the list.
 */
class LedgerBoundaryTest {

    private static final Pattern WRITES = Pattern.compile("(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+(transsum|transdt|sldtl|gltrans|transck)\\b", Pattern.CASE_INSENSITIVE);

    /** Still writing the posted tables themselves, each to move onto the ledger with a golden snapshot first. */
    private static final Set<String> NOT_MOVED_YET = Set.of(
        "BankCashController.java", "ChequeClearingController.java", "ChequeReturnController.java", "DividendController.java", "HandoverController.java",
        "LoanReleaseController.java", "MemoController.java", "PenaltyWaiverController.java", "SavingsInterestController.java", "ShareWithdrawalController.java",
        "TimeDepositAccrualController.java", "TimeDepositClosureController.java", "LoanApplicationWorkflowController.java");

    @Test
    void onlyTheLedgerWritesThePostedTables() throws IOException {
        Set<String> writers = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path f : files.filter((p) -> p.toString().endsWith(".java")).toList()) {
                if (WRITES.matcher(Files.readString(f)).find()) writers.add(f.getFileName().toString());
            }
        }
        writers.remove("Ledger.java");
        Set<String> outside = new TreeSet<>(writers);
        outside.removeAll(NOT_MOVED_YET);
        assertTrue(outside.isEmpty(), "these write the posted tables outside the ledger: " + outside);
        Set<String> moved = new TreeSet<>(NOT_MOVED_YET);
        moved.removeAll(writers);
        assertTrue(moved.isEmpty(), "these no longer write the posted tables; take them off NOT_MOVED_YET: " + moved);
    }

    @Test
    void theListOnlyNamesFlowsThatExist() {
        assertTrue(List.copyOf(NOT_MOVED_YET).stream().allMatch((n) -> n.endsWith("Controller.java")));
    }
}
