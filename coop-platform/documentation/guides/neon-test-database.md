# A test database on Neon

A blank cooperative on Neon (hosted PostgreSQL) for trying the application from scratch, over the internet, or by someone without this PC. **Test data only**:
never copy `coop`, the old MySQL database or any real member's details there.

What it is not for: the backend test suite (many tests check against `coop`'s own ledger and expected results, so they fail on a blank database), and judging
speed (every query goes to Singapore, and the free plan's database is small).

## 1. The Neon project

1. Sign up at `https://neon.tech` and create a project, e.g. `coop-test`.
2. Region: **AWS Asia Pacific (Singapore)**, the closest to the Philippines.
3. PostgreSQL version: **18** if offered (the same as ours), otherwise 17 (see step 3 if so).
4. Scale to zero can stay on for testing: the first request after a quiet spell then waits a few seconds.

## 2. The two database users

The application expects `coop_admin` (owns the tables, applies database changes) and `coop_app` (what the backend signs in as: no table changes, posted
entries insert-only).

1. **`coop_admin`**: Neon console > your project > *Roles* > New role `coop_admin`. Note its password.
2. **The database**: *Databases* > New database `coop_test`, owner `coop_admin`.
3. **`coop_app`** must be created **with SQL**, not in the console: roles made in the console get Neon's administrator rights. In the console's *SQL
   Editor*, database `coop_test`, signed in as `coop_admin`:

   ```sql
   CREATE ROLE coop_app LOGIN PASSWORD '<a long random password>';
   ```

   Neon refuses weak passwords for roles made this way. Make one in PowerShell with
   `$b = New-Object byte[] 24; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b) -replace '[+/=]', ''`.

Keep both passwords like `db.txt`: never in code, documents or chat.

## 3. Load the blank database

1. Make the blank file (needs Docker and `eclon-postgres` running; it reads only the structure of `coop`, no data):

   ```powershell
   .\coop-platform\scripts\make-blank-database.ps1
   ```

   It writes `backups\blank\coop-blank-<date>.dump` (git-ignored) and says how many tables and database changes it holds.

   **For testing, use the sample cooperative instead**: `.\coop-platform\scripts\make-blank-database.ps1 -Sample` writes `coop-sample-<date>.dump`, which
   also has the standard lists, member types, an office, a department, member defaults, share capital and regular savings with their chart accounts, and
   account opening, so members can be registered as soon as two Membership Officers exist (step 5). Restore that file in the next step instead.

2. In the Neon console, *Connect*: choose database `coop_test`, role `coop_admin`, and switch **off** *Connection pooling*. Copy the host: it looks like
   `ep-xxxx-xxxx.ap-southeast-1.aws.neon.tech` (no `-pooler` in it). Our connection sets the Manila time zone when it starts, which the pooler refuses.

3. Restore (PostgreSQL 18's tools, installed with the Windows PostgreSQL):

   ```powershell
   $env:PGSSLMODE = 'require'
   & 'C:\Program Files\PostgreSQL\18\bin\pg_restore.exe' -h <host> -U coop_admin -d coop_test --no-owner --role=coop_admin --exit-on-error backups\blank\coop-blank-<date>.dump
   ```

   It asks for `coop_admin`'s password and must end with no error. If the project is PostgreSQL 17 and this fails on a setting it does not know, tell the
   developer: the file is made from PostgreSQL 18.

## 4. Run the backend here against Neon

The easy way: `scripts\run-backend-neon.ps1 -NeonHost <host>` starts a second backend on port 8092 beside the usual one (which it leaves running), asks for
`coop_app`'s password and a bootstrap sign-in, and never reads `db.txt`. In the desktop app's Connection window, add `http://localhost:8092`.

Or set these yourself before `scripts\run-backend.ps1`, or in a PowerShell that starts the jar (the passwords typed at run time, never saved in a file):

| Variable | Value |
|---|---|
| `DB_HOST` | the Neon host from step 3 |
| `DB_PORT` | `5432` |
| `DB_NAME` | `coop_test` |
| `DB_USER` | `coop_app` |
| `DB_PASSWORD` | `coop_app`'s password |
| `DB_SSLMODE` | `require` |
| `COOP_CONNECTIONS_FILE` | a file that does not exist, e.g. `$env:TEMP\none.json` |
| `DEV_API_USER`, `DEV_API_PASSWORD` | the bootstrap sign-in for this test database |

`COOP_CONNECTIONS_FILE` matters: otherwise the saved default connection (Settings > System > Database connection) takes over and the backend quietly
connects to another database. Note that `run-backend.ps1` sets `DB_USER` and `DB_PASSWORD` itself from `db.txt`; for Neon start the jar yourself or change
those two lines for the run.

## 5. Try it

1. Sign in with the bootstrap account.
2. Settings > System > Database connection: up to date.
3. Settings > System > Starter setup: load the standard lists (already done in the sample cooperative).
4. Settings > Users > New user: create the people you test with. The bootstrap account cannot register members itself (it is not on the staff list).
   For registering, create two users and give each the **Membership Officer** role (Settings > Users > Roles): one sends the registration, the other
   approves it. Each signs in once with the temporary password and sets their own.
5. Register a member (Client Profile > New member), sign in as the other officer, approve it on Waiting for you: the member opens with share capital and
   savings accounts. Then work through the other screens.

When the test is over, delete the Neon project.
