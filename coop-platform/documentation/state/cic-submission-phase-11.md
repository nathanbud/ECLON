# CIC submission (ECIS.pdf phase 11)

Settings > Compliance and Audit > CIC submission, or Menu > Compliance and records > CIC submission (administrators and accountants). Code: `ph.coop.core.cic`, `CicPage.tsx`, `api/cic.ts`; tables `ecis_cic_batch`, `ecis_cic_log` (V047), `ecis_cic_record`, `ecis_cic_validation`, `ecis_cic_response` (V052, V053).

## Steps of a batch
DRAFT (header) -> GENERATED (`POST /api/cic/batches/{id}/generate?sample=N`: borrowers as ID records, their loans as CI or CN records, from the books as at the cut-off date) -> VALIDATED (each record VALID / WARNING / ERROR, findings in words) -> APPROVED (someone other than the maker; refused while `coop.cic.mapping-confirmed` is false; errors need `excludeErrors`, which leaves those records and the loans of a left-out borrower out of the file) -> SUBMITTED (reference the CIC gave) -> RESULT (accepted / rejected counts) -> RECONCILED (counts must equal what was sent). A correction batch (type 1, `corrects_batch_id`) can follow. Every step is audited (`cic.batch.*`, `cic.file.downloaded`). `GET .../file` gives the file (`PREVIEW_` name before approval), CRLF lines, no trailing newline, delivered as `<provider>_CSDF_<yyyymmddhhmmss>.zip` holding `<same name>.txt` at its root (checked against the 32 zips the cooperative submitted in 2024-2025, which all have that shape and carry the month-end cut-off as the file date in HD, FT and column D).
Encryption, the Excel copy and the transmittal letter still answer 501: their layouts are unknown.

## Format (CSDF)
Pipe-delimited; HD, all ID, all CI, all CN, FT. Mapping `Csdf1` (version CSDF-1) was reverse-engineered from the cooperative's own November 2025 file and checked against the ledger (cut-off 2024-12-31): ID/CI/CN column letters, contract number (`0001` + loan type 2 digits + reference 10 digits), subject key (branch 2 + client 9), civil status and periodicity codes, lump-sum loans (payment mode 9) as CN type 46. Code lists come from the CIC workbook (`resources/cic/csdf-codes.tsv`, made by `scripts/gen-cic-codes.py`).

## Not confirmed (kept empty, listed in `Csdf1.UNVERIFIED`, shown as a warning on every batch)
- Address text (AG, AR) is street, barangay, city, province; the old utility's exact composition was not reproduced.
- CI columns U, Z, AA, AC, AD, AE, AF, AG, Y, J and the overdue bucket AI: the rule could not be reproduced. Income and employment columns are empty.
- Population: the old file had 7,774 of the 10,389 loans that show a balance on 2024-12-31. The other 2,615 were left out by a rule not found (they are not different by status, type or activity; included borrowers almost all have a TIN and a mobile number, excluded ones far less often, so the old utility probably dropped incomplete members). This build reports them with errors or warnings instead.
- Loan type 49: mapped to CIC type 23 here, reported as 29 in the file. 9 of 33 loan types have no CIC type (their loans get an error).
- The file's own header says November 2024 and file date 30112025, but its data is as at 31 December 2024.
To turn approval on, set `coop.cic.mapping-confirmed=true` only after the CIC accepts a generated file.

## Privacy
The real submission file holds members' personal data: it is git-ignored (`pics/CIC/**/*_CSDF_*`) and must never be committed or pasted into notes. Generating a full batch reads the whole loan ledger and takes about a minute.
Tests: `CsdfTest`, `CicValidatorTest`, `CicControllerTest`, `CicWorkflowTest`, `CicPage.test.tsx`.

