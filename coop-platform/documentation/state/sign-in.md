# Sign-in

Moved from CLAUDE.md unchanged (one sentence per line). Keep this note current when the feature changes.

**Sign-in**: people come from the legacy `access` table (enabled users only).
Legacy passwords (`access.AccessPassword`) use an encrypted format that cannot be verified, so each person gets a new password, stored as a bcrypt hash in `ecis_user_credential`.
An administrator can only issue a **temporary** password (`PUT /api/admin/users/{username}/password` with `validityHours` 1, 4, 24 (default) or 72): the system generates it (12 characters, no look-alikes), the reply shows it once, it is stored only as a hash (`ecis_user_credential.must_change`, `expires_at`, `V023_temporary_passwords.sql`, applied), and the audit row never holds it.
Nobody issues one for themselves and only a SUPERADMIN issues one for an administrator.
Signing in with it works only until it expires (a 401 with `X-Auth-Reason: TEMPORARY_PASSWORD_EXPIRED`), and until the person replaces it (`POST /api/me/password`, min 8 characters with letters and numbers, not the username, not a common password) every other endpoint answers 403 `PASSWORD_CHANGE_REQUIRED` (`TemporaryPasswordGate` in `SecurityConfig`); the desktop shows `ChangePasswordScreen` instead of the app.
Issuing a new one also ends any sign-in already open for that person.
Tested by `TemporaryPasswordTest` and `TemporaryPasswordSignInTest`. **Sign-in lockout:** 5 wrong passwords for a name within 15 minutes lock it for 15 minutes (`LoginAttempts`, in memory so a restart clears it; unknown names are counted too; a locked name is refused even with the right password, 401 with `X-Auth-Reason: ACCOUNT_LOCKED` and `Retry-After`; audit rows `signin.locked` and `signin.unlocked`); administrators see and lift locks from the Users screen (`GET /api/admin/locked-users`, `POST /api/admin/users/{username}/unlock`); tested by `LoginLockTest`.
The env-configured `DEV_API_USER` remains only as a bootstrap administrator.
Keycloak replaces all of this later.
