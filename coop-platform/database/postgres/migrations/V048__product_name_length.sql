-- A product type's name (sltype) may be up to 100 characters: the setup screens ask for one Name and the application stores it in both name columns
-- (the old system's short name, 20, and full name, 30). Only widens; nothing is cut or changed. MySQL copy: V085.
ALTER TABLE legacy.sltype ALTER COLUMN sltypem_desc TYPE varchar(100);
ALTER TABLE legacy.sltype ALTER COLUMN sltypem_desc2 TYPE varchar(100);
