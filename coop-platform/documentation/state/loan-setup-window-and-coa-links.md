# Loan Setup window and COA Links

Moved from CLAUDE.md unchanged (one sentence per line). Keep this note current when the feature changes.

**Loan Setup window and COA Links**: Settings > Products > Loan products (and Amortization) opens `LoanSetupWindow` in its own window like the loan application (`openLoanSetupWindow`, route `#/loan-setup`, capability `loansetup-*`; falls back to a browser popup outside Tauri).
A loan type's dialog has tabs Setup and COA Links. **COA Links are editable** (`GET/POST/PUT/DELETE /api/admin/loan-setup/types/{code}/accounts`, `glcontrol` class 12): point a link at another account, add a missing one (balance side and payment class copied from what other types use for that event) or remove one that posting does not need; principal (per loan status 11-14), interest paid 23, service fee 31 and penalty 41 can be changed but never removed; the account must be an enabled detail account; reason required; audit actions `loan.type.link.add/change/remove`; `V041_loan_coa_links.sql` (UPDATE, DELETE on `glcontrol`) applied; tested in `LoanSetupTest`.
Entries already posted keep their account.
