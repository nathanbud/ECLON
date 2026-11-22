# Document management (ECIS.pdf phase 16)

The guide lists the register fields (document id, entity type and id, object key, file name, mime type, hash, classification, created by and at, retention date), names MinIO or another S3-compatible store for the files, and says: "Documents should not be freely downloadable by every authenticated user. Access must be permission-controlled."

## Built (2026-10-29)

* **Register** `ecis_document` (`V035_documents.sql`, applied) and `ecis_document_grant`; numbers `DOC-yyyy-nnnnnn`. A document belongs to a **kind of record** (MEMBER, LOAN, SUPPLIER, PURCHASE_ORDER, ASSET, JOURNAL, PRODUCT, OTHER) and its number; a member must exist.
* **Storage** behind `DocumentStore` (put, get, exists). `LocalDocumentStore` keeps files under `coop.documents.root` (default `./data/documents`, git-ignored) with an opaque object key (`yyyy/MM/uuid`, never built from the user's file name); keys cannot climb out of the folder and a key is **written once, never overwritten**. A MinIO or S3 implementation replaces it without touching the rest.
* **What may be uploaded** (`DocumentRules`): PDF, PNG, JPEG, Word (docx), Excel (xlsx), CSV and plain text, up to 10 MB; the file name is cleaned (no folders or odd characters); **the first bytes must match the extension** (a program renamed to .pdf is refused; text files must hold no binary characters); an empty file, an unknown kind of record, a bad classification or the same file twice on one record is refused.
* **Who may read** follows the classification (PUBLIC anyone signed in; INTERNAL administrators, document clerks, accountants, the roles that look after that kind of record, the uploader and those granted; CONFIDENTIAL administrators, document clerks, the uploader and those granted; RESTRICTED administrators, the uploader and those granted). Members' and loans' documents default to CONFIDENTIAL, the rest to INTERNAL. **A document one may not read is not listed and answers "no such document"**, so its existence is not revealed. Whoever uploaded a document, a document clerk or an administrator can **grant and take back access** for named users, see **who has used it** (uploads, downloads, grants, removal, from the audit log) and remove it.
* **Every download is checked and audited** (`document.download`); the file is compared with the SHA-256 taken when it was stored, and **a file that no longer matches is not released** (`document.integrity.failed`, HTTP 500). Responses are `attachment`, `nosniff` and `no-store`.
* **Versions**: a corrected or renewed file is a new version (same kind of file, different content) that supersedes the old one; the old version stays readable in the history. Grants carry over.
* **Retention**: each document carries a retention date, at least 10 years for member, loan, journal and asset documents and 5 for the others (starting defaults: the guide leaves the periods to the cooperative and to the privacy review of phase 19). **A document can be removed only after that date, and only the current version**; removing hides it, the file stays in the store.
* Role `DOCUMENT_CLERK` (granted by API). Screens: a Documents tab on the member profile, a Documents section in an asset's window, and Settings > Compliance and Audit > Documents (a search over everything the person may read; the Settings tile now also opens for document clerks).
* Tests: `DocumentRulesTest` and `DocumentFlowTest` (real files in `target/test-documents`, the real register, rolled back): classification and visibility, grants, refused uploads leave nothing, path names never reach the store, versions, retention, tampering, audit.

## Not built

* **MinIO / S3 store** (the interface is ready), **encryption at rest** and virus scanning of uploads.
* **Physical destruction** of documents past retention, legal holds and data-subject requests: the data privacy phase (19).
* Documents on loan applications, journals, purchase orders and suppliers from their own screens (the API accepts those kinds; only members and assets have the panel so far), preview in the browser (a download only), and attaching a document to a transaction as a condition of approval (workflow, phase 17).
