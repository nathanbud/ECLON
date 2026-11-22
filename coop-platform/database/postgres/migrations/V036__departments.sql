-- Departments (Settings > Organization > Departments, DepartmentController): the old table dept (DeptID, DeptDesc, DeptParentID, DeptTIN, DeptAddress,
-- DeptIsDisabled). A member (client.ClientDEPT, given department 2 when none is chosen), a user (access.AccessDepartment) and the books (gltrans, transdt,
-- transck, loanapp, holidays and others) point to it, so a database without departments cannot register members. Administrators add a department,
-- change its name, parent, TIN and address, switch it off or on, and delete one nothing has ever used; the number never changes. Every change needs a
-- reason and is audited. The MySQL copy is V073. Applied with scripts/migrate-postgres.ps1, or from the application (Apply them now).
GRANT INSERT, DELETE ON legacy.dept TO coop_app;
GRANT UPDATE (deptdesc, deptparentid, depttin, deptaddress, deptisdisabled) ON legacy.dept TO coop_app;
