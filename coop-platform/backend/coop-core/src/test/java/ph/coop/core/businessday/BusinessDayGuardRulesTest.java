package ph.coop.core.businessday;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Which requests are transactions, refused while no business day is open. Pure, no database. */
class BusinessDayGuardRulesTest {

    private static boolean tx(String method, String path) {
        return BusinessDayGuardRules.isTransaction(method, path);
    }

    @Test void postingsAndPreparationsInTheMoneyAreasAreTransactions() {
        for (String p : new String[] {
            "/api/front-office/savings/deposits", "/api/front-office/savings/withdrawals", "/api/front-office/loan-payments", "/api/front-office/share-payments",
            "/api/front-office/drafts", "/api/front-office/drafts/12/post", "/api/front-office/vouchers/21/2026/5/approve", "/api/front-office/handover/close",
            "/api/front-office/cheque-clearing", "/api/front-office/dividends/encode", "/api/front-office/time-deposits/ask",
            "/api/journals", "/api/journals/4/post", "/api/memos", "/api/memos/9/approve", "/api/loan-penalty-waivers/post", "/api/loan-penalty-waivers/3/decide",
            "/api/year-end-close", "/api/year-end-close/2/post", "/api/pos/sales", "/api/pos/returns/7/approve",
            "/api/inventory/movements", "/api/inventory/transfers", "/api/inventory/adjustments/1/approve", "/api/inventory/counts",
            "/api/procurement/receipts", "/api/procurement/receipts/3/reverse", "/api/assets/depreciation/run", "/api/assets/disposals/for/5" }) {
            assertTrue(tx("POST", p), p);
        }
        assertTrue(tx("PUT", "/api/front-office/drafts/12"));
        assertTrue(tx("DELETE", "/api/front-office/drafts/12"));
        assertTrue(tx("post", "/API/Journals/4/post/"), "case and a trailing slash do not matter");
    }

    @Test void lookingIsNeverATransaction() {
        assertFalse(tx("GET", "/api/front-office/drafts"));
        assertFalse(tx("GET", "/api/journals/4"));
        assertFalse(tx("HEAD", "/api/pos/sales"));
        assertFalse(tx("OPTIONS", "/api/memos"));
    }

    @Test void quotesChecksPreviewsAndPrintsStayAllowed() {
        for (String p : new String[] {
            "/api/front-office/savings/withdrawals/check", "/api/front-office/loan-payments/check", "/api/front-office/drafts/12/check",
            "/api/front-office/time-deposit-closures/quote", "/api/front-office/time-deposit-closures/interest/quote", "/api/front-office/dividends/preview",
            "/api/front-office/dividends/statement", "/api/front-office/dividends/register", "/api/loan-penalty-waivers/check", "/api/assets/depreciation/preview",
            "/api/front-office/passbooks/layout/test.pdf" }) {
            assertFalse(tx("POST", p), p);
        }
    }

    @Test void settingsAndPassbookPrintingStayAllowed() {
        assertFalse(tx("PUT", "/api/front-office/drawer-open/policy"));
        assertFalse(tx("PUT", "/api/front-office/dividends/settings/policy"));
        assertFalse(tx("PUT", "/api/front-office/passbooks/layout"));
        assertFalse(tx("POST", "/api/front-office/passbooks/5/1/3/confirm"));
        assertFalse(tx("POST", "/api/front-office/handover/snapshot/refresh"));
    }

    @Test void everythingOutsideTheMoneyAreasIsNotRefusedHere() {
        for (String p : new String[] {
            "/api/business-day/start", "/api/business-day/end", "/api/me/password", "/api/admin/users/ana/profile", "/api/members/1/5/photo",
            "/api/member-requests", "/api/members/1/5/loan-applications", "/api/inventory/products", "/api/pos/prices", "/api/procurement/requests",
            "/api/accounting/chart", "/api/accounting-periods/3/status", "/api/journalsx", "/api/pos/salesman" }) {
            assertFalse(tx("POST", p), p);
        }
    }
}
