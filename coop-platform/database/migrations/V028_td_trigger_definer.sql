-- The old trigger on td (copied with the database) was created by 'root'@'%', an account that does not exist on this server, so every INSERT into td fails with
-- "The user specified as a definer ('root'@'%') does not exist". It only sets the certificate number equal to the reference number before an insert.
-- This re-creates it in iaccsbsucmpc01 only, with the same body and the definer 'root'@'localhost'. Run by an administrator as root. Safe to run again.
DROP TRIGGER IF EXISTS iaccsbsucmpc01.update_td_certno;

DELIMITER $$
CREATE DEFINER = 'root'@'localhost' TRIGGER iaccsbsucmpc01.update_td_certno BEFORE INSERT ON iaccsbsucmpc01.td
FOR EACH ROW
BEGIN
SET new.TDCERT_NUM = new.TDREF_NO;
END$$
DELIMITER ;
