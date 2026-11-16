-- The posting engine (documentation/design/posting-engine.md) remembers the request key of every voucher it writes, so the same request sent twice
-- (a double click, or a retry after a lost connection) gives back the first voucher instead of posting again. One row per voucher; never changed.
-- The MySQL copy is V063. Apply with scripts/migrate-postgres.ps1.
CREATE TABLE legacy.ecis_posting_request (
    request_key varchar(100) PRIMARY KEY,
    username    varchar(60)  NOT NULL,
    branch_code integer      NOT NULL,
    tr_year     integer      NOT NULL,
    tr_module   integer      NOT NULL,
    tr_code     integer      NOT NULL,
    tr_ctlno    bigint       NOT NULL,
    doc_no      varchar(20)  NOT NULL,
    tr_date     date         NOT NULL,
    created_at  timestamp    NOT NULL
);
GRANT SELECT, INSERT ON legacy.ecis_posting_request TO coop_app;
