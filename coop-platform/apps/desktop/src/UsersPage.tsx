import {
  Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, InputAdornment, Skeleton,
  Snackbar, Switch, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography,
} from '@mui/material'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { FiLock, FiSearch } from 'react-icons/fi'
import { grantRole, grantSingleHand, listSingleHand, listUsers, revokeRole, revokeSingleHand, type AdminUser } from './api'
import { appBackground, glass } from './glass'
import { useMe } from './session'
import PasswordChip from './PasswordChip'
import TemporaryPasswordDialog from './TemporaryPasswordDialog'
import UserProfileDialog from './UserProfileDialog'

const OFFICER = 'MEMBERSHIP_OFFICER'

/** Lets one person take every step of a loan alone. Needs a reason; it is recorded and can be taken back any time. */
function AloneDialog({ user, onClose, onDone }: { user: AdminUser | null; onClose: () => void; onDone: (message: string) => void }) {
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const submit = async () => {
    if (!user || !reason.trim()) return
    setBusy(true)
    setError(null)
    try {
      await grantSingleHand(user.username, reason.trim())
      onDone(`${user.username} may now do loans alone`)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open={!!user} onClose={busy ? undefined : onClose} fullWidth maxWidth="sm" aria-labelledby="alone-title"
      slotProps={{ transition: { onExited: () => { setReason(''); setError(null) } } }}>
      <DialogTitle id="alone-title">Let {user?.username} do loans alone</DialogTitle>
      <form onSubmit={(e) => { e.preventDefault(); void submit() }}>
        <DialogContent>
          <DialogContentText sx={{ mb: 2 }}>
            Normally the person who encodes a loan application, the one who verifies it, the one who approves it and the ones who approve and post its release voucher must be different.
            This lets <strong>{user?.username}</strong> take every one of those steps alone. They still need the access flag for each step. It is recorded with your name and the reason.
          </DialogContentText>
          <TextField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} fullWidth autoFocus multiline minRows={2} slotProps={{ htmlInput: { maxLength: 500 } }} />
          {error && <Alert severity="error" sx={{ mt: 1 }}>{error}</Alert>}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} disabled={busy}>Cancel</Button>
          <Button type="submit" variant="contained" color="warning" disabled={!reason.trim() || busy}>{busy ? 'Saving…' : 'Allow'}</Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}

/** Administrators: who can sign in, their app passwords, and who is a Membership Officer. */
export default function UsersPage() {
  const me = useMe()
  const qc = useQueryClient()
  const [filter, setFilter] = useState('')
  const [target, setTarget] = useState<AdminUser | null>(null)
  const [profile, setProfile] = useState<AdminUser | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busyUser, setBusyUser] = useState<string | null>(null)
  const [alone, setAlone] = useState<AdminUser | null>(null)

  const { data, error: loadError, isLoading } = useQuery({ queryKey: ['users'], queryFn: listUsers, enabled: me.admin })
  const single = useQuery({ queryKey: ['single-hand'], queryFn: listSingleHand, enabled: me.admin })
  const grants = useMemo(() => new Map((single.data ?? []).map((g) => [g.username.toLowerCase(), g])), [single.data])
  const rows = useMemo(() => {
    const f = filter.trim().toLowerCase()
    return (data ?? []).filter((u) => !f || `${u.username} ${u.name ?? ''}`.toLowerCase().includes(f))
  }, [data, filter])

  const toggleOfficer = async (u: AdminUser, on: boolean) => {
    setBusyUser(u.username)
    setError(null)
    try {
      await (on ? grantRole(u.username, OFFICER) : revokeRole(u.username, OFFICER))
      await Promise.all([qc.invalidateQueries({ queryKey: ['users'] }), qc.invalidateQueries({ queryKey: ['user-profile'] })])
      setNotice(`${u.username} is ${on ? 'now' : 'no longer'} a Membership Officer`)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusyUser(null)
    }
  }

  const toggleAlone = async (u: AdminUser, on: boolean) => {
    if (on) { setAlone(u); return }
    setBusyUser(u.username)
    setError(null)
    try {
      await revokeSingleHand(u.username)
      await qc.invalidateQueries({ queryKey: ['single-hand'] })
      setNotice(`${u.username} needs other people for loans again`)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusyUser(null)
    }
  }

  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1100, mx: 'auto' }}>
        <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', mb: 0.5 }}>Users</Typography>
        <Typography sx={{ fontSize: 13, mb: 2 }} color="text.secondary">
          People come from the iAccs user list. Select a person to see their account setup, issue them a temporary password, and choose who is a Membership Officer.
        </Typography>

        {!me.admin && <Alert severity="info">Only administrators can manage users.</Alert>}
        {me.admin && (
          <>
            <TextField
              label="Filter by username or name" value={filter} onChange={(e) => setFilter(e.target.value)} sx={{ mb: 1.5, width: 320 }}
              slotProps={{ input: { startAdornment: <InputAdornment position="start"><FiSearch /></InputAdornment> } }}
            />
            {(loadError || error) && <Alert severity="error" sx={{ mb: 1.5 }}>{((loadError ?? new Error(error!)) as Error).message}</Alert>}
            {isLoading && <Skeleton variant="rounded" height={240} />}
            {data && (
              <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
                <Table size="small" aria-label="Users">
                  <TableHead>
                    <TableRow>
                      <TableCell>User</TableCell><TableCell>Level</TableCell><TableCell>Can edit clients</TableCell>
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
                          <Typography sx={{ fontSize: 13.5, fontWeight: 600 }}>{u.username}</Typography>
                          <Typography sx={{ fontSize: 12 }} color="text.secondary">{u.name || '—'}{u.enabled ? '' : ' · disabled'}</Typography>
                        </TableCell>
                        <TableCell>{u.level ?? '—'}</TableCell>
                        <TableCell>{u.canEdit ? 'Yes' : 'No'}</TableCell>
                        <TableCell onClick={(e) => e.stopPropagation()}>
                          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                            <PasswordChip u={u} />
                            <Button size="small" startIcon={<FiLock />} disabled={!u.enabled} onClick={() => setTarget(u)}>
                              {u.passwordState === 'NONE' ? 'Issue' : 'Reissue'}
                            </Button>
                          </Box>
                        </TableCell>
                        <TableCell align="center" onClick={(e) => e.stopPropagation()}>
                          <Switch
                            checked={u.roles.includes(OFFICER)} disabled={!u.enabled || busyUser === u.username}
                            onChange={(_, on) => void toggleOfficer(u, on)}
                            slotProps={{ input: { 'aria-label': `${u.username} is a Membership Officer` } }}
                          />
                        </TableCell>
                        <TableCell align="center" onClick={(e) => e.stopPropagation()} title={grants.get(u.username.toLowerCase()) ? `Allowed by ${grants.get(u.username.toLowerCase())!.grantedBy}: ${grants.get(u.username.toLowerCase())!.reason}` : u.username.toLowerCase() === me.username.toLowerCase() ? 'You cannot allow this for yourself' : ''}>
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

      <UserProfileDialog user={profile} onClose={() => setProfile(null)} onSetPassword={setTarget} onToggleOfficer={(u, on) => void toggleOfficer(u, on)} onNotice={setNotice} busy={busyUser !== null} />
      <TemporaryPasswordDialog
        user={target} onClose={() => setTarget(null)}
        onIssued={(u) => { setNotice(`Temporary password issued for ${u.username}`); void qc.invalidateQueries({ queryKey: ['users'] }); void qc.invalidateQueries({ queryKey: ['user-profile'] }) }}
      />
      <AloneDialog user={alone} onClose={() => setAlone(null)} onDone={(m) => { setAlone(null); setNotice(m); void qc.invalidateQueries({ queryKey: ['single-hand'] }) }} />
      <Snackbar open={!!notice} autoHideDuration={3500} onClose={() => setNotice(null)} message={notice} />
    </Box>
  )
}
