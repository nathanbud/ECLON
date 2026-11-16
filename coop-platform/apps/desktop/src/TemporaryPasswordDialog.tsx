import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material'
import { useState } from 'react'
import { FiCheck, FiCopy } from 'react-icons/fi'
import { issueTemporaryPassword, type AdminUser, type TemporaryPassword } from './api'

const CHOICES = [
  { hours: 1, label: '1 hour' },
  { hours: 4, label: '4 hours' },
  { hours: 24, label: '24 hours' },
  { hours: 72, label: '3 days' },
]
const DEFAULT_HOURS = 24

const when = (d: Date) => d.toLocaleString(undefined, { weekday: 'short', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })

/** What issuing will do, said plainly for the state the person's password is in. */
function note(u: AdminUser): string {
  switch (u.passwordState) {
    case 'ACTIVE': return `This replaces ${u.username}'s own password. They are signed out and must choose a new one when they sign in again.`
    case 'TEMPORARY': return `${u.username} has a temporary password they have not replaced yet. This one replaces it.`
    case 'EXPIRED': return `${u.username}'s temporary password ran out before it was used. This gives them a fresh one.`
    default: return `${u.username} has no password yet. They sign in with this one and then choose their own.`
  }
}

/** Issues a temporary password. The system makes it, it is shown once, and the person must replace it at their first sign-in. */
export default function TemporaryPasswordDialog({ user, onClose, onIssued }: { user: AdminUser | null; onClose: () => void; onIssued: (u: AdminUser) => void }) {
  const [hours, setHours] = useState(DEFAULT_HOURS)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [issued, setIssued] = useState<TemporaryPassword | null>(null)
  const [copied, setCopied] = useState(false)

  const reset = () => { setHours(DEFAULT_HOURS); setError(null); setIssued(null); setCopied(false) }
  const endsAt = new Date(Date.now() + hours * 3_600_000)

  const issue = async () => {
    if (!user) return
    setBusy(true)
    setError(null)
    try {
      setIssued(await issueTemporaryPassword(user.username, hours))
      onIssued(user)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const copy = async () => {
    if (!issued) return
    try {
      await navigator.clipboard.writeText(issued.password)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2000)
    } catch { /* the password is on screen; it can be read out */ }
  }

  const groups = issued ? issued.password.match(/.{1,4}/g) ?? [] : []

  return (
    <Dialog open={!!user} onClose={busy ? undefined : onClose} fullWidth maxWidth="xs" aria-labelledby="temp-password-title"
      slotProps={{ transition: { onExited: reset } }}>
      <DialogTitle id="temp-password-title" sx={{ pb: 0.5 }}>{issued ? 'Temporary password' : 'Issue temporary password'}</DialogTitle>
      {!issued && user && (
        <form onSubmit={(e) => { e.preventDefault(); void issue() }}>
          <DialogContent>
            <Typography sx={{ fontSize: 13, mb: 2.5 }} color="text.secondary">
              For <strong>{user.username}</strong>{user.name ? ` (${user.name})` : ''}. {note(user)}
            </Typography>
            <Typography id="validity-label" sx={{ fontSize: 12, fontWeight: 600, letterSpacing: '0.04em', textTransform: 'uppercase', mb: 0.75 }} color="text.secondary">Valid for</Typography>
            <ToggleButtonGroup exclusive fullWidth value={hours} onChange={(_, v) => v != null && setHours(v)} aria-labelledby="validity-label" size="small"
              sx={{ mb: 1, '& .MuiToggleButton-root': { textTransform: 'none', py: 0.9, fontWeight: 500 } }}>
              {CHOICES.map((c) => <ToggleButton key={c.hours} value={c.hours}>{c.label}</ToggleButton>)}
            </ToggleButtonGroup>
            <Typography sx={{ fontSize: 12 }} color="text.secondary">
              Stops working {when(endsAt)} if it has not been used. They must choose their own password at first sign-in.
            </Typography>
            {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}
          </DialogContent>
          <DialogActions sx={{ px: 3, pb: 2 }}>
            <Button onClick={onClose} disabled={busy}>Cancel</Button>
            <Button type="submit" variant="contained" disabled={busy}>{busy ? 'Issuing…' : 'Issue password'}</Button>
          </DialogActions>
        </form>
      )}
      {issued && user && (
        <>
          <DialogContent>
            <Typography sx={{ fontSize: 13, mb: 2 }} color="text.secondary">
              Give this to <strong>{issued.username}</strong> in person or by phone. It is shown once and cannot be looked up again.
            </Typography>
            <Box
              role="group" aria-label="Temporary password"
              sx={(t) => ({
                display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 1.5, py: 2.25, px: 2, borderRadius: '12px',
                bgcolor: t.palette.mode === 'dark' ? 'rgba(255,255,255,0.07)' : 'rgba(0,0,0,0.045)',
                fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace', fontSize: 22, fontWeight: 600, letterSpacing: '0.06em', userSelect: 'all',
              })}
            >
              {groups.map((g, i) => <span key={i}>{g}</span>)}
            </Box>
            <Box sx={{ display: 'flex', justifyContent: 'center', mt: 1.5 }}>
              <Button size="small" startIcon={copied ? <FiCheck /> : <FiCopy />} onClick={() => void copy()}>{copied ? 'Copied' : 'Copy'}</Button>
            </Box>
            <Alert severity="info" icon={false} sx={{ mt: 2, fontSize: 13 }}>
              Works until <strong>{when(new Date(issued.expiresAt))}</strong>. {issued.username} will be asked to choose their own password at first sign-in.
            </Alert>
          </DialogContent>
          <DialogActions sx={{ px: 3, pb: 2 }}>
            <Button variant="contained" onClick={onClose}>Done</Button>
          </DialogActions>
        </>
      )}
    </Dialog>
  )
}
