package ph.coop.core.asset;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.accounting.journal.JournalDrafts;
import ph.coop.core.accounting.journal.JournalLine;
import ph.coop.core.db.Db;
import ph.coop.core.inventory.InventorySupport;

/**
 * Disposals (guide phase 15): a sale, scrapping, donation or loss of an asset. A custodian or accountant asks for it; the asset must be depreciated up to the month before
 * the disposal date. A different accountant approves, which marks the asset DISPOSED and leaves a DRAFT journal (debit accumulated depreciation, the proceeds and any loss,
 * credit the cost and any gain) for the usual review and posting. Depreciation stops: the month of the disposal is not depreciated.
 */
@RestController
@RequestMapping("/api/assets/disposals")
@PreAuthorize("hasAnyRole('ASSET_CUSTODIAN','ACCOUNTANT','ADMIN')")
class AssetDisposalController {

    record DisposalIn(LocalDate disposalDate, String kind, BigDecimal proceeds, String proceedsAccount, String gainAccount, String reason) {}

    record Decision(String note) {}

    record Summary(long id, String disposalNo, long assetId, String assetNo, String asset, LocalDate disposalDate, String kind, BigDecimal proceeds, BigDecimal bookValue, BigDecimal gainLoss,
                   String reason, String status, String requestedBy, String decidedBy, String decisionNote, String journal) {}

    private static final String SUMMARY = """
            SELECT d.id, d.disposal_no, d.asset_id, a.asset_no, a.name, d.disposal_date, d.kind, d.proceeds, d.book_value, d.gain_loss, d.reason, d.status, d.requested_by, d.decided_by, d.decision_note, j.reference
            FROM ecis_asset_disposal d JOIN ecis_asset a ON a.id = d.asset_id LEFT JOIN ecis_journal j ON j.id = d.journal_id
            """;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final InventorySupport support;
    private final AssetSupport assets;
    private final JournalDrafts journals;
    private final DepreciationPlanner depreciation;

    AssetDisposalController(JdbcTemplate jdbc, Db db, InventorySupport support, AssetSupport assets, JournalDrafts journals, DepreciationPlanner depreciation) {
        this.jdbc = jdbc;
        this.db = db;
        this.support = support;
        this.assets = assets;
        this.journals = journals;
        this.depreciation = depreciation;
    }

    private static Summary map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Summary(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getDate(6).toLocalDate(), rs.getString(7), rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(10),
            rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16));
    }

    @GetMapping
    List<Summary> list(@RequestParam(required = false) String status) {
        return jdbc.query(SUMMARY + " WHERE d.branch_code = ? AND (CAST(? AS CHAR) IS NULL OR d.status = ?) ORDER BY d.id DESC LIMIT 300", AssetDisposalController::map, InventorySupport.BRANCH, status, status);
    }

    @GetMapping("/{id}")
    Summary get(@PathVariable long id) {
        List<Summary> s = jdbc.query(SUMMARY + " WHERE d.branch_code = ? AND d.id = ?", AssetDisposalController::map, InventorySupport.BRANCH, id);
        if (s.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such disposal.");
        return s.get(0);
    }

    /** Asks to dispose of an asset. */
    @PostMapping("/for/{assetId}")
    @Transactional
    Summary ask(@PathVariable long assetId, @RequestBody DisposalIn in, Authentication auth) {
        assets.requireCustodian(auth);
        List<Object[]> a = jdbc.query("""
                SELECT a.status, a.cost, a.in_service_date, a.acquisition_date, c.asset_account, c.accum_account FROM ecis_asset a JOIN ecis_asset_category c ON c.id = a.category_id WHERE a.branch_code = ? AND a.id = ? FOR UPDATE
                """, (rs, i) -> new Object[] {rs.getString(1), rs.getBigDecimal(2), rs.getDate(3).toLocalDate(), rs.getDate(4).toLocalDate()}, InventorySupport.BRANCH, assetId);
        if (a.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such asset.");
        if (!a.get(0)[0].equals("ACTIVE")) throw new ResponseStatusException(HttpStatus.CONFLICT, "This asset has already been disposed of.");
        Integer waiting = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_asset_disposal WHERE asset_id = ? AND status = 'PENDING'", Integer.class, assetId);
        if (waiting != null && waiting > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "A disposal of this asset is already waiting to be decided.");
        if (in.reason() == null || in.reason().isBlank() || in.reason().length() > 255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the reason (255 characters at most).");
        LocalDate date = in.disposalDate();
        if (date == null || date.isAfter(support.today())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the disposal date, not in the future.");
        if (date.isBefore((LocalDate) a.get(0)[3])) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The asset cannot be disposed of before it was acquired.");
        // depreciated up to the month before the disposal, and not into the month of the disposal
        LocalDate through = date.withDayOfMonth(1).minusDays(1);
        boolean behind = depreciation.due(through).stream().anyMatch((d) -> d.assetId() == assetId);
        if (behind) throw new ResponseStatusException(HttpStatus.CONFLICT, "Run depreciation up to " + through + " first, so the book value is right.");
        LocalDate last = jdbc.queryForObject("SELECT MAX(period_end) FROM ecis_asset_depreciation WHERE asset_id = ?", LocalDate.class, assetId);
        if (last != null && !date.isAfter(last)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Depreciation has already been booked to " + last + ", so the disposal must be dated after that.");
        BigDecimal booked = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM ecis_asset_depreciation WHERE asset_id = ?", BigDecimal.class, assetId);
        BigDecimal bookValue = ((BigDecimal) a.get(0)[1]).subtract(booked);
        BigDecimal proceeds = in.proceeds() == null ? BigDecimal.ZERO : in.proceeds();
        BigDecimal gainLoss = AssetRules.gainLoss(proceeds, bookValue);
        List<String> problems = AssetRules.disposalProblems(in.kind(), proceeds, in.proceedsAccount(), in.gainAccount(), gainLoss);
        if (!problems.isEmpty()) throw InventorySupport.bad(problems);
        if (proceeds.signum() > 0) assets.requireUsableAccount(InventorySupport.BRANCH, in.proceedsAccount().trim(), "Proceeds account");
        if (gainLoss.signum() > 0) assets.requireUsableAccount(InventorySupport.BRANCH, in.gainAccount().trim(), "Gain account");
        String no = support.nextNumber("ecis_asset_disposal", "disposal_no", "AD", support.today().getYear());
        long id = db.insertReturningId("""
                INSERT INTO ecis_asset_disposal (branch_code, disposal_no, asset_id, disposal_date, kind, proceeds, proceeds_account, gain_account, book_value, gain_loss, reason, status, requested_by, requested_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, "id", InventorySupport.BRANCH, no, assetId, date, in.kind(), proceeds, proceeds.signum() > 0 ? in.proceedsAccount().trim() : null, gainLoss.signum() > 0 ? in.gainAccount().trim() : null,
            bookValue, gainLoss, in.reason().trim(), auth.getName(), Timestamp.valueOf(support.now()));
        support.audit(auth, "asset.disposal.create", "ecis_asset_disposal", no, in.kind() + ", book value " + bookValue.toPlainString() + ", proceeds " + proceeds.toPlainString());
        return get(id);
    }

    private Summary decide(long id, boolean approve, Decision in, Authentication auth) {
        assets.requireAccountant(auth);
        // the row is locked on its own: PostgreSQL does not lock through the outer join of SUMMARY
        jdbc.queryForList("SELECT id FROM ecis_asset_disposal WHERE branch_code = ? AND id = ? FOR UPDATE", Long.class, InventorySupport.BRANCH, id);
        List<Summary> found = jdbc.query(SUMMARY + " WHERE d.branch_code = ? AND d.id = ?", AssetDisposalController::map, InventorySupport.BRANCH, id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such disposal.");
        Summary d = found.get(0);
        if (!d.status().equals("PENDING")) throw new ResponseStatusException(HttpStatus.CONFLICT, "This disposal is already " + d.status().toLowerCase() + ".");
        String own = InventorySupport.own(d.requestedBy(), auth.getName(), "disposal");
        if (own != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, own);
        String note = in == null || in.note() == null || in.note().isBlank() ? null : in.note().trim();
        if (!approve && note == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say why the disposal is rejected.");
        if (note != null && note.length() > 255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The note is too long (255 characters at most).");
        Long journal = null;
        if (approve) {
            List<Object[]> a = jdbc.query("""
                    SELECT a.status, a.cost, c.asset_account, c.accum_account FROM ecis_asset a JOIN ecis_asset_category c ON c.id = a.category_id WHERE a.id = ? FOR UPDATE
                    """, (rs, i) -> new Object[] {rs.getString(1), rs.getBigDecimal(2), rs.getString(3), rs.getString(4)}, d.assetId());
            if (!a.get(0)[0].equals("ACTIVE")) throw new ResponseStatusException(HttpStatus.CONFLICT, "This asset has already been disposed of.");
            BigDecimal booked = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM ecis_asset_depreciation WHERE asset_id = ?", BigDecimal.class, d.assetId());
            BigDecimal bookValue = ((BigDecimal) a.get(0)[1]).subtract(booked);
            if (bookValue.compareTo(d.bookValue()) != 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Depreciation changed since this was asked for; reject it and ask again.");
            List<Object[]> accts = jdbc.query("SELECT proceeds_account, gain_account FROM ecis_asset_disposal WHERE id = ?", (rs, i) -> new Object[] {rs.getString(1), rs.getString(2)}, id);
            List<JournalLine> lines = AssetRules.disposalLines((String) a.get(0)[2], (String) a.get(0)[3], (BigDecimal) a.get(0)[1], booked, d.proceeds(), (String) accts.get(0)[0], d.gainLoss(), (String) accts.get(0)[1], AssetRules.LOSS_ACCOUNT);
            journal = journals.create(InventorySupport.BRANCH, d.disposalNo(), d.disposalDate(), "Disposal of " + d.assetNo() + " " + d.asset() + " (" + d.kind().toLowerCase() + ")", auth.getName(), lines);
            jdbc.update("UPDATE ecis_asset SET status = 'DISPOSED', disposed_on = ? WHERE id = ?", d.disposalDate(), d.assetId());
        }
        jdbc.update("UPDATE ecis_asset_disposal SET status = ?, decided_by = ?, decided_at = ?, decision_note = ?, journal_id = ? WHERE id = ? AND status = 'PENDING'", approve ? "APPROVED" : "REJECTED", auth.getName(), Timestamp.valueOf(support.now()), note, journal, id);
        support.audit(auth, "asset.disposal." + (approve ? "approve" : "reject"), "ecis_asset_disposal", d.disposalNo(), note == null ? "book value " + d.bookValue().toPlainString() : note);
        return get(id);
    }

    @PostMapping("/{id}/approve")
    @Transactional
    Summary approve(@PathVariable long id, @RequestBody(required = false) Decision in, Authentication auth) {
        return decide(id, true, in, auth);
    }

    @PostMapping("/{id}/reject")
    @Transactional
    Summary reject(@PathVariable long id, @RequestBody Decision in, Authentication auth) {
        return decide(id, false, in, auth);
    }
}
