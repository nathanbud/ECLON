# Member fingerprints

The old database holds no fingerprints (its `image` table has photos and signatures only), so this is new (`V040_fingerprints.sql`, applied).

## How it is built (industry practice)

- **Consent first, and withdrawable.** A fingerprint is sensitive personal information (Data Privacy Act). `ecis_biometric_consent` records who recorded the member's agreement, when and which wording (`BiometricRules.CONSENT_VERSION`). Withdrawing consent erases every template of the member.
- **Capture on the workstation, matching on the server.** The scanner is driven by its maker's SDK on the workstation. The app talks to it through a small local agent (contract in `fingerprintScanner.ts`: `GET /status`, `POST /capture`) that returns a standard template (ISO/IEC 19794-2) and a quality score (0 to 100, NFIQ2-style). The server never sees an image.
- **Quality gate.** A scan under 50 is refused and the finger is scanned again.
- **One person, one record.** Enrolling a finger that matches another member's, or another finger of the same member, is refused and audited (`member.fingerprint.duplicate`); the message names only the member number.
- **Encrypted at rest.** AES-256-GCM, a fresh IV each time, member and finger bound in as authenticated data; a template copied to another member or finger cannot be read. The key is never in the database: `coop.biometric.key` (base64, 32 bytes, from the environment or a key service in production) or, for development, a key file created once at `./data/biometric.key` (git-ignored). **Back the key up with the database: without it every template is unreadable.**
- **Templates never leave the server.** No endpoint returns one. Replaced and revoked templates are wiped (NULL) and only their record stays.
- **At least two fingers** are asked for, in case one is injured. Ten finger positions follow ISO/IEC 19794 numbering.
- **Who:** enrol, erase and consent need the edit-client and edit-client-images permissions (the same as photos). Anyone signed in may check a finger. Every step is audited (`member.biometric.*`, `member.fingerprint.*`) without the template.
- **Where:** the Fingerprint chip under the member's photo.

## What is a stand-in

No scanner has been bought. `SimulatedScanner` (browser) and `SimulatedMatcher` ("SIM1" templates, server) let the whole flow be exercised, and the dialog says so. **They are not biometrics and must not enrol real members.** To go live: install the scanner's SDK and local agent, add a `FingerprintMatcher` implementation for ISO/IEC 19794-2 (the maker's SDK or a certified matcher) and remove the simulator. Nothing else changes. A matcher that scans every template (1:N) is fine for thousands of members; for a large database a matcher with an index is needed.

Not built: liveness (presentation attack) detection, which comes with the scanner; using a fingerprint to authorise a transaction.

Tests: `BiometricRulesTest`, `FingerprintFlowTest` (rolls back).
