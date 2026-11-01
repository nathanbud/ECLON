-- The notes about columns not yet mapped (V052) are longer than 255 characters.
ALTER TABLE ecis_cic_validation MODIFY message VARCHAR(1000) NOT NULL;
