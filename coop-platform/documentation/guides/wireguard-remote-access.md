# Reaching the office server from another network (WireGuard)

This guide lets branch PCs and staff laptops use the Coop Platform while the server stays in the office. WireGuard is free, open-source software. It makes an
encrypted link between each remote PC and the office server, so the app is never published on the internet.

**The internet provider's router is enough.** WireGuard runs on the server PC; the router only lets one port through to it.

```
 Branch PC / laptop                       Internet                    Office
 ┌──────────────────┐                                          ┌──────────────────┐
 │ Coop Platform app│                                          │ Provider router  │
 │ WireGuard        │── encrypted, UDP 51820 ─────────────────▶│ port forward     │
 │ 10.66.0.11       │                                          │   ▼              │
 └──────────────────┘                                          │ Server PC        │
                                                               │ WireGuard        │
                                                               │ 10.66.0.1        │
                                                               │ backend :8090    │
                                                               └──────────────────┘
```

Remote PCs reach **only the server PC** (address `10.66.0.1`), not the rest of the office network, and their own internet traffic does not go through the office.

---

## Part 0. Before you start: does the office line have a public IP?

WireGuard needs the office to be reachable from the internet.

1. On a PC in the office, open `https://whatismyip.com` (or any "what is my IP" site) and note the address.
2. Sign in to the provider's router (usually `http://192.168.1.1`; the login is on a sticker under the router or from the provider) and find the **WAN** or
   **Internet** IP on its status page.
3. Compare them:
   - **The same:** the line has a public IP. Continue with this guide.
   - **Different, or the router's WAN IP starts with `100.64.` to `100.127.`, or `10.`:** the line is behind **CGNAT** and cannot be reached from outside. Ask
     the provider for a public IP (often available on request or with a business plan). If they will not give one, this guide does not work as written; see
     *If there is no public IP* at the end.

Also ask the provider whether the public IP is **static** (never changes) or **dynamic** (can change). A dynamic IP needs Part 2.

---

## Part 1. Prepare the server PC

The server PC is the one running the Coop Platform backend.

1. **Give it a fixed address on the office network**, so the router's forward always reaches it.
   - Easiest: in the router, find **DHCP** / **LAN** settings and *reserve* the server PC's current address (for example `192.168.1.10`) for its network card.
   - Or set a fixed IPv4 address on the PC itself (Settings > Network > Ethernet > IP assignment > Edit > Manual), outside the router's DHCP range.
2. **Keep it awake.** Settings > System > Power: *Screen* may turn off, but set *Sleep* to **Never**. Plug it into a UPS.
3. **Check the backend answers locally:** open `http://localhost:8090/actuator/health` on the server. It should show `"status":"UP"`.

---

## Part 2. A name for the office (only if the public IP is dynamic)

If the IP can change, give the office a name that always points at its current IP. Skip this part if the IP is static; use the IP itself wherever this
guide says `office.example`.

- **If the router has a DDNS setting** (look for *DDNS* or *Dynamic DNS*), use it with one of the services it lists. This is best: the router updates the
  name itself.
- **Otherwise use DuckDNS (free):**
  1. Go to `https://www.duckdns.org`, sign in, and add a subdomain, for example `ourcoop` (giving `ourcoop.duckdns.org`).
  2. DuckDNS shows a **token**. Treat it as a password: anyone with it can point your name elsewhere.
  3. On the server PC, create a scheduled task that updates the name every 5 minutes. In PowerShell **as Administrator** (replace the two values):
     ```powershell
     $url = 'https://www.duckdns.org/update?domains=ourcoop&token=PUT-THE-TOKEN-HERE'
     $act = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -WindowStyle Hidden -Command `"Invoke-WebRequest -UseBasicParsing '$url' | Out-Null`""
     $trg = New-ScheduledTaskTrigger -Once -At (Get-Date) -RepetitionInterval (New-TimeSpan -Minutes 5)
     Register-ScheduledTask -TaskName 'DuckDNS update' -Action $act -Trigger $trg -User 'SYSTEM' -RunLevel Highest
     ```
  4. After a few minutes, `nslookup ourcoop.duckdns.org` should give the office's public IP.

From here on, `office.example` means your DuckDNS name, the router's DDNS name or the static IP.

---

## Part 3. Install WireGuard on the server PC

1. Download and install WireGuard for Windows from `https://www.wireguard.com/install/`.
2. Open WireGuard > **Add Tunnel** (arrow) > **Add empty tunnel…**. It creates a key pair and shows:
   ```
   [Interface]
   PrivateKey = (filled in for you)
   ```
3. Name it `coop-server` and complete it like this (keep the PrivateKey line it made):
   ```ini
   [Interface]
   PrivateKey = (filled in for you, never share it)
   Address = 10.66.0.1/24
   ListenPort = 51820
   ```
   Note the **Public key** shown at the top of the window: every remote PC needs it.
4. **Save**. Peers (the remote PCs) are added in Part 5.
5. **Make it start with Windows, before anyone signs in.** Click **Activate**. The WireGuard app runs an active tunnel as a Windows service, which starts
   again after a restart. Restart the PC once and check that WireGuard shows `coop-server` as *Active* without anyone opening the app.

### Windows Firewall on the server

In PowerShell **as Administrator**:

```powershell
# let WireGuard connections in
New-NetFirewallRule -DisplayName 'WireGuard (Coop Platform)' -Direction Inbound -Protocol UDP -LocalPort 51820 -Action Allow -Profile Any

# let the app's port in only from the WireGuard addresses and the office network (adjust 192.168.1.0/24 to your office network)
New-NetFirewallRule -DisplayName 'Coop Platform backend' -Direction Inbound -Protocol TCP -LocalPort 8090 -Action Allow -Profile Any `
  -RemoteAddress 10.66.0.0/24,192.168.1.0/24
```

If Windows ever asked about Java on *public networks* and it was allowed, open *Windows Defender Firewall > Allow an app*, and make sure Java is **not**
allowed on Public, so the rule above is the only way in.

---

## Part 4. Forward the port on the provider's router

1. Sign in to the router (Part 0, step 2).
2. Find port forwarding. Depending on the model it is under **Forward Rules**, **Port Forwarding**, **NAT**, **Virtual Server** or **Applications**.
3. Add one rule:

   | Setting | Value |
   |---|---|
   | Name | WireGuard |
   | Protocol | **UDP** |
   | External (WAN) port | 51820 |
   | Internal (LAN) host | the server PC's fixed address, e.g. `192.168.1.10` |
   | Internal port | 51820 |

4. Save. **Do not forward port 8090** or any other port: WireGuard is the only way in.

If the router has no port forwarding page, the login you have is probably a limited one. Call the provider and ask them to forward **UDP 51820** to your
server's address, or to give you the administrator login.

---

## Part 5. Add a remote PC (a branch PC or a laptop)

Do this once per PC. Each PC gets its own address and its own key, so a lost laptop can be cut off alone, and the audit log shows which PC each action came
from.

### Keep a register

Keep a list (on paper or in a spreadsheet, not on the remote PCs) of who has which address:

| Address | Person / PC | Public key (first 8 characters) | Added | Removed |
|---|---|---|---|---|
| 10.66.0.1 | Office server | | | |
| 10.66.0.11 | Ana's laptop | `Xy3kQ9…` | 2026-11-27 | |
| 10.66.0.21 | Branch 2, teller PC | `Lm8pR2…` | 2026-11-27 | |

A suggested plan: `10.66.0.11`–`.19` staff laptops, `.21`–`.99` branch PCs, `.100` and up for later.

### On the remote PC

1. Install WireGuard for Windows from `https://www.wireguard.com/install/`.
2. **Add Tunnel > Add empty tunnel…**, name it `coop`. The private key is created **on this PC and never leaves it**.
3. Complete it (use this PC's address from the register, and the server's public key from Part 3):
   ```ini
   [Interface]
   PrivateKey = (filled in for you, never share it)
   Address = 10.66.0.11/32

   [Peer]
   PublicKey = (the server's public key from Part 3)
   Endpoint = office.example:51820
   AllowedIPs = 10.66.0.1/32
   PersistentKeepalive = 25
   ```
   - `AllowedIPs = 10.66.0.1/32` sends only the server's traffic through the tunnel; the PC's normal internet is unaffected.
   - `PersistentKeepalive = 25` keeps the link open through the PC's own router.
4. Save, and send this PC's **Public key** (shown at the top) to whoever manages the server. The public key is safe to send by chat or email; the private key
   is not.

### On the server PC

1. Open WireGuard, select `coop-server`, **Edit**, and add at the end:
   ```ini
   [Peer]
   # Ana's laptop
   PublicKey = (the remote PC's public key)
   AllowedIPs = 10.66.0.11/32
   ```
2. Save. If the tunnel was active, WireGuard applies it; if not, **Activate**.

### Connect and test

1. On the remote PC, **Activate** the `coop` tunnel. Within a few seconds *Latest handshake* shows a time on both sides.
2. On the remote PC, in PowerShell:
   ```powershell
   Test-NetConnection 10.66.0.1 -Port 8090
   ```
   `TcpTestSucceeded : True` means it works. A browser at `http://10.66.0.1:8090/actuator/health` should show `"status":"UP"`.
3. Open the Coop Platform app. On the sign-in screen, open **Connection**, add the server `http://10.66.0.1:8090`, choose it, and sign in.

To have the tunnel on all the time, leave it activated: WireGuard turns it on again after a restart.

---

## Part 6. Removing a person or a lost laptop

1. On the server PC, WireGuard > `coop-server` > **Edit**, delete that PC's `[Peer]` block, **Save**.
2. Mark the address as removed in the register (do not reuse it for a while, so old audit entries stay clear).
3. In the Coop Platform, also switch off the person's account if they left (Settings > Users > Enabled).

The removed PC can no longer connect, even with its own configuration, because the server no longer knows its key.

---

## Branches with many PCs (optional, later)

Installing WireGuard on every branch PC works and keeps each PC's address in the audit log. With many PCs, a small router that supports WireGuard (for
example a MikroTik with RouterOS 7) can sit behind the provider's router at the branch and carry the whole branch through one tunnel, so the PCs need nothing
installed. Then the server sees every PC of that branch as the router's one address (`10.66.0.2x`), so the audit log tells branches apart but not the PCs
within one. Ask for a separate setup note when you get there.

---

## Troubleshooting

| What you see | Likely cause and fix |
|---|---|
| No *Latest handshake* on the remote PC | The port forward is missing or points at the wrong PC (Part 4); the server tunnel is not active; the firewall rule for UDP 51820 is missing (Part 3); `office.example` does not give the office's current IP (`nslookup`, Part 2); or the line is behind CGNAT (Part 0). |
| Handshake shows, but `Test-NetConnection` fails | The backend is not running, or the firewall rule for TCP 8090 is missing or has the wrong `RemoteAddress` (Part 3). |
| Works in the office, not from home | Testing from inside the office through `office.example` often fails on provider routers. Test from another network (a phone hotspot). |
| Pages start to load then hang | Lower the packet size: add `MTU = 1380` under `[Interface]` on the remote PC. |
| Worked yesterday, not today | The public IP changed and the name was not updated (Part 2), or the server PC restarted with a new office address (Part 1). |
| The app says it cannot reach the server | The tunnel is off on that PC, or the Connection window points at another address. |

## If there is no public IP

If the provider cannot give the office a public IP, nothing from outside can reach it directly. The options are:

- **A small rented server** (a few dollars a month) with a public IP, as the meeting point: both the office server and the remote PCs connect *out* to it,
  which works behind CGNAT.
- **Tailscale** (paid for business use) or **Headscale** (free, run on such a rented server), which handle this for you.

Ask for a separate guide for whichever you choose.

## Security notes

- Never forward port 8090, 5432 or 5440 on the router. Only UDP 51820.
- A private key never leaves the PC it was made on. Only public keys are sent around.
- The link is encrypted by WireGuard; the app's sign-in still sends the password with each request, which is why it must only be reached through the tunnel or
  the office network, never directly from the internet.
- Every remote action is recorded in the audit log with the person's name and their WireGuard address (`10.66.0.x`); the register in Part 5 tells you which
  PC that was.
