# Chart of accounts setup

Moved from CLAUDE.md unchanged (one sentence per line). Keep this note current when the feature changes.

**Chart of accounts setup** (`ChartOfAccountsController`, `/api/accounting/chart`, administrators and accountants, `AccountsPage` = tile Back Office): add an account (detail or summary), rename it, move it under another summary account (only while it has no ledger entries) and switch it off; accounts and numbers are never deleted or changed.
Numbering follows the old chart (first digit = type 1 to 6, number starts with its parent's, parent is an enabled summary account of the same type); new rows copy the pattern of the recent legacy ones (`COAacct_tag` D/S, `COAconso_acct` = own number for detail, `COAcanGLOnly`, user and time stamped).
An account that `glcontrol`, `bankcode` or `branches` points at cannot be switched off.
Journals now refuse summary and switched-off accounts.
Reason required, audit rows `chart.account.*` (entity `coa`). `V022_chart_of_accounts.sql` (INSERT and column UPDATE on `coa`, applied), `ChartOfAccountsTest` (10 pass, rolls back).
