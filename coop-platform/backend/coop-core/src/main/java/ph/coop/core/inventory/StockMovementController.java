package ph.coop.core.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.inventory.StockService.Move;
import ph.coop.core.inventory.StockService.Posted;

/**
 * Stock coming in and going out by hand (guide phase 13): RECEIVE (with the cost of a unit), ISSUE, RETURN to stock, and TRANSFER between warehouses. Done by a
 * storekeeper and posted at once as ledger rows; SALE belongs to the point of sale, and ADJUSTMENT, DAMAGE and EXPIRY go through an approved stock adjustment.
 */
@RestController
@RequestMapping("/api/inventory")
@PreAuthorize("hasAnyRole('STOREKEEPER','ADMIN')")
class StockMovementController {

    record MoveIn(String type, long productId, long warehouseId, String batchNo, LocalDate expiryDate, BigDecimal quantity, BigDecimal unitCost, String reference, String remarks) {}

    record TransferIn(long productId, long fromWarehouseId, long toWarehouseId, String batchNo, BigDecimal quantity, String remarks) {}

    record Result(String txnNo, BigDecimal unitCost) {}

    record TransferResult(String outTxnNo, String inTxnNo, BigDecimal unitCost) {}

    private final StockService stock;
    private final InventorySupport support;

    StockMovementController(StockService stock, InventorySupport support) {
        this.stock = stock;
        this.support = support;
    }

    @PostMapping("/movements")
    @Transactional
    Result move(@RequestBody MoveIn in, Authentication auth) {
        support.requireKeeper(auth);
        if (in.type() == null || !(in.type().equals("RECEIVE") || in.type().equals("ISSUE") || in.type().equals("RETURN")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "By hand only RECEIVE, ISSUE and RETURN can be recorded; adjustments, damage and expiry need an approved adjustment, sales come from the point of sale.");
        var problems = InventoryRules.quantityProblems(in.quantity());
        if (!problems.isEmpty()) throw InventorySupport.bad(problems);
        BigDecimal signed = in.quantity().multiply(BigDecimal.valueOf(InventoryRules.sign(in.type())));
        Posted p = stock.post(new Move(in.type(), in.productId(), in.warehouseId(), in.batchNo(), in.expiryDate(), signed, in.unitCost(), "MANUAL", in.reference(), in.remarks()), auth.getName());
        support.audit(auth, "inventory." + in.type().toLowerCase(), "ecis_inventory_txn", p.txnNo(), "product " + in.productId() + ", warehouse " + in.warehouseId() + ", " + signed.stripTrailingZeros().toPlainString());
        return new Result(p.txnNo(), p.unitCost());
    }

    /** Moves stock from one warehouse to another: an out row and an in row, at the product's current cost, in one transaction. */
    @PostMapping("/transfers")
    @Transactional
    TransferResult transfer(@RequestBody TransferIn in, Authentication auth) {
        support.requireKeeper(auth);
        if (in.fromWarehouseId() == in.toWarehouseId()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose two different warehouses.");
        var problems = InventoryRules.quantityProblems(in.quantity());
        if (!problems.isEmpty()) throw InventorySupport.bad(problems);
        Posted out = stock.post(new Move("TRANSFER", in.productId(), in.fromWarehouseId(), in.batchNo(), null, in.quantity().negate(), null, "TRANSFER", null, in.remarks()), auth.getName());
        Posted inn = stock.post(new Move("TRANSFER", in.productId(), in.toWarehouseId(), in.batchNo(), null, in.quantity(), null, "TRANSFER", out.txnNo(), in.remarks()), auth.getName());
        support.audit(auth, "inventory.transfer", "ecis_inventory_txn", out.txnNo(), "product " + in.productId() + " " + in.fromWarehouseId() + " -> " + in.toWarehouseId() + ", " + in.quantity().stripTrailingZeros().toPlainString() + ", in " + inn.txnNo());
        return new TransferResult(out.txnNo(), inn.txnNo(), out.unitCost());
    }
}
