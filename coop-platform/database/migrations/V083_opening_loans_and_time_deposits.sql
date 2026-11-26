-- Opening balances for loans and time deposits: the MySQL copy of PostgreSQL V046 (see there). Not applied while the MySQL server is read-only.
ALTER TABLE ecis_opening_line MODIFY kind varchar(10) NOT NULL;
ALTER TABLE ecis_opening_line
    ADD COLUMN status_code     int NULL,
    ADD COLUMN rate            decimal(9,4) NULL,
    ADD COLUMN terms           int NULL,
    ADD COLUMN start_date      date NULL,
    ADD COLUMN original_amount decimal(15,2) NULL,
    ADD COLUMN granted_on      date NULL,
    ADD COLUMN accrued         decimal(15,2) NULL,
    ADD COLUMN cert_no         varchar(20) NULL;
GRANT UPDATE (ref_no) ON ecis_opening_line TO 'coop_app'@'localhost';
GRANT UPDATE (ref_no) ON ecis_opening_line TO 'coop_app'@'%';
