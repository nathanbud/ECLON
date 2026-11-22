-- SL Controls (Settings > Membership > SL Controls, documentation/state/sl-controls.md): which accounts open by themselves, empty, when a Membership Officer
-- approves a new member or reactivates one, per member type (client.ClientType) and account type (sltype class and code). Administrators switch them with a reason;
-- every change is audited as sl.control.changed. Starts with exactly what the application opened until now (LifecycleRules.DEFAULTS), for every branch.
-- The MySQL copy is V068. Apply with scripts/migrate-postgres.ps1.

CREATE TABLE legacy.ecis_sl_control (
    branch_code integer      NOT NULL,
    client_type integer      NOT NULL,   -- clienttype.ClientTypeID: 1 Regular Individual, 2 Associate
    slc_code    integer      NOT NULL,   -- 22 savings, 29 funds, 31 share capital
    slt_code    integer      NOT NULL,
    auto_open   boolean      NOT NULL,
    updated_by  varchar(60)  NOT NULL,
    updated_at  timestamp(3) NOT NULL,
    PRIMARY KEY (branch_code, client_type, slc_code, slt_code)
);
GRANT SELECT, INSERT, UPDATE ON legacy.ecis_sl_control TO coop_app;

INSERT INTO legacy.ecis_sl_control (branch_code, client_type, slc_code, slt_code, auto_open, updated_by, updated_at)
SELECT b.branchesid, d.client_type, d.slc_code, d.slt_code, true, 'system', CURRENT_TIMESTAMP
FROM legacy.branches b
CROSS JOIN (VALUES (1, 22, 1), (1, 29, 5), (1, 29, 6), (1, 31, 1), (2, 22, 2), (2, 31, 3)) AS d (client_type, slc_code, slt_code)
WHERE EXISTS (SELECT 1 FROM legacy.sltype t WHERE t.sltypebr_code = b.branchesid AND t.sltypeslc_code = d.slc_code AND t.sltypeslt_code = d.slt_code);
