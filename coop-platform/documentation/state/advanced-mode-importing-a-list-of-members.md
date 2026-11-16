# Advanced mode: importing a list of members

Moved from CLAUDE.md unchanged (one sentence per line). Keep this note current when the feature changes.

**Advanced mode: importing a list of members** (`MemberImportController`, `/api/member-imports`, `SpreadsheetReader` reads CSV and .xlsx without a new library, `V043_member_import.sql` applied: table `ecis_member_import` and `ecis_member_request.batch_id`; `AdvancedRegistration` screen, button on the Client Profile finder screen, administrators only; `SpreadsheetReaderTest`, `MemberImportTest`).
The list is checked row by row with the single-registration rules (required fields, age, Gender/CivilStatus as names or codes, Excel dates, same name + birth date already on file, waiting or earlier in the file); preview saves nothing; the upload makes one batch of pending REGISTER requests (hidden from the single-request list); a Membership Officer other than the uploader approves the whole batch from Approvals (members opened one by one through the normal approval, failures are left as separate requests and reported) or rejects it.
No photos or fingerprints in a list.
Up to 5,000 rows, 5 MB.
