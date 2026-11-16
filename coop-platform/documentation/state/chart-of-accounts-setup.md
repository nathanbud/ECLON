# Chart of accounts setup

Moved from CLAUDE.md unchanged (one sentence per line). Keep this note current when the feature changes.

**Chart of accounts setup** (`ChartOfAccountsController`, `/api/accounting/chart`, administrators and accountants, `AccountsPage` = tile Back Office): add an account (detail or summary), rename it, move it under another summary account (only while it has no ledger entries) and switch it off; accounts and numbers are never deleted or changed.
Numbering follows the old chart (first digit = type 1 to 6, number starts with its parent's, parent is an enabled summary account of the same type); new rows copy the pattern of the recent legacy ones (`COAacct_tag` D/S, `COAconso_acct` = own number for detail, `COAcanGLOnly`, user and time stamped).
An account that `glcontrol`, `bankcode` or `branches` points at cannot be switched off.
Journals now refuse summary and switched-off accounts.
Reason required, audit rows `chart.account.*` (entity `coa`). `V022_chart_of_accounts.sql` (INSERT and column UPDATE on `coa`, applied), `ChartOfAccountsTest` (10 pass, rolls back).
**Advanced mode: uploading a list of accounts** (`ChartImportController`, `/api/accounting/chart/import`, **administrators only**; rules in the pure `ChartImportRules`; screen `ChartUpload`, button Upload from Excel on the Chart of Accounts screen).
The file is CSV or .xlsx (read by `SpreadsheetReader`, first sheet, up to 5,000 rows and 5 MB) with the columns Account number, Account name, Kind (Heading or Account; Account if empty), Under (the heading's number) and Journal entries (Yes or No); the old column names (`COAID`, `COADesc`, `COAacct_sum`) are understood too, and unknown columns are reported and ignored.
`POST /preview` checks every row and saves nothing; `POST` (file, `reason`, `skipErrors`) adds the new accounts in one transaction through the same insert as a single account, with one `chart.account.create` audit row each (reason plus the file name) and one `chart.import` row for the file (entity id `<branch>/upload`).
A list **only adds**: a number already in the chart is reported as already there and left as it is, so nothing is renamed, moved, switched off or deleted by a file.
With Under empty an account goes under the longest heading its number starts with (in the chart or in the same file); a heading with none above it becomes a new top-level heading, so a whole chart can be loaded into an empty database; an account (not a heading) with no heading above it is refused.
The other rules are those of a single account: digits starting with 1 to 6, the number starts with its heading's, the heading is a heading and in use, a name is used once under a heading, no number twice in a file; a row under a heading that has a problem is refused with it.
`GET /template` is a blank workbook (Accounts sheet with the column names only, Guide sheet with an example), `GET /template.csv` the same as CSV.
Unlike the member list there is no second approver, because adding one account by hand has none either: the control is the administrator role, the required reason and the audit log.
Tested by `ChartImportRulesTest` (7), `ChartImportTest` (6, rolls back) and `ChartUpload.test.tsx`.
