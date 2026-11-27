import {
  Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, InputAdornment, Skeleton,
  Snackbar, Switch, Table, TableBody, TableCell, TableHead, TableRow, TextField, Tooltip, Typography,
} from '@mui/material'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState, type MouseEvent } from 'react'
import { FiLock, FiSearch, FiUserPlus } from 'react-icons/fi'
import { grantRole, grantSingleHand, listLockedUsers, listSingleHand, listUsers, revokeRole, revokeSingleHand, setUserEnabled, unlockUser, type AdminUser } from './api'
import { appBackground, glass } from './glass'
import { useMe } from './session'
import PasswordChip from './PasswordChip'
import TemporaryPasswordDialog from './TemporaryPasswordDialog'
import UserProfileDialog from './UserProfileDialog'
import NewUserDialog from './NewUserDialog'
import { ToolbarPortal } from './toolbarSlot'
import { nameCase } from './ui/format'
import { USER_FILTERS, UsersMenu, type UserFilter } from './UsersMenu'
import { ScreenTitle } from './appleUi'
import { notifyError } from './ui/notices'
import UserRolesDialog from './UserRolesDialog'

const isMac = /Mac|iPhone|iPad/.test(navigator.platform)

const OFFICER = 'MEMBERSHIP_OFFICER'

/** Lets one person take every step of a loan alone. Needs a reason; it is recorded and can be taken back any time. */
function AloneDialog({ user, onClose, onDone }: { user: AdminUser | null; onClose: () => void; onDone: (message: string) => void }) {
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const submit = async () => {
    if (!user || !reason.trim()) return
    setBusy(true)
    try {
      await grantSingleHand(user.username, reason.trim())
      onDone(`${user.username} may now do loans alone`)
    } catch (e) {
      notifyError(e)
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open={!!user} onClose={busy ? undefined : onClose} fullWidth maxWidth="sm" aria-labelledby="alone-title"
      slotProps={{ transition: { onExited: () => setReason('') } }}>
      <DialogTitle id="alone-title">Let {user?.username} do loans alone</DialogTitle>
      <form onSubmit={(e) => { e.preventDefault(); void submit() }}>
        <DialogContent>
          <DialogContentText sx={{ mb: 2 }}>
            Normally the person who encodes a loan application, the one who verifies it, the one who approves it and the ones who approve and post its release voucher must be different.
            This lets <strong>{user?.username}</strong> take every one of those steps alone. They still need the access flag for each step. It is recorded with your name and the reason.
          </DialogContentText>
          <TextField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} fullWidth autoFocus multiline minRows={2} slotProps={{ htmlInput: { maxLength: 500 } }} />
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} disabled={busy}>Cancel</Button>
          <Button type="submit" variant="contained" color="warning" disabled={!reason.trim() || busy}>{busy ? 'Saving…' : 'Allow'}</Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}

/**
 * A click anywhere on a user's row opens their account setup, except on the row's own controls (a switch, even a disabled one, or a button), which
 * do their own thing; so the cells guard only their controls, not the whole cell.
 */
function keepToControl(e: MouseEvent) {
  if ((e.target as HTMLElement).closest('button, input, label, a, .MuiSwitch-root')) e.stopPropagation()
}

/** Administrators: who can sign in, their app passwords, and who is a Membership Officer. */
export default function UsersPage({ onAuditLog }: { onAuditLog?: () => void } = {}) {
  const me = useMe()
  const qc = useQueryClient()
  const [filter, setFilter] = useState('')
  const [target, setTarget] = useState<AdminUser | null>(null)
  const [profile, setProfile] = useState<AdminUser | null>(null)
  const [rolesOf, setRolesOf] = useState<AdminUser | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busyUser, setBusyUser] = useState<string | null>(null)
  const [alone, setAlone] = useState<AdminUser | null>(null)
  const [adding, setAdding] = useState(false)

  const { data, error: loadError, isLoading } = useQuery({ queryKey: ['users'], queryFn: listUsers, enabled: me.admin })
  const single = useQuery({ queryKey: ['single-hand'], queryFn: listSingleHand, enabled: me.admin })
  const locked = useQuery({ queryKey: ['locked-users'], queryFn: listLockedUsers, enabled: me.admin, refetchInterval: 20_000 })
  const isLocked = (name: string) => (locked.data ?? []).some((l) => l.username === name.toLowerCase())
  const unlock = async (u: AdminUser) => {
    try { await unlockUser(u.username); setNotice(`${u.username} can sign in again`); void qc.invalidateQueries({ queryKey: ['locked-users'] }) } catch (e) { notifyError(e) }
  }
  const grants = useMemo(() => new Map((single.data ?? []).map((g) => [g.username.toLowerCase(), g])), [single.data])
  // Which people the list shows, chosen from the Users menu in the title bar; it always opens on everyone.
  const [show, setShow] = useState<UserFilter>('all')
  const showTest = USER_FILTERS.find((x) => x.key === show)!.test
  const typed = filter.trim().toLowerCase()
  const rows = (data ?? []).filter((u) => showTest(u, isLocked) && (!typed || `${u.username} ${u.name ?? ''}`.toLowerCase().includes(typed)))
  const counts = data ? Object.fromEntries(USER_FILTERS.map((x) => [x.key, data.filter((u) => x.test(u, isLocked)).length])) as Record<UserFilter, number> : null

  // Ctrl+N (⌘N on a Mac) adds a new user, as it starts a new member on Member Profile. Not while a dialog or menu is open, nor before the list has loaded
  // (the New user window copies permissions from it).
  const mayNew = me.admin && !!data
  const mayNewRef = useRef(mayNew)
  mayNewRef.current = mayNew
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if (!(isMac ? e.metaKey : e.ctrlKey) || e.shiftKey || e.altKey || e.key.toLowerCase() !== 'n' || e.repeat) return
      if (document.querySelector('.MuiModal-root:not(.MuiModal-hidden), [role="dialog"], [role="listbox"], [role="menu"]')) return
      e.preventDefault() // the browser would open a new window of its own
      if (mayNewRef.current) setAdding(true)
    }
    window.addEventListener('keydown', on)
    return () => window.removeEventListener('keydown', on)
  }, [])

  const toggleOfficer = async (u: AdminUser, on: boolean) => {
    setBusyUser(u.username)
    try {
      if (on) {
        // granting the role also switches on every permission on the person's Member tab
        const granted = await grantRole(u.username, OFFICER)
        const n = granted?.switchedOn?.length ?? 0
        setNotice(`${u.username} is now a Membership Officer` + (n ? `, and ${n} Member permission${n === 1 ? ' was' : 's were'} switched on` : ', with every Member permission on'))
      } else {
        await revokeRole(u.username, OFFICER)
        setNotice(`${u.username} is no longer a Membership Officer. Their Member permissions are unchanged.`)
      }
      await Promise.all([qc.invalidateQueries({ queryKey: ['users'] }), qc.invalidateQueries({ queryKey: ['user-profile'] })])
    } catch (e) {
      notifyError(e)
    } finally {
      setBusyUser(null)
    }
  }

  // Switching an account off asks first (the person is signed out on their next action); switching it back on does not.
  const [disabling, setDisabling] = useState<AdminUser | null>(null)
  const setEnabled = async (u: AdminUser, on: boolean) => {
    setDisabling(null)
    setBusyUser(u.username)
    try {
      await setUserEnabled(u.username, on)
      await Promise.all([qc.invalidateQueries({ queryKey: ['users'] }), qc.invalidateQueries({ queryKey: ['user-profile'] })])
      setNotice(on ? `${u.username} can sign in again` : `${u.username} is switched off and can no longer sign in`)
    } catch (e) {
      notifyError(e)
    } finally {
      setBusyUser(null)
    }
  }

  const toggleAlone = async (u: AdminUser, on: boolean) => {
    if (on) { setAlone(u); return }
    setBusyUser(u.username)
    try {
      await revokeSingleHand(u.username)
      await qc.invalidateQueries({ queryKey: ['single-hand'] })
      setNotice(`${u.username} needs other people for loans again`)
    } catch (e) {
      notifyError(e)
    } finally {
      setBusyUser(null)
    }
  }

  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      {me.admin && (
        <ToolbarPortal>
          <UsersMenu onNew={() => setAdding(true)} mayNew={mayNew} filter={show} onFilter={setShow} counts={counts} onAuditLog={onAuditLog} />
        </ToolbarPortal>
      )}
      <Box sx={{ maxWidth: 1100, mx: 'auto' }}>
        <ScreenTitle title="Users" sx={{ mb: 2 }}>
          Add a person with New user. Select a person to see their account setup, issue them a temporary password, and choose who is a Membership Officer.
        </ScreenTitle>

        {!me.admin && <Alert severity="info">Only administrators can manage users.</Alert>}
        {me.admin && (
          <>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 1.5, flexWrap: 'wrap' }}>
              <TextField
                label="Filter by username or name" value={filter} onChange={(e) => setFilter(e.target.value)} sx={{ width: 320, maxWidth: '100%' }}
                slotProps={{ input: { startAdornment: <InputAdornment position="start"><FiSearch /></InputAdornment> } }}
              />
              <Box sx={{ flex: 1 }} />
              {show !== 'all' && (
                <Button size="small" onClick={() => setShow('all')} title="Show everyone again">
                  Showing: {USER_FILTERS.find((x) => x.key === show)!.label} · show all
                </Button>
              )}
              <Tooltip title={`New user (${isMac ? '⌘N' : 'Ctrl+N'})`}>
                <span><Button variant="contained" startIcon={<FiUserPlus />} onClick={() => setAdding(true)} disabled={!data} aria-keyshortcuts={isMac ? 'Meta+N' : 'Control+N'}>New user</Button></span>
              </Tooltip>
            </Box>
            {loadError && <Alert severity="error" sx={{ mb: 1.5 }}>{(loadError as Error).message}</Alert>}
            {isLoading && <Skeleton variant="rounded" height={240} />}
            {data && (
              <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
                <Table size="small" aria-label="Users">
                  <TableHead>
                    <TableRow>
                      <TableCell>User</TableCell><TableCell align="center">Enabled</TableCell><TableCell>Level</TableCell>
                      <TableCell>Password</TableCell><TableCell align="center">Membership Officer</TableCell><TableCell align="center">Loans alone</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {rows.map((u) => (
                      <TableRow
                        key={u.username} hover tabIndex={0} sx={{ opacity: u.enabled ? 1 : 0.55, cursor: 'pointer' }}
                        onClick={() => setProfile(u)} onKeyDown={(e) => { if (e.key === 'Enter' && e.target === e.currentTarget) setProfile(u) }}
                        aria-label={`Open ${u.username}'s account setup`}
                      >
                        <TableCell>
                          <Typography sx={{ fontSize: 13, fontWeight: 600 }}>{u.username}</Typography>
                          <Typography sx={{ fontSize: 12 }} color="text.secondary">{u.name ? nameCase(u.name) : '—'}{u.enabled ? '' : ' · disabled'}</Typography>
                        </TableCell>
                        <TableCell align="center" onClick={keepToControl} title={u.username.toLowerCase() === me.username.toLowerCase() ? 'You cannot switch off your own account' : u.enabled ? 'Switch off: they can no longer sign in' : 'Switch on: they can sign in again'}>
                          <Switch
                            color="success" checked={u.enabled}
                            disabled={busyUser === u.username || u.username.toLowerCase() === me.username.toLowerCase()}
                            onChange={(_, on) => { if (on) void setEnabled(u, true); else setDisabling(u) }}
                            slotProps={{ input: { 'aria-label': `${u.username} is enabled` } }}
                          />
                        </TableCell>
                        <TableCell onClick={keepToControl}>
                          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, whiteSpace: 'nowrap' }}>
                            {u.level ?? '—'}
                            <Button size="small" onClick={() => setRolesOf(u)} aria-label={`Roles of ${u.username}`}>
                              Roles{u.roles.filter((r) => r !== OFFICER).length > 0 ? ` (${u.roles.filter((r) => r !== OFFICER).length})` : ''}
                            </Button>
                          </Box>
                        </TableCell>
                        <TableCell onClick={keepToControl}>
                          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                            <PasswordChip u={u} />
                            {isLocked(u.username) && <Button size="small" color="warning" onClick={() => void unlock(u)}>Locked: unlock</Button>}
                            <Button size="small" startIcon={<FiLock />} disabled={!u.enabled} onClick={() => setTarget(u)}>
                              {u.passwordState === 'NONE' ? 'Issue' : 'Reissue'}
                            </Button>
                          </Box>
                        </TableCell>
                        <TableCell align="center" onClick={keepToControl} title={u.roles.includes(OFFICER) ? 'Take the role away (their Member permissions stay as they are)' : 'Make a Membership Officer: also switches on every permission on their Member tab'}>
                          <Switch
                            checked={u.roles.includes(OFFICER)} disabled={!u.enabled || busyUser === u.username}
                            onChange={(_, on) => void toggleOfficer(u, on)}
                            slotProps={{ input: { 'aria-label': `${u.username} is a Membership Officer` } }}
                          />
                        </TableCell>
                        <TableCell align="center" onClick={keepToControl} title={grants.get(u.username.toLowerCase()) ? `Allowed by ${grants.get(u.username.toLowerCase())!.grantedBy}: ${grants.get(u.username.toLowerCase())!.reason}` : u.username.toLowerCase() === me.username.toLowerCase() ? 'You cannot allow this for yourself' : ''}>
                          <Switch
                            color="warning" checked={grants.has(u.username.toLowerCase())}
                            disabled={!u.enabled || busyUser === u.username || u.username.toLowerCase() === me.username.toLowerCase()}
                            onChange={(_, on) => void toggleAlone(u, on)}
                            slotProps={{ input: { 'aria-label': `${u.username} may do loans alone` } }}
                          />
                        </TableCell>
                      </TableRow>
                    ))}
                    {rows.length === 0 && (
                      <TableRow><TableCell colSpan={6}><Typography sx={{ p: 2, textAlign: 'center' }} color="text.secondary">No users match.</Typography></TableCell></TableRow>
                    )}
                  </TableBody>
                </Table>
              </Box>
            )}
          </>
        )}
      </Box>

      {rolesOf && <UserRolesDialog user={rolesOf} onClose={() => setRolesOf(null)} onNotice={setNotice} />}
      <UserProfileDialog user={profile} onClose={() => setProfile(null)} onSetPassword={setTarget} onToggleOfficer={(u, on) => void toggleOfficer(u, on)} onNotice={setNotice} busy={busyUser !== null} />
      <TemporaryPasswordDialog
        user={target} onClose={() => setTarget(null)}
        onIssued={(u) => { setNotice(`Temporary password issued for ${u.username}`); void qc.invalidateQueries({ queryKey: ['users'] }); void qc.invalidateQueries({ queryKey: ['user-profile'] }) }}
      />
      <NewUserDialog
        open={adding} users={data ?? []} onClose={() => setAdding(false)}
        onCreated={(u) => { setNotice(`${u.username} was created`); void qc.invalidateQueries({ queryKey: ['users'] }) }}
      />
      <Dialog open={!!disabling} onClose={() => setDisabling(null)} aria-labelledby="disable-title">
        <DialogTitle id="disable-title">Switch off {disabling?.username}?</DialogTitle>
        <DialogContent>
          <DialogContentText>
            {disabling?.name ? `${nameCase(disabling.name)} ` : 'They '}will no longer be able to sign in. If they are signed in now, they are signed out on their next action.
            Nothing they did is removed, and you can switch the account on again at any time. The change is written to the audit log.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDisabling(null)} autoFocus>Cancel</Button>
          <Button color="error" variant="contained" onClick={() => disabling && void setEnabled(disabling, false)}>Switch off</Button>
        </DialogActions>
      </Dialog>
      <AloneDialog user={alone} onClose={() => setAlone(null)} onDone={(m) => { setAlone(null); setNotice(m); void qc.invalidateQueries({ queryKey: ['single-hand'] }) }} />
      <Snackbar open={!!notice} autoHideDuration={3500} onClose={() => setNotice(null)} message={notice} />
    </Box>
  )
}
