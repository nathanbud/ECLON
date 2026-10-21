package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;
import ph.coop.core.frontoffice.FrontOfficeAccess.Perms;
import ph.coop.core.frontoffice.HandoverRules.Drawer;
import ph.coop.core.frontoffice.MemberLedgerPoster.Person;

/**
 * Moves cash between the vault and a bank account, as the old system's Bank Deposit (module 5, type 33) and Bank Withdrawal (type 35) did; see
 * documentation/bank-cash-posting-map.md. Only the head cashier does it, it posts at once, and each posting is two transdt lines (vault, bank), two gltrans
 * lines and the count by note and coin in denomsum / denomdtl. Deposits of cheques and collections (event 51) are not built.
 */
@RestController
@RequestMapping("/api/front-office/bank-cash")
class BankCashController {

    static final int MODULE = 5, DEPOSIT = 33, WITHDRAWAL = 35, BANK_CLASS = 11, BANK_TYPE = 2;

    record BankRow(int bankId, String name, String ledgerAccount, int event) {}

    record Request(int bankId, BigDecimal amount, Map<Integer, Integer> counts, String remarks) {}

    record Posted(int year, long controlNo, String documentNo, String bank, BigDecimal amount, BigDecimal vaultAfter) {}

    private final FrontOfficeAccess access;
    private final MemberLedgerPoster poster;
    private final JdbcTemplate jdbc;
    private final Db db;
    private final Clock clock;
    private final DrawerBalances balances;
    private final TransactionTemplate tx;

    BankCashController(FrontOfficeAccess access, MemberLedgerPoster poster, JdbcTemplate jdbc, Db db, Clock clock, DrawerBalances balances, PlatformTransactionManager txm) {
        this.access = access;
        this.poster = poster;
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
        this.balances = balances;
        this.tx = new TransactionTemplate(txm);
    }

    /** The bank accounts the ledger knows: glcontrol rows of class 11 type 2 with event 61 (current) or 62 (savings), the number being the bank's id in bankcode. */
    @GetMapping("/banks")
    List<BankRow> banks(Authentication auth) {
        Perms perms = access.of(auth);
        return banksOf(perms.branch());
    }

    private List<BankRow> banksOf(int branch) {
        return jdbc.query("""
                SELECT g.GLControlCTS_NUM, COALESCE(NULLIF(b.BCDesc, ''), CONCAT('Bank ', g.GLControlCTS_NUM)), g.GLControlACCT_CODE, g.GLControlSLE_CODE
                FROM glcontrol g LEFT JOIN bankcode b ON b.BCBR_CODE = g.GLControlBR_CODE AND b.BCID = g.GLControlCTS_NUM
                WHERE g.GLControlBR_CODE = ? AND g.GLControlSLC_CODE = ? AND g.GLControlSLT_CODE = ? AND g.GLControlSLE_CODE IN (61, 62)
                ORDER BY 2, 1
                """, (rs, i) -> new BankRow(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4)), branch, BANK_CLASS, BANK_TYPE);
    }

    @PostMapping("/deposits")
    Posted deposit(@RequestBody Request req, Authentication auth) {
        return post(req, auth, DEPOSIT);
    }

    @PostMapping("/withdrawals")
    Posted withdraw(@RequestBody Request req, Authentication auth) {
        return post(req, auth, WITHDRAWAL);
    }

    private Posted post(Request req, Authentication auth, int code) {
        Perms perms = access.of(auth);
        access.requireNew(perms, code);
        Person p = poster.person(auth.getName());
        if (!p.head()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the head cashier moves cash between the vault and a bank.");
        if (p.dept() <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Your account has no department set, so this cannot be posted.");
        if (req.amount() == null || req.amount().signum() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter an amount above zero.");
        if (req.amount().stripTrailingZeros().scale() > 2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The amount can have at most 2 decimal places.");
        BigDecimal amount = req.amount().setScale(2, java.math.RoundingMode.UNNECESSARY);
        Map<Integer, Integer> counts = req.counts() == null ? Map.of() : req.counts();
        Map<Integer, BigDecimal> faces = new LinkedHashMap<>();
        jdbc.query("SELECT ID, AMOUNT FROM denomtype ORDER BY ID", (rs) -> { faces.put(rs.getInt(1), rs.getBigDecimal(2)); });
        List<String> problems = new java.util.ArrayList<>();
        if (counts.isEmpty()) problems.add("Count the notes and coins.");
        else {
            BigDecimal counted = HandoverRules.total(counts, faces);
            if (counted.compareTo(amount) != 0) problems.add("The notes and coins add up to " + counted.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + ", not " + amount.toPlainString() + ".");
            if (counts.keySet().stream().anyMatch((k) -> !faces.containsKey(k)) || counts.values().stream().anyMatch((v) -> v < 0)) problems.add("The count has an unknown note or a negative number.");
        }
        if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", problems));

        return tx.execute((st) -> {
            int branch = p.branch();
            BankRow bank = banksOf(branch).stream().filter((b) -> b.bankId() == req.bankId()).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That bank account is not set up in the ledger."));
            Drawer vault = new Drawer(HandoverRules.VAULT, HandoverRules.VAULT_NUMBER);
            BigDecimal vaultBefore = balances.of(branch, vault);
            boolean in = code == DEPOSIT; // a deposit takes cash out of the vault
            if (in && vaultBefore.compareTo(amount) < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The vault holds only " + vaultBefore.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + ".");
            }
            if (!in) {
                BigDecimal book = jdbc.queryForObject("SELECT COALESCE(SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT), 0) FROM gltrans WHERE GLTransBR_CODE = ? AND GLTransACCT_CODE = ?", BigDecimal.class, branch, bank.ledgerAccount());
                if (book.compareTo(amount) < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The ledger shows only " + book.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " in " + bank.name() + ".");
            }
            LocalDateTime now = LocalDateTime.now(clock).withNano(0);
            LocalDate day = now.toLocalDate();
            int year = day.getYear();
            long ctl = db.nextControlNumber(branch, MODULE, code, year);
            String docNo = SharePaymentRules.documentNo(null, ctl);
            String explanation = ((in ? "CIB-" : "BW ") + bank.name() + (req.remarks() == null || req.remarks().isBlank() ? "" : " " + req.remarks().trim()));
            explanation = explanation.substring(0, Math.min(explanation.length(), 200));
            jdbc.update("""
                    INSERT INTO transsum (TR_BRCODE, TR_YEAR, TR_MODULE, TR_CODE, TR_CTLNO, TR_DOCNO, TR_DATE, TR_TIME, BATCH_NO, EXPLANATION, IS_FO, DATETIMEADDED)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                    """, branch, year, MODULE, code, ctl, docNo, day.toString(), now.format(DateTimeFormatter.ofPattern("HH:mm:ss")), day.format(DateTimeFormatter.BASIC_ISO_DATE),
                explanation, Timestamp.valueOf(now));
            String vaultAccount = HandoverRules.account(HandoverRules.VAULT);
            String sql = """
                    INSERT INTO transdt (DTTR_BRCODE, DTTR_YEAR, DTTR_MODULE, DTTR_CODE, DTTR_CTLNO, DTSEQ_NO, DTDEPT, DTACCT_CODE, DTSLC_CODE, DTSLT_CODE, DTREF_NO,
                      DTSLE_CODE, DTCTS_NUM, DTCOCI_TYPE, DTAMT, DTENCDBY, DTPOSTBY, DTUPD_TAG, DTCHECKISRELEASE, DTISTRANSIT, DTISUNDENTIFIED)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 11, ?, 1, ?, ?, 1, ?, ?, ?, 1, 0, 0, 0)
                    """;
            // deposit: the vault is credited and the bank debited; withdrawal: the other way round
            BigDecimal vaultAmount = in ? amount.negate() : amount;
            jdbc.update(sql, branch, year, MODULE, code, ctl, 0, p.dept(), vaultAccount, 1, HandoverRules.VAULT, HandoverRules.VAULT_NUMBER, vaultAmount, p.id(), p.id());
            jdbc.update(sql, branch, year, MODULE, code, ctl, 1, p.dept(), bank.ledgerAccount(), BANK_TYPE, bank.event(), bank.bankId(), vaultAmount.negate(), p.id(), p.id());
            Timestamp stamp = Timestamp.valueOf(now);
            poster.writeGlLine(branch, vaultAccount, day, code, p.dept(), vaultAmount, stamp);
            poster.writeGlLine(branch, bank.ledgerAccount(), day, code, p.dept(), vaultAmount.negate(), stamp);
            long denomSum = db.insertReturningId("INSERT INTO denomsum (BR_CODE, TR_CODE, TR_CTLNO, FROM_USERID, TO_USERID) VALUES (?, ?, ?, ?, ?)", "ID",
                branch, code, ctl, in ? null : p.id(), in ? p.id() : null);
            for (int typeId : faces.keySet()) jdbc.update("INSERT INTO denomdtl (DENOMSUMID, DENOMTYPEID, QTY) VALUES (?, ?, ?)", denomSum, typeId, counts.getOrDefault(typeId, 0));
            balances.changed();
            jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value) VALUES (?, ?, ?, 'transsum', ?, ?)",
                stamp, auth.getName(), in ? "bank.deposit.post" : "bank.withdrawal.post", branch + "/" + year + "/" + MODULE + "/" + code + "/" + ctl,
                bank.name() + " " + amount.toPlainString() + " doc " + docNo);
            return new Posted(year, ctl, docNo, bank.name(), amount, in ? vaultBefore.subtract(amount) : vaultBefore.add(amount));
        });
    }
}
