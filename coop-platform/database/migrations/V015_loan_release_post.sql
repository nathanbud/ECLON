-- Posting an approved loan release voucher (Cash Voucher, module 2 type 21) sets who approved and who posted it and flips its lines from
-- tag 2 (encoded) to tag 1 (posted). Only those three columns of transdt may be changed; the ledger rows it writes (sldtl, slsum, gltrans)
-- use grants that already exist (V006, V011, V014). Run by an administrator, try it on a copy (iaccs_sim) first. Idempotent.
GRANT UPDATE (DTAPPRVBY, DTPOSTBY, DTUPD_TAG) ON transdt TO 'coop_app'@'localhost';
GRANT UPDATE (DTAPPRVBY, DTPOSTBY, DTUPD_TAG) ON transdt TO 'coop_app'@'%';
