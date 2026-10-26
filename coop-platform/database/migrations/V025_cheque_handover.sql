-- V025: cheques travel with the cash on a drawer hand-off.
-- The old window wrote one transfer (type 31/32) with a cash pair of lines and, when cheques were given, a second pair on the
-- cheque accounts (100111/100112/100113, sub-ledger type 3). This adds the cheque total to the pending hand-off.
-- Run by an administrator: the app user has no ALTER right. MySQL 5.6; safe to run once.

ALTER TABLE ecis_cash_handover
  ADD COLUMN cheque_amount DECIMAL(15,2) NOT NULL DEFAULT 0 AFTER amount;
