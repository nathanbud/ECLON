# Data privacy (ECIS.pdf phase 19, built)

Migration `V046_data_privacy.sql` (applied). Package `ph.coop.core.privacy`. Screen: Settings > Data privacy (`PrivacyPage`), for administrators and the `DATA_PROTECTION_OFFICER` role (granted like `MEMBERSHIP_OFFICER`; that role also opens Settings).

- **Requests from people** (`PrivacyRequestController`, `/api/privacy/requests`, `DSR-yyyy-nnnn`): access, correction, erasure, objection, portability, withdraw consent, other. Due date = received + `coop.privacy.request-days` (default 30; a placeholder to confirm with the DPO, not a stated legal deadline). Identity must be checked before fulfilment, a data package, or biometric erasure. Steps (check identity, start, fulfil, refuse, withdraw) are audited with the note.
- **Member data package** (`GET .../{id}/package`): JSON of the member row, accounts, ledger sums, fingerprint details (never templates), consent, photo count, notices given, change history. Each download is written to `ecis_data_export` and the audit log.
- **Erase fingerprints** (`POST .../{id}/erase-biometrics`): nulls templates, revokes, ends consent. Photos (no DELETE on `image`) and financial records are not erased by the app; the officer records why in the outcome.
- **Registers** (`PrivacyController`, `/api/privacy/registers/{key}`, columns described once in `PrivacyRegisters`): incidents (`INC-`, 72-hour `notify_by`, a constant to confirm), data sharing, impact assessments (`DPIA-`), privacy notices (one ACTIVE at a time; an active notice cannot be rewritten), retention rules, data classification. Starting retention and classification rows are marked NOT confirmed / NOT reviewed.
- **Review** (`GET /api/privacy/retention/review`): counts of documents past retention, unconfirmed rules, unreviewed classifications, overdue assessments, late incidents. Nothing is deleted by it.
- **Notice and a member** (`MemberPrivacyController`): chip "Privacy notice" in the profile header; staff with `PERM_MEMBER_EDIT` record that the current version was given and how.
- **Export log** (`ExportLogController`, `POST /api/exports/log`): report CSV downloads are logged; the Review tab lists them.
- Tests: `PrivacyFlowTest` (9), `PrivacyPage.test.tsx` (3). The final retention periods, deadlines and wording must be reviewed against the Data Privacy Act before production.
