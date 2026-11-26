# Setting up the office server on Windows 11 Pro

This guide sets up one PC as the cooperative's server: PostgreSQL (the database) and the Coop Platform backend, both running as Windows services that start
by themselves, with nightly backups. Staff PCs run only the desktop app and connect to this server. For branches and staff outside the office, follow
[wireguard-remote-access.md](wireguard-remote-access.md) after this guide.

Sized for about 20 users. See the end for the hardware.

**Before you start**, you need from the developer:

- the backend file `coop-core.jar` (the version to install);
- the blank database file `coop-blank.dump` (see Part 5; it holds the tables and no other cooperative's data).

Write every password this guide asks you to make in the cooperative's password manager or a sealed envelope kept away from the server. **Never** save them on
staff PCs, in email or chat.

---

## Part 1. Windows

1. **Edition:** Windows 11 **Pro** (Settings > System > About). Home cannot host Remote Desktop or fully manage BitLocker.
2. **Accounts:** keep one administrator account for whoever looks after the server, with a strong password. Staff do not use this PC as a workstation.
3. **Name the PC** something clear, e.g. `COOP-SERVER` (Settings > System > About > Rename this PC). Restart.
4. **Fixed address on the office network**: reserve its address in the router's DHCP settings (for example `192.168.1.10`), or set it manually outside the
   router's DHCP range (Settings > Network & internet > Ethernet > IP assignment > Edit > Manual). Use a **wired** connection, not Wi-Fi.
5. **Power** (Settings > System > Power & battery): *Sleep* **Never**; the screen may turn off. In Control Panel > Power Options > *Choose what the power
   buttons do*, set the power button to **Shut down**, not Sleep.
6. **Restart after a power cut:** in the PC's BIOS/UEFI setup, set *AC power recovery* / *Restore on AC power loss* to **Power On**, so it comes back by
   itself when power returns.
7. **Windows Update** (Settings > Windows Update > Advanced options):
   - *Active hours*: set **Manually**, e.g. 6:00 AM to 10:00 PM, so it never restarts during office hours.
   - Leave updates on. Check once a week that updates installed and the PC restarted.
8. **Disk encryption:** Settings > Privacy & security > Device encryption (or Control Panel > BitLocker Drive Encryption) > turn on for **every** drive,
   including the backup drive. **Print the recovery key** and keep it with the passwords, away from the server. Without it a failed motherboard can mean
   losing the disk.
9. **Remote Desktop** (Settings > System > Remote Desktop) **on**, with *Require devices to use Network Level Authentication*. It is reached only from the
   office network or through WireGuard; never forward its port (3389) on the router.
10. **Microsoft Defender** stays on. Add exclusions for the database's folder so scanning does not slow it down (Windows Security > Virus & threat protection >
    Manage settings > Exclusions > Add): the folder `C:\Program Files\PostgreSQL\18\data`.

### Folders

Make these folders (any drive; this guide uses C: for the app and D: for backups):

| Folder | Holds |
|---|---|
| `C:\CoopPlatform\app` | the backend `coop-core.jar` |
| `C:\CoopPlatform\service` | the service settings, with passwords (locked down in Part 4) |
| `C:\CoopPlatform\data\documents` | uploaded documents, photos of IDs |
| `C:\CoopPlatform\logs` | the backend's log and error journal |
| `D:\CoopBackups` | nightly backups (a second disk, not the system disk) |

---

## Part 2. PostgreSQL

1. Download the PostgreSQL **18** installer for Windows (by EDB) from `https://www.postgresql.org/download/windows/`.
2. Run it. Choose: PostgreSQL Server and Command Line Tools (pgAdmin optional; Stack Builder not needed). Data folder: leave the default. Port: **5432**. Locale:
   default. Set the password for the `postgres` account and write it down.
3. Open `C:\Program Files\PostgreSQL\18\data\postgresql.conf` in Notepad (as administrator) and set these lines (remove the `#` in front where there is one):

   ```ini
   listen_addresses = 'localhost'      # only the backend on this PC talks to the database; nothing on the network can reach it
   max_connections = 50
   timezone = 'Asia/Manila'

   # 32 GB of memory (for 16 GB: shared_buffers = 2GB, effective_cache_size = 8GB, maintenance_work_mem = 256MB)
   shared_buffers = 4GB
   effective_cache_size = 16GB
   work_mem = 16MB
   maintenance_work_mem = 512MB
   ```

4. Restart it: in PowerShell **as Administrator**, `Restart-Service postgresql-x64-18`.
5. Add PostgreSQL's tools to the path for this guide's commands (PowerShell as Administrator):

   ```powershell
   [Environment]::SetEnvironmentVariable('Path', $env:Path + ';C:\Program Files\PostgreSQL\18\bin', 'Machine')
   ```

   Close and reopen PowerShell.

---

## Part 3. Java

1. Install **Eclipse Temurin 21 (LTS), JRE**, Windows x64 `.msi`, from `https://adoptium.net/temurin/releases/?version=21`.
2. In the installer, switch on *Add to PATH* and *Set JAVA_HOME*.
3. Check in a new PowerShell: `java -version` shows version 21.

---

## Part 4. Make the secrets

You need five secrets. Make each one long and random. In PowerShell (any account), this prints one at a time:

```powershell
$b = New-Object byte[] 24; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b) -replace '[+/=]', ''
```

| Secret | Used for |
|---|---|
| `coop_admin` password | the database owner: applying database changes and backups |
| `coop_app` password | the backend's own database login (it cannot change the table design) |
| Bootstrap user name and password | the first sign-in, before any user exists; it is always a super administrator, so keep it for emergencies afterwards |

The **fingerprint key** is different: exactly 32 random bytes, written in base64:

```powershell
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

**Keep a copy of the fingerprint key away from the server** (password manager or sealed envelope). Every enrolled fingerprint is encrypted with it: lose the
key and every fingerprint has to be taken again. Never change it once fingerprints are enrolled.

---

## Part 5. Create the cooperative's database

The database starts from the developer's blank file `coop-blank.dump`: every table the application uses, its record of database changes, and no members,
entries or settings of any other cooperative.

> **Note for the developer:** there is no script yet that produces `coop-blank.dump` (with the change record and one branch, which the app cannot add yet).
> Make it before the first install; see "Still to build" at the end.

In PowerShell **as Administrator**, replacing the passwords and choosing a database name (here `coop_main`):

```powershell
psql -h localhost -U postgres -d postgres -c "CREATE ROLE coop_admin LOGIN PASSWORD 'COOP-ADMIN-PASSWORD'"
psql -h localhost -U postgres -d postgres -c "CREATE ROLE coop_app LOGIN PASSWORD 'COOP-APP-PASSWORD'"
psql -h localhost -U postgres -d postgres -c "CREATE DATABASE coop_main OWNER coop_admin ENCODING 'UTF8' TEMPLATE template0"
pg_restore -h localhost -U coop_admin -d coop_main --no-owner --role=coop_admin --exit-on-error "C:\path\to\coop-blank.dump"
```

Each `psql`/`pg_restore` asks for the password of the account named after `-U`. The last line must finish with no error. Then check:

```powershell
psql -h localhost -U coop_admin -d coop_main -c "SELECT count(*) FROM legacy.ecis_pg_migration"
```

It shows the number of database changes recorded (not 0).

---

## Part 6. The backend as a Windows service

The backend runs as a Windows service through **WinSW**, a small free program that turns any program into a service.

1. Copy `coop-core.jar` to `C:\CoopPlatform\app\coop-core.jar`.
2. Download `WinSW-x64.exe` (version 2.12 or later) from `https://github.com/winsw/winsw/releases`, save it as `C:\CoopPlatform\service\coop-platform.exe`.
3. Next to it, create `C:\CoopPlatform\service\coop-platform.xml` (Notepad) with this, replacing the five secrets from Part 4:

   ```xml
   <service>
     <id>coop-platform</id>
     <name>Coop Platform backend</name>
     <description>Coop Platform server (needs PostgreSQL)</description>
     <executable>java</executable>
     <arguments>-Xmx2g -Duser.timezone=Asia/Manila -jar "C:\CoopPlatform\app\coop-core.jar"</arguments>
     <workingdirectory>C:\CoopPlatform\app</workingdirectory>
     <depend>postgresql-x64-18</depend>
     <startmode>Automatic</startmode>
     <delayedAutoStart>true</delayedAutoStart>
     <onfailure action="restart" delay="20 sec"/>
     <onfailure action="restart" delay="60 sec"/>
     <resetfailure>1 hour</resetfailure>
     <stoptimeout>30 sec</stoptimeout>
     <log mode="roll-by-size"><sizeThreshold>10240</sizeThreshold><keepFiles>5</keepFiles></log>
     <logpath>C:\CoopPlatform\logs\service</logpath>

     <!-- database -->
     <env name="DB_HOST" value="localhost"/>
     <env name="DB_PORT" value="5432"/>
     <env name="DB_NAME" value="coop_main"/>
     <env name="DB_USER" value="coop_app"/>
     <env name="DB_PASSWORD" value="COOP-APP-PASSWORD"/>
     <env name="SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE" value="15"/>

     <!-- the server -->
     <env name="SERVER_PORT" value="8090"/>
     <env name="COOP_EDIT_ENABLED" value="true"/>
     <env name="DEV_API_USER" value="BOOTSTRAP-USER-NAME"/>
     <env name="DEV_API_PASSWORD" value="BOOTSTRAP-PASSWORD"/>
     <env name="COOP_BIOMETRIC_KEY" value="FINGERPRINT-KEY"/>

     <!-- where it keeps files -->
     <env name="COOP_DOCUMENTS_ROOT" value="C:\CoopPlatform\data\documents"/>
     <env name="COOP_CONNECTIONS_FILE" value="C:\CoopPlatform\data\connections.json"/>
     <env name="LOGGING_FILE_NAME" value="C:\CoopPlatform\logs\backend.log"/>
     <env name="LOGGING_LOGBACK_ROLLINGPOLICY_MAX_FILE_SIZE" value="10MB"/>
     <env name="LOGGING_LOGBACK_ROLLINGPOLICY_MAX_HISTORY" value="14"/>
     <env name="LOGGING_LOGBACK_ROLLINGPOLICY_TOTAL_SIZE_CAP" value="200MB"/>
   </service>
   ```

   If `java` is not found when the service starts, replace `java` in `<executable>` with the full path, e.g.
   `C:\Program Files\Eclipse Adoptium\jre-21.0.x-hotspot\bin\java.exe` (look in that folder for the exact name).

4. **Lock the folders** that hold passwords or members' files, so only administrators and Windows itself can open them (PowerShell **as Administrator**):

   ```powershell
   foreach ($f in 'C:\CoopPlatform\service', 'C:\CoopPlatform\data', 'C:\CoopPlatform\logs') {
     icacls $f /inheritance:r /grant:r 'Administrators:(OI)(CI)F' 'SYSTEM:(OI)(CI)F'
   }
   ```

   The log can hold member details from failed requests, so it is locked too.

5. **Install and start** the service (PowerShell as Administrator):

   ```powershell
   C:\CoopPlatform\service\coop-platform.exe install
   C:\CoopPlatform\service\coop-platform.exe start
   ```

6. **Check it answers** (it takes 20 to 60 seconds to start): open `http://localhost:8090/actuator/health` on the server. It should show `"status":"UP"`. If
   it does not, read the end of `C:\CoopPlatform\logs\backend.log` (and `C:\CoopPlatform\logs\service`).

### Firewall

Let staff PCs reach the backend, and nothing else (PowerShell as Administrator; change `192.168.1.0/24` to your office network, and keep `10.66.0.0/24` if you
use WireGuard):

```powershell
New-NetFirewallRule -DisplayName 'Coop Platform backend' -Direction Inbound -Protocol TCP -LocalPort 8090 -Action Allow -Profile Any `
  -RemoteAddress 192.168.1.0/24,10.66.0.0/24
```

PostgreSQL needs no rule: it listens only on the server itself (Part 2).

---

## Part 7. First sign-in and setup

1. On a staff PC, install the Coop Platform desktop app. On its sign-in screen, open **Connection**, add `http://192.168.1.10:8090` (the server's address),
   choose it.
2. Sign in with the **bootstrap** user name and password (Part 4).
3. **Settings > System > Database connection**: the database changes check should say it is up to date. If it lists changes missing, press *Apply them now*
   and sign in there as `coop_admin` (Part 4).
4. **Settings > System > Starter setup**: load the standard lists (statuses, transaction types, payment modes and so on), with a reason.
5. **Settings > Users > New user**: create the cooperative's own super administrator, then sign out and sign in as that person. From now on the bootstrap
   account is for emergencies only.
6. Continue with the cooperative's own setup: Settings > Organization (offices, departments), Membership > Member defaults, Accounting > Chart of Accounts,
   Products (account types and loan types, SL Controls, product roles, cash types), then Accounting > Opening balances.

---

## Part 8. Backups

The cooperative's whole record (members, accounts, ledger, audit log) is in the database, so the backup is a full dump of it, every night, plus the uploaded
documents. Settings > System > Backup in the app is **not** used on this server (see "Still to build").

1. **A password file** for the backup, so it runs unattended. Create `C:\CoopPlatform\service\pgpass.conf` with one line (the folder is already locked):

   ```
   localhost:5432:*:coop_admin:COOP-ADMIN-PASSWORD
   ```

2. **The backup script**: create `C:\CoopPlatform\service\backup.ps1`:

   ```powershell
   $ErrorActionPreference = 'Stop'
   $env:PGPASSFILE = 'C:\CoopPlatform\service\pgpass.conf'
   $root  = 'D:\CoopBackups'
   $stamp = Get-Date -Format 'yyyyMMdd-HHmm'
   $dir   = Join-Path $root $stamp
   New-Item -ItemType Directory -Force $dir | Out-Null

   # the whole database, compressed
   & 'C:\Program Files\PostgreSQL\18\bin\pg_dump.exe' -h localhost -U coop_admin -d coop_main -Fc -f (Join-Path $dir 'coop_main.dump')
   if ($LASTEXITCODE -ne 0) { throw "pg_dump failed ($LASTEXITCODE)" }

   # uploaded documents: kept as one up-to-date copy (the app never overwrites a stored file, and this copy never deletes one)
   robocopy 'C:\CoopPlatform\data\documents' (Join-Path $root 'documents') /E /R:2 /W:5 /NP /NFL /NDL | Out-Null
   if ($LASTEXITCODE -ge 8) { throw "copying the documents failed ($LASTEXITCODE)" }

   # keep the last 14 nightly backups
   Get-ChildItem $root -Directory | Where-Object Name -match '^\d{8}-\d{4}$' | Sort-Object Name -Descending | Select-Object -Skip 14 | Remove-Item -Recurse -Force
   "$stamp ok, $([math]::Round((Get-Item (Join-Path $dir 'coop_main.dump')).Length / 1MB, 1)) MB" | Add-Content (Join-Path $root 'backup-log.txt')
   ```

3. **Run it every night at 11 PM** (PowerShell as Administrator):

   ```powershell
   $act = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument '-NoProfile -ExecutionPolicy Bypass -File C:\CoopPlatform\service\backup.ps1'
   $trg = New-ScheduledTaskTrigger -Daily -At 11pm
   Register-ScheduledTask -TaskName 'Coop Platform backup' -Action $act -Trigger $trg -User 'SYSTEM' -RunLevel Highest
   Start-ScheduledTask -TaskName 'Coop Platform backup'
   ```

   After a minute, `D:\CoopBackups\backup-log.txt` should have an `ok` line.

4. **Off the machine.** A backup on the same PC is lost with it in a fire, theft or ransomware. At least once a week, copy the newest backup folder and
   `D:\CoopBackups\documents` to an encrypted external drive (BitLocker To Go) that is then kept somewhere else, with two drives taking turns. Any cloud
   copy must be encrypted first and its location is a Data Privacy Act matter for the cooperative.

5. **Try a restore every month** into a scratch database, so you know the backups work:

   ```powershell
   $env:PGPASSFILE = 'C:\CoopPlatform\service\pgpass.conf'
   createdb -h localhost -U coop_admin restore_test
   pg_restore -h localhost -U coop_admin -d restore_test --no-owner "D:\CoopBackups\<newest folder>\coop_main.dump"
   psql -h localhost -U coop_admin -d restore_test -c "SELECT count(*) FROM legacy.client"
   dropdb -h localhost -U coop_admin restore_test
   ```

   The count is the number of members.

**A full restore** (the server's disk died): set up a new PC with Parts 1 to 4 and 6 using the **same** passwords and fingerprint key, create the roles
and the database as in Part 5 but restore the newest `coop_main.dump` instead of the blank file, copy `documents` back to `C:\CoopPlatform\data\documents`,
then start the service.

---

## Part 9. Power protection

Connect the UPS's USB cable to the server and install its maker's software (APC PowerChute, CyberPower PowerPanel, or similar). Set it to **shut Windows
down** after about 5 minutes on battery. PostgreSQL survives a sudden power loss, but a clean shutdown is safer and protects the disks.

---

## Part 10. Updating the application

1. Wait until no one is working (after End Day).
2. Take a backup now: `Start-ScheduledTask -TaskName 'Coop Platform backup'`, and check the log line.
3. Stop the service: `C:\CoopPlatform\service\coop-platform.exe stop`.
4. Keep the old file: rename `C:\CoopPlatform\app\coop-core.jar` to `coop-core-previous.jar`, then copy the new `coop-core.jar` in.
5. Start it: `C:\CoopPlatform\service\coop-platform.exe start`, and check `http://localhost:8090/actuator/health`.
6. Sign in as a super administrator: if Settings shows database changes waiting, press *Apply them now* (as `coop_admin`).
7. Update the desktop app on staff PCs if the developer says the new version needs it.

To go back: stop, put `coop-core-previous.jar` back as `coop-core.jar`, start. (Database changes already applied stay; the developer says when going back is
safe.)

---

## Weekly checks (5 minutes)

- `D:\CoopBackups\backup-log.txt` has an `ok` line for every night.
- Free space on C: and D: (keep at least 20% free).
- Windows Update installed and restarted.
- Settings > System > Error log in the app: anything new and unexplained.
- The UPS shows no battery warning.

---

## Hardware (about 20 users)

| | Minimum | Recommended |
|---|---|---|
| CPU | 4 cores / 8 threads (Core i5 12th gen, Ryzen 5 5000 or newer) | 6–8 cores |
| Memory | 16 GB | 32 GB |
| System and data | 512 GB NVMe SSD | 1 TB NVMe SSD |
| Backups | external drive | a second internal disk (D:) plus two external drives taking turns off-site |
| Network | wired gigabit | wired gigabit |
| Power | UPS 1000 VA | UPS 1500 VA with USB shutdown |

A business desktop (Dell OptiPlex/Pro, HP Elite/Pro, Lenovo ThinkCentre) with a 3-year on-site warranty is enough. Staff PCs: Windows 10 or 11, 8 GB memory,
SSD.

---

## Still to build (for the developer)

- **The blank database file** `coop-blank.dump`: a script that makes it from an up-to-date database with the structure only, the database change record, the
  rows the database changes add for every cooperative, and one branch, leaving out any cooperative's own members, entries, users and settings.
- **Settings > System > Backup** runs `scripts/backup-app-data.ps1`, which reaches the database through Docker and dumps only the `ecis_` tables (it was made
  when the old system's tables held the old data). For a new cooperative the members and ledger are in the other tables too, so on this server it would
  miss most of the record. Until it is changed to a full `pg_dump` of the database, use Part 8.
