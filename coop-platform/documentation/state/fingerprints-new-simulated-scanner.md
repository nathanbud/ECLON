# Fingerprints (new, simulated scanner)

Moved from CLAUDE.md unchanged (one sentence per line). Keep this note current when the feature changes.

**Fingerprints (new, simulated scanner)**: `ph.coop.core.biometric` (`FingerprintController`, `BiometricVault` AES-256-GCM, `FingerprintMatcher` + `SimulatedMatcher`, `BiometricRules`), `V040_fingerprints.sql` applied, `FingerprintDialog` from the Fingerprint chip under the photo.
Consent first (withdrawing erases all templates), scan quality at least 50, duplicate finger across members refused, templates encrypted with a key outside the database (`coop.biometric.key` or dev file `./data/biometric.key`; back it up), never returned by any endpoint, all steps audited.
Enrol/erase need edit-client plus edit-images permissions. **No real scanner or matcher yet: the browser `SimulatedScanner` and server `SimulatedMatcher` (format `SIM1`) are testing stand-ins and must not enrol real members**; a real one is a new `FingerprintMatcher` plus the scanner's local agent (contract in `fingerprintScanner.ts`). `documentation/fingerprint-design.md`; tested by `BiometricRulesTest`, `FingerprintFlowTest`.
