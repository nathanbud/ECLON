# Fixed assets (ECIS.pdf phase 15)

The guide lists: asset, asset_category, asset_acquisition, asset_transfer, asset_depreciation, asset_maintenance, asset_disposal, and "support depreciation schedules and accounting integration".

## What the old books show

There is no asset register or depreciation schedule in the old database. Depreciation was booked by hand: 411 lines in 2025 and 2026 on **50328 "Depreciation & amortization"**, always in General Journals (module 1 type 1) at month end, with explanations like "depreciation for the month of January 2026", one journal per branch or asset group, the other side the accumulated depreciation accounts (for example 1160112 office equipment, 1160202 building, 1160702 furniture and fixtures). Losses on the sale of property go to **6004108**. So the register is new (`V034_fixed_assets.sql`, applied), and the accounting integration is **draft journals through the existing journal flow**, which is exactly where the old entries lived.

## Built (2026-10-28)

* **Categories** (`/api/assets/categories`): a kind of asset with the three accounts it uses (cost, accumulated depreciation, depreciation expense; each must be an enabled detail account of the chart and the three must differ), a default useful life and a default salvage percent. Accountants keep them.
* **Register** (`/api/assets`, `FA-yyyy-nnnn`): tag (the sticker number, unique), name, category, serial number, location, acquisition date, in-service date, cost, salvage value, life in months (category defaults fill the blanks), optional supplier and reference (purchase order, invoice, deed). Cost, dates and life never change after registration. Registered by a custodian (`ASSET_CUSTODIAN`) or an accountant.
* **Depreciation** (`/api/assets/depreciation`): straight line, monthly, **starting the month after the asset goes into service** (the owner's rule for the first month is not known; this is the common one). Each month's amount is the difference of two cumulative figures (depreciable amount x months / life, rounded to the centavo), so rounding never drifts and the last month lands on the exact total. The accountant picks a month end, previews, and runs: every active asset gets a row for each month it has not been depreciated for (a missed month is caught up), and **one DRAFT journal per month** (`DEP-yyyy-MM`) is left through `JournalDrafts`: debit each category's expense account, credit its accumulated depreciation account. Someone else reviews, approves and posts it on the Journals screen, as before. `ecis_asset_depreciation` is insert-only with a unique key on asset and month. An asset's net book value is cost less depreciation booked; the amount actually posted is shown beside it.
* **Transfers**: a custodian asks to move an asset to another location; a different accountant approves (a rejection needs a reason); the location changes only then.
* **Maintenance**: recorded as it is done (what, cost, who, next due); assets whose latest record says work is due within 30 days show a warning on the register.
* **Disposals** (`AD-yyyy-nnnn`): sale, scrap, donation or loss. The asset must be depreciated up to the month before the disposal date and the date must come after the last booked month. The request fixes the book value; a **different accountant approves**, which marks the asset DISPOSED and leaves a DRAFT journal (reference = the disposal number): debit accumulated depreciation, the proceeds (to the account the requester names) and any loss (6004108), credit the cost and any gain (to the account named). It balances by construction.
* Roles: `ASSET_CUSTODIAN` (granted by API) keeps the register, asks for transfers and disposals and records maintenance; `ACCOUNTANT` also sets categories, runs depreciation and decides transfers and disposals; nobody decides their own request. Audit rows `asset.*`.
* Screen: the Fixed Assets tile (it took the unbuilt Treasury placeholder's cell, the dashboard grid has no free one): Register (with an asset window showing depreciation, transfers and maintenance), Transfers, Disposals, Depreciation, Categories.
* Tests: `AssetRulesTest` (pure, including that every disposal journal balances) and `AssetFlowTest` (real database, rolled back).

## Not built

* Acquisition does not post anything: buying an asset is booked by whatever paid for it (cash voucher, journal, and later supplier invoices), and the register just records it. Linking a register entry to a purchase order or goods receipt line is only a typed reference.
* Other depreciation methods (declining balance, units of production), a partial first month, revaluation and impairment.
* Component assets, asset photos or documents, barcode printing, and a physical verification count like the stock count.
* A report of the register against the ledger balances of the asset accounts (the totals should agree once the opening register is loaded).
* Loading the existing assets with their accumulated depreciation to date (an opening balance import), which the accountants must do before the first run, otherwise the first run catches up from the in-service date.
