-- Know Your Customer: one current record per member (the valid ID presented, where the money comes from, politically-exposed status, risk rating and the next review date),
-- with an optional scan of the ID. Changes are written by the app and each one is in the audit log, so the history is there. Apply with scripts/migrate-postgres.ps1.
CREATE TABLE legacy.ecis_member_kyc (
    branch_code     integer      NOT NULL,
    client_id       bigint       NOT NULL,
    id_type         varchar(40),
    id_number       varchar(60),
    id_issuer       varchar(80),
    id_expiry       date,
    source_of_funds varchar(255),
    pep             smallint     NOT NULL DEFAULT 0,
    pep_note        varchar(255),
    risk            varchar(6),
    next_review     date,
    remarks         varchar(255),
    id_scan         bytea,
    id_scan_type    varchar(20),
    updated_by      varchar(60)  NOT NULL,
    updated_at      timestamp    NOT NULL,
    PRIMARY KEY (branch_code, client_id)
);
GRANT SELECT, INSERT, UPDATE ON legacy.ecis_member_kyc TO coop_app;
